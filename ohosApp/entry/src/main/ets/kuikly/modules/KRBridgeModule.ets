import { pasteboard } from '@kit.BasicServicesKit';
import { promptAction } from '@kit.ArkUI';
import { KRAny } from '@kuikly-open/render';
import { KuiklyRenderBaseModule } from '@kuikly-open/render';
import { KuiklyRenderCallback } from '@kuikly-open/render';
import router from '@ohos.router';
import { jsonString, mapOf, moduleParams } from '../native/DshJson';
import { DshSshKeyStore } from '../native/DshSshKeyStore';
import { DshOhosContext, dshError } from '../native/DshOhosContext';

const UNSUPPORTED = 'unsupported bridge method';

export class KRBridgeModule extends KuiklyRenderBaseModule {
  static readonly MODULE_NAME = 'HRBridgeModule';

  syncMode(): boolean {
    return false;
  }

  call(method: string, params: KRAny, callback: KuiklyRenderCallback): KRAny {
    switch (method) {
      case 'copyToPasteboard':
        this.copyToPasteboard(params);
        break;
      case 'toast':
        this.toast(params);
        break;
      case 'currentTimestamp':
        return Date.now().toString();
      case 'closePage':
        router.back();
        break;
      case 'closeKeyboard':
      case 'setSystemBarsDimmed':
        break;
      case 'pickSshKey':
        this.pickSshKey(callback);
        break;
      case 'importSshKey':
        this.importSshKey(params, callback);
        break;
      case 'validateSshKey':
        this.validateSshKey(params, callback);
        break;
      case 'deleteSshKey':
        this.deleteSshKey(params);
        break;
      default:
        callback(mapOf(['code', 'message'], [-1, UNSUPPORTED]));
        break;
    }
    return null;
  }

  private copyToPasteboard(params: KRAny): void {
    const content = jsonString(moduleParams(params as Object), 'content');
    const data = pasteboard.createData(pasteboard.MIMETYPE_TEXT_PLAIN, content);
    pasteboard.getSystemPasteboard().setData(data);
  }

  private toast(params: KRAny): void {
    const content = jsonString(moduleParams(params as Object), 'content');
    try {
      promptAction.showToast({ message: content, duration: 2000 });
    } catch (error) {
      dshError('toast failed', error as Object);
    }
  }

  private pickSshKey(callback: KuiklyRenderCallback): void {
    const slot = DshOhosContext.bindOnce((value: Record<string, Object>) => callback(value));
    new DshSshKeyStore().pickKey().then((uri: string) => {
      DshOhosContext.postToMain(slot, mapOf(['uri'], [uri]));
    }).catch((error: Object) => {
      dshError('pickSshKey failed', error);
      DshOhosContext.postToMain(slot, mapOf(['uri'], ['']));
    });
  }

  private importSshKey(params: KRAny, callback: KuiklyRenderCallback): void {
    const uri = jsonString(moduleParams(params as Object), 'uri');
    const keyId = new DshSshKeyStore().importUri(uri);
    if (keyId.length === 0) {
      callback(mapOf(['ok', 'message'], [false, '无法导入 SSH 私钥']));
      return;
    }
    callback(mapOf(['ok', 'keyId'], [true, keyId]));
  }

  private validateSshKey(params: KRAny, callback: KuiklyRenderCallback): void {
    callback(mapOf(['valid'], [false]));
  }

  private deleteSshKey(params: KRAny): void {
    const keyId = jsonString(moduleParams(params as Object), 'keyId');
    new DshSshKeyStore().deleteKey(keyId);
  }
}
