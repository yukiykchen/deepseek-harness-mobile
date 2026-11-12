import { KRAny, KuiklyRenderBaseModule, KuiklyRenderCallback } from '@kuikly-open/render';
import { mapOf, moduleParams } from '../native/DshJson';
import { scanRelayQr } from '../native/DshQrScan';
import { DshRelayNativeState, DshRelayRuntime } from '../native/DshRelayRuntime';
import { DshOhosContext, dshError, dshLog } from '../native/DshOhosContext';

export class KRDshRelayModule extends KuiklyRenderBaseModule {
  static readonly MODULE_NAME = 'DshRelayModule';
  private stateListener: ((state: DshRelayNativeState) => void) | null = null;
  private stateSlot: string = '';
  private pairSlot: string = '';
  private readonly onceSlots: Set<string> = new Set();
  private disposed: boolean = false;

  syncMode(): boolean {
    return true;
  }

  call(method: string, params: KRAny, callback: KuiklyRenderCallback | null): KRAny {
    if (this.disposed) return null;
    switch (method) {
      case 'scanAndPair':
        this.scanAndPair(callback);
        return null;
      case 'connect':
        this.listen(callback);
        DshRelayRuntime.shared.connect().catch(() => dshLog('Relay connection command failed'));
        return null;
      case 'disconnect':
        this.cancelPairing();
        DshRelayRuntime.shared.disconnect();
        return null;
      case 'forget':
        this.cancelPairing();
        this.forget(callback);
        return null;
      case 'status':
        return JSON.stringify(DshRelayRuntime.shared.current().toMap());
      case 'subscribe':
        this.listen(callback);
        return null;
      default:
        void moduleParams(params as Object);
        return null;
    }
  }

  private scanAndPair(callback: KuiklyRenderCallback | null): void {
    dshLog('scanAndPair start');
    if (!callback) {
      return;
    }
    if (this.pairSlot) { callback(mapOf(['ok', 'message'], [false, '扫码配对正在进行中'])); return; }
    const slot = DshOhosContext.bindOnce((value: Record<string, Object>) => {
      this.onceSlots.delete(slot); this.pairSlot = '';
      if (!this.disposed) callback(value);
    });
    this.pairSlot = slot; this.onceSlots.add(slot);
    scanRelayQr().then((qr: string) => {
      if (this.disposed || this.pairSlot !== slot) return;
      dshLog(`scanAndPair qrLength=${qr.length}`);
      if (qr.length === 0) {
        dshLog('scanAndPair cancelled empty qr');
        DshOhosContext.postToMain(slot, mapOf(['ok', 'message'], [false, '已取消扫码']));
        return;
      }
      return DshRelayRuntime.shared.pairFromQr(qr).then((result) => {
        if (this.disposed || this.pairSlot !== slot) return;
        const ok = result['ok'] === true;
        dshLog(`scanAndPair done ok=${ok} message=${result['message'] ?? ''}`);
        DshOhosContext.postToMain(slot, result);
      });
    }).catch((error: Object) => {
      if (this.disposed || this.pairSlot !== slot) return;
      dshError('scanAndPair failed', error);
      const message = error instanceof Error ? error.message : '已取消扫码';
      DshOhosContext.postToMain(slot, mapOf(['ok', 'message'], [false, message]));
    });
  }

  private forget(callback: KuiklyRenderCallback | null): void {
    const slot = callback ? DshOhosContext.bindOnce((value: Record<string, Object>) => {
      this.onceSlots.delete(slot);
      if (!this.disposed) callback(value);
    }) : '';
    if (slot) this.onceSlots.add(slot);
    DshRelayRuntime.shared.forget().then(() => {
      if (slot.length > 0) {
        DshOhosContext.postToMain(slot, mapOf(['ok'], [true]));
      }
    }).catch(() => {
      if (!this.disposed && slot) DshOhosContext.postToMain(slot, mapOf(['ok', 'message'], [false, '无法移除配对']));
    });
  }

  private listen(callback: KuiklyRenderCallback | null): void {
    if (this.stateSlot.length > 0) {
      DshOhosContext.unbindSlot(this.stateSlot);
      this.stateSlot = '';
    }
    if (this.stateListener) {
      DshRelayRuntime.shared.removeListener(this.stateListener);
      this.stateListener = null;
    }
    if (!callback) {
      return;
    }
    const slot = DshOhosContext.bindSlot((value: Record<string, Object>) => {
      if (!this.disposed && this.stateSlot === slot) callback(value);
    });
    this.stateSlot = slot;
    const listener = (state: DshRelayNativeState) => {
      DshOhosContext.postToMain(slot, state.toMap());
    };
    this.stateListener = listener;
    DshRelayRuntime.shared.addListener(listener);
  }

  onDestroy(): void {
    this.disposed = true;
    if (this.pairSlot) DshRelayRuntime.shared.disconnect();
    if (this.stateListener) DshRelayRuntime.shared.removeListener(this.stateListener);
    this.stateListener = null;
    if (this.stateSlot) DshOhosContext.unbindSlot(this.stateSlot);
    this.stateSlot = ''; this.pairSlot = '';
    this.onceSlots.forEach((slot: string) => DshOhosContext.unbindSlot(slot)); this.onceSlots.clear();
    super.onDestroy();
  }

  private cancelPairing(): void {
    if (!this.pairSlot) return;
    DshOhosContext.unbindSlot(this.pairSlot);
    this.onceSlots.delete(this.pairSlot);
    this.pairSlot = '';
  }
}
