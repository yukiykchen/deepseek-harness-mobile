package com.example.dsh.module

import com.example.dsh.KuiklyRenderActivity
import com.tencent.kuikly.core.render.android.export.KuiklyRenderBaseModule
import com.tencent.kuikly.core.render.android.export.KuiklyRenderCallback
import org.json.JSONObject

/** Android 实现：Kuikly 侧切换主题后同步窗口背景与系统栏外观。 */
class KRDshThemeModule : KuiklyRenderBaseModule() {

    override fun call(method: String, params: String?, callback: KuiklyRenderCallback?): Any? {
        return when (method) {
            METHOD_APPLY_NATIVE_CHROME -> applyNativeChrome(params)
            else -> null
        }
    }

    private fun applyNativeChrome(params: String?) {
        val isDark = JSONObject(params ?: "{}").optBoolean("isDark")
        val host = activity as? KuiklyRenderActivity ?: return
        host.runOnUiThread { host.applyThemeChrome(isDark) }
    }

    companion object {
        const val MODULE_NAME = "DshThemeModule"
        private const val METHOD_APPLY_NATIVE_CHROME = "applyNativeChrome"
    }
}
