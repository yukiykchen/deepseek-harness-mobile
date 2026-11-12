const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { createLoader } = require('./loader.cjs');
const { deferred, flush } = require('./runtime-tests.cjs');

const upgrade = 'GET /events HTTP/1.1\r\nAuthorization: Bearer local-token\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Key: MDEyMzQ1Njc4OWFiY2RlZg==\r\n\r\n';
async function gatewayFixture(send = () => {}) {
  const loader = createLoader(); const { DshRelayLoopbackServer } = loader.load('DshRelayLoopback');
  let gateway; gateway = new DshRelayLoopbackServer('local-token', (...args) => send(gateway, ...args)); await gateway.start();
  function peer() { const peer = new loader.mocks.Socket(); loader.mocks.servers[0].emit('connect', peer); return peer; }
  return { ...loader, gateway, peer, DshRelayLoopbackServer };
}

async function gatewayTests(test) {
  await test('Host WebSocket disconnect and destruction release callback slots and sockets', async () => {
    const f = createLoader(), sockets = [];
    f.mocks.kit.webSocket.createWebSocket = () => {
      const socket = new f.mocks.Socket();
      sockets.push(socket);
      return socket;
    };
    const { KRDshWebSocketModule } = f.loadModule('KRDshWebSocketModule');
    const host = new KRDshWebSocketModule();
    const connect = (id) => host.call('connect', JSON.stringify({ connectionId: id, url: 'ws://127.0.0.1/events' }), () => {});
    connect('first');
    host.call('disconnect', JSON.stringify({ connectionId: 'first' }), null);
    assert.equal(f.mocks.slots.size, 0);
    assert.equal(sockets[0].closed, true);
    connect('second');
    host.onDestroy();
    assert.equal(f.mocks.slots.size, 0);
    assert.equal(sockets[1].closed, true);
  });
  await test('disconnect cancels the scanner slot before a late QR result can start native pairing', async () => {
    const f = createLoader(), scan = deferred(), { KRDshRelayModule } = f.loadModule('KRDshRelayModule');
    const runtime = f.load('DshRelayRuntime').DshRelayRuntime.shared;
    let pairings = 0, responses = 0;
    runtime.pairFromQr = async () => { pairings++; return {}; };
    f.scanner.scanRelayQr = () => scan.promise;
    const module = new KRDshRelayModule();
    module.call('scanAndPair', null, () => responses++);
    module.call('disconnect', null, null);
    scan.resolve('https://relay.example/?id=pair&key=secret'); await flush();
    assert.equal(pairings, 0); assert.equal(responses, 0); assert.equal(f.mocks.slots.size, 0);
    module.onDestroy();
  });
  await test('API12 gateway replacements reuse one listener and close old unupgraded peers', async () => {
    const f = await gatewayFixture(), old = f.peer(); f.gateway.stop();
    assert.equal(old.closed, true); assert.equal(f.gateway.isListening(), false);
    const next = new f.DshRelayLoopbackServer('next-token', () => {}); await next.start();
    assert.equal(f.mocks.servers.length, 1); const peer = f.peer(); next.stop(); assert.equal(peer.closed, true);
  });
  await test('early WebSocket frames follow the complete upgrade in order', async () => {
    const f = await gatewayFixture((gateway, type, _payload, channel) => {
      if (type !== 'ws_open') return;
      gateway.onInner('ws_frame', { opcode: 1, dataB64: Buffer.from('first').toString('base64') }, channel);
      gateway.onInner('ws_open_ok', {}, channel);
      gateway.onInner('ws_frame', { opcode: 1, dataB64: Buffer.from('second').toString('base64') }, channel);
    });
    const peer = f.peer(); peer.message(Buffer.from(upgrade)); await flush();
    assert.match(peer.sent[0].toString(), /^HTTP\/1.1 101/);
    const frames = f.load('DshWebSocketFrames'), reader = new frames.FrameReader();
    for (const chunk of peer.sent.slice(1)) reader.push(chunk);
    assert.equal(Buffer.from(reader.takeFrame().payload).toString(), 'first');
    assert.equal(Buffer.from(reader.takeFrame().payload).toString(), 'second'); f.gateway.stop();
  });
  await test('a stopped pending WebSocket upgrade cannot continue or double decrement counters', async () => {
    const f = await gatewayFixture(), peer = f.peer(); peer.message(Buffer.from(upgrade)); await flush();
    assert.equal(f.gateway.wsCount, 1); f.gateway.stop(); await flush();
    assert.equal(f.gateway.wsCount, 0); assert.equal(peer.closed, true); assert.equal(peer.sent.length, 0);
  });
  await test('oversized content lengths are rejected before waiting for the body', async () => {
    let forwarded = 0; const f = await gatewayFixture(() => forwarded++), peer = f.peer();
    peer.message(Buffer.from('POST /bad HTTP/1.1\r\nAuthorization: Bearer local-token\r\nContent-Length: 2097153\r\n\r\n')); await flush();
    assert.match(peer.sent[0].toString(), /^HTTP\/1.1 413/); assert.equal(forwarded, 0); assert.equal(peer.closed, true); f.gateway.stop();
  });
  await test('only one HTTP request is dispatched while its response is pending', async () => {
    let forwarded = 0; const f = await gatewayFixture(() => forwarded++), peer = f.peer();
    const request = Buffer.from('GET /one HTTP/1.1\r\nAuthorization: Bearer local-token\r\n\r\n');
    peer.message(request); peer.message(request); await flush(); assert.equal(forwarded, 1);
    f.gateway.stop(); await flush(); assert.equal(f.gateway.httpCount, 0);
  });
  await test('waiter cancellation is permanent and queued response parts are bounded', async () => {
    const { DshRelayWaiter } = createLoader().load('DshRelayLoopback'), waiter = new DshRelayWaiter();
    const waiting = waiter.poll(120000); waiter.cancel(); assert.equal(await waiting, null);
    assert.equal(waiter.offer({}), false); assert.equal(await waiter.poll(1), null);
    const bounded = new DshRelayWaiter(); for (let i = 0; i < 64; i++) assert.equal(bounded.offer({}), true);
    assert.equal(bounded.offer({}), false); bounded.cancel();
  });
  await test('slow unauthenticated peers are capped and closed on stop', async () => {
    const f = await gatewayFixture(); const peers = Array.from({ length: 65 }, () => f.peer());
    assert.equal(peers[64].closed, true); assert.equal(f.gateway.peers.size, 64); f.gateway.stop();
    assert.equal(peers.every((peer) => peer.closed), true); assert.equal(f.gateway.peers.size, 0);
  });
  await test('local write queue preserves invocation order despite delayed native sends', async () => {
    const f = await gatewayFixture(), peer = f.peer(), first = deferred(), writes = [];
    peer.send = async (value) => { writes.push(Buffer.from(value.data).toString()); if (writes.length === 1) await first.promise; };
    const conn = [...f.gateway.peers][0];
    const one = f.gateway.sendBytes(conn, Buffer.from('first')); const two = f.gateway.sendBytes(conn, Buffer.from('second'));
    await flush(); assert.deepEqual(writes, ['first']); first.resolve(); await Promise.all([one, two]);
    assert.deepEqual(writes, ['first', 'second']); f.gateway.stop();
  });
  await test('64-bit oversized frame length is rejected before allocating payload', async () => {
    const { FrameReader } = createLoader().load('DshWebSocketFrames'); const reader = new FrameReader(true);
    reader.push(Uint8Array.from([0x82, 0xff, 0, 0, 0, 1, 0, 0, 0, 1]));
    assert.throws(() => reader.takeFrame(), /size/);
  });
  await test('binary continuations aggregate around ping frames with bounded sequence rules', async () => {
    const frames = createLoader().load('DshWebSocketFrames'), messages = new frames.MessageReader();
    assert.equal(messages.accept(new frames.WsFrame(2, Uint8Array.from([1, 2]), false)), null);
    assert.equal(messages.accept(new frames.WsFrame(9, Uint8Array.from([3]))).opcode, 9);
    const completed = messages.accept(new frames.WsFrame(0, Uint8Array.from([4])));
    assert.equal(completed.opcode, 2); assert.deepEqual([...completed.payload], [1, 2, 4]);
    assert.throws(() => messages.accept(new frames.WsFrame(0, new Uint8Array())), /continuation/);
  });
  await test('malformed mask and control frames are rejected', async () => {
    const { FrameReader } = createLoader().load('DshWebSocketFrames');
    for (const data of [[0x81, 0], [0x09, 0x80, 0, 0, 0, 0], [0xc1, 0x80, 0, 0, 0, 0]]) {
      const reader = new FrameReader(true); reader.push(Uint8Array.from(data)); assert.throws(() => reader.takeFrame());
    }
  });
  await test('actual crypto module matches shared fixture and rejects replay and unsafe integer sequences', async () => {
    const f = createLoader(), crypto = f.load('DshSealedTunnelCrypto');
    const v = JSON.parse(fs.readFileSync(path.join(f.root, 'androidApp/src/test/resources/sealed-tunnel-v1.json'), 'utf8'));
    assert.equal(await crypto.DshSealedTunnelCrypto.deriveClaimToken(v.masterKeyB64), v.claimTokenB64);
    const host = await crypto.DshSealedTunnelCrypto.createHostCipher(v.masterKeyB64, v.accessSessionId, v.clientRandomB64, v.serverRandomB64);
    const payload = new crypto.DshSealedPayload('0', v.httpReqCiphertextB64);
    await assert.rejects(host.open(new crypto.DshSealedPayload('9007199254740992', v.httpReqCiphertextB64)), /sequence/);
    const opened = await host.open(payload); assert.equal(opened.type, 'http_req'); await assert.rejects(host.open(payload), /sequence/);
  });
  await test('concurrent opens cannot accept sequence zero twice and failed authentication remains retryable', async () => {
    const f = createLoader(), crypto = f.load('DshSealedTunnelCrypto');
    const v = JSON.parse(fs.readFileSync(path.join(f.root, 'androidApp/src/test/resources/sealed-tunnel-v1.json'), 'utf8'));
    const host = await crypto.DshSealedTunnelCrypto.createHostCipher(v.masterKeyB64, v.accessSessionId, v.clientRandomB64, v.serverRandomB64);
    const corrupted = Buffer.from(v.httpReqCiphertextB64, 'base64url'); corrupted[0] ^= 1;
    await assert.rejects(host.open(new crypto.DshSealedPayload('0', corrupted.toString('base64url'))));
    const payload = new crypto.DshSealedPayload('0', v.httpReqCiphertextB64);
    const opened = await Promise.allSettled([host.open(payload), host.open(payload)]);
    assert.equal(opened.filter((result) => result.status === 'fulfilled').length, 1);
  });
  await test('credential encryption commits one complete record and clear rejects an in-flight save', async () => {
    const f = createLoader(), { DshRelaySecrets } = f.load('DshRelaySecrets');
    const store = new DshRelaySecrets(), master = Buffer.alloc(32, 1).toString('base64url');
    await store.save(master, 'client-token', 'host', 'Host', 'http://relay.example');
    const loaded = await store.load(); assert.equal(loaded.masterKey, master); assert.equal(loaded.clientToken, 'client-token');
    assert.equal(f.mocks.files.has('/test-files/relay-secrets/pairing-huks.bin'), true);
    const saving = store.save(master, 'next-token', 'next', 'Next', 'http://relay.example'); store.clear();
    await assert.rejects(saving, /cancelled/); assert.equal(await store.load(), null);
  });
  await test('QR parsing rejects ambiguous credentials and preserves encoded delimiters', async () => {
    const { parsePairQr } = createLoader().load('DshRelayNetwork');
    const link = parsePairQr('https://relay.example/#/pair?id=one%26two&key=secret'); assert.equal(link.pairId, 'one&two');
    for (const input of ['https://relay.example/?id=one&id=two&key=secret', 'https://user:password@relay.example/?id=one&key=secret', 'file:///pair?id=one&key=secret']) {
      assert.throws(() => parsePairQr(input));
    }
  });
  await test('invalidated system HTTP request cannot start TCP fallback I/O', async () => {
    const f = createLoader(), failed = deferred(); let current = true, fallback = 0;
    f.tcp.postJsonOverSystemHttp = () => failed.promise; f.tcp.postJsonOverTcp = async () => { fallback++; return {}; };
    const { relayPost } = f.load('DshRelayNetwork'); const request = relayPost('http://relay.example', '{}', '', () => current);
    current = false; failed.reject(new Error('network failed')); await assert.rejects(request); assert.equal(fallback, 0);
  });
  await test('malformed UTF8 text frames are rejected after reassembly', async () => {
    const frames = createLoader().load('DshWebSocketFrames'), messages = new frames.MessageReader();
    assert.throws(() => messages.accept(new frames.WsFrame(1, Uint8Array.from([0xff]))));
  });
  await test('native module destruction removes all global slots and pending scanner callbacks', async () => {
    const f = createLoader(), scan = deferred(), { KRDshRelayModule } = f.loadModule('KRDshRelayModule');
    f.scanner.scanRelayQr = () => scan.promise; const module = new KRDshRelayModule(); let called = 0;
    module.call('subscribe', null, () => called++); module.call('scanAndPair', null, () => called++);
    assert.equal(f.mocks.slots.size, 2); module.onDestroy(); scan.resolve(''); await flush();
    assert.equal(f.mocks.slots.size, 0); assert.equal(called, 1);
    module.call('subscribe', null, () => called++); assert.equal(f.mocks.slots.size, 0);
  });
  await test('replacing module subscriptions releases the old state slot', async () => {
    const f = createLoader(), { KRDshRelayModule } = f.loadModule('KRDshRelayModule'), module = new KRDshRelayModule();
    let old = 0, current = 0; module.call('subscribe', null, () => old++); module.call('subscribe', null, () => current++);
    assert.equal(f.mocks.slots.size, 1); f.load('DshRelayRuntime').DshRelayRuntime.shared.disconnect();
    assert.equal(old, 1); assert.equal(current, 2); module.onDestroy();
  });
  await test('bounded write queue closes a slow peer instead of accumulating unbounded buffers', async () => {
    const f = await gatewayFixture(), peer = f.peer(), blocked = deferred(); peer.send = () => blocked.promise;
    const conn = [...f.gateway.peers][0], requests = [];
    for (let i = 0; i < 65; i++) requests.push(f.gateway.sendBytes(conn, Buffer.from('x')).catch(() => {}));
    assert.equal(peer.closed, true); blocked.resolve(); await Promise.all(requests); assert.equal(conn.queuedBytes, 0); f.gateway.stop();
  });
  await test('gateway start resolving after stop cannot claim a new owner of the shared listener', async () => {
    const f = createLoader(), listen = deferred(); const kit = f.mocks.kit.socket;
    const real = kit.constructTCPSocketServerInstance;
    kit.constructTCPSocketServerInstance = () => { const server = real(); server.listen = () => listen.promise; return server; };
    const { DshRelayLoopbackServer } = f.load('DshRelayLoopback');
    const old = new DshRelayLoopbackServer('old', () => {}), starting = old.start(); old.stop();
    const next = new DshRelayLoopbackServer('next', () => {}), replacement = next.start(); listen.resolve();
    await assert.rejects(starting, /cancelled/); await replacement;
    const peer = new f.mocks.Socket(); f.mocks.servers[0].emit('connect', peer); assert.equal(next.peers.size, 1);
    next.stop(); assert.equal(peer.closed, true);
  });
}
module.exports = { gatewayTests };
