import { IKRRouterAdapter, KRRecord } from '@kuikly-open/render';
import router from '@ohos.router';
import common from '@ohos.app.ability.common';

export class RouterAdapter implements IKRRouterAdapter {
  openPage(context: common.UIAbilityContext, pageName: string, pageData: KRRecord): void {
    const route: router.RouterOptions = {
      url: 'pages/Index',
      params: {
        pageName,
        pageData
      }
    };
    if (pageData['replaceCurrentPage'] === true) {
      router.replaceUrl(route);
    } else {
      router.pushUrl(route);
    }
  }

  closePage(context: common.UIAbilityContext): void {
    router.back()
  }
}