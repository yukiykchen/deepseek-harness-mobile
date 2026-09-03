package com.example.dsh.dsh

import com.tencent.kuikly.core.module.Module
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

/**
 * 主题相关的原生能力：让宿主窗口背景、状态栏图标颜色与 Kuikly 页面的深浅色保持一致。
 * Android / iOS / 鸿蒙均需注册同名 Native Module；调用失败只记日志，不阻断页面换色。
 */
internal class DshThemeModule : Module() {
    override fun moduleName(): String = MODULE_NAME

    fun applyNativeChrome(isDark: Boolean) {
        toNative(
            false,
            METHOD_APPLY_NATIVE_CHROME,
            JSONObject().apply { put("isDark", isDark) }.toString(),
            null,
            false,
        )
    }

    companion object {
        const val MODULE_NAME = "DshThemeModule"
        const val METHOD_APPLY_NATIVE_CHROME = "applyNativeChrome"
    }
}
