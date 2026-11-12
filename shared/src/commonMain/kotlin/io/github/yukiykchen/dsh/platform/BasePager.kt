package io.github.yukiykchen.dsh.platform

import com.tencent.kuikly.core.base.*
import com.tencent.kuikly.core.module.Module
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.pager.Pager
import com.tencent.kuikly.core.reactive.handler.*
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.views.*
import io.github.yukiykchen.dsh.framework.BridgeModule
import io.github.yukiykchen.dsh.framework.DshDiagnostics

abstract class BasePager : Pager() {
    var nightModel: Boolean? by observable(null)

    override fun createExternalModules(): Map<String, Module>? {
        val externalModules = hashMapOf<String, Module>()
        externalModules[BridgeModule.MODULE_NAME] = BridgeModule()
        externalModules[DshEngineModule.MODULE_NAME] = DshEngineModule()
        externalModules[DshRelayModule.MODULE_NAME] = DshRelayModule()
        externalModules[DshWebSocketModule.MODULE_NAME] = DshWebSocketModule()
        return externalModules
    }

    override fun created() {
        super.created()
        DshDiagnostics.configure(pageData.params.optBoolean(VERBOSE_DIAGNOSTICS_KEY))
        isNightMode()
    }

    override fun themeDidChanged(data: JSONObject) {
        super.themeDidChanged(data)
        nightModel = data.optBoolean(IS_NIGHT_MODE_KEY)
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

    companion object {
        const val IS_NIGHT_MODE_KEY = "isNightMode"
        const val VERBOSE_DIAGNOSTICS_KEY = "verboseDiagnostics"
    }

}
