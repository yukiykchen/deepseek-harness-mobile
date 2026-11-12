package io.github.yukiykchen.dsh.relay

import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

internal fun interface DshRelayCancellation { fun cancel() }

internal interface DshRelaySocket {
    fun send(text: String): Boolean
    fun cancel()
}

internal data class DshRelayTicket(val sessionId: String, val token: String, val tunnelUrl: String, val hostId: String) {
    override fun toString() = "DshRelayTicket(sessionId=$sessionId, token=[redacted], hostId=$hostId)"
}

internal class DshRelayHttpError(val status: Int, message: String) : IOException(message)

internal fun readDshRelayJson(response: Response): JSONObject {
    val body = requireNotNull(response.body) { "empty Relay response" }
    require(body.contentLength() <= 64 * 1024) { "Relay response too large" }
    val source = body.source()
    require(!source.request(64 * 1024L + 1)) { "Relay response too large" }
    val json = JSONObject(source.readUtf8().ifBlank { "{}" })
    if (!response.isSuccessful) throw DshRelayHttpError(response.code,
        json.optString("error").ifBlank { "Relay request failed (${response.code})" }.take(512))
    return json
}

internal fun redactRelayMessage(message: String, vararg secrets: String): String {
    var safe = message
    for (secret in secrets) if (secret.isNotEmpty()) safe = safe.replace(secret, "[redacted]")
    return safe.take(512)
}

internal interface DshRelayTransport {
    interface Listener {
        fun onOpen(socket: DshRelaySocket)
        fun onMessage(socket: DshRelaySocket, text: String)
        fun onClosed(socket: DshRelaySocket, message: String)
    }
    fun ticket(credentials: DshRelayCredentials, callback: (Result<DshRelayTicket>) -> Unit): DshRelayCancellation
    fun open(ticket: DshRelayTicket, listener: Listener): DshRelaySocket
}

internal class DshOkHttpRelayTransport(
    private val userAgent: String,
    private val http: OkHttpClient = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build(),
    private val tunnel: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build(),
) : DshRelayTransport {
    override fun ticket(credentials: DshRelayCredentials, callback: (Result<DshRelayTicket>) -> Unit): DshRelayCancellation {
        val call = http.newCall(Request.Builder()
            .url("${credentials.relayOrigin.trimEnd('/')}/access-ticket")
            .header("Authorization", "Bearer ${credentials.clientToken}")
            .header("User-Agent", userAgent)
            .post("{}".toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build())
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { callback(Result.failure(e)) }
            override fun onResponse(call: Call, response: Response) {
                val result = runCatching {
                    response.use {
                        val json = readDshRelayJson(it)
                        val sessionId = json.optString("accessSessionId")
                        val token = json.optString("ticket")
                        require(sessionId.isNotBlank() && token.isNotBlank()) { "ticket response incomplete" }
                        val origin = credentials.relayOrigin.trimEnd('/')
                        val wsOrigin = if (origin.startsWith("https:")) origin.replaceFirst("https:", "wss:")
                            else origin.replaceFirst("http:", "ws:")
                        DshRelayTicket(sessionId, token, json.optString("tunnelUrl").ifBlank { "$wsOrigin/client-tunnel" },
                            json.optString("hostId").ifBlank { credentials.hostId })
                    }
                }
                callback(result)
            }
        })
        return DshRelayCancellation { call.cancel() }
    }

    override fun open(ticket: DshRelayTicket, listener: DshRelayTransport.Listener): DshRelaySocket {
        val socket = NativeSocket()
        val native = tunnel.newWebSocket(Request.Builder()
            .url(ticket.tunnelUrl)
            .header("Authorization", "Bearer ${ticket.token}")
            .header("User-Agent", userAgent)
            .header("Sec-WebSocket-Protocol", "dsh-e2ee-v1")
            .build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                socket.attach(webSocket)
                listener.onOpen(socket)
            }
            override fun onMessage(webSocket: WebSocket, text: String) { listener.onMessage(socket, text) }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { listener.onClosed(socket, reason) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                response?.close()
                listener.onClosed(socket, t.message.orEmpty())
            }
        })
        socket.attach(native)
        return socket
    }

    private class NativeSocket : DshRelaySocket {
        private var delegate: WebSocket? = null
        private var cancelled = false
        @Synchronized fun attach(socket: WebSocket) {
            delegate = socket
            if (cancelled) socket.cancel()
        }
        @Synchronized override fun send(text: String): Boolean = !cancelled && delegate?.send(text) == true
        @Synchronized override fun cancel() {
            cancelled = true
            delegate?.cancel()
        }
    }
}

internal object DshRelayTimers {
    private val executor = ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, "dsh-relay-timer").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }

    fun schedule(delayMs: Long, action: () -> Unit): DshRelayCancellation {
        val task = executor.schedule(action, delayMs, TimeUnit.MILLISECONDS)
        return DshRelayCancellation { task.cancel(false) }
    }
}
