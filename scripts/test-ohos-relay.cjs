// Run production .ets logic through the SDK TypeScript transpiler with controlled native boundaries.
const { runtimeTests } = require('./ohos-relay/runtime-tests.cjs');
const { gatewayTests } = require('./ohos-relay/gateway-tests.cjs');
const { secretsTests } = require('./ohos-relay/secrets-tests.cjs');
const { tcpHttpTests } = require('./ohos-relay/tcp-http-tests.cjs');

(async () => {
  let passed = 0, failed = 0;
  async function test(name, work) {
    try { await work(); passed++; console.log(`PASS ${name}`); }
    catch (error) { failed++; console.error(`FAIL ${name}:`, error); }
  }
  await runtimeTests(test);
  await gatewayTests(test);
  await secretsTests(test);
  await tcpHttpTests(test);
  console.log(`OHOS Relay host tests: ${passed} passed, ${failed} failed`);
  if (failed) process.exitCode = 1;
})().catch((error) => { console.error(error); process.exitCode = 1; });
