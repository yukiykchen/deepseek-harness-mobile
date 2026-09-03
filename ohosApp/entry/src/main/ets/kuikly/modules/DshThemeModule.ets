import { KRAny, KuiklyRenderBaseModule, KuiklyRenderCallback } from '@kuikly-open/render';
import { DshThemeChrome } from '../DshThemeChrome';

export class DshThemeModule extends KuiklyRenderBaseModule {
  static readonly MODULE_NAME = 'DshThemeModule';
  static readonly APPLY_NATIVE_CHROME = 'applyNativeChrome';

  syncMode(): boolean {
    return false;
  }

  call(method: string, params: KRAny, _callback: KuiklyRenderCallback | null): KRAny {
    if (method === DshThemeModule.APPLY_NATIVE_CHROME) {
      try {
        const parsed = JSON.parse((params as string) || '{}') as Record<string, Object>;
        DshThemeChrome.invalidatePreferenceCache();
        DshThemeChrome.applyIsDark(parsed['isDark'] === true || parsed['isDark'] === 'true');
      } catch (_e) {
        // ignore malformed chrome payloads
      }
    }
    return null;
  }
}
