import {
  IKuiklyViewDelegate,
  KRRenderModuleExportCreator,
  KRRenderViewExportCreator
} from '@kuikly-open/render';
import { KRBridgeModule } from './modules/KRBridgeModule';
import { KRDshEngineModule } from './modules/KRDshEngineModule';
import { KRDshRelayModule } from './modules/KRDshRelayModule';
import { KRDshWebSocketModule } from './modules/KRDshWebSocketModule';

export class KuiklyViewDelegate extends IKuiklyViewDelegate {
  getCustomRenderViewCreatorRegisterMap(): Map<string, KRRenderViewExportCreator> {
    const map: Map<string, KRRenderViewExportCreator> = new Map();
    return map;
  }

  getCustomRenderModuleCreatorRegisterMap(): Map<string, KRRenderModuleExportCreator> {
    const map: Map<string, KRRenderModuleExportCreator> = new Map();
    map.set(KRBridgeModule.MODULE_NAME, () => new KRBridgeModule());
    map.set(KRDshRelayModule.MODULE_NAME, () => new KRDshRelayModule());
    map.set(KRDshWebSocketModule.MODULE_NAME, () => new KRDshWebSocketModule());
    map.set(KRDshEngineModule.MODULE_NAME, () => new KRDshEngineModule());
    return map;
  }
}
