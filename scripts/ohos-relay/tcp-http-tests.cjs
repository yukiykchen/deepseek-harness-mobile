const assert = require('node:assert/strict');
const { createLoader } = require('./loader.cjs');

async function tcpHttpTests(test) {
  async function request(responseChunks, options = {}) {
    const loader = createLoader();
    const { postJsonOverTcp } = loader.load('DshTcpHttp');
    const pending = postJsonOverTcp('http://127.0.0.1:8080/path', '{"hello":"world"}', 'token', options.timeoutMs ?? 2000);
    await new Promise(setImmediate);
    const socket = loader.mocks.tcpSockets.at(-1);
    assert.ok(socket, 'TCP socket was not created');
    for (const chunk of responseChunks) socket.message(Buffer.from(chunk));
    return { pending, socket, loader };
  }

  await test('TCP HTTP parses content-length responses without leaking business body', async () => {
    const { pending } = await request(['HTTP/1.1 200 OK\r\nContent-Length: 8\r\n\r\n{"ok":1}']);
    const result = await pending;
    assert.equal(result.code, 200);
    assert.equal(result.json.ok, 1);
  });

  await test('TCP HTTP decodes a chunked response split across socket events', async () => {
    const { pending } = await request([
      'HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n4\r\n{"a"',
      '\r\n3\r\n:1}\r\n0\r\n\r\n',
    ]);
    const result = await pending;
    assert.equal(result.code, 200);
    assert.equal(result.json.a, 1);
  });

  await test('TCP HTTP rejects oversized responses before unbounded buffering', async () => {
    const header = 'HTTP/1.1 200 OK\r\nContent-Length: 2097153\r\n\r\n';
    const { pending } = await request([header + 'x'.repeat(2 * 1024 * 1024 + 1)]);
    await assert.rejects(pending, /too large/);
  });

  await test('TCP HTTP truncates incomplete bodies on EOF', async () => {
    const { pending, socket } = await request(['HTTP/1.1 200 OK\r\nContent-Length: 10\r\n\r\nshort']);
    socket.emit('close');
    await assert.rejects(pending, /incomplete body/);
  });

  await test('TCP HTTP timeout settles exactly once and closes the socket', async () => {
    const loader = createLoader();
    const { postJsonOverTcp } = loader.load('DshTcpHttp');
    const pending = postJsonOverTcp('http://127.0.0.1:8080/path', '{}', '', 5);
    await Promise.resolve();
    const timer = [...loader.timers.keys()].at(-1);
    loader.timers.get(timer).callback();
    await assert.rejects(pending, /timeout/);
    assert.equal(loader.mocks.tcpSockets.at(-1).closed, true);
  });
}

module.exports = { tcpHttpTests };
