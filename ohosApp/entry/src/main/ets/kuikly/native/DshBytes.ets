import { util } from '@kit.ArkTS';

export function utf8Encode(text: string): Uint8Array {
  return new util.TextEncoder().encodeInto(text);
}

export function utf8Decode(bytes: Uint8Array): string {
  return util.TextDecoder.create('utf-8', { fatal: true }).decodeToString(bytes);
}

export function concatBytes(left: Uint8Array, right: Uint8Array): Uint8Array {
  const out = new Uint8Array(left.length + right.length);
  out.set(left, 0);
  out.set(right, left.length);
  return out;
}

export function toArrayBuffer(bytes: Uint8Array): ArrayBuffer {
  const copy = new Uint8Array(bytes.length);
  copy.set(bytes);
  return copy.buffer;
}

export function fromArrayBuffer(buffer: ArrayBuffer): Uint8Array {
  return new Uint8Array(buffer);
}

export function encodeBase64Url(bytes: Uint8Array): string {
  const helper = new util.Base64Helper();
  return helper.encodeToStringSync(bytes)
    .replace(/\+/g, '-')
    .replace(/\//g, '_')
    .replace(/=+$/g, '');
}

export function decodeBase64Url(value: string, expected?: number): Uint8Array {
  if (!/^[A-Za-z0-9_-]*$/.test(value)) {
    throw new Error('invalid base64url');
  }
  let padded = value.replace(/-/g, '+').replace(/_/g, '/');
  while (padded.length % 4 !== 0) {
    padded += '=';
  }
  const helper = new util.Base64Helper();
  const decoded = helper.decodeSync(padded);
  if (encodeBase64Url(decoded) !== value) {
    throw new Error('non-canonical base64url');
  }
  if (expected !== undefined && decoded.length !== expected) {
    throw new Error('invalid binary length');
  }
  return decoded;
}

export function encodeBase64Std(bytes: Uint8Array): string {
  return new util.Base64Helper().encodeToStringSync(bytes).replace(/\n/g, '');
}

export function decodeBase64Std(value: string): Uint8Array {
  return new util.Base64Helper().decodeSync(value);
}

export function bytesToHex(bytes: Uint8Array): string {
  let hex = '';
  for (let i = 0; i < bytes.length; i++) {
    const part = bytes[i].toString(16);
    hex += part.length === 1 ? `0${part}` : part;
  }
  return hex;
}

export function writeUInt16BE(value: number): Uint8Array {
  const out = new Uint8Array(2);
  out[0] = (value >> 8) & 0xff;
  out[1] = value & 0xff;
  return out;
}

export function writeUInt64BE(value: number): Uint8Array {
  const out = new Uint8Array(8);
  let n = value;
  for (let i = 7; i >= 0; i--) {
    out[i] = n & 0xff;
    n = Math.floor(n / 256);
  }
  return out;
}

export function readUInt16BE(bytes: Uint8Array, offset: number): number {
  return ((bytes[offset] & 0xff) << 8) | (bytes[offset + 1] & 0xff);
}

export function constantTimeEquals(left: Uint8Array, right: Uint8Array): boolean {
  if (left.length !== right.length) {
    return false;
  }
  let diff = 0;
  for (let i = 0; i < left.length; i++) {
    diff |= left[i] ^ right[i];
  }
  return diff === 0;
}

export function callbackHasError(err: Object): boolean {
  if (err == null) {
    return false;
  }
  if (typeof err === 'string') {
    return false;
  }
  const rec = err as Record<string, Object>;
  const code = rec['code'];
  return typeof code === 'number' && code !== 0;
}

export function webSocketText(first: Object, second: Object): string {
  if (typeof first === 'string' || first instanceof ArrayBuffer) {
    return asUtf8(first);
  }
  if (second == null) {
    return '';
  }
  return asUtf8(second);
}

function asUtf8(value: Object): string {
  if (typeof value === 'string') {
    return value;
  }
  try {
    return utf8Decode(new Uint8Array(value as ArrayBuffer));
  } catch (error) {
    return '';
  }
}
