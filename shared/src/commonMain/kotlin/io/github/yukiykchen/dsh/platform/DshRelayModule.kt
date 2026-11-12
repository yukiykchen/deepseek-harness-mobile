package io.github.yukiykchen.dsh.platform

import com.tencent.kuikly.core.base.*
import com.tencent.kuikly.core.module.Module
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.reactive.handler.*
import com.tencent.kuikly.core.views.*

enum class DshRelayPhase {
    IDLE, SCANNING, PAIRING, CONNECTING, HANDSHAKING, READY, RECONNECTING, ERROR, STOPPED,
}

data class DshRelayState(
    val phase: DshRelayPhase = DshRelayPhase.IDLE,
    val message: String = "",
    val localPort: Int = 0,
    val localToken: String = "",
    val hostId: String = "",
    val hostName: String = "",
    val relayOrigin: String = "",
    val paired: Boolean = false,
    val generation: Long = 0,
)

data class DshRelayPairResult(
    val ok: Boolean,
    val message: String = "",
    val hostId: String = "",
    val hostName: String = "",
    val relayOrigin: String = "",
    val pairedAt: Long = 0,
)

class DshRelayModule : Module() {
    override fun moduleName(): String = MODULE_NAME
    fun scanAndPair(onResult: (DshRelayPairResult) -> Unit) {
        toNative(true, "scanAndPair", null, { value ->
            onResult(
                DshRelayPairResult(
                    ok = value?.optBoolean("ok") == true,
                    message = value?.optString("message").orEmpty(),
                    hostId = value?.optString("hostId").orEmpty(),
                    hostName = value?.optString("hostName").orEmpty(),
                    relayOrigin = value?.optString("relayOrigin").orEmpty(),
                    pairedAt = value?.optLong("pairedAt") ?: 0L,
                ),
            )
        }, false)
    }
    fun connect(onState: (DshRelayState) -> Unit) {
        toNative(true, "connect", null, { value -> onState(value.toRelayState()) }, false)
    }
    fun disconnect() {
        toNative(false, "disconnect", null, null, false)
    }
    fun forget(onDone: (Boolean) -> Unit) {
        toNative(false, "forget", null, { value ->
            onDone(value?.optBoolean("ok") == true)
        }, false)
    }
    fun status(): DshRelayState {
        val raw = toNative(false, "status", null, null, true).toString()
        return runCatching { JSONObject(raw).toRelayState() }.getOrDefault(DshRelayState())
    }
    fun subscribe(onState: (DshRelayState) -> Unit) {
        toNative(true, "subscribe", null, { value -> onState(value.toRelayState()) }, false)
    }

    companion object {
        const val MODULE_NAME = "DshRelayModule"
    }
}

fun JSONObject?.toRelayState(): DshRelayState {
    val value = this ?: return DshRelayState()
    return DshRelayState(
        phase = runCatching { DshRelayPhase.valueOf(value.optString("phase", "IDLE")) }
            .getOrDefault(DshRelayPhase.IDLE),
        message = value.optString("message"),
        localPort = value.optInt("localPort"),
        localToken = value.optString("localToken"),
        hostId = value.optString("hostId"),
        hostName = value.optString("hostName"),
        relayOrigin = value.optString("relayOrigin"),
        paired = value.optBoolean("paired"),
        generation = value.optLong("generation"),
    )
}
