import { ErrorReason, KRRecord, Kuikly } from '@kuikly-open/render';
import router from '@ohos.router';
import hilog from '@ohos.hilog';
import { common } from '@kit.AbilityKit';
import { KuiklyViewDelegate } from '../kuikly/KuiklyViewDelegate';
import globalNativeManager from '../kuikly/MyNativeManager';
import { DshOhosContext } from '../kuikly/native/DshOhosContext';
import { DshRelayRuntime } from '../kuikly/native/DshRelayRuntime';

@Entry
@Component
struct Index {
  kuiklyViewDelegate = new KuiklyViewDelegate();
  pageName?: string;
  pageData?: KRRecord;
  @State exception?: string = "";

  aboutToAppear() {
    AppStorage.setOrCreate<UIContext>("context", this.getUIContext());
    const context = getContext(this) as common.UIAbilityContext;
    DshOhosContext.attach(context);
    DshRelayRuntime.shared.attach();

    const params = router.getParams() as Record<string, Object>;
    this.pageName = params?.pageName as string;
    const incoming = ((params?.pageData as KRRecord) ?? {}) as Record<string, Object>;
    incoming['appId'] = 1;
    incoming['embeddedEngine'] = false;
    incoming['databaseDir'] = DshOhosContext.databaseDir();
    incoming['verboseDiagnostics'] = false;
    this.pageData = incoming as KRRecord;
  }

  build() {
    Stack() {
        Kuikly({
        pagerName: this.pageName ?? 'connection_setup',
        pagerData: this.pageData ?? {},
        delegate: this.kuiklyViewDelegate,
        nativeManager: globalNativeManager,
        onRenderException: (exception: Error, reason: ErrorReason) => {
          hilog.error(0x0000, 'Kuikly', 'render %{public}s %{public}s reason=%{public}s',
            exception.name, exception.message, JSON.stringify(reason));
          this.exception = exception.name;
        },
      })
      if (this.exception) {
        Text(this.exception)
          .fontSize(12)
          .fontColor(Color.Red)
          .maxLines(2)
          .margin({ top: 8, left: 12, right: 12 })
      }
    }
    .width('100%')
    .height('100%')
    .expandSafeArea([SafeAreaType.KEYBOARD])
  }

  onPageShow(): void {
    this.kuiklyViewDelegate.pageDidAppear();
  }

  onPageHide(): void {
    this.kuiklyViewDelegate.pageDidDisappear();
  }
}
