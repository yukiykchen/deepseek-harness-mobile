package io.github.yukiykchen.dsh.relay

import android.util.Log
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

internal class DshRelayLoopbackServer(
    private val token: String,
    private val sendInner: (String, JSONObject, String) -> Unit,
    private val responseTimeoutMs: Long = 120_000,
    private val wsOpenTimeoutMs: Long = 10_000,
    private val warning: (String) -> Unit = { Log.w("DshRelayLoopback", it) },
) : DshRelayLoopback {
    private class Waiter(val socket: Socket) {
        val queue = LinkedBlockingQueue<JSONObject>(64)
    }
    private class WsPeer(val socket: Socket) {
        var upgraded = false
        val pending = ArrayDeque<ByteArray>()
        var pendingBytes = 0
    }
    private val lifecycle = Any()
    private val connections = Semaphore(64)
    private val peers = ConcurrentHashMap.newKeySet<Socket>()
    private val httpLimit = AtomicInteger(0)
    private val wsLimit = AtomicInteger(0)
    private val waiters = ConcurrentHashMap<String, Waiter>()
    private val webSockets = ConcurrentHashMap<String, WsPeer>()
    @Volatile private var server: ServerSocket? = null
    @Volatile override var port = 0
        private set
    @Volatile private var stopped = false
    val activePeerCount: Int get() = peers.size

    override fun start(): Int = synchronized(lifecycle) {
        check(!stopped && server == null) { "Loopback server already started or stopped" }
        val bind = ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"))
        server = bind
        port = bind.localPort
        thread(name = "dsh-relay-loopback", isDaemon = true) {
            while (!stopped) {
                val socket = runCatching { bind.accept() }.getOrNull() ?: break
                if (!connections.tryAcquire()) { socket.close(); continue }
                synchronized(lifecycle) {
                    if (stopped) { socket.close(); connections.release(); return@thread }
                    peers.add(socket)
                }
                thread(name = "dsh-relay-conn", isDaemon = true) {
                    try { handle(socket) } finally {
                        peers.remove(socket)
                        connections.release()
                        runCatching { socket.close() }
                    }
                }
            }
        }
        port
    }

    override fun isListening(): Boolean = !stopped && server?.isClosed == false && port > 0

    override fun stop() = synchronized(lifecycle) {
        if (stopped) return@synchronized
        stopped = true
        port = 0
        runCatching { server?.close() }
        server = null
        // Wake response/upgrade waiters as well as readers stalled in an incomplete HTTP header.
        waiters.values.forEach { it.queue.offer(JSONObject().put("shutdown", true)) }
        peers.forEach { runCatching { it.close() } }
        waiters.clear()
        webSockets.clear()
    }

    override fun onInner(type: String, payload: JSONObject, channel: String) {
        if (stopped) return
        when (type) {
            "http_res", "ws_open_ok" -> {
                val waiter = waiters[channel]
                if (waiter == null) {
                    if (type == "ws_open_ok") sendInner("ws_close", JSONObject().put("code", 1001).put("reason", "gateway closed"), channel)
                    return
                }
                val value = if (type == "ws_open_ok") JSONObject().put("ok", true) else payload
                if (!waiter.queue.offer(value)) runCatching { waiter.socket.close() }
            }
            "ws_frame" -> writeWsFrame(channel, payload)
            "ws_close" -> {
                waiters[channel]?.queue?.offer(JSONObject().put("ok", false)
                    .put("reason", payload.optString("reason").ifBlank { "host websocket closed" }))
                webSockets.remove(channel)?.let { runCatching { it.socket.close() } }
            }
        }
    }

    private fun handle(socket: Socket) {
        try {
            socket.soTimeout = 10_000
            val input = BufferedInputStream(socket.getInputStream())
            val output = BufferedOutputStream(socket.getOutputStream())
            val raw = readHeaders(input) ?: return
            val lines = raw.toString(Charsets.ISO_8859_1).split("\r\n")
            val request = lines.first().split(' ', limit = 3)
            if (request.size != 3 || !request[1].startsWith('/') || request[2] !in setOf("HTTP/1.1", "HTTP/1.0")) {
                writeHttp(output, 400, "invalid request line"); return
            }
            val headers = linkedMapOf<String, String>()
            for (line in lines.drop(1).filter { it.isNotEmpty() }) {
                val colon = line.indexOf(':')
                if (colon <= 0) { writeHttp(output, 400, "invalid header"); return }
                val name = line.substring(0, colon).trim().lowercase()
                val value = line.substring(colon + 1).trim()
                if (!validHeader(name, value) || headers.containsKey(name)) { writeHttp(output, 400, "invalid header"); return }
                headers[name] = value
            }
            if (!MessageDigest.isEqual(headers["authorization"].orEmpty().toByteArray(), "Bearer $token".toByteArray())) {
                writeHttp(output, 401, "unauthorized"); return
            }
            if (headers["upgrade"].equals("websocket", true)) {
                acceptWebSocket(socket, input, output, request[1], headers)
                return
            }
            val length = headers["content-length"]?.toLongOrNull() ?: if (headers.containsKey("content-length")) -1 else 0
            if (length < 0 || headers.containsKey("transfer-encoding")) { writeHttp(output, 400, "unsupported request body"); return }
            if (length > 2 * 1024 * 1024) { writeHttp(output, 413, "request too large"); return }
            val body = readExact(input, length.toInt())
            proxyHttp(socket, output, request[0], request[1], headers, body)
        } catch (_: Exception) {
            // A closed peer or truncated input terminates only this local connection.
        }
    }

    private fun proxyHttp(socket: Socket, output: BufferedOutputStream, method: String, path: String,
                          headers: Map<String, String>, body: ByteArray) {
        if (httpLimit.incrementAndGet() > 32) {
            httpLimit.decrementAndGet(); writeHttp(output, 429, "too many tunnels"); return
        }
        val channel = "http-${UUID.randomUUID()}"
        val waiter = Waiter(socket)
        waiters[channel] = waiter
        try {
            if (stopped) return
            sendInner("http_req", JSONObject().put("method", method).put("path", path)
                .put("headers", forwardedHeaders(headers)).put("bodyB64", body.toByteString().base64()), channel)
            var headerWritten = false
            while (!stopped) {
                val part = waiter.queue.poll(responseTimeoutMs, TimeUnit.MILLISECONDS)
                if (part == null) {
                    if (!headerWritten) writeHttp(output, 504, "host response timeout")
                    break
                }
                if (part.optBoolean("shutdown")) break
                if (!headerWritten) {
                    val status = part.optInt("status", 200)
                    require(status in 100..599) { "invalid host status" }
                    val responseHeaders = part.optJSONObject("headers")
                    val pairs = responseHeaders?.keys()?.asSequence()?.map { it to responseHeaders.optString(it) }?.toList().orEmpty()
                    require(pairs.all { validHeader(it.first, it.second) }) { "invalid host header" }
                    writeRaw(output, "HTTP/1.1 $status OK\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n")
                    pairs.filterNot { it.first.lowercase() in HOP_HEADERS }.forEach { (name, value) -> writeRaw(output, "$name: $value\r\n") }
                    writeRaw(output, "\r\n")
                    headerWritten = true
                }
                val chunk = decodeBytes(part.optString("bodyB64"))
                if (chunk.isNotEmpty()) {
                    writeRaw(output, "${chunk.size.toString(16)}\r\n")
                    output.write(chunk)
                    writeRaw(output, "\r\n")
                }
                output.flush()
                if (part.optBoolean("final")) { writeRaw(output, "0\r\n\r\n"); output.flush(); break }
            }
        } finally { waiters.remove(channel); httpLimit.decrementAndGet() }
    }

    private fun acceptWebSocket(socket: Socket, input: BufferedInputStream, output: BufferedOutputStream,
                                path: String, headers: Map<String, String>) {
        val key = headers["sec-websocket-key"].orEmpty()
        val keyBytes = runCatching { decodeBytes(key) }.getOrNull()
        if (headers["sec-websocket-version"] != "13" || keyBytes?.size != 16) { writeHttp(output, 400, "invalid websocket handshake"); return }
        if (wsLimit.incrementAndGet() > 16) { wsLimit.decrementAndGet(); writeHttp(output, 503, "too many tunnels"); return }
        val channel = "ws-${UUID.randomUUID()}"
        val waiter = Waiter(socket)
        val peer = WsPeer(socket)
        waiters[channel] = waiter
        webSockets[channel] = peer
        try {
            if (stopped) return
            sendInner("ws_open", JSONObject().put("path", path).put("headers", forwardedHeaders(headers)), channel)
            val opened = waiter.queue.poll(wsOpenTimeoutMs, TimeUnit.MILLISECONDS)
            waiters.remove(channel)
            if (stopped || opened?.optBoolean("shutdown") == true) return
            if (opened == null || !opened.optBoolean("ok")) {
                warning("host WebSocket open failed")
                writeHttp(output, 502, opened?.optString("reason").orEmpty().ifBlank { "host websocket timeout" })
                return
            }
            synchronized(socket) {
                val accept = MessageDigest.getInstance("SHA-1")
                    .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()).toByteString().base64()
                writeRaw(output, "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n")
                output.flush()
                peer.upgraded = true
                while (peer.pending.isNotEmpty()) socket.getOutputStream().write(peer.pending.removeFirst())
                socket.getOutputStream().flush()
                peer.pendingBytes = 0
            }
            socket.soTimeout = 0
            val messages = DshWebSocketMessages()
            while (!stopped && !socket.isClosed) {
                val frame = DshWebSocketFrames.readFrame(input, requireMasked = true) ?: break
                when {
                    DshWebSocketFrames.isClose(frame.opcode) -> {
                        writeFrame(peer, DshWebSocketFrames.encodeUnmasked(DshWebSocketFrames.OPCODE_CLOSE, frame.payload))
                        break
                    }
                    DshWebSocketFrames.isPing(frame.opcode) -> writeFrame(peer,
                        DshWebSocketFrames.encodeUnmasked(DshWebSocketFrames.OPCODE_PONG, frame.payload))
                    DshWebSocketFrames.isPong(frame.opcode) -> Unit
                    else -> messages.accept(frame)?.let { message ->
                        sendInner("ws_frame", JSONObject().put("dataB64", message.payload.toByteString().base64())
                            .put("opcode", message.opcode), channel)
                    }
                }
            }
        } finally {
            waiters.remove(channel)
            webSockets.remove(channel)
            wsLimit.decrementAndGet()
            if (!stopped) runCatching { sendInner("ws_close", JSONObject().put("code", 1000).put("reason", ""), channel) }
        }
    }

    private fun writeWsFrame(channel: String, payload: JSONObject) {
        val peer = webSockets[channel] ?: return
        try {
            val data = decodeBytes(payload.optString("dataB64"))
            require(data.size <= DshWebSocketFrames.MAX_MESSAGE_BYTES)
            writeFrame(peer, DshWebSocketFrames.encodeUnmasked(DshWebSocketFrames.forwardOpcode(payload.optInt("opcode")), data))
        } catch (_: Exception) { runCatching { peer.socket.close() } }
    }

    private fun writeFrame(peer: WsPeer, frame: ByteArray) = synchronized(peer.socket) {
        if (stopped || peer.socket.isClosed) return@synchronized
        if (!peer.upgraded) {
            require(peer.pending.size < 64 && frame.size <= DshWebSocketFrames.MAX_MESSAGE_BYTES - peer.pendingBytes) { "early WebSocket frames exceeded limit" }
            peer.pending.add(frame)
            peer.pendingBytes += frame.size
        } else {
            peer.socket.getOutputStream().write(frame)
            peer.socket.getOutputStream().flush()
        }
    }

    private fun readHeaders(input: BufferedInputStream): ByteArray? {
        val buffer = ByteArrayOutputStream()
        var tail = 0
        while (buffer.size() < 64 * 1024) {
            val next = input.read()
            if (next < 0) return null
            buffer.write(next)
            tail = (tail shl 8) or next
            if (tail == 0x0d0a0d0a) return buffer.toByteArray()
        }
        throw IllegalArgumentException("HTTP header too large")
    }

    private fun readExact(input: BufferedInputStream, count: Int): ByteArray {
        val bytes = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val n = input.read(bytes, offset, count - offset)
            if (n < 0) throw EOFException("truncated HTTP body")
            offset += n
        }
        return bytes
    }

    private fun forwardedHeaders(headers: Map<String, String>) = JSONObject().apply {
        headers.filterKeys { it !in HOP_HEADERS && it != "authorization" && it != "host" }
            .forEach { (name, value) -> put(name, value) }
    }
    private fun validHeader(name: String, value: String) = name.matches(HEADER_NAME) &&
        value.none { it == '\r' || it == '\n' || it == '\u0000' }
    private fun decodeBytes(text: String): ByteArray = requireNotNull(text.decodeBase64()) { "invalid base64" }.toByteArray()
    private fun writeRaw(output: BufferedOutputStream, text: String) { output.write(text.toByteArray(Charsets.ISO_8859_1)) }
    private fun writeHttp(output: BufferedOutputStream, status: Int, text: String) {
        val body = text.toByteArray()
        writeRaw(output, "HTTP/1.1 $status ERROR\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n")
        output.write(body)
        output.flush()
    }
    companion object {
        private val HEADER_NAME = Regex("^[!#$%&'*+.^_`|~0-9A-Za-z-]+$")
        private val HOP_HEADERS = setOf("content-length", "transfer-encoding", "connection", "keep-alive", "upgrade", "trailer")
    }
}
