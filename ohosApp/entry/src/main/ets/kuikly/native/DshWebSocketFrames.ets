import { utf8Decode } from './DshBytes';

export const OPCODE_CONTINUATION = 0;
export const OPCODE_TEXT = 1;
export const OPCODE_BINARY = 2;
export const OPCODE_CLOSE = 8;
export const OPCODE_PING = 9;
export const OPCODE_PONG = 10;
export const MAX_MESSAGE_BYTES = 16 * 1024 * 1024;

export class WsFrame {
  opcode: number;
  payload: Uint8Array;
  final: boolean;

  constructor(opcode: number, payload: Uint8Array, final: boolean = true) {
    this.opcode = opcode;
    this.payload = payload;
    this.final = final;
  }
}

export function isClose(opcode: number): boolean {
  return opcode === OPCODE_CLOSE;
}

export function isPing(opcode: number): boolean {
  return opcode === OPCODE_PING;
}

export function isPong(opcode: number): boolean {
  return opcode === OPCODE_PONG;
}

export function isData(opcode: number): boolean {
  return opcode === OPCODE_CONTINUATION || opcode === OPCODE_TEXT || opcode === OPCODE_BINARY;
}

export function forwardOpcode(opcode: number): number {
  return opcode === OPCODE_BINARY ? OPCODE_BINARY : OPCODE_TEXT;
}

export function encodeUnmasked(opcode: number, payload: Uint8Array): Uint8Array {
  const headerSize = payload.length < 126 ? 2 : (payload.length <= 0xffff ? 4 : 10);
  const out = new Uint8Array(headerSize + payload.length);
  out[0] = 0x80 | (opcode & 0x0f);
  writeLength(out, payload.length, false);
  out.set(payload, headerSize);
  return out;
}

export class FrameReader {
  private buffer: Uint8Array = new Uint8Array(0);
  private readonly requireMasked: boolean;
  constructor(requireMasked: boolean = false) { this.requireMasked = requireMasked; }

  push(chunk: Uint8Array): void {
    if (chunk.length > MAX_MESSAGE_BYTES + 14 - this.buffer.length) throw new Error('WebSocket buffer too large');
    const next = new Uint8Array(this.buffer.length + chunk.length);
    next.set(this.buffer, 0);
    next.set(chunk, this.buffer.length);
    this.buffer = next;
  }

  takeFrame(): WsFrame | null {
    if (this.buffer.length < 2) {
      return null;
    }
    const b1 = this.buffer[0];
    const b2 = this.buffer[1];
    const opcode = b1 & 0x0f;
    const masked = (b2 & 0x80) !== 0;
    const final = (b1 & 0x80) !== 0;
    if ((b1 & 0x70) !== 0 || ![0, 1, 2, 8, 9, 10].includes(opcode) || (this.requireMasked && !masked)) {
      throw new Error('Invalid WebSocket frame');
    }
    let len = b2 & 0x7f;
    let offset = 2;
    if (len === 126) {
      if (this.buffer.length < 4) {
        return null;
      }
      len = ((this.buffer[2] & 0xff) << 8) | (this.buffer[3] & 0xff);
      if (len < 126) throw new Error('Nonminimal WebSocket length');
      offset = 4;
    } else if (len === 127) {
      if (this.buffer.length < 10) {
        return null;
      }
      len = 0;
      if ((this.buffer[2] & 0x80) !== 0) throw new Error('Invalid WebSocket length');
      for (let i = 2; i < 10; i++) {
        len = len * 256 + (this.buffer[i] & 0xff);
      }
      if (len < 65536) throw new Error('Nonminimal WebSocket length');
      offset = 10;
    }
    if (!Number.isSafeInteger(len) || len > MAX_MESSAGE_BYTES || (opcode >= 8 && (!final || len > 125))) {
      throw new Error('Invalid WebSocket frame size');
    }
    const maskLen = masked ? 4 : 0;
    if (this.buffer.length < offset + maskLen + len) {
      return null;
    }
    const mask = masked ? this.buffer.subarray(offset, offset + 4) : new Uint8Array(0);
    const dataStart = offset + maskLen;
    const data = this.buffer.subarray(dataStart, dataStart + len);
    const payload = new Uint8Array(len);
    payload.set(data);
    if (masked) {
      for (let i = 0; i < payload.length; i++) {
        payload[i] = payload[i] ^ mask[i % 4];
      }
    }
    this.buffer = this.buffer.subarray(dataStart + len);
    if (opcode === 8 && len === 1) throw new Error('Invalid WebSocket close frame');
    return new WsFrame(opcode, payload, final);
  }
}

export class MessageReader {
  private opcode: number = -1;
  private chunks: Uint8Array[] = [];
  private length: number = 0;
  accept(frame: WsFrame): WsFrame | null {
    if (!isData(frame.opcode)) return frame;
    if (frame.opcode === OPCODE_CONTINUATION) {
      if (this.opcode < 0) throw new Error('Unexpected WebSocket continuation');
    } else {
      if (this.opcode >= 0) throw new Error('Nested WebSocket message');
      this.opcode = frame.opcode;
    }
    if (frame.payload.length > MAX_MESSAGE_BYTES - this.length || this.chunks.length >= 4096) {
      throw new Error('WebSocket message too large');
    }
    this.chunks.push(frame.payload); this.length += frame.payload.length;
    if (!frame.final) return null;
    const payload = new Uint8Array(this.length);
    let offset = 0;
    for (let i = 0; i < this.chunks.length; i++) { payload.set(this.chunks[i], offset); offset += this.chunks[i].length; }
    const message = new WsFrame(this.opcode, payload);
    if (this.opcode === OPCODE_TEXT) utf8Decode(payload);
    this.chunks = []; this.length = 0; this.opcode = -1;
    return message;
  }
}

function writeLength(out: Uint8Array, size: number, masked: boolean): void {
  const maskBit = masked ? 0x80 : 0;
  if (size < 126) {
    out[1] = maskBit | size;
    return;
  }
  if (size <= 0xffff) {
    out[1] = maskBit | 126;
    out[2] = (size >> 8) & 0xff;
    out[3] = size & 0xff;
    return;
  }
  out[1] = maskBit | 127;
  let n = size;
  for (let i = 9; i >= 2; i--) {
    out[i] = n & 0xff;
    n = Math.floor(n / 256);
  }
}
