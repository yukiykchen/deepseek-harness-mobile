package com.example.dsh.base

import com.example.dsh.dsh.DshEngineModule
import com.example.dsh.dsh.DshRelayModule
import com.example.dsh.dsh.DshSseModule
import com.example.dsh.dsh.DshThemeModule
import com.example.dsh.dsh.DshWebSocketModule
import com.example.dsh.theme.DshTheme
import com.example.dsh.theme.DshThemePreference
import com.example.dsh.theme.DshThemeSnapshot
import com.tencent.kuikly.core.log.KLog
import com.tencent.kuikly.core.module.CallbackRef
import com.tencent.kuikly.core.module.Module
import com.tencent.kuikly.core.module.NotifyModule
import com.tencent.kuikly.core.module.SharedPreferencesModule
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.pager.Pager
import com.tencent.kuikly.core.reactive.handler.*

internal abstract class BasePager : Pager() {
    private var systemDark = false

    /**
     * 当前 Pager 的主题镜像。真正的状态源是 [DshTheme]；
     * 这里镜像是为了让 Kuikly observable 能驱动 attr 更新。
     */
    var theme: DshThemeSnapshot by observable(DshTheme.snapshot)
        private set

    private var themeCallbackRef: CallbackRef? = null

    override fun createExternalModules(): Map<String, Module>? {
        val externalModules = hashMapOf<String, Module>()
        externalModules[BridgeModule.MODULE_NAME] = BridgeModule()
        externalModules[DshEngineModule.MODULE_NAME] = DshEngineModule()
        externalModules[DshRelayModule.MODULE_NAME] = DshRelayModule()
        externalModules[DshSseModule.MODULE_NAME] = DshSseModule()
        externalModules[DshWebSocketModule.MODULE_NAME] = DshWebSocketModule()
        externalModules[DshThemeModule.MODULE_NAME] = DshThemeModule()
        return externalModules
    }

    override fun created() {
        super.created()
        systemDark = pageData.params.optBoolean(IS_NIGHT_MODE_KEY)
        val stored = runCatching { sharedPreferences().getItem(DshTheme.PREF_KEY) }
            .onFailure { KLog.e(TAG, "read theme preference failed: ${it.message}") }
            .getOrNull()
        DshTheme.bootstrap(stored?.ifEmpty { null }, systemDark)
        theme = DshTheme.snapshot
        themeCallbackRef = notifyModule().addNotify(DshTheme.EVENT) { theme = DshTheme.snapshot }
    }

    override fun pageWillDestroy() {
        themeCallbackRef?.let { notifyModule().removeNotify(DshTheme.EVENT, it) }
        themeCallbackRef = null
        super.pageWillDestroy()
    }

    override fun themeDidChanged(data: JSONObject) {
        super.themeDidChanged(data)
        systemDark = data.optBoolean(IS_NIGHT_MODE_KEY)
        if (DshTheme.updateSystemDark(systemDark)) {
            publishTheme()
        }
    }

    fun setThemePreference(preference: DshThemePreference) {
        val persisted = persistThemePreference(preference)
        if (DshTheme.setPreference(preference)) {
            publishTheme()
        }
        if (!persisted) {
            acquireModule<BridgeModule>(BridgeModule.MODULE_NAME)
                .toast("设置未能保存，下次启动可能恢复默认")
        }
    }

    override fun isNightMode(): Boolean = systemDark

    // 不开启调试UI模式
    override fun debugUIInspector(): Boolean {
        return false
    }

    private fun publishTheme() {
        theme = DshTheme.snapshot
        notifyModule().postNotify(DshTheme.EVENT, JSONObject())
        runCatching {
            acquireModule<DshThemeModule>(DshThemeModule.MODULE_NAME).applyNativeChrome(DshTheme.snapshot.isDark)
        }.onFailure { KLog.e(TAG, "applyNativeChrome failed: ${it.message}") }
    }

    private fun persistThemePreference(preference: DshThemePreference): Boolean {
        return runCatching {
            sharedPreferences().setItem(DshTheme.PREF_KEY, preference.storageValue)
            true
        }.onFailure { KLog.e(TAG, "persist theme preference failed: ${it.message}") }
            .getOrDefault(false)
    }

    private fun sharedPreferences(): SharedPreferencesModule =
        acquireModule(SharedPreferencesModule.MODULE_NAME)

    private fun notifyModule(): NotifyModule = acquireModule(NotifyModule.MODULE_NAME)

    companion object {
        const val IS_NIGHT_MODE_KEY = "isNightMode"
        private const val TAG = "BasePager"
    }
}
