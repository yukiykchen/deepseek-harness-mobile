package com.example.dsh.dsh

import com.tencent.kuikly.core.module.Module
import com.tencent.kuikly.core.log.KLog
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

internal enum class DshWebSocketEventKind {
    OPEN,
    FRAME,
    ERROR,
    CLOSED,
}

internal data class DshWebSocketEvent(
    val kind: DshWebSocketEventKind,
    val data: String = "",
    val message: String = "",
    /** HTTP status of a failed upgrade (401 = cookie rejected), 0 when unknown. */
    val httpStatus: Int = 0,
)

internal interface DshWebSocketHandle {
    fun send(text: String)
    fun close()
}

/** Native WebSocket bridge for the Host's `/api/remote.mux` stream socket. */
internal class DshWebSocketModule : Module() {
    private var connectionSequence = 0

    override fun moduleName(): String = MODULE_NAME

    fun connect(
        url: String,
        token: String = "",
        cookie: String = "",
        onEvent: (DshWebSocketEvent) -> Unit,
    ): DshWebSocketHandle {
        val connectionId = "dsh-ws-${++connectionSequence}"
        val params = JSONObject().apply {
            put("connectionId", connectionId)
            put("url", url)
            put("token", token)
            put("cookie", cookie)
        }
        KLog.i(TAG, "connect requested")
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
                    httpStatus = value?.optInt("httpStatus") ?: 0,
                ))
            },
            syncCall = false,
        )
        return object : DshWebSocketHandle {
            private var closed = false

            override fun send(text: String) {
                if (closed) return
                toNative(
                    keepCallbackAlive = false,
                    methodName = "send",
                    param = JSONObject().apply {
                        put("connectionId", connectionId)
                        put("data", text)
                    }.toString(),
                    callback = null,
                    syncCall = false,
                )
            }

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
        private const val TAG = "DshWebSocket"
    }
}
