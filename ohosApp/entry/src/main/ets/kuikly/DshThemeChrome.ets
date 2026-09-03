import { common, Configuration, ConfigurationConstant } from '@kit.AbilityKit';
import { BusinessError } from '@kit.BasicServicesKit';
import { util } from '@kit.ArkTS';
import fs from '@ohos.file.fs';
import window from '@ohos.window';
import { KRRecord } from '@kuikly-open/render';
import { KuiklyViewDelegate } from './KuiklyViewDelegate';

const PREF_KEY = 'theme_preference';
const PREF_FILE = 'KRSharedPreferencesModule';
const EVENT_THEME_DID_CHANGED = 'themeDidChanged';
// ARGB copies of DshChromePalette (Kotlin cannot be imported from ArkTS).
const LIGHT_BG = '#FFF5F6F7';
const DARK_BG = '#FF151517';
const LIGHT_BAR_CONTENT = '#FF0F1115';
const DARK_BAR_CONTENT = '#FFF9FAFB';

export class DshThemeChrome {
  private static mainWindow: window.Window | undefined = undefined;
  private static pager: KuiklyViewDelegate | undefined = undefined;
  private static lastSystemDark: boolean | undefined = undefined;
  private static lastAppliedIsDark: boolean | undefined = undefined;
  private static cachedPreference: string | undefined = undefined;

  static attachWindow(win: window.Window): void {
    DshThemeChrome.mainWindow = win;
  }

  static attachPager(pager: KuiklyViewDelegate): void {
    DshThemeChrome.pager = pager;
  }

  static systemIsDark(context?: common.UIAbilityContext): boolean {
    return context?.config?.colorMode === ConfigurationConstant.ColorMode.COLOR_MODE_DARK;
  }

  static resolveIsDark(context: common.UIAbilityContext): boolean {
    const preference = DshThemeChrome.readPreference(context);
    if (preference === 'light') {
      return false;
    }
    if (preference === 'dark') {
      return true;
    }
    return DshThemeChrome.systemIsDark(context);
  }

  static applyResolved(context: common.UIAbilityContext): void {
    DshThemeChrome.applyIsDark(DshThemeChrome.resolveIsDark(context));
  }

  static applyIsDark(isDark: boolean): void {
    if (DshThemeChrome.lastAppliedIsDark === isDark) {
      return;
    }
    const win = DshThemeChrome.mainWindow;
    if (!win) {
      return;
    }
    DshThemeChrome.lastAppliedIsDark = isDark;
    const bg = isDark ? DARK_BG : LIGHT_BG;
    const barContent = isDark ? DARK_BAR_CONTENT : LIGHT_BAR_CONTENT;
    try {
      win.setWindowBackgroundColor(bg);
    } catch (e) {
      console.error(`DshThemeChrome setWindowBackgroundColor failed: ${(e as BusinessError).message}`);
    }
    win.setWindowSystemBarProperties({
      statusBarColor: bg,
      navigationBarColor: bg,
      statusBarContentColor: barContent,
      navigationBarContentColor: barContent,
    }).catch((e: BusinessError) => {
      console.error(`DshThemeChrome setWindowSystemBarProperties failed: ${e.message}`);
    });
  }

  static onConfigurationUpdate(config: Configuration, context: common.UIAbilityContext): void {
    const systemDark = config.colorMode === ConfigurationConstant.ColorMode.COLOR_MODE_DARK;
    if (systemDark === DshThemeChrome.lastSystemDark) {
      return;
    }
    DshThemeChrome.lastSystemDark = systemDark;
    const data: KRRecord = { 'isNightMode': systemDark };
    DshThemeChrome.pager?.sendEvent(EVENT_THEME_DID_CHANGED, data);
    DshThemeChrome.applyResolved(context);
  }

  static seedSystemDark(systemDark: boolean): void {
    DshThemeChrome.lastSystemDark = systemDark;
  }

  static invalidatePreferenceCache(): void {
    DshThemeChrome.cachedPreference = undefined;
  }

  private static readPreference(context: common.UIAbilityContext): string {
    if (DshThemeChrome.cachedPreference !== undefined) {
      return DshThemeChrome.cachedPreference;
    }
    try {
      const path = `${context.filesDir}/${PREF_FILE}`;
      if (!fs.accessSync(path)) {
        return '';
      }
      const file = fs.openSync(path, fs.OpenMode.READ_ONLY);
      const stat = fs.statSync(file.fd);
      const buffer = new ArrayBuffer(stat.size);
      fs.readSync(file.fd, buffer);
      fs.closeSync(file);
      const xml = util.TextDecoder.create('utf-8').decodeToString(new Uint8Array(buffer));
      const match = xml.match(new RegExp(`<string (?:name|key)="${PREF_KEY}">([^<]*)</string>`));
      const value = (match?.[1] ?? '').trim().toLowerCase();
      DshThemeChrome.cachedPreference = value;
      return value;
    } catch (e) {
      console.error(`DshThemeChrome readPreference failed: ${(e as BusinessError).message}`);
      return '';
    }
  }
}
