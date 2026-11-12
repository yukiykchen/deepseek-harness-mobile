package io.github.yukiykchen.dsh.data

import com.tencent.kuikly.core.module.NMAllResponse
import com.tencent.kuikly.core.module.NetworkModule
import com.tencent.kuikly.core.module.NetworkResponse
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import io.github.yukiykchen.dsh.model.DshHostConnection
import io.github.yukiykchen.dsh.platform.DshWebSocketEvent
import io.github.yukiykchen.dsh.platform.DshWebSocketEventKind
import io.github.yukiykchen.dsh.platform.DshWebSocketHandle
import io.github.yukiykchen.dsh.platform.DshWebSocketModule

internal class FakeHostTransport : DshHostTransport {
    class Socket(val onEvent: (DshWebSocketEvent) -> Unit) : DshWebSocketHandle {
        var closed = false
        fun open() = onEvent(DshWebSocketEvent(DshWebSocketEventKind.OPEN))
        fun disconnect() = onEvent(DshWebSocketEvent(DshWebSocketEventKind.CLOSED))
        fun frame(data: String) = onEvent(DshWebSocketEvent(DshWebSocketEventKind.FRAME, data))
        override fun close() { closed = true }
    }

    class Request(val body: JSONObject, val callback: NMAllResponse) {
        val method get() = body.optString("method")
        fun reply(value: String = "{}") = callback(
            JSONObject("""{"result":{"ok":true,"value":$value}}"""), true, "", NetworkResponse(JSONObject(), 200),
        )
        fun receipt(accepted: Boolean, reason: String = "") = callback(
            JSONObject().apply { put("accepted", accepted); put("reason", reason) },
            true, "", NetworkResponse(JSONObject(), 200),
        )
        fun fail(message: String = "transport failed") = callback(
            JSONObject(), false, message, NetworkResponse(JSONObject(), 503),
        )
    }

    val sockets = mutableListOf<Socket>()
    val requests = mutableListOf<Request>()
    var onConnect: (Socket) -> Unit = {}
    var onPost: (Request) -> Unit = {}
    override fun connect(url: String, token: String, onEvent: (DshWebSocketEvent) -> Unit): DshWebSocketHandle {
        val socket = Socket(onEvent)
        sockets.add(socket)
        onConnect(socket)
        return socket
    }

    override fun post(url: String, body: JSONObject, headers: JSONObject, timeoutSeconds: Int, callback: NMAllResponse) {
        val request = Request(body, callback)
        requests.add(request)
        onPost(request)
    }

    fun completeHandshake() {
        sockets.takeLast(2).forEach { it.open() }
        requests.last { it.method == "host.describe" }.reply()
        requests.last { it.method == "workspace.list" }.reply("""{"items":[]}""")
        requests.last { it.method == "session.list" }.reply("""{"items":[]}""")
    }
}

internal fun createTestHostClient(transport: FakeHostTransport = FakeHostTransport()) = DshHostClient(
    network = NetworkModule(),
    webSocket = DshWebSocketModule(),
    connection = DshHostConnection("http://localhost"),
    pagerId = "test-page",
    transport = transport,
    schedule = { _, _ -> },
).also { it.start() }
