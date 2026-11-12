const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { createMocks } = require('./mocks.cjs');

const defaultCompiler = '/Applications/DevEco-Studio.app/Contents/sdk/default/openharmony/ets/build-tools/ets-loader/node_modules/typescript/lib/typescript.js';
const ts = require(process.env.ETS_TYPESCRIPT || defaultCompiler);
const root = path.resolve(__dirname, '../..');
const sources = path.join(root, 'ohosApp/entry/src/main/ets/kuikly/native');

function createLoader() {
  const mocks = createMocks();
  const cache = new Map();
  const timers = new Map();
  let timerSeq = 0;
  const tcp = {
    async postJsonOverSystemHttp() { throw new Error('network unavailable in deterministic test'); },
    async postJsonOverTcp() { throw new Error('network unavailable in deterministic test'); },
  };
  const scanner = { async scanRelayQr() { return ''; } };
  const tcpSockets = [];
  const render = { KuiklyRenderBaseModule: class { onDestroy() {} } };
  function load(name) {
    if (cache.has(name)) return cache.get(name).exports;
    const filename = path.join(sources, `${name}.ets`);
    const result = ts.transpileModule(fs.readFileSync(filename, 'utf8'), {
      compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 },
      fileName: `${name}.ts`, reportDiagnostics: true,
    });
    const errors = result.diagnostics.filter((diagnostic) => diagnostic.category === ts.DiagnosticCategory.Error);
    if (errors.length) throw new Error(ts.formatDiagnosticsWithColorAndContext(errors, {
      getCurrentDirectory: () => root, getCanonicalFileName: (file) => file, getNewLine: () => '\n',
    }));
    const module = { exports: {} }; cache.set(name, module);
    function requireSource(id) {
      if (id === '@kit.NetworkKit') return mocks.kit;
      if (id === '@kit.CryptoArchitectureKit') return { cryptoFramework: mocks.cryptoFramework };
      if (id === '@kit.ArkTS') return { util: mocks.util };
      if (id === '@ohos.url') return { default: { URL, URLParams: URLSearchParams } };
      if (id === '@ohos.file.fs') return { default: mocks.fs };
      if (id === '@ohos.security.huks') return { default: mocks.huks };
      if (id === '@kuikly-open/render') return render;
      if (id.endsWith('DshQrScan')) return scanner;
      if (id === './DshOhosContext') return mocks.context;
      if (id === './DshTcpHttp') return tcp;
      if (id.startsWith('.')) return load(path.basename(id));
      throw new Error(`Unmocked native dependency ${id}`);
    }
    const context = vm.createContext({ module, exports: module.exports, require: requireSource, console,
      Uint8Array, ArrayBuffer, Promise, Error, Date, JSON, Map, Set, Math, Number, Object, Buffer, TextEncoder, TextDecoder,
      URL, URLSearchParams,
      setTimeout(callback, delay) { const id = ++timerSeq; timers.set(id, { callback, delay }); return id; },
      clearTimeout(id) { timers.delete(id); },
    });
    new vm.Script(result.outputText, { filename }).runInContext(context);
    return module.exports;
  }
  function loadModule(name) {
    const file = path.join(sources, '../modules', `${name}.ets`);
    const result = ts.transpileModule(fs.readFileSync(file, 'utf8'), { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 } });
    const module = { exports: {} };
    function moduleRequire(id) {
      if (id === '@kit.NetworkKit') return mocks.kit;
      if (id === '@kit.ArkTS') return { util: mocks.util };
      if (id === '@kit.CryptoArchitectureKit') return { cryptoFramework: mocks.cryptoFramework };
      if (id === '@ohos.file.fs') return { default: mocks.fs };
      if (id === '@ohos.security.huks') return { default: mocks.huks };
      if (id === '@kuikly-open/render') return render;
      if (id.endsWith('DshQrScan')) return scanner;
      if (id.endsWith('DshOhosContext')) return mocks.context;
      if (id.startsWith('.')) return load(path.basename(id));
      throw new Error(`Unmocked module dependency ${id}`);
    }
    const context = vm.createContext({ module, exports: module.exports, require: moduleRequire, Promise, Set, Map,
      Object, Error, console, Uint8Array, ArrayBuffer, Buffer, TextEncoder, TextDecoder, URL, URLSearchParams, Date, JSON });
    new vm.Script(result.outputText, { filename: file }).runInContext(context);
    return module.exports;
  }
  return { load, loadModule, mocks, timers, tcp, tcpSockets, scanner, root };
}
module.exports = { createLoader };
