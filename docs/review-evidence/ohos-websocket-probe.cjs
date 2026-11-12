const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const { createLoader } = require('../../scripts/ohos-relay/loader.cjs');
const ts = require(process.env.ETS_TYPESCRIPT || '/Applications/DevEco-Studio.app/Contents/sdk/default/openharmony/ets/build-tools/ets-loader/node_modules/typescript/lib/typescript.js');
const loader = createLoader();
const sockets = [];
loader.mocks.kit.webSocket.createWebSocket = () => {
  const socket = new loader.mocks.Socket();
  sockets.push(socket);
  return socket;
};
const moduleUnderTest = { exports: {} };
const filename = path.join(loader.root, 'ohosApp/entry/src/main/ets/kuikly/modules/KRDshWebSocketModule.ets');
const source = ts.transpileModule(fs.readFileSync(filename, 'utf8'), {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 },
}).outputText;
const context = vm.createContext({
  module: moduleUnderTest,
  exports: moduleUnderTest.exports,
  require(id) {
    if (id === '@kit.NetworkKit') return loader.mocks.kit;
    if (id === '@kuikly-open/render') return { KuiklyRenderBaseModule: class { onDestroy() {} } };
    if (id.endsWith('DshOhosContext')) return loader.mocks.context;
    return loader.load(path.basename(id));
  },
  Map, Date, Object, Error, String, Promise,
});
new vm.Script(source, { filename }).runInContext(context);
const host = new moduleUnderTest.exports.KRDshWebSocketModule();
host.call('connect', JSON.stringify({ connectionId: 'probe', url: 'ws://127.0.0.1/events' }), () => {});
console.log(`afterConnect slots=${loader.mocks.slots.size} sockets=${sockets.length}`);
host.call('disconnect', JSON.stringify({ connectionId: 'probe' }), null);
console.log(`afterDisconnect slots=${loader.mocks.slots.size} socketClosed=${sockets[0].closed}`);
host.call('connect', JSON.stringify({ connectionId: 'probe2', url: 'ws://127.0.0.1/events' }), () => {});
host.onDestroy();
console.log(`afterDestroy slots=${loader.mocks.slots.size} newestSocketClosed=${sockets[1].closed}`);
