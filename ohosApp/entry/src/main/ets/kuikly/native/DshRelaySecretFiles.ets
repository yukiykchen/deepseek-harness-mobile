import fs from '@ohos.file.fs';
import { toArrayBuffer } from './DshBytes';
import { DshOhosContext, pathExists } from './DshOhosContext';

export const PROTECTED_PAIRING_FILE = 'pairing-huks.bin';
const TEMP_FILE = `${PROTECTED_PAIRING_FILE}.tmp`;
const LEGACY_FILES = ['pairing.bin', 'master.bin', 'client.bin', 'host.bin', 'name.bin', 'origin.bin', 'kek.bin'];
const MAX_FILE_BYTES = 64 * 1024;

/** Only this class owns the on-disk layout and atomic record replacement. */
export class DshRelaySecretFiles {
  private readonly root: string = `${DshOhosContext.filesDir()}/relay-secrets`;

  constructor() {
    if (!pathExists(this.root)) fs.mkdirSync(this.root, true);
  }

  read(name: string): Uint8Array | null {
    const path = `${this.root}/${name}`;
    if (!pathExists(path)) return null;
    const file = fs.openSync(path, fs.OpenMode.READ_ONLY);
    try {
      const size = fs.statSync(file.fd).size;
      if (size < 1 || size > MAX_FILE_BYTES) throw new Error('Invalid pairing file size');
      const buffer = new ArrayBuffer(size);
      if (fs.readSync(file.fd, buffer) !== size) throw new Error('Incomplete pairing file');
      return new Uint8Array(buffer);
    } finally {
      fs.closeSync(file);
    }
  }

  commit(bytes: Uint8Array, current: () => boolean): void {
    if (!current()) throw new Error('Pairing save cancelled');
    if (bytes.length < 1 || bytes.length > MAX_FILE_BYTES) throw new Error('Invalid pairing file size');
    const temp = `${this.root}/${TEMP_FILE}`;
    try {
      const file = fs.openSync(temp, fs.OpenMode.READ_WRITE | fs.OpenMode.CREATE | fs.OpenMode.TRUNC);
      try {
        if (fs.writeSync(file.fd, toArrayBuffer(bytes)) !== bytes.length) throw new Error('Incomplete pairing write');
        fs.fsyncSync(file.fd);
      } finally {
        fs.closeSync(file);
      }
      if (!current()) throw new Error('Pairing save cancelled');
      fs.renameSync(temp, `${this.root}/${PROTECTED_PAIRING_FILE}`);
    } finally {
      if (pathExists(temp)) fs.unlinkSync(temp);
    }
  }

  removeLegacy(): void {
    for (const name of LEGACY_FILES) this.remove(name);
  }

  clear(): void {
    // Try every file even if one unlink fails; report failure to the caller.
    let failed = false;
    for (const name of [PROTECTED_PAIRING_FILE, TEMP_FILE, ...LEGACY_FILES]) {
      try { this.remove(name); } catch (error) { failed = true; }
    }
    if (failed) throw new Error('Cannot remove pairing credentials');
  }

  private remove(name: string): void {
    const path = `${this.root}/${name}`;
    if (pathExists(path)) fs.unlinkSync(path);
  }
}

export function unpackSecret(packed: Uint8Array): Uint8Array[] {
  if (packed.length < 29 || packed[0] !== 12) throw new Error('Invalid encrypted pairing');
  return [packed.subarray(1, 13), packed.subarray(13)];
}

export function packSecret(iv: Uint8Array, sealed: Uint8Array): Uint8Array {
  const packed = new Uint8Array(1 + iv.length + sealed.length);
  packed[0] = iv.length;
  packed.set(iv, 1);
  packed.set(sealed, 1 + iv.length);
  return packed;
}
