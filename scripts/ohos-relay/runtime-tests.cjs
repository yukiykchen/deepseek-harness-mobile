const assert = require('node:assert/strict');
const { createLoader } = require('./loader.cjs');

function deferred() {
  let resolve, reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}
const flush = () => new Promise(setImmediate);

function fixture() {
  const loader = createLoader();
  const { DshRelayRuntime, DshRelayDependencies } = loader.load('DshRelayRuntime');
  const { DshRelayCredentials } = loader.load('DshRelaySecrets');
  const { DshSealedTunnelCrypto } = loader.load('DshSealedTunnelCrypto');
  const master = Buffer.from(Array.from({ length: 32 }, (_, i) => i)).toString('base64url');
  const serverRandom = Buffer.alloc(32, 2).toString('base64url');
  const credentials = new DshRelayCredentials(master, 'client-token', 'host', 'Host', 'http://relay.example');
  const store = { credentials, async load() { return this.credentials; },
    async save(master, token, host, name, origin, current) { if (current()) this.credentials = new DshRelayCredentials(master, token, host, name, origin); },
    clear() { this.credentials = null; },
  };
  const sockets = [], gateways = [], requests = [];
  const dependencies = new DshRelayDependencies();
  dependencies.createStore = () => store;
  dependencies.prepare = async () => {};
  dependencies.request = async (...args) => { requests.push(args); return { code: 200, json: { accessSessionId: `session-${requests.length}`, ticket: 'ticket-token' } }; };
  dependencies.createSocket = () => { const socket = new loader.mocks.Socket(); sockets.push(socket); return socket; };
  dependencies.createGateway = (token, send) => {
    const gateway = { port: 18999, stopped: false, received: [], send,
      async start() { return this.port; }, stop() { this.stopped = true; }, isListening() { return !this.stopped; },
      onInner(...values) { if (!this.stopped) this.received.push(values); },
    };
    gateways.push(gateway); return gateway;
  };
  const runtime = new DshRelayRuntime(dependencies);
  async function ready() {
    await runtime.connect();
    const socket = sockets.at(-1);
    socket.emit('open', { code: 0 }); await flush();
    const hello = JSON.parse(socket.sent[0]);
    const proof = await DshSealedTunnelCrypto.serverProof(master, hello.payload.accessSessionId, hello.payload.clientRandomB64, serverRandom);
    socket.emit('message', null, JSON.stringify({ type: 'server_hello', payload: { serverRandomB64: serverRandom, serverProofB64: proof } }));
    await flush();
    return socket;
  }
  return { ...loader, dependencies, runtime, store, credentials, sockets, gateways, requests,
    crypto: DshSealedTunnelCrypto, master, serverRandom, ready };
}

async function runtimeTests(test) {
  await test('stop before credential load resolves cannot resurrect a connection', async () => {
    const f = fixture(), load = deferred(); f.store.load = () => load.promise;
    const connecting = f.runtime.connect(); f.runtime.disconnect(); load.resolve(f.credentials); await connecting;
    assert.equal(f.sockets.length, 0); assert.equal(f.requests.length, 0); assert.equal(f.runtime.current().phase, 'STOPPED');
  });
  await test('concurrent connect commands share one pending attempt', async () => {
    const f = fixture(); await Promise.all([f.runtime.connect(), f.runtime.connect(), f.runtime.connect()]);
    assert.equal(f.requests.length, 1); assert.equal(f.sockets.length, 1); f.runtime.disconnect();
  });
  await test('late ticket response cannot open a socket after stop', async () => {
    const f = fixture(), ticket = deferred(); f.dependencies.request = () => ticket.promise;
    const connecting = f.runtime.connect(); await flush(); f.runtime.disconnect();
    ticket.resolve({ code: 200, json: { accessSessionId: 'old', ticket: 'old-ticket' } }); await connecting;
    assert.equal(f.sockets.length, 0); assert.equal(f.runtime.current().phase, 'STOPPED');
  });
  await test('late server-proof verification cannot replace the next attempt cipher', async () => {
    const f = fixture(), verification = deferred(); const real = f.dependencies.clientCipher;
    f.dependencies.clientCipher = () => verification.promise;
    await f.runtime.connect(); const old = f.sockets[0]; old.emit('open', { code: 0 }); await flush();
    old.emit('message', null, JSON.stringify({ type: 'server_hello', payload: {} })); await flush();
    f.runtime.disconnect(); f.dependencies.clientCipher = real; await f.ready();
    verification.resolve({ seal() {}, open() {} }); await flush();
    assert.equal(f.gateways.length, 1); assert.equal(f.runtime.current().phase, 'READY'); f.runtime.disconnect();
  });
  await test('gateway start finishing after stop releases the returned gateway', async () => {
    const f = fixture(), starting = deferred(); const make = f.dependencies.createGateway;
    f.dependencies.createGateway = (...args) => { const gateway = make(...args); gateway.start = () => starting.promise; return gateway; };
    await f.ready(); f.runtime.disconnect(); starting.resolve(18999); await flush();
    assert.equal(f.gateways[0].stopped, true); assert.equal(f.runtime.current().phase, 'STOPPED');
  });
  await test('old gateway and delayed seal cannot send through the new socket', async () => {
    const f = fixture(); const old = await f.ready(), gateway = f.gateways[0], sealing = deferred();
    f.runtime.active.cipher.seal = () => sealing.promise;
    gateway.send('http_req', {}, 'old'); await flush(); f.runtime.disconnect(); const current = await f.ready();
    const count = current.sent.length; sealing.resolve({ seq: '0', ciphertextB64: 'old' }); await flush();
    gateway.send('http_req', {}, 'later'); old.emit('close'); await flush();
    assert.equal(current.sent.length, count); assert.equal(current.closed, false); f.runtime.disconnect();
  });
  await test('duplicate server hello cannot replace the gateway or reset send sequences', async () => {
    const f = fixture(), socket = await f.ready(); f.gateways[0].send('http_req', {}, 'first'); await flush();
    assert.equal(JSON.parse(socket.sent.at(-1)).payload.seq, '0');
    socket.emit('message', null, JSON.stringify({ type: 'server_hello', payload: {} })); await flush();
    f.gateways[0].send('http_req', {}, 'second'); await flush();
    assert.equal(JSON.parse(socket.sent.at(-1)).payload.seq, '1'); assert.equal(f.gateways.length, 1); f.runtime.disconnect();
  });
  await test('late decrypted inbound frame cannot reach a new gateway', async () => {
    const f = fixture(), old = await f.ready(), opened = deferred();
    f.runtime.active.cipher.open = () => opened.promise;
    old.emit('message', null, JSON.stringify({ type: 'sealed', payload: { seq: '0', ciphertextB64: '' } })); await flush();
    f.runtime.disconnect(); await f.ready(); opened.resolve({ type: 'http_res', payload: {}, channel: 'old' }); await flush();
    assert.equal(f.gateways[1].received.length, 0); f.runtime.disconnect();
  });
  await test('revoked ticket and bad server proof stop automatic reconnect', async () => {
    const f = fixture(); f.dependencies.request = async () => ({ code: 403, json: {} }); await f.runtime.connect();
    assert.equal(f.runtime.current().phase, 'ERROR'); assert.equal(f.timers.size, 0);
    f.dependencies.request = async () => ({ code: 200, json: { accessSessionId: 'bad', ticket: 'ticket' } });
    await f.runtime.connect(); f.sockets[0].emit('open', { code: 0 }); await flush();
    f.sockets[0].emit('message', null, JSON.stringify({ type: 'server_hello', payload: { serverRandomB64: f.serverRandom, serverProofB64: 'bad' } })); await flush();
    assert.equal(f.runtime.current().phase, 'ERROR'); assert.equal(f.gateways.length, 0); assert.equal(f.timers.size, 0);
  });
  await test('already-dequeued retry cannot replace a newer ready attempt', async () => {
    const f = fixture(), old = await f.ready(); old.emit('close'); const retry = [...f.timers.values()].find((value) => value.delay === 2000);
    const current = await f.ready(), count = f.sockets.length; retry.callback(); await flush();
    assert.equal(f.sockets.length, count); assert.equal(current.closed, false); f.runtime.disconnect();
  });
  await test('outbound failure is not reported as success and triggers resource release', async () => {
    const f = fixture(), socket = await f.ready(); socket.send = (_value, callback) => callback({ code: 1 }, false);
    f.gateways[0].send('http_req', {}, 'channel'); await flush();
    assert.equal(f.runtime.current().phase, 'RECONNECTING'); assert.equal(f.gateways[0].stopped, true); f.runtime.disconnect();
  });
  await test('reentrant disconnect cannot publish stale READY to later listeners', async () => {
    const f = fixture(), phases = []; f.runtime.addListener((state) => { if (state.phase === 'READY') f.runtime.disconnect(); });
    f.runtime.addListener((state) => phases.push(state.phase)); await f.ready();
    assert.equal(phases.at(-1), 'STOPPED'); assert.equal(phases.includes('READY'), false);
  });
  await test('stale attach does not overwrite a stopped state', async () => {
    const f = fixture(), load = deferred(); f.store.load = () => load.promise;
    const attaching = f.runtime.attach(); f.runtime.disconnect(); load.resolve(f.credentials); await attaching;
    assert.equal(f.runtime.current().phase, 'STOPPED');
  });
  await test('pairing response after forget cannot restore credentials', async () => {
    const f = fixture(), response = deferred(); f.dependencies.request = () => response.promise;
    const pairing = f.runtime.pairFromQr(`http://relay.example/#/pair?id=pair&key=${f.master}`); await flush(); await f.runtime.forget();
    response.resolve({ code: 200, json: { hostId: 'old', clientToken: 'old-token' } }); const result = await pairing;
    assert.equal(result.ok, false); assert.equal(f.store.credentials, null); assert.equal(f.runtime.current().paired, false);
  });
  await test('credential commit predicate rejects a save invalidated while encrypting', async () => {
    const f = fixture(), save = deferred(); let committed = false;
    f.dependencies.request = async () => ({ code: 200, json: { hostId: 'new', clientToken: 'new-token' } });
    f.store.save = async (_master, _token, _host, _name, _origin, current) => { await save.promise; committed = current(); };
    const pairing = f.runtime.pairFromQr(`http://relay.example/#/pair?id=pair&key=${f.master}`); await flush();
    f.runtime.disconnect(); save.resolve(); const result = await pairing;
    assert.equal(committed, false); assert.equal(result.ok, false);
  });
  await test('handshake timeout cannot restart a stopped attempt', async () => {
    const f = fixture(), ticket = deferred(); f.dependencies.request = () => ticket.promise;
    const connecting = f.runtime.connect(); await flush(); const deadline = [...f.timers.values()].find((value) => value.delay === 30000);
    deadline.callback(); assert.equal(f.runtime.current().phase, 'RECONNECTING'); f.runtime.disconnect();
    ticket.resolve({ code: 200, json: { accessSessionId: 'old', ticket: 'old' } }); await connecting;
    assert.equal(f.sockets.length, 0); assert.equal(f.runtime.current().phase, 'STOPPED');
  });
  await test('pending gateway startup remains subject to the handshake deadline', async () => {
    const f = fixture(), starting = deferred(), make = f.dependencies.createGateway;
    f.dependencies.createGateway = (...args) => { const gateway = make(...args); gateway.start = () => starting.promise; return gateway; };
    await f.ready(); const deadline = [...f.timers.values()].find((value) => value.delay === 30000);
    deadline.callback(); assert.equal(f.runtime.current().phase, 'RECONNECTING');
    starting.resolve(18999); await flush(); assert.equal(f.gateways[0].stopped, true); f.runtime.disconnect();
  });
  await test('closing a socket cancels its pending send watchdog and settles work', async () => {
    const f = fixture(); await f.runtime.connect(); const socket = f.sockets[0]; socket.send = () => {};
    socket.emit('open', { code: 0 }); assert.equal([...f.timers.values()].filter((task) => task.delay === 20000).length, 1);
    f.runtime.disconnect(); await flush(); assert.equal(f.timers.size, 0);
  });
  await test('repeated open callbacks send the client proof only once', async () => {
    const f = fixture(); await f.runtime.connect(); const socket = f.sockets[0];
    socket.emit('open', { code: 0 }); socket.emit('open', { code: 0 }); await flush();
    assert.equal(socket.sent.length, 1); f.runtime.disconnect();
  });
}
module.exports = { runtimeTests, deferred, flush };
