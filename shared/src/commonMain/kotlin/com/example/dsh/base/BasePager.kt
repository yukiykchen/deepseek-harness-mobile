package com.example.dsh.base

import com.example.dsh.dsh.DshCodeTheme
import com.example.dsh.dsh.DshEngineModule
import com.example.dsh.dsh.DshMediaModule
import com.example.dsh.dsh.DshPalette
import com.example.dsh.dsh.DshRelayModule
import com.example.dsh.dsh.DshSolarClock
import com.example.dsh.dsh.DshTheme
import com.example.dsh.dsh.DshThemeMode
import com.example.dsh.dsh.DshWebSocketModule
import com.tencent.kuikly.core.datetime.DateTime
import com.tencent.kuikly.core.pager.Pager
import com.tencent.kuikly.core.module.Module
import com.tencent.kuikly.core.module.SharedPreferencesModule
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.reactive.handler.*
import com.tencent.kuikly.core.timer.setTimeout

internal abstract class BasePager : Pager() {
    private var nightModel: Boolean? by observable(null)
    private var themeMode: DshThemeMode by observable(DshThemeMode.SYSTEM)
    private var codeTheme: DshCodeTheme by observable(DshCodeTheme.AUTO)
    private var solarNight: Boolean by observable(false)
    private var solarTickArmed = false

    /** Resolved colours for the current mode. Surfaces read it through `PagerScope.theme`. */
    internal val palette: DshPalette
        get() = DshTheme.palette(themeMode, nightModel ?: false, solarNight, codeTheme)

    internal fun currentThemeMode(): DshThemeMode = themeMode

    internal fun currentCodeTheme(): DshCodeTheme = codeTheme

    internal fun applyThemeMode(mode: DshThemeMode) {
        if (themeMode == mode) return
        themeMode = mode
        runCatching { themePrefs().setItem(DshTheme.THEME_MODE_KEY, mode.storageValue) }
        refreshSolarNight()
        syncNativeThemeChrome()
    }

    internal fun applyCodeTheme(code: DshCodeTheme) {
        if (codeTheme == code) return
        codeTheme = code
        runCatching { themePrefs().setItem(DshTheme.CODE_THEME_KEY, code.storageValue) }
    }

    override fun createExternalModules(): Map<String, Module>? {
        val externalModules = hashMapOf<String, Module>()
        externalModules[BridgeModule.MODULE_NAME] = BridgeModule()
        externalModules[DshEngineModule.MODULE_NAME] = DshEngineModule()
        externalModules[DshRelayModule.MODULE_NAME] = DshRelayModule()
        externalModules[DshWebSocketModule.MODULE_NAME] = DshWebSocketModule()
        externalModules[DshMediaModule.MODULE_NAME] = DshMediaModule()
        return externalModules
    }

    override fun created() {
        super.created()
        isNightMode()
        restoreThemePreferences()
    }

    override fun pageDidAppear() {
        super.pageDidAppear()
        syncNativeThemeChrome()
    }

    override fun themeDidChanged(data: JSONObject) {
        super.themeDidChanged(data)
        nightModel = data.optBoolean(IS_NIGHT_MODE_KEY)
        syncNativeThemeChrome()
    }

    // 是否为夜间模式
    override fun isNightMode(): Boolean {
        if (nightModel == null) {
            nightModel = pageData.params.optBoolean(IS_NIGHT_MODE_KEY)
        }
        return nightModel!!
    }

    // 不开启调试UI模式
    override fun debugUIInspector(): Boolean {
        return false
    }

    private fun restoreThemePreferences() {
        val prefs = runCatching { themePrefs() }.getOrNull() ?: return
        themeMode = DshThemeMode.fromStorage(prefs.getItem(DshTheme.THEME_MODE_KEY))
        codeTheme = DshCodeTheme.fromStorage(prefs.getItem(DshTheme.CODE_THEME_KEY))
        refreshSolarNight()
    }

    /**
     * Re-resolves daylight and keeps a one-minute tick running while the solar mode is
     * selected. The tick re-arms itself rather than repeating, so leaving the mode stops
     * it, and only one is ever in flight.
     */
    private fun refreshSolarNight() {
        if (themeMode != DshThemeMode.SOLAR) {
            solarNight = false
            return
        }
        val night = DshSolarClock.isNight(DateTime.currentTimestamp(), utcOffsetMinutes())
        if (solarNight != night) {
            solarNight = night
            syncNativeThemeChrome()
        }
        if (solarTickArmed) return
        solarTickArmed = true
        setTimeout(pagerId, SOLAR_TICK_MS) {
            solarTickArmed = false
            refreshSolarNight()
        }
    }

    /** Injected by the native host next to `isNightMode`; UTC when a host does not send it. */
    private fun utcOffsetMinutes(): Int = pageData.params.optInt(UTC_OFFSET_MINUTES_KEY, 0)

    /** Status bar glyphs and the native window background follow the resolved palette. */
    private fun syncNativeThemeChrome() {
        runCatching {
            acquireModule<BridgeModule>(BridgeModule.MODULE_NAME).setStatusBarStyle(palette.isDark)
        }
    }

    private fun themePrefs(): SharedPreferencesModule =
        acquireModule(SharedPreferencesModule.MODULE_NAME)

    companion object {
        const val IS_NIGHT_MODE_KEY = "isNightMode"
        const val UTC_OFFSET_MINUTES_KEY = "utcOffsetMinutes"
        private const val SOLAR_TICK_MS = 60_000
    }

}
