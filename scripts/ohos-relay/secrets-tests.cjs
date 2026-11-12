const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const { createLoader } = require('./loader.cjs');
const { deferred, flush } = require('./runtime-tests.cjs');

const root = '/test-files/relay-secrets/';
const protectedPath = root + 'pairing-huks.bin';
const credentials = { masterKey: Buffer.alloc(32, 1).toString('base64url'), clientToken: 'client-token',
  hostId: 'host', hostName: 'Host', origin: 'http://relay.example' };
function fixture() {
  const f = createLoader(), { DshRelaySecrets } = f.load('DshRelaySecrets');
  return { ...f, store: new DshRelaySecrets(), DshRelaySecrets };
}
function save(f, token = credentials.clientToken, current) {
  const c = credentials; return f.store.save(c.masterKey, token, c.hostId, c.hostName, c.origin, current);
}
function seedLegacy(f, fields = false) {
  const key = crypto.randomBytes(32); f.mocks.files.set(root + 'kek.bin', Buffer.from(key));
  const records = fields ? { 'master.bin': credentials.masterKey, 'client.bin': credentials.clientToken,
    'host.bin': credentials.hostId, 'name.bin': credentials.hostName, 'origin.bin': credentials.origin }
    : { 'pairing.bin': JSON.stringify(credentials) };
  for (const [name, text] of Object.entries(records)) {
    const iv = crypto.randomBytes(12), cipher = crypto.createCipheriv('aes-256-gcm', key, iv);
    const sealed = Buffer.concat([cipher.update(text), cipher.final(), cipher.getAuthTag()]);
    f.mocks.files.set(root + name, Buffer.concat([Buffer.from([12]), iv, sealed]));
  }
}

async function secretsTests(test) {
  await test('new pairing stores no exported key bytes or plaintext credentials', async () => {
    const f = fixture(); await save(f);
    assert.deepEqual([...f.mocks.files.keys()], [protectedPath]);
    assert.equal(f.mocks.protectedKeys.size, 1); assert.equal(f.mocks.sessions.size, 0);
    const record = f.mocks.files.get(protectedPath);
    for (const value of [credentials.masterKey, credentials.clientToken]) assert.equal(record.includes(Buffer.from(value)), false);
    assert.equal((await f.store.load()).clientToken, credentials.clientToken);
  });
  for (const fields of [false, true]) {
    await test(`legacy ${fields ? 'five-field' : 'whole-record'} pairing migrates without losing credentials`, async () => {
      const f = fixture(); seedLegacy(f, fields);
      assert.equal(JSON.stringify(await f.store.load()), JSON.stringify(credentials));
      assert.deepEqual([...f.mocks.files.keys()], [protectedPath]);
      assert.equal(JSON.stringify(await f.store.load()), JSON.stringify(credentials));
    });
  }
  await test('protected authentication failure cannot fall back to stale legacy credentials', async () => {
    const f = fixture(); await save(f); seedLegacy(f);
    f.mocks.files.get(protectedPath)[20] ^= 1;
    await assert.rejects(f.store.load()); assert.equal(f.mocks.sessions.size, 0);
    assert.equal(f.mocks.files.has(root + 'pairing.bin'), true);
  });
  await test('missing protected key is not regenerated or downgraded to the legacy record', async () => {
    const f = fixture(); await save(f); seedLegacy(f); f.mocks.protectedKeys.clear();
    await assert.rejects(f.store.load(), /unavailable/); assert.equal(f.mocks.protectedKeys.size, 0);
  });
  await test('missing legacy key never generates a replacement raw key', async () => {
    const f = fixture(); seedLegacy(f); f.mocks.files.delete(root + 'kek.bin');
    await assert.rejects(f.store.load(), /unavailable/);
    assert.equal(f.mocks.files.has(root + 'kek.bin'), false); assert.equal(f.mocks.protectedKeys.size, 0);
  });
  await test('failed protected migration retains the original pairing and key', async () => {
    const f = fixture(); seedLegacy(f); f.mocks.huks.generateKeyItem = async () => { throw new Error('HUKS unavailable'); };
    await assert.rejects(f.store.load()); assert.equal(f.mocks.files.has(protectedPath), false);
    assert.equal(f.mocks.files.has(root + 'pairing.bin'), true); assert.equal(f.mocks.files.has(root + 'kek.bin'), true);
  });
  await test('clear invalidates migration already waiting on native encryption', async () => {
    const f = fixture(); seedLegacy(f); const gate = deferred(), finish = f.mocks.huks.finishSession;
    f.mocks.huks.finishSession = async (...args) => { await gate.promise; return finish(...args); };
    const loading = f.store.load(); await flush(); f.store.clear(); gate.resolve();
    await assert.rejects(loading, /cancelled/); assert.equal(f.mocks.files.size, 0);
  });
  await test('a newer save supersedes an in-flight save across store instances', async () => {
    const f = fixture(), gate = deferred(), finish = f.mocks.huks.finishSession;
    f.mocks.huks.finishSession = async (...args) => { await gate.promise; return finish(...args); };
    const first = save(f, 'first'); await flush();
    const second = new f.DshRelaySecrets().save(credentials.masterKey, 'second', 'host', 'Host', credentials.origin);
    gate.resolve(); await assert.rejects(first, /cancelled/); await second;
    assert.equal((await f.store.load()).clientToken, 'second');
  });
  await test('failed atomic write leaves old protected pairing intact and removes temporary file', async () => {
    const f = fixture(); await save(f); const old = Buffer.from(f.mocks.files.get(protectedPath));
    f.mocks.fs.writeSync = () => 1; await assert.rejects(save(f, 'new-token'), /Incomplete/);
    assert.deepEqual(f.mocks.files.get(protectedPath), old);
    assert.equal(f.mocks.files.has(protectedPath + '.tmp'), false);
    assert.equal((await f.store.load()).clientToken, credentials.clientToken);
  });
  await test('native finish failures release HUKS sessions and leave stored credentials unchanged', async () => {
    const f = fixture(); await save(f);
    f.mocks.huks.finishSession = async () => { throw new Error('native finish failed'); };
    await assert.rejects(save(f), /native finish failed/); assert.equal(f.mocks.sessions.size, 0);
  });
  await test('oversized or truncated files are rejected before decryption', async () => {
    const f = fixture(); f.mocks.files.set(protectedPath, Buffer.alloc(65537));
    await assert.rejects(f.store.load(), /size/);
    await save(f); f.mocks.fs.readSync = () => 0;
    await assert.rejects(f.store.load(), /Incomplete/); assert.equal(f.mocks.sessions.size, 0);
  });
  await test('caller cancellation prevents a protected record commit', async () => {
    const f = fixture(), gate = deferred(), finish = f.mocks.huks.finishSession; let current = true;
    f.mocks.huks.finishSession = async (...args) => { await gate.promise; return finish(...args); };
    const pending = save(f, 'token', () => current); await flush(); current = false; gate.resolve();
    await assert.rejects(pending, /cancelled/); assert.equal(f.mocks.files.size, 0);
  });
}
module.exports = { secretsTests };
