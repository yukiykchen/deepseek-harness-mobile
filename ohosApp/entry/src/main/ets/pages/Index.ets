import { ErrorReason, KRRecord, Kuikly } from '@kuikly-open/render';
import { common } from '@kit.AbilityKit';
import router from '@ohos.router';
import { KuiklyViewDelegate } from '../kuikly/KuiklyViewDelegate';
import { DshThemeChrome } from '../kuikly/DshThemeChrome';
import globalNativeManager from '../kuikly/MyNativeManager';

@Entry
@Component
struct Index {
  kuiklyViewDelegate = new KuiklyViewDelegate();
  pageName?: string;
  pageData: KRRecord = {};
  @State exception?: string = "";

  aboutToAppear() {
    AppStorage.setOrCreate<UIContext>("context", this.getUIContext());

    const params = router.getParams() as Record<string, Object>;
    this.pageName = params?.pageName as string;
    const incoming: KRRecord | undefined = params?.pageData as KRRecord;
    const context = this.getUIContext().getHostContext() as common.UIAbilityContext;
    const systemDark = DshThemeChrome.systemIsDark(context);
    DshThemeChrome.attachPager(this.kuiklyViewDelegate);
    DshThemeChrome.seedSystemDark(systemDark);
    this.pageData = { ...(incoming ?? {}), 'isNightMode': systemDark };
  }

  build() {
    Stack() {
        Kuikly({
        pagerName: this.pageName ?? 'connection_setup',
        pagerData: this.pageData,
        delegate: this.kuiklyViewDelegate,
        nativeManager: globalNativeManager,
        onRenderException: (exception: Error, reason: ErrorReason) => {
          this.exception = `${exception.name}:\n${exception.message}`;
        },
      })
      if (this.exception) {
        Text(this.exception)
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
