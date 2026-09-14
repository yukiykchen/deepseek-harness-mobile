package com.example.dsh.module

import android.util.Log
import com.tencent.kuikly.core.render.android.export.KuiklyRenderBaseModule
import com.tencent.kuikly.core.render.android.export.KuiklyRenderCallback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Bidirectional WebSocket transport for the Host's `/api/remote.mux` stream socket. */
internal class KRDshWebSocketModule : KuiklyRenderBaseModule() {
    private val connections = ConcurrentHashMap<String, WebSocketConnection>()

    override fun onDestroy() {
        connections.values.forEach(WebSocketConnection::close)
        connections.clear()
        super.onDestroy()
    }

    override fun call(method: String, params: String?, callback: KuiklyRenderCallback?): Any? {
        val value = runCatching { JSONObject(params ?: "{}") }.getOrDefault(JSONObject())
        return when (method) {
            "connect" -> {
                val connectionId = value.optString("connectionId")
                val url = value.optString("url")
                if (connectionId.isEmpty() || url.isEmpty() || callback == null) return null
                connections.remove(connectionId)?.close()
                WebSocketConnection(
                    url = url,
                    token = value.optString("token"),
                    cookie = value.optString("cookie"),
                    emit = { event -> activity?.runOnUiThread { callback.invoke(event) } },
                    onFinished = { connections.remove(connectionId) },
                ).also {
                    connections[connectionId] = it
                    it.start()
                }
                null
            }
            "send" -> {
                connections[value.optString("connectionId")]?.send(value.optString("data"))
                null
            }
            "disconnect" -> {
                connections.remove(value.optString("connectionId"))?.close()
                null
            }
            else -> null
        }
    }

    private class WebSocketConnection(
        private val url: String,
        private val token: String,
        private val cookie: String,
        private val emit: (Map<String, Any>) -> Unit,
        private val onFinished: () -> Unit,
    ) {
        @Volatile private var closed = false
        @Volatile private var socket: WebSocket? = null
        private val finished = AtomicBoolean(false)

        fun start() {
            val request = Request.Builder()
                .url(url)
                .apply {
                    if (token.isNotEmpty()) header("Authorization", "Bearer $token")
                    if (cookie.isNotEmpty()) header("Cookie", cookie)
                }
                .build()
            socket = WEB_SOCKET_CLIENT.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (!closed) emit(mapOf("kind" to "OPEN"))
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (!closed && text.isNotEmpty()) emit(mapOf("kind" to "FRAME", "data" to text))
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, reason)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    socket = null
                    if (!closed) emit(mapOf("kind" to "CLOSED", "message" to reason))
                    finish()
                }

                override fun onFailure(webSocket: WebSocket, error: Throwable, response: Response?) {
                    socket = null
                    if (!closed) {
                        val status = response?.code ?: 0
                        Log.e(TAG, "Host WebSocket failed url=$url http=$status", error)
                        emit(mapOf(
                            "kind" to "ERROR",
                            "message" to (error.message ?: "WebSocket connection failed"),
                            "httpStatus" to status,
                        ))
                    }
                    finish()
                }
            })
        }

        fun send(text: String) {
            if (closed || text.isEmpty()) return
            socket?.send(text)
        }

        fun close() {
            closed = true
            socket?.close(1000, "client closed")
            socket = null
            finish()
        }

        private fun finish() {
            if (finished.compareAndSet(false, true)) onFinished()
        }
    }

    companion object {
        const val MODULE_NAME = "DshWebSocketModule"
        private const val TAG = "DshWebSocket"
        private val WEB_SOCKET_CLIENT = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }
}
