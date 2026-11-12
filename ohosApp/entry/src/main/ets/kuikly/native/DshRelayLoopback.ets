import { socket } from '@kit.NetworkKit';
import { cryptoFramework } from '@kit.CryptoArchitectureKit';
import { decodeBase64Std, encodeBase64Std, utf8Encode, toArrayBuffer, fromArrayBuffer } from './DshBytes';
import { jsonBool, jsonNumber, jsonObject, jsonString, mapOf } from './DshJson';
import { DshLoopbackListener } from './DshLoopbackListener';
import { FrameReader, MessageReader, MAX_MESSAGE_BYTES, OPCODE_PONG, encodeUnmasked, forwardOpcode, isClose, isData, isPing } from './DshWebSocketFrames';

type SendInner = (type: string, payload: Record<string, Object>, channel: string) => void;

export class DshRelayWaiter {
  private readonly queued: Record<string, Object>[] = [];
  private pending: ((value: Record<string, Object> | null) => void) | null = null;
  private timer: number = -1;
  private closed: boolean = false;
  offer(value: Record<string, Object>): boolean {
    if (this.closed) return false;
    if (this.pending) {
      const resolve = this.pending; this.pending = null;
      if (this.timer >= 0) clearTimeout(this.timer);
      this.timer = -1; resolve(value); return true;
    }
    if (this.queued.length >= 64) return false;
    this.queued.push(value); return true;
  }
  poll(timeout: number): Promise<Record<string, Object> | null> {
    if (this.closed) return Promise.resolve(null);
    if (this.queued.length) return Promise.resolve(this.queued.shift() as Record<string, Object>);
    return new Promise<Record<string, Object> | null>((resolve) => {
      this.pending = resolve;
      this.timer = setTimeout(() => { this.timer = -1; this.pending = null; resolve(null); }, timeout);
    });
  }
  cancel(): void {
    this.closed = true; this.queued.splice(0);
    if (this.timer >= 0) clearTimeout(this.timer);
    this.timer = -1;
    const resolve = this.pending; this.pending = null;
    if (resolve) resolve(null);
  }
}

class ClientConn {
  client: socket.TCPSocketConnection;
  buffer: Uint8Array = new Uint8Array(0);
  upgraded: boolean = false;
  upgrading: boolean = false;
  processing: boolean = false;
  closed: boolean = false;
  channel: string = '';
  kind: string = '';
  waiter: DshRelayWaiter | null = null;
  reader: FrameReader = new FrameReader(true);
  messages: MessageReader = new MessageReader();
  sendChain: Promise<void> = Promise.resolve();
  pendingFrames: Uint8Array[] = [];
  pendingBytes: number = 0;
  deadline: number = -1;
  queuedBytes: number = 0;
  queuedWrites: number = 0;
  constructor(client: socket.TCPSocketConnection) { this.client = client; }
}

export class DshRelayLoopbackServer {
  port: number = 0;
  private readonly token: string;
  private readonly sendInner: SendInner;
  private stopped: boolean = false;
  private started: boolean = false;
  private httpCount: number = 0;
  private wsCount: number = 0;
  private readonly peers: Set<ClientConn> = new Set();
  private readonly channels: Map<string, ClientConn> = new Map();
  constructor(token: string, sendInner: SendInner) { this.token = token; this.sendInner = sendInner; }

  async start(): Promise<number> {
    if (this.started || this.stopped) throw new Error('Loopback already started or stopped');
    this.started = true;
    const port = await DshLoopbackListener.acquire(this, (client) => this.accept(client));
    if (this.stopped) { DshLoopbackListener.release(this); throw new Error('Loopback start cancelled'); }
    this.port = port; return port;
  }
  isListening(): boolean { return !this.stopped && this.port > 0; }
  stop(): void {
    if (this.stopped) return;
    this.stopped = true; this.port = 0;
    DshLoopbackListener.release(this);
    this.peers.forEach((conn) => this.close(conn));
    this.channels.clear();
  }
  onInner(type: string, payload: Record<string, Object>, channel: string): void {
    if (this.stopped) return;
    const conn = this.channels.get(channel);
    if (!conn) {
      if (type === 'ws_open_ok') this.sendInner('ws_close', mapOf(['code', 'reason'], [1001, 'gateway closed']), channel);
      return;
    }
    if (type === 'http_res' || type === 'ws_open_ok') {
      if (conn.waiter && !conn.waiter.offer(type === 'ws_open_ok' ? mapOf(['ok'], [true]) : payload)) this.close(conn);
    } else if (type === 'ws_close') {
      conn.waiter?.offer(mapOf(['ok', 'reason'], [false, 'host websocket closed']));
      this.close(conn);
    } else if (type === 'ws_frame') {
      try {
        const bytes = decodeBase64Std(jsonString(payload, 'dataB64'));
        if (bytes.length > MAX_MESSAGE_BYTES) { this.close(conn); return; }
        const frame = encodeUnmasked(forwardOpcode(jsonNumber(payload, 'opcode')), bytes);
        if (!conn.upgraded) {
          if (conn.pendingFrames.length >= 64 || frame.length > MAX_MESSAGE_BYTES - conn.pendingBytes) { this.close(conn); return; }
          conn.pendingFrames.push(frame); conn.pendingBytes += frame.length;
        } else this.sendBytes(conn, frame).catch(() => this.close(conn));
      } catch (error) { this.close(conn); }
    }
  }

  private accept(client: socket.TCPSocketConnection): void {
    if (this.stopped || this.peers.size >= 64) { client.close().catch(() => {}); return; }
    const conn = new ClientConn(client); this.peers.add(conn);
    conn.deadline = setTimeout(() => this.close(conn), 10000);
    client.on('message', (value: socket.SocketMessageInfo) => {
      if (conn.closed || this.stopped) return;
      try {
        const bytes = fromArrayBuffer(value.message);
        if (conn.upgraded) { conn.reader.push(bytes); this.drainWs(conn); return; }
        if (conn.processing && !conn.upgrading) return;
        const limit = conn.upgrading ? MAX_MESSAGE_BYTES : 2 * 1024 * 1024 + 64 * 1024;
        if (bytes.length > limit - conn.buffer.length) { this.close(conn); return; }
        const next = new Uint8Array(conn.buffer.length + bytes.length);
        next.set(conn.buffer); next.set(bytes, conn.buffer.length); conn.buffer = next;
        if (!conn.processing) this.tryHttp(conn);
      } catch (error) { this.close(conn); }
    });
    client.on('close', () => this.close(conn, false));
    client.on('error', () => this.close(conn));
  }

  private tryHttp(conn: ClientConn): void {
    const split = indexOfHeaderEnd(conn.buffer);
    if (split < 0) {
      if (conn.buffer.length > 64 * 1024) this.writeHttp(conn, 400, 'header too large');
      return;
    }
    if (split > 64 * 1024) { this.writeHttp(conn, 400, 'header too large'); return; }
    const lines = bytesToAscii(conn.buffer.subarray(0, split)).split('\r\n');
    const request = lines[0].split(' ');
    if (request.length !== 3 || !request[1].startsWith('/') || !['HTTP/1.0', 'HTTP/1.1'].includes(request[2])) {
      this.writeHttp(conn, 400, 'invalid request'); return;
    }
    const headers: Record<string, string> = {};
    for (let i = 1; i < lines.length; i++) {
      const separator = lines[i].indexOf(':');
      if (separator < 1) { this.writeHttp(conn, 400, 'invalid header'); return; }
      const name = lines[i].substring(0, separator).trim().toLowerCase();
      const value = lines[i].substring(separator + 1).trim();
      if (!validHeader(name, value) || headers[name] !== undefined) { this.writeHttp(conn, 400, 'invalid header'); return; }
      headers[name] = value;
    }
    if (headers['authorization'] !== `Bearer ${this.token}`) { this.writeHttp(conn, 401, 'unauthorized'); return; }
    const rawLength = headers['content-length'] ?? '0';
    const length = Number(rawLength);
    if (!/^\d+$/.test(rawLength) || !Number.isSafeInteger(length) || headers['transfer-encoding'] !== undefined) {
      this.writeHttp(conn, 400, 'unsupported body'); return;
    }
    if (length > 2 * 1024 * 1024) { this.writeHttp(conn, 413, 'request too large'); return; }
    const bodyStart = split + 4;
    if (conn.buffer.length < bodyStart + length) return;
    const body = conn.buffer.slice(bodyStart, bodyStart + length);
    conn.buffer = conn.buffer.slice(bodyStart + length); conn.processing = true;
    if (conn.deadline >= 0) clearTimeout(conn.deadline);
    conn.deadline = -1;
    if ((headers['upgrade'] ?? '').toLowerCase() === 'websocket') {
      conn.upgrading = true;
      this.acceptWebSocket(conn, request[1], headers).catch(() => this.close(conn));
    } else this.proxyHttp(conn, request[0], request[1], headers, body).catch(() => this.close(conn));
  }

  private async proxyHttp(conn: ClientConn, method: string, path: string, headers: Record<string, string>, body: Uint8Array): Promise<void> {
    if (this.httpCount >= 32) { this.writeHttp(conn, 429, 'too many requests'); return; }
    this.httpCount++; conn.kind = 'http'; this.registerChannel(conn, 'http');
    const waiter = conn.waiter as DshRelayWaiter;
    try {
      this.sendInner('http_req', mapOf(['method', 'path', 'headers', 'bodyB64'],
        [method, path, forwardedHeaders(headers), encodeBase64Std(body)]), conn.channel);
      let headerWritten = false;
      while (!conn.closed && !this.stopped) {
        const part = await waiter.poll(120000);
        if (conn.closed || this.stopped) return;
        if (!part) { if (!headerWritten) await this.sendRaw(conn, 'HTTP/1.1 504 ERROR\r\nContent-Length: 0\r\nConnection: close\r\n\r\n'); return; }
        if (!headerWritten) {
          const status = jsonNumber(part, 'status', 200), responseHeaders = jsonObject(part, 'headers');
          if (!Number.isInteger(status) || status < 100 || status > 599) throw new Error('invalid status');
          let header = `HTTP/1.1 ${status} OK\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n`;
          const keys = Object.keys(responseHeaders);
          for (let i = 0; i < keys.length; i++) {
            const value = jsonString(responseHeaders, keys[i]);
            if (!validHeader(keys[i], value)) throw new Error('invalid response header');
            if (!hopHeaders.includes(keys[i].toLowerCase())) header += `${keys[i]}: ${value}\r\n`;
          }
          await this.sendRaw(conn, header + '\r\n'); headerWritten = true;
        }
        const bytes = decodeBase64Std(jsonString(part, 'bodyB64'));
        if (bytes.length) {
          await this.sendRaw(conn, `${bytes.length.toString(16)}\r\n`); await this.sendBytes(conn, bytes); await this.sendRaw(conn, '\r\n');
        }
        if (jsonBool(part, 'final')) { await this.sendRaw(conn, '0\r\n\r\n'); return; }
      }
    } finally { this.close(conn); }
  }

  private async acceptWebSocket(conn: ClientConn, path: string, headers: Record<string, string>): Promise<void> {
    if (decodeBase64Std(headers['sec-websocket-key'] ?? '').length !== 16 || headers['sec-websocket-version'] !== '13') {
      this.writeHttp(conn, 400, 'invalid WebSocket handshake'); return;
    }
    if (this.wsCount >= 16) { this.writeHttp(conn, 503, 'too many WebSockets'); return; }
    this.wsCount++; conn.kind = 'ws'; this.registerChannel(conn, 'ws');
    const waiter = conn.waiter as DshRelayWaiter;
    this.sendInner('ws_open', mapOf(['path', 'headers'], [path, forwardedHeaders(headers)]), conn.channel);
    const opened = await waiter.poll(10000);
    if (conn.closed || this.stopped) return;
    if (!opened || !jsonBool(opened, 'ok')) { this.writeHttp(conn, 502, 'host WebSocket rejected'); return; }
    waiter.cancel(); conn.waiter = null;
    const accept = await websocketAccept(headers['sec-websocket-key']);
    if (conn.closed || this.stopped) return;
    await this.sendRaw(conn, `HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: ${accept}\r\n\r\n`);
    if (conn.closed || this.stopped) return;
    const pending = conn.pendingFrames; conn.pendingFrames = []; conn.pendingBytes = 0;
    // Queue all early frames before accepting newly arriving Host frames.
    for (let i = 0; i < pending.length; i++) this.sendBytes(conn, pending[i]).catch(() => this.close(conn));
    conn.upgraded = true; conn.upgrading = false;
    if (conn.buffer.length) { conn.reader.push(conn.buffer); conn.buffer = new Uint8Array(0); this.drainWs(conn); }
  }

  private drainWs(conn: ClientConn): void {
    let frame = conn.reader.takeFrame();
    while (frame && !conn.closed) {
      if (isClose(frame.opcode)) { this.close(conn); return; }
      if (isPing(frame.opcode)) this.sendBytes(conn, encodeUnmasked(OPCODE_PONG, frame.payload)).catch(() => this.close(conn));
      else if (isData(frame.opcode)) {
        const message = conn.messages.accept(frame);
        if (message) this.sendInner('ws_frame', mapOf(['dataB64', 'opcode'], [encodeBase64Std(message.payload), message.opcode]), conn.channel);
      }
      frame = conn.reader.takeFrame();
    }
  }
  private registerChannel(conn: ClientConn, prefix: string): void {
    conn.channel = `${prefix}-${Date.now()}-${Math.random()}`; conn.waiter = new DshRelayWaiter(); this.channels.set(conn.channel, conn);
  }
  private close(conn: ClientConn, native: boolean = true): void {
    if (conn.closed) return;
    conn.closed = true; this.peers.delete(conn);
    if (conn.deadline >= 0) clearTimeout(conn.deadline);
    conn.waiter?.cancel(); conn.waiter = null;
    if (this.channels.get(conn.channel) === conn) this.channels.delete(conn.channel);
    if (conn.kind === 'http') this.httpCount--; else if (conn.kind === 'ws') {
      this.wsCount--;
      if (!this.stopped) this.sendInner('ws_close', mapOf(['code', 'reason'], [1000, '']), conn.channel);
    }
    conn.kind = ''; conn.buffer = new Uint8Array(0); conn.pendingFrames = [];
    if (native) { try { conn.client.close().catch(() => {}); } catch (error) {} }
  }
  private writeHttp(conn: ClientConn, status: number, body: string): void {
    conn.processing = true;
    const bytes = utf8Encode(body);
    this.sendRaw(conn, `HTTP/1.1 ${status} ERROR\r\nContent-Length: ${bytes.length}\r\nConnection: close\r\n\r\n`)
      .then(() => this.sendBytes(conn, bytes)).then(() => this.close(conn), () => this.close(conn));
  }
  private sendRaw(conn: ClientConn, text: string): Promise<void> { return this.sendBytes(conn, utf8Encode(text)); }
  private sendBytes(conn: ClientConn, bytes: Uint8Array): Promise<void> {
    if (conn.queuedWrites >= 64 || bytes.length > MAX_MESSAGE_BYTES - conn.queuedBytes) {
      this.close(conn); return Promise.reject(new Error('Loopback send queue too large'));
    }
    const data = toArrayBuffer(bytes);
    conn.queuedBytes += bytes.length; conn.queuedWrites++;
    const run = async (): Promise<void> => {
      try {
        if (conn.closed || this.stopped) throw new Error('Loopback peer closed');
        await conn.client.send({ data: data });
      } finally { conn.queuedBytes -= bytes.length; conn.queuedWrites--; }
    };
    const result = conn.sendChain.then(run);
    conn.sendChain = result.catch(() => this.close(conn)); return result;
  }
}

const hopHeaders = ['authorization', 'host', 'content-length', 'transfer-encoding', 'connection', 'upgrade', 'keep-alive', 'trailer'];
function forwardedHeaders(headers: Record<string, string>): Record<string, Object> {
  const result: Record<string, Object> = {}, keys = Object.keys(headers);
  for (let i = 0; i < keys.length; i++) if (!hopHeaders.includes(keys[i])) result[keys[i]] = headers[keys[i]];
  return result;
}
function validHeader(name: string, value: string): boolean { return /^[!#$%&'*+.^_`|~0-9A-Za-z-]+$/.test(name) && !/[\r\n\x00]/.test(value); }
function indexOfHeaderEnd(bytes: Uint8Array): number {
  for (let i = 0; i + 3 < bytes.length; i++) if (bytes[i] === 13 && bytes[i + 1] === 10 && bytes[i + 2] === 13 && bytes[i + 3] === 10) return i;
  return -1;
}
function bytesToAscii(bytes: Uint8Array): string {
  let text = ''; for (let i = 0; i < bytes.length; i++) text += String.fromCharCode(bytes[i]); return text;
}
async function websocketAccept(key: string): Promise<string> {
  const md = cryptoFramework.createMd('SHA1'); await md.update({ data: utf8Encode(`${key}258EAFA5-E914-47DA-95CA-C5AB0DC85B11`) });
  return encodeBase64Std((await md.digest()).data);
}
