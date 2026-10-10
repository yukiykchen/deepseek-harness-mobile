package com.example.dsh.dsh

import com.tencent.kuikly.core.module.Module
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

/** Native SSH tunnel bridge (module name kept for native compatibility). */
internal class DshEngineModule : Module() {
    override fun moduleName(): String = MODULE_NAME

    fun startSsh(config: DshSshConfig, onState: (DshSshState) -> Unit) {
        val params = JSONObject().apply {
            put("host", config.host)
            put("port", config.port)
            put("username", config.username)
            put("remoteDshPort", config.remoteDshPort)
            put("keyId", config.keyId)
            put("hostFingerprint", config.hostFingerprint)
            put("keyPassphrase", config.keyPassphrase)
        }
        toNative(
            keepCallbackAlive = true,
            methodName = "startSsh",
            param = params.toString(),
            callback = { value ->
                onState(DshSshState(
                    phase = runCatching { DshSshPhase.valueOf(value?.optString("phase") ?: "ERROR") }
                        .getOrDefault(DshSshPhase.ERROR),
                    message = value?.optString("message").orEmpty(),
                    localPort = value?.optInt("localPort") ?: 0,
                    generation = value?.optLong("generation") ?: 0L,
                ))
            },
            syncCall = false,
        )
    }

    fun trustSshFingerprint(fingerprint: String) {
        toNative(false, "trustSshFingerprint", JSONObject().apply {
            put("fingerprint", fingerprint)
        }.toString(), null, false)
    }

    fun stopSsh() {
        toNative(false, "stopSsh", null, null, false)
    }

    companion object {
        const val MODULE_NAME = "DshEngineModule"
    }
}

internal enum class DshSshPhase {
    IDLE, CONNECTING, AUTHENTICATING, FINGERPRINT_REQUIRED, FORWARDING, READY,
    RECONNECTING, ERROR, STOPPED,
}

internal data class DshSshState(
    val phase: DshSshPhase,
    val message: String = "",
    val localPort: Int = 0,
    val generation: Long = 0,
)

internal data class DshSshConfig(
    val host: String,
    val port: Int = 22,
    val username: String,
    val remoteDshPort: Int = 3080,
    val keyId: String,
    val hostFingerprint: String = "",
    val keyPassphrase: String = "",
)
