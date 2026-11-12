import { cryptoFramework } from '@kit.CryptoArchitectureKit';
import url from '@ohos.url';
import { decodeBase64Url, utf8Decode, utf8Encode } from './DshBytes';
import { DshHuksSecretCipher } from './DshHuksSecretCipher';
import { DshRelaySecretFiles, packSecret, PROTECTED_PAIRING_FILE, unpackSecret } from './DshRelaySecretFiles';
import { aesGcmDecrypt } from './DshSealedTunnelCrypto';

// All store instances share cancellation and ordering, including legacy migration.
let version = 0;
let tail: Promise<void> = Promise.resolve();

export class DshRelayCredentials {
  masterKey: string;
  clientToken: string;
  hostId: string;
  hostName: string;
  origin: string;
  constructor(
    masterKey: string, clientToken: string, hostId: string, hostName: string, origin: string,
  ) {
    this.masterKey = masterKey;
    this.clientToken = clientToken;
    this.hostId = hostId;
    this.hostName = hostName;
    this.origin = origin;
  }
}

export class DshRelaySecrets {
  private readonly files: DshRelaySecretFiles = new DshRelaySecretFiles();
  private readonly cipher: DshHuksSecretCipher = new DshHuksSecretCipher();

  save(master: string, token: string, host: string, name: string, origin: string,
    current: () => boolean = () => true): Promise<void> {
    const credentials = new DshRelayCredentials(master, token, host, name, origin);
    validate(credentials);
    const expected = ++version;
    const valid = (): boolean => expected === version && current();
    const pending = tail.then(async () => {
      assertCurrent(valid);
      await this.persist(credentials, valid);
    });
    tail = pending.catch(() => {});
    return pending;
  }

  load(): Promise<DshRelayCredentials | null> {
    const expected = version;
    const valid = (): boolean => expected === version;
    const pending = tail.then(async (): Promise<DshRelayCredentials | null> => {
      assertCurrent(valid);
      const protectedRecord = this.files.read(PROTECTED_PAIRING_FILE);
      if (protectedRecord) {
        const parts = unpackSecret(protectedRecord);
        const plaintext = await this.cipher.open(parts[0], parts[1]);
        try {
          assertCurrent(valid);
          const credentials = parseCredentials(utf8Decode(plaintext));
          this.files.removeLegacy();
          return credentials;
        } finally { plaintext.fill(0); }
      }
      const credentials = await this.loadLegacy(valid);
      assertCurrent(valid);
      if (credentials) await this.persist(credentials, valid);
      return credentials;
    });
    tail = pending.then(() => {}, () => {});
    return pending;
  }

  async masterKey(): Promise<string> { return (await this.load())?.masterKey ?? ''; }
  async clientToken(): Promise<string> { return (await this.load())?.clientToken ?? ''; }
  async hostId(): Promise<string> { return (await this.load())?.hostId ?? ''; }
  async hostName(): Promise<string> { return (await this.load())?.hostName ?? ''; }
  async relayOrigin(): Promise<string> { return (await this.load())?.origin ?? ''; }
  async hasPairing(): Promise<boolean> { return await this.load() !== null; }

  clear(): void {
    version++;
    this.files.clear();
    // Keep the non-exportable HUKS alias so asynchronous key deletion cannot race a new save.
  }

  private async persist(credentials: DshRelayCredentials, current: () => boolean): Promise<void> {
    const plaintext = utf8Encode(JSON.stringify(credentials));
    try {
      const iv = cryptoFramework.createRandom().generateRandomSync(12).data;
      const sealed = await this.cipher.seal(iv, plaintext);
      assertCurrent(current);
      this.files.commit(packSecret(iv, sealed), current);
      this.files.removeLegacy();
    } finally { plaintext.fill(0); }
  }

  private async loadLegacy(current: () => boolean): Promise<DshRelayCredentials | null> {
    const record = this.files.read('pairing.bin');
    const master = record ? null : this.files.read('master.bin');
    if (!record && !master) return null;
    const key = this.files.read('kek.bin');
    if (!key) throw new Error('Legacy pairing key unavailable');
    try {
      if (key.length !== 32) throw new Error('Invalid legacy pairing key');
      if (record) return parseCredentials(await this.decryptLegacy(record, key, current));
      const masterText = await this.decryptLegacy(master!, key, current);
      const token = await this.readLegacyField('client.bin', key, current);
      const host = await this.readLegacyField('host.bin', key, current);
      const name = await this.readLegacyField('name.bin', key, current);
      const origin = await this.readLegacyField('origin.bin', key, current);
      const credentials = new DshRelayCredentials(masterText, token, host, name, origin);
      validate(credentials);
      return credentials;
    } finally { key.fill(0); }
  }

  private async readLegacyField(name: string, key: Uint8Array, current: () => boolean): Promise<string> {
    const packed = this.files.read(name);
    if (!packed) throw new Error('Incomplete legacy pairing');
    return this.decryptLegacy(packed, key, current);
  }

  private async decryptLegacy(packed: Uint8Array, key: Uint8Array, current: () => boolean): Promise<string> {
    const parts = unpackSecret(packed);
    const plaintext = await aesGcmDecrypt(key, parts[0], new Uint8Array(0), parts[1]);
    try { assertCurrent(current); return utf8Decode(plaintext); } finally { plaintext.fill(0); }
  }
}

function assertCurrent(current: () => boolean): void {
  if (!current()) throw new Error('Pairing save cancelled');
}

function parseCredentials(text: string): DshRelayCredentials {
  const record = JSON.parse(text) as Record<string, Object>;
  if (record === null || typeof record !== 'object') throw new Error('Invalid pairing record');
  const fields = ['masterKey', 'clientToken', 'hostId', 'hostName', 'origin'];
  for (const field of fields) {
    if (typeof record[field] !== 'string') throw new Error('Invalid pairing record');
  }
  const credentials = new DshRelayCredentials(record['masterKey'] as string, record['clientToken'] as string,
    record['hostId'] as string, record['hostName'] as string, record['origin'] as string);
  validate(credentials);
  return credentials;
}

function validate(credentials: DshRelayCredentials): void {
  const decoded = decodeBase64Url(credentials.masterKey, 32);
  decoded.fill(0);
  if (!/^[\x21-\x7e]+$/.test(credentials.clientToken) || !credentials.hostId.trim()) {
    throw new Error('Invalid pairing credentials');
  }
  const parsed = new url.URL(credentials.origin);
  if ((parsed.protocol !== 'http:' && parsed.protocol !== 'https:') || !parsed.hostname || parsed.username || parsed.password) {
    throw new Error('Invalid relay origin');
  }
  if (utf8Encode(JSON.stringify(credentials)).length > 60 * 1024) throw new Error('Pairing credentials too large');
}
