const crypto = require('node:crypto');

class Socket {
  constructor() { this.events = new Map(); this.sent = []; this.closed = false; }
  on(name, callback) { this.events.set(name, callback); }
  off(name) { this.events.delete(name); }
  emit(name, ...values) { this.events.get(name)?.(...values); }
  async connect() {}
  send(value, callback) {
    if (this.closed) { callback?.({ code: 1 }, false); return Promise.reject(new Error('closed')); }
    this.sent.push(typeof value === 'string' ? value : Buffer.from(value.data));
    callback?.({ code: 0 }, true);
    return Promise.resolve();
  }
  async close() { if (this.closed) return; this.closed = true; this.emit('close'); }
  message(bytes) { this.emit('message', { message: Uint8Array.from(bytes).buffer }); }
}

function createMocks() {
  const files = new Map();
  const handles = new Map();
  const servers = [];
  const tcpSockets = [];
  const slots = new Map();
  let sequence = 0;
  const fs = {
    OpenMode: { READ_ONLY: 1, READ_WRITE: 2, CREATE: 4, TRUNC: 8 },
    mkdirSync() {},
    openSync(path, mode) {
      if (mode & 4 && !files.has(path)) files.set(path, Buffer.alloc(0));
      if (mode & 8) files.set(path, Buffer.alloc(0));
      const fd = ++sequence; handles.set(fd, path); return { fd };
    },
    closeSync(file) { handles.delete(typeof file === 'number' ? file : file.fd); },
    writeSync(fd, buffer) { files.set(handles.get(fd), Buffer.from(buffer)); return buffer.byteLength; },
    statSync(fd) { return { size: files.get(handles.get(fd)).length }; },
    readSync(fd, buffer) { const value = files.get(handles.get(fd)); new Uint8Array(buffer).set(value); return value.length; },
    unlinkSync(path) { files.delete(path); },
    renameSync(from, to) { files.set(to, files.get(from)); files.delete(from); },
    fsyncSync() {},
  };
  const kit = {
    connection: {
      NetBearType: { BEARER_WIFI: 1, BEARER_ETHERNET: 2 }, NetCap: { NET_CAPABILITY_INTERNET: 1 },
      async getAllNets() { return []; },
      async getNetCapabilities() { return { bearerTypes: [], networkCap: [] }; },
      async setAppNet() {},
      getAppNetSync() { throw new Error('no app net'); },
      getDefaultNetSync() { throw new Error('no default net'); },
      async getDefaultNet() { throw new Error('no default net'); },
      createNetConnection() { return { on() {}, register(callback) { callback({ code: 0 }); }, unregister() {} }; },
    },
    webSocket: { createWebSocket: () => new Socket() },
    socket: {
      constructTCPSocketInstance() {
        const socket = new Socket();
        socket.bind = async () => {};
        socket.getLocalAddress = async () => ({ address: '127.0.0.1', port: 0 });
        socket.setExtraOptions = async () => {};
        tcpSockets.push(socket);
        return socket;
      },
      constructTCPSocketServerInstance() {
        const server = new Socket(); server.listen = async () => {}; server.getLocalAddress = async () => ({ port: 18999 });
        servers.push(server); return server;
      },
    },
  };
  const cryptoFramework = {
    CryptoMode: { ENCRYPT_MODE: 1, DECRYPT_MODE: 2 },
    createRandom: () => ({ generateRandomSync: (count) => ({ data: Uint8Array.from(crypto.randomBytes(count)) }) }),
    createSymKeyGenerator: () => ({ async convertKey(blob) { return { bytes: Buffer.from(blob.data) }; } }),
    createMd(algorithm) {
      const hash = crypto.createHash(algorithm.toLowerCase());
      return { async update(blob) { hash.update(blob.data); }, async digest() { return { data: Uint8Array.from(hash.digest()) }; } };
    },
    createMac(algorithm) {
      let mac;
      return { async init(key) { mac = crypto.createHmac(algorithm.toLowerCase(), key.bytes); },
        async update(blob) { mac.update(blob.data); }, async doFinal() { return { data: Uint8Array.from(mac.digest()) }; } };
    },
    createCipher() {
      let operation, encryption;
      return {
        async init(mode, key, params) {
          encryption = mode === 1;
          operation = encryption ? crypto.createCipheriv('aes-256-gcm', key.bytes, params.iv.data)
            : crypto.createDecipheriv('aes-256-gcm', key.bytes, params.iv.data);
          operation.setAAD(Buffer.from(params.aad.data));
          if (!encryption) operation.setAuthTag(Buffer.from(params.authTag.data));
        },
        async doFinal(blob) {
          const bytes = Buffer.concat([operation.update(blob.data), operation.final()]);
          return { data: Uint8Array.from(encryption ? Buffer.concat([bytes, operation.getAuthTag()]) : bytes) };
        },
      };
    },
  };
  const protectedKeys = new Map(), sessions = new Map();
  const huks = {
    HuksTag: { HUKS_TAG_ALGORITHM: 1, HUKS_TAG_KEY_SIZE: 2, HUKS_TAG_PURPOSE: 3,
      HUKS_TAG_BLOCK_MODE: 4, HUKS_TAG_PADDING: 5, HUKS_TAG_NONCE: 6, HUKS_TAG_AE_TAG: 7 },
    HuksKeyPurpose: { HUKS_KEY_PURPOSE_ENCRYPT: 1, HUKS_KEY_PURPOSE_DECRYPT: 2 },
    HuksKeyAlg: { HUKS_ALG_AES: 1 }, HuksCipherMode: { HUKS_MODE_GCM: 1 },
    HuksKeyPadding: { HUKS_PADDING_NONE: 0 },
    async isKeyItemExist(alias) { return protectedKeys.has(alias); },
    async generateKeyItem(alias) {
      if (protectedKeys.has(alias)) throw new Error('key already exists');
      protectedKeys.set(alias, crypto.randomBytes(32));
    },
    async initSession(alias, options) {
      const value = (tag) => options.properties.find((property) => property.tag === tag)?.value;
      const encrypt = value(3) === 1, key = protectedKeys.get(alias);
      if (!key) throw new Error('protected key missing');
      const operation = encrypt ? crypto.createCipheriv('aes-256-gcm', key, value(6))
        : crypto.createDecipheriv('aes-256-gcm', key, value(6));
      if (!encrypt) operation.setAuthTag(Buffer.from(value(7)));
      const handle = ++sequence; sessions.set(handle, { operation, encrypt }); return { handle };
    },
    async finishSession(handle, options) {
      const { operation, encrypt } = sessions.get(handle);
      const output = Buffer.concat([operation.update(options.inData), operation.final()]);
      sessions.delete(handle);
      return { outData: Uint8Array.from(encrypt ? Buffer.concat([output, operation.getAuthTag()]) : output) };
    },
    async abortSession(handle) { sessions.delete(handle); },
  };
  const context = {
    DshOhosContext: {
      filesDir: () => '/test-files',
      bindSlot(callback) { const id = `slot-${++sequence}`; slots.set(id, callback); return id; },
      bindOnce(callback) { const id = `slot-${++sequence}`; slots.set(id, (value) => { slots.delete(id); callback(value); }); return id; },
      unbindSlot(id) { slots.delete(id); }, postToMain(id, value) { slots.get(id)?.(value); },
    },
    pathExists: (path) => files.has(path), dshLog() {}, dshError() {},
  };
  const util = {
    TextEncoder: class { encodeInto(text) { return Buffer.from(text, 'utf8'); } },
    TextDecoder: class {
      static create(encoding, options) { return new this(encoding, options); }
      constructor(encoding, options) { this.decoder = new TextDecoder(encoding, options); }
      decodeToString(bytes) { return this.decoder.decode(bytes, { fatal: true }); }
      decodeWithStream(bytes) { return this.decoder.decode(bytes, { fatal: true }); }
    },
    Base64Helper: class {
      encodeToStringSync(bytes) { return Buffer.from(bytes).toString('base64'); }
      decodeSync(text) { return Uint8Array.from(Buffer.from(text, 'base64')); }
    },
    generateRandomUUID: () => crypto.randomUUID(),
  };
  return { kit, cryptoFramework, huks, protectedKeys, sessions, fs, context, util, files, servers, tcpSockets, slots, Socket };
}
module.exports = { createMocks, Socket };
