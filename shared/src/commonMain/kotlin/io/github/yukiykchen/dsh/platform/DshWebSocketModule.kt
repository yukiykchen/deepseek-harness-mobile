package io.github.yukiykchen.dsh.platform

import com.tencent.kuikly.core.base.*
import io.github.yukiykchen.dsh.framework.DshDiagnostics
import com.tencent.kuikly.core.module.Module
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.reactive.handler.*
import com.tencent.kuikly.core.views.*

enum class DshWebSocketEventKind {
    OPEN,
    FRAME,
    ERROR,
    CLOSED,
}

data class DshWebSocketEvent(
    val kind: DshWebSocketEventKind,
    val data: String = "",
    val message: String = "",
)

interface DshWebSocketHandle {
    fun close()
}

/** Native WebSocket bridge for the Host's two downlink-only event routes. */

class DshWebSocketModule : Module() {
    var connectionSequence = 0

    override fun moduleName(): String = MODULE_NAME
    fun connect(
        url: String,
        token: String = "",
        onEvent: (DshWebSocketEvent) -> Unit,
    ): DshWebSocketHandle {
        val connectionId = "dsh-ws-${++connectionSequence}"
        val params = JSONObject().apply {
            put("connectionId", connectionId)
            put("url", url)
            put("token", token)
        }
        DshDiagnostics.info(TAG, "connect requested")
        toNative(
            keepCallbackAlive = true,
            methodName = "connect",
            param = params.toString(),
            callback = { value ->
                val kind = runCatching {
                    DshWebSocketEventKind.valueOf(value?.optString("kind").orEmpty())
                }.getOrDefault(DshWebSocketEventKind.ERROR)
                onEvent(DshWebSocketEvent(
                    kind = kind,
                    data = value?.optString("data").orEmpty(),
                    message = value?.optString("message").orEmpty(),
                ))
            },
            syncCall = false,
        )
        return object : DshWebSocketHandle {
            var closed = false

            override fun close() {
                if (closed) return
                closed = true
                toNative(
                    keepCallbackAlive = false,
                    methodName = "disconnect",
                    param = JSONObject().apply { put("connectionId", connectionId) }.toString(),
                    callback = null,
                    syncCall = false,
                )
            }
        }
    }

    companion object {
        const val MODULE_NAME = "DshWebSocketModule"
        const val TAG = "DshWebSocket"
    }
}
