package io.github.yukiykchen.dsh.data

import com.tencent.kuikly.core.module.NMAllResponse
import com.tencent.kuikly.core.module.NetworkModule
import com.tencent.kuikly.core.module.NetworkResponse
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import io.github.yukiykchen.dsh.platform.DshWebSocketEvent
import io.github.yukiykchen.dsh.platform.DshWebSocketHandle
import io.github.yukiykchen.dsh.platform.DshWebSocketModule

/** Native bridge boundary used by the Host connection state machine. */
interface DshHostTransport {
    fun connect(url: String, token: String, onEvent: (DshWebSocketEvent) -> Unit): DshWebSocketHandle
    fun post(url: String, body: JSONObject, headers: JSONObject, timeoutSeconds: Int, callback: NMAllResponse)
}

class DshNativeHostTransport(
    private val network: NetworkModule,
    private val webSocket: DshWebSocketModule,
) : DshHostTransport {
    override fun connect(url: String, token: String, onEvent: (DshWebSocketEvent) -> Unit) =
        webSocket.connect(url, token, onEvent)

    override fun post(url: String, body: JSONObject, headers: JSONObject, timeoutSeconds: Int, callback: NMAllResponse) {
        network.httpRequest(url, true, body, headers, null, timeoutSeconds, callback)
    }
}

internal fun DshHostTransport.postSafely(
    url: String,
    body: JSONObject,
    headers: JSONObject,
    timeoutSeconds: Int,
    callback: NMAllResponse,
) {
    var delivered = false
    try {
        post(url, body, headers, timeoutSeconds) { data, success, message, response ->
            delivered = true
            callback(data, success, message, response)
        }
    } catch (error: Throwable) {
        // A synchronous bridge failure is a transport error, not a second callback.
        if (delivered) throw error
        callback(JSONObject(), false, error.message ?: "HTTP bridge failed", NetworkResponse(JSONObject(), 0))
    }
}
