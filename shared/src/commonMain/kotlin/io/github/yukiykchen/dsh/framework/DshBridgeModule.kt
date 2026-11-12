package io.github.yukiykchen.dsh.framework

import com.tencent.kuikly.core.module.CallbackFn
import com.tencent.kuikly.core.module.Module
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

/** Native capabilities actually used by DSH pages. */
class BridgeModule : Module() {
    override fun moduleName(): String = MODULE_NAME

    fun copyToPasteboard(content: String) {
        callNativeMethod("copyToPasteboard", JSONObject().put("content", content), null)
    }

    fun toast(content: String) {
        callNativeMethod("toast", JSONObject().put("content", content), null)
    }

    fun currentTimeStamp(): Long =
        syncCallNativeMethod("currentTimestamp", null, null).toLongOrNull() ?: 0L

    fun closeKeyboard(): String = syncCallNativeMethod("closeKeyboard", null, null)

    fun setSystemBarsDimmed(dimmed: Boolean) {
        callNativeMethod("setSystemBarsDimmed", JSONObject().put("dimmed", dimmed), null)
    }

    fun pickSshKey(callback: (String) -> Unit) {
        callNativeMethod("pickSshKey", null) { value ->
            callback(value?.optString("uri").orEmpty())
        }
    }

    fun importSshKey(uri: String, callback: (String) -> Unit) {
        callNativeMethod("importSshKey", JSONObject().put("uri", uri)) { value ->
            callback(if (value?.optBoolean("ok") == true) value.optString("keyId") else "")
        }
    }

    fun deleteSshKey(keyId: String) {
        callNativeMethod("deleteSshKey", JSONObject().put("keyId", keyId), null)
    }

    fun validateSshKey(keyId: String, passphrase: String = "", callback: (Boolean) -> Unit) {
        callNativeMethod("validateSshKey", JSONObject()
            .put("keyId", keyId)
            .put("keyPassphrase", passphrase)) { value ->
            callback(value?.optBoolean("valid") == true)
        }
    }

    fun callNativeMethod(methodName: String, data: JSONObject?, callbackFn: CallbackFn?) {
        toNative(false, methodName, data?.toString(), callbackFn, false)
    }

    fun syncCallNativeMethod(methodName: String, data: JSONObject?, callbackFn: CallbackFn?): String =
        toNative(false, methodName, data?.toString(), callbackFn, true).toString()

    companion object {
        const val MODULE_NAME = "HRBridgeModule"
    }
}
