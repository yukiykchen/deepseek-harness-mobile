import { cryptoFramework } from '@kit.CryptoArchitectureKit';
import {
  concatBytes,
  constantTimeEquals,
  decodeBase64Url,
  encodeBase64Url,
  utf8Decode,
  utf8Encode,
} from './DshBytes';
import { dshError } from './DshOhosContext';

const CLAIM_INFO = 'dsh-claim-v1';
const KEY_BYTES = 32;
const RANDOM_BYTES = 32;
const TAG_BYTES = 16;

class Material {
  c2dKey: Uint8Array;
  d2cKey: Uint8Array;
  c2dNonceBase: Uint8Array;
  d2cNonceBase: Uint8Array;

  constructor(
    c2dKey: Uint8Array,
    d2cKey: Uint8Array,
    c2dNonceBase: Uint8Array,
    d2cNonceBase: Uint8Array,
  ) {
    this.c2dKey = c2dKey;
    this.d2cKey = d2cKey;
    this.c2dNonceBase = c2dNonceBase;
    this.d2cNonceBase = d2cNonceBase;
  }
}

export class DshSealedPayload {
  seq: string;
  ciphertextB64: string;

  constructor(seq: string, ciphertextB64: string) {
    this.seq = seq;
    this.ciphertextB64 = ciphertextB64;
  }
}

export class DshSecureCipher {
  private readonly accessSessionId: string;
  private readonly sendDirection: string;
  private readonly sendKey: Uint8Array;
  private readonly sendNonceBase: Uint8Array;
  private readonly receiveDirection: string;
  private readonly receiveKey: Uint8Array;
  private readonly receiveNonceBase: Uint8Array;
  private sendSequence: number = 0;
  private receiveSequence: number = 0;
  private sendChain: Promise<void> = Promise.resolve();
  private receiveChain: Promise<void> = Promise.resolve();

  constructor(
    accessSessionId: string,
    sendDirection: string,
    sendKey: Uint8Array,
    sendNonceBase: Uint8Array,
    receiveDirection: string,
    receiveKey: Uint8Array,
    receiveNonceBase: Uint8Array,
  ) {
    this.accessSessionId = accessSessionId;
    this.sendDirection = sendDirection;
    this.sendKey = sendKey;
    this.sendNonceBase = sendNonceBase;
    this.receiveDirection = receiveDirection;
    this.receiveKey = receiveKey;
    this.receiveNonceBase = receiveNonceBase;
  }

  seal(value: Record<string, Object>): Promise<DshSealedPayload> {
    const snapshot = JSON.stringify(value);
    const result = this.sendChain.then(() => this.sealNext(snapshot));
    this.sendChain = result.then(() => {}, () => {});
    return result;
  }

  private async sealNext(snapshot: string): Promise<DshSealedPayload> {
    const sequence = this.sendSequence;
    if (!Number.isSafeInteger(sequence) || sequence >= Number.MAX_SAFE_INTEGER) throw new Error('sequence exhausted');
    this.sendSequence += 1;
    const ciphertext = await aesGcmSeal(
      this.sendKey,
      nonce(this.sendNonceBase, sequence),
      aad(this.sendDirection, this.accessSessionId, sequence),
      utf8Encode(snapshot),
    );
    return new DshSealedPayload(`${sequence}`, encodeBase64Url(ciphertext));
  }

  open(payload: DshSealedPayload): Promise<Record<string, Object>> {
    const snapshot = new DshSealedPayload(payload.seq, payload.ciphertextB64);
    const result = this.receiveChain.then(() => this.openNext(snapshot));
    this.receiveChain = result.then(() => {}, () => {});
    return result;
  }

  private async openNext(payload: DshSealedPayload): Promise<Record<string, Object>> {
    if (!/^(0|[1-9][0-9]*)$/.test(payload.seq)) {
      throw new Error('invalid sequence');
    }
    const sequence = Number(payload.seq);
    if (!Number.isSafeInteger(sequence) || sequence >= Number.MAX_SAFE_INTEGER || sequence !== this.receiveSequence) {
      throw new Error('unexpected sequence');
    }
    const sealed = decodeBase64Url(payload.ciphertextB64);
    if (sealed.length < TAG_BYTES) {
      throw new Error('truncated ciphertext');
    }
    try {
      const plaintext = await aesGcmOpen(
        this.receiveKey,
        nonce(this.receiveNonceBase, sequence),
        aad(this.receiveDirection, this.accessSessionId, sequence),
        sealed,
      );
      const result = JSON.parse(utf8Decode(plaintext)) as Record<string, Object>;
      if (!result || typeof result !== 'object' || Array.isArray(result)) throw new Error('invalid sealed object');
      this.receiveSequence += 1;
      return result;
    } catch (error) {
      dshError('ciphertext authentication failed', error as Object);
      throw new Error('ciphertext authentication failed');
    }
  }
}

export class DshSealedTunnelCrypto {
  static async randomBytes(count: number = RANDOM_BYTES): Promise<Uint8Array> {
    return cryptoFramework.createRandom().generateRandomSync(count).data;
  }

  static async deriveClaimToken(masterKeyB64: string): Promise<string> {
    const key = decodeBase64Url(masterKeyB64, KEY_BYTES);
    const derived = await hkdfSha256(key, new Uint8Array(0), utf8Encode(CLAIM_INFO), KEY_BYTES);
    return encodeBase64Url(derived);
  }

  static async clientProof(
    masterKeyB64: string,
    accessSessionId: string,
    clientRandomB64: string,
  ): Promise<string> {
    const key = decodeBase64Url(masterKeyB64, KEY_BYTES);
    decodeBase64Url(clientRandomB64, RANDOM_BYTES);
    const mac = await hmacSha256(key, canonical(['dsh-e2ee-client', 1, accessSessionId, clientRandomB64]));
    return encodeBase64Url(mac);
  }

  static async serverProof(
    masterKeyB64: string,
    accessSessionId: string,
    clientRandomB64: string,
    serverRandomB64: string,
  ): Promise<string> {
    const key = decodeBase64Url(masterKeyB64, KEY_BYTES);
    decodeBase64Url(clientRandomB64, RANDOM_BYTES);
    decodeBase64Url(serverRandomB64, RANDOM_BYTES);
    const mac = await hmacSha256(
      key,
      canonical(['dsh-e2ee-server', 1, accessSessionId, clientRandomB64, serverRandomB64]),
    );
    return encodeBase64Url(mac);
  }

  static async createClientCipher(
    masterKeyB64: string,
    accessSessionId: string,
    clientRandomB64: string,
    serverRandomB64: string,
    serverProofB64: string,
  ): Promise<DshSecureCipher> {
    const expected = await DshSealedTunnelCrypto.serverProof(
      masterKeyB64,
      accessSessionId,
      clientRandomB64,
      serverRandomB64,
    );
    if (!constantTimeEquals(decodeBase64Url(expected), decodeBase64Url(serverProofB64))) {
      throw new Error('server proof failed');
    }
    const material = await deriveMaterial(masterKeyB64, accessSessionId, clientRandomB64, serverRandomB64);
    return new DshSecureCipher(
      accessSessionId,
      'c2d',
      material.c2dKey,
      material.c2dNonceBase,
      'd2c',
      material.d2cKey,
      material.d2cNonceBase,
    );
  }

  static async createHostCipher(master: string, session: string, client: string, server: string): Promise<DshSecureCipher> {
    const material = await deriveMaterial(master, session, client, server);
    return new DshSecureCipher(session, 'd2c', material.d2cKey, material.d2cNonceBase,
      'c2d', material.c2dKey, material.c2dNonceBase);
  }
}

export async function aesGcmEncrypt(
  key: Uint8Array,
  iv: Uint8Array,
  aadBytes: Uint8Array,
  plaintext: Uint8Array,
): Promise<Uint8Array> {
  return aesGcmSeal(key, iv, aadBytes, plaintext);
}

export async function aesGcmDecrypt(
  key: Uint8Array,
  iv: Uint8Array,
  aadBytes: Uint8Array,
  sealed: Uint8Array,
): Promise<Uint8Array> {
  return aesGcmOpen(key, iv, aadBytes, sealed);
}

export function canonical(parts: Array<string | number>): Uint8Array {
  return utf8Encode(JSON.stringify(parts));
}

function aad(direction: string, accessSessionId: string, sequence: number): Uint8Array {
  return canonical(['dsh-e2ee', 1, accessSessionId, direction, `${sequence}`]);
}

function nonce(prefix: Uint8Array, sequence: number): Uint8Array {
  const out = new Uint8Array(12);
  for (let i = 0; i < 4; i++) {
    out[i] = i < prefix.length ? prefix[i] : 0;
  }
  let n = sequence;
  for (let i = 11; i >= 4; i--) {
    out[i] = n & 0xff;
    n = Math.floor(n / 256);
  }
  return out;
}

async function sha256(message: Uint8Array): Promise<Uint8Array> {
  const md = cryptoFramework.createMd('SHA256');
  await md.update({ data: message });
  return (await md.digest()).data;
}

async function hmacSha256(key: Uint8Array, message: Uint8Array): Promise<Uint8Array> {
  const mac = cryptoFramework.createMac('SHA256');
  const blob: cryptoFramework.DataBlob = { data: key };
  let symKey: cryptoFramework.SymKey;
  try {
    symKey = await cryptoFramework.createSymKeyGenerator('HMAC').convertKey(blob);
  } catch (error) {
    symKey = await cryptoFramework.createSymKeyGenerator('HMAC|SHA256').convertKey(blob);
  }
  await mac.init(symKey);
  await mac.update({ data: message });
  return (await mac.doFinal()).data;
}

async function hkdfSha256(
  ikm: Uint8Array,
  salt: Uint8Array,
  info: Uint8Array,
  length: number,
): Promise<Uint8Array> {
  const extractSalt = salt.length === 0 ? new Uint8Array(32) : salt;
  const prk = await hmacSha256(extractSalt, ikm);
  const result = new Uint8Array(length);
  let previous = new Uint8Array(0);
  let offset = 0;
  let counter = 1;
  while (offset < length) {
    const counterByte = new Uint8Array(1);
    counterByte[0] = counter;
    const block = concatBytes(concatBytes(previous, info), counterByte);
    previous = await hmacSha256(prk, block);
    const copy = Math.min(previous.length, length - offset);
    result.set(previous.subarray(0, copy), offset);
    offset += copy;
    counter += 1;
  }
  return result;
}

async function deriveMaterial(
  masterKeyB64: string,
  accessSessionId: string,
  clientRandomB64: string,
  serverRandomB64: string,
): Promise<Material> {
  const key = decodeBase64Url(masterKeyB64, KEY_BYTES);
  decodeBase64Url(clientRandomB64, RANDOM_BYTES);
  decodeBase64Url(serverRandomB64, RANDOM_BYTES);
  const salt = await sha256(canonical(['dsh-e2ee-salt', 1, accessSessionId, clientRandomB64, serverRandomB64]));
  const expand = async (info: string, length: number): Promise<Uint8Array> =>
    hkdfSha256(key, salt, utf8Encode(info), length);
  return new Material(
    await expand('dsh-e2ee-v1:c2d:key', KEY_BYTES),
    await expand('dsh-e2ee-v1:d2c:key', KEY_BYTES),
    await expand('dsh-e2ee-v1:c2d:nonce', 4),
    await expand('dsh-e2ee-v1:d2c:nonce', 4),
  );
}

async function importAesKey(key: Uint8Array): Promise<cryptoFramework.SymKey> {
  const generator = cryptoFramework.createSymKeyGenerator('AES256');
  return await generator.convertKey({ data: key });
}

async function aesGcmSeal(
  key: Uint8Array,
  iv: Uint8Array,
  aadBytes: Uint8Array,
  plaintext: Uint8Array,
): Promise<Uint8Array> {
  const cipher = cryptoFramework.createCipher('AES256|GCM|NoPadding');
  const params: cryptoFramework.GcmParamsSpec = {
    algName: 'GcmParamsSpec',
    iv: { data: iv },
    aad: { data: aadBytes },
    authTag: { data: new Uint8Array(TAG_BYTES) },
  };
  await cipher.init(cryptoFramework.CryptoMode.ENCRYPT_MODE, await importAesKey(key), params);
  const encrypted = (await cipher.doFinal({ data: plaintext })).data;
  if (encrypted.length >= plaintext.length + TAG_BYTES) {
    return encrypted;
  }
  const tag = params.authTag.data;
  if (tag.length === TAG_BYTES) {
    return concatBytes(encrypted, tag);
  }
  throw new Error('gcm encrypt missing auth tag');
}

async function aesGcmOpen(
  key: Uint8Array,
  iv: Uint8Array,
  aadBytes: Uint8Array,
  sealed: Uint8Array,
): Promise<Uint8Array> {
  const body = sealed.subarray(0, sealed.length - TAG_BYTES);
  const tag = sealed.subarray(sealed.length - TAG_BYTES);
  const cipher = cryptoFramework.createCipher('AES256|GCM|NoPadding');
  const params: cryptoFramework.GcmParamsSpec = {
    algName: 'GcmParamsSpec',
    iv: { data: iv },
    aad: { data: aadBytes },
    authTag: { data: tag },
  };
  await cipher.init(cryptoFramework.CryptoMode.DECRYPT_MODE, await importAesKey(key), params);
  return (await cipher.doFinal({ data: body })).data;
}
