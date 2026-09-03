package com.example.dsh

import com.example.dsh.theme.DshChromePalette
import com.example.dsh.theme.DshThemePreference
import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.View
import androidx.core.view.WindowInsetsControllerCompat

/**
 * 原生壳层的主题处理：在 Kuikly 页面创建之前解析深浅色并给窗口上色，避免首帧白闪；
 * 页面内切换主题时再由 [com.example.dsh.module.KRDshThemeModule] 调用 [apply] 刷新。
 *
 * 偏好来源与 Kuikly `SharedPreferencesModule` 共用同一个 SharedPreferences 文件，
 * 因此这里能在 `setContentView` 之前同步读到用户选择。
 */
object DshThemeChrome {
    const val SP_FILE = "KRSharedPreferencesModule"
    const val PREF_KEY = "theme_preference"
    const val IS_NIGHT_MODE_KEY = "isNightMode"
    const val THEME_DID_CHANGED = "themeDidChanged"

    /** 与 [DshChromePalette] / `DshThemeTokens.background` 同源。 */
    fun backgroundColor(isDark: Boolean): Int = DshChromePalette.backgroundArgb(isDark)

    fun systemIsDark(context: Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    /** 按用户偏好 + 系统状态解析当前应使用的深浅色。任何异常都回退为跟随系统。 */
    fun resolveIsDark(context: Context): Boolean {
        val preference = runCatching {
            context.getSharedPreferences(SP_FILE, Context.MODE_PRIVATE).getString(PREF_KEY, null)
        }.getOrNull()?.trim()?.lowercase()
        return DshThemePreference.fromStorage(preference).resolvedIsDark(systemIsDark(context))
    }

    /** 刷新窗口背景、状态栏图标风格以及传入的容器视图背景。 */
    fun apply(activity: Activity, isDark: Boolean, vararg containers: View?) {
        val window = activity.window ?: return
        val bg = backgroundColor(isDark)
        window.setBackgroundDrawable(ColorDrawable(bg))
        containers.forEach { it?.setBackgroundColor(bg) }

        // 深色主题用浅色图标，浅色主题用深色图标。同时走 InsetsController 与 legacy flag，
        // 兼容 Android 11+ 与旧系统，且避免 OEM 在窗口重建后丢掉其中一种设置。
        val decor = window.decorView
        WindowInsetsControllerCompat(window, decor).apply {
            isAppearanceLightStatusBars = !isDark
            isAppearanceLightNavigationBars = !isDark
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            var flags = decor.systemUiVisibility
            flags = if (isDark) {
                flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
            } else {
                flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                flags = if (isDark) {
                    flags and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
                } else {
                    flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                }
            }
            decor.systemUiVisibility = flags
        }
    }
}
