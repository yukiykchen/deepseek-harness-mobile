package io.github.yukiykchen.dsh.relay

import org.json.JSONObject
import java.util.UUID

internal data class DshRelayCredentials(
    val masterKey: String,
    val clientToken: String,
    val hostId: String,
    val hostName: String,
    val relayOrigin: String,
) {
    override fun toString() = "DshRelayCredentials(hostId=$hostId, masterKey=[redacted], clientToken=[redacted])"
}

internal interface DshRelayCredentialStore {
    fun load(): DshRelayCredentials?
    fun save(credentials: DshRelayCredentials)
    fun clear()
}

internal data class DshRelayNativeState(
    val phase: String,
    val message: String = "",
    val localPort: Int = 0,
    val localToken: String = "",
    val hostId: String = "",
    val hostName: String = "",
    val relayOrigin: String = "",
    val paired: Boolean = false,
    val generation: Long = 0,
) {
    override fun toString() = "DshRelayNativeState(phase=$phase, generation=$generation, hostId=$hostId, localPort=$localPort, localToken=[redacted])"
}

internal interface DshRelayLoopback {
    val port: Int
    fun start(): Int
    fun isListening(): Boolean
    fun stop()
    fun onInner(type: String, payload: JSONObject, channel: String)
}

/** Serializes lifecycle changes. Ticket/socket I/O is asynchronous and every resource belongs to one attempt. */
internal class DshRelayRuntime(
    private val store: DshRelayCredentialStore,
    private val transport: DshRelayTransport,
    private val loopbackFactory: (String, (String, JSONObject, String) -> Unit) -> DshRelayLoopback = { token, send ->
        DshRelayLoopbackServer(token, send)
    },
    private val schedule: (Long, () -> Unit) -> DshRelayCancellation = DshRelayTimers::schedule,
    private val log: (String) -> Unit = {},
) {
    private class Attempt(val generation: Long, val credentials: DshRelayCredentials) {
        val random = SealedTunnelCrypto.encodeBase64Url(SealedTunnelCrypto.randomBytes())
        var ticket: DshRelayTicket? = null
        var ticketCall: DshRelayCancellation? = null
        var socket: DshRelaySocket? = null
        var opened = false
        var cipher: SecureCipher? = null
        var loopback: DshRelayLoopback? = null
        var deadline: DshRelayCancellation? = null
    }

    private val lock = Any()
    private val listeners = mutableListOf<(DshRelayNativeState) -> Unit>()
    private var generation = 0L
    private var intentVersion = 0L
    private var enabled = false
    private var active: Attempt? = null
    private var retry: DshRelayCancellation? = null
    @Volatile private var state = DshRelayNativeState("IDLE")

    fun current(): DshRelayNativeState = state
    fun isEnabled(): Boolean = synchronized(lock) { enabled }
    fun addListener(listener: (DshRelayNativeState) -> Unit) = synchronized(lock) {
        listeners.add(listener)
        runCatching { listener(state) }.onFailure { log("Relay state listener failed") }
        Unit
    }
    fun removeListener(listener: (DshRelayNativeState) -> Unit) = synchronized(lock) { listeners.remove(listener); Unit }

    fun refreshPairing() = synchronized(lock) {
        val credentials = runCatching { store.load() }.getOrNull()
        if (credentials != null && active == null) emit(state.copy(hostId = credentials.hostId, hostName = credentials.hostName,
            relayOrigin = credentials.relayOrigin, paired = true))
    }

    fun beginPairing(): Long = synchronized(lock) {
        stopResources()
        val token = ++generation
        emit(state.copy(phase = "PAIRING", message = "正在配对", localPort = 0, localToken = "", generation = token))
        token
    }

    fun completePairing(token: Long, credentials: DshRelayCredentials): Boolean = synchronized(lock) {
        if (generation != token || state.phase != "PAIRING") return@synchronized false
        store.save(credentials)
        emit(DshRelayNativeState("IDLE", "已配对", hostId = credentials.hostId, hostName = credentials.hostName,
            relayOrigin = credentials.relayOrigin, paired = true, generation = token))
        true
    }
    fun isPairing(token: Long): Boolean = synchronized(lock) { generation == token && state.phase == "PAIRING" }

    fun failPairing(token: Long, message: String) = synchronized(lock) {
        if (generation == token && state.phase == "PAIRING") emit(state.copy(phase = "ERROR", message = message))
    }

    fun beginConnect(): Long = synchronized(lock) {
        enabled = true
        ++intentVersion
    }

    fun connect(intent: Long = beginConnect()) = synchronized(lock) {
        if (intent != intentVersion || !enabled) return@synchronized
        val attempt = active
        if (attempt != null && (state.phase != "READY" || attempt.loopback?.isListening() == true)) return@synchronized
        startAttempt()
    }

    fun networkAvailable() = synchronized(lock) {
        if (enabled && active == null && state.phase in setOf("ERROR", "RECONNECTING")) startAttempt()
    }

    fun disconnect() = synchronized(lock) {
        stopResources()
        emit(state.copy(phase = "STOPPED", message = "扫码连接已断开", localPort = 0, localToken = "", generation = ++generation))
    }

    fun forget() = synchronized(lock) {
        stopResources()
        ++generation
        store.clear()
        emit(DshRelayNativeState("IDLE", "已移除配对", generation = generation))
    }

    private fun startAttempt() {
        retry?.cancel()
        retry = null
        releaseAttempt()
        val credentials = try { store.load() } catch (_: Exception) { null }
        if (credentials == null) {
            enabled = false
            emit(DshRelayNativeState("ERROR", "请重新扫码配对电脑", generation = ++generation))
            return
        }
        val attempt = Attempt(++generation, credentials)
        active = attempt
        emit(DshRelayNativeState("CONNECTING", "正在申请访问票", hostId = credentials.hostId,
            hostName = credentials.hostName, relayOrigin = credentials.relayOrigin, paired = true, generation = generation))
        if (!accepts(attempt)) return
        val deadline = schedule(30_000) { synchronized(lock) {
            if (accepts(attempt) && attempt.cipher == null) fail(attempt, "扫码隧道握手超时")
        } }
        if (accepts(attempt)) attempt.deadline = deadline else { deadline.cancel(); return }
        try {
            val call = transport.ticket(credentials) { result -> synchronized(lock) {
                if (!accepts(attempt)) return@synchronized
                result.fold({ ticket -> openSocket(attempt, ticket) }, { error ->
                    val revoked = error is DshRelayHttpError && error.status in setOf(401, 403)
                    fail(attempt, error.message ?: "访问票申请失败", permanent = revoked)
                })
            } }
            if (accepts(attempt)) attempt.ticketCall = call else call.cancel()
        } catch (error: Exception) { fail(attempt, error.message ?: "访问票申请失败") }
    }

    private fun openSocket(attempt: Attempt, ticket: DshRelayTicket) {
        if (!accepts(attempt) || attempt.ticket != null) return
        attempt.ticket = ticket
        emit(state.copy(phase = "HANDSHAKING", message = "正在建立加密隧道", hostId = ticket.hostId))
        if (!accepts(attempt)) return
        try {
            val socket = transport.open(ticket, object : DshRelayTransport.Listener {
                override fun onOpen(socket: DshRelaySocket) = synchronized(lock) {
                    if (!accepts(attempt)) { socket.cancel(); return@synchronized }
                    if (attempt.opened) return@synchronized
                    attempt.socket = socket
                    attempt.opened = true
                    val hello = envelope("client_hello", JSONObject().put("accessSessionId", ticket.sessionId)
                        .put("clientRandomB64", attempt.random)
                        .put("clientProofB64", SealedTunnelCrypto.clientProof(attempt.credentials.masterKey, ticket.sessionId, attempt.random)))
                    if (!socket.send(hello.toString())) fail(attempt, "扫码隧道发送失败")
                }
                override fun onMessage(socket: DshRelaySocket, text: String) {
                    var delivery: Pair<DshRelayLoopback, JSONObject>? = null
                    synchronized(lock) {
                        if (!accepts(attempt) || attempt.socket !== socket) return
                        try {
                            require(text.length <= 16 * 1024 * 1024) { "Relay frame too large" }
                            val msg = JSONObject(text)
                            val payload = msg.optJSONObject("payload") ?: return
                            when (msg.optString("type")) {
                                "server_hello" -> try { establish(attempt, payload) } catch (_: E2eeException) {
                                    fail(attempt, "加密验证失败，请重新扫码", permanent = true)
                                }
                                "sealed" -> {
                                    val cipher = attempt.cipher ?: return
                                    val opened = cipher.open(SealedPayload(payload.optString("seq"), payload.optString("ciphertextB64")))
                                    attempt.loopback?.let { delivery = it to opened }
                                }
                                "device_close", "close" -> fail(attempt, payload.optString("reason").ifBlank { "电脑连接已断开" })
                            }
                        } catch (error: Exception) { fail(attempt, error.message ?: "扫码隧道失败") }
                    }
                    // Socket writes may block; closing the attempt must remain able to close its loopback peers.
                    delivery?.let { (server, opened) ->
                        runCatching {
                            server.onInner(opened.optString("type"), opened.optJSONObject("payload") ?: JSONObject(), opened.optString("channel"))
                        }.onFailure { log("Loopback peer delivery failed") }
                    }
                }
                override fun onClosed(socket: DshRelaySocket, message: String) = synchronized(lock) {
                    if (accepts(attempt) && (attempt.socket == null || attempt.socket === socket)) {
                        fail(attempt, message.ifBlank { "扫码连接已断开" })
                    }
                }
            })
            if (accepts(attempt)) attempt.socket = socket else socket.cancel()
        } catch (error: Exception) { fail(attempt, error.message ?: "无法打开扫码隧道") }
    }

    private fun establish(attempt: Attempt, payload: JSONObject) {
        if (attempt.cipher != null) return // Never reset AES-GCM sequences for a duplicate hello.
        val ticket = requireNotNull(attempt.ticket)
        val cipher = SealedTunnelCrypto.createClientCipher(attempt.credentials.masterKey, ticket.sessionId, attempt.random,
            payload.optString("serverRandomB64"), payload.optString("serverProofB64"))
        val token = SealedTunnelCrypto.encodeBase64Url(SealedTunnelCrypto.randomBytes(24))
        val server = loopbackFactory(token) { type, data, channel -> sendInner(attempt, type, data, channel) }
        attempt.loopback = server
        attempt.cipher = cipher
        val port = server.start()
        check(port in 1..65535) { "invalid loopback port" }
        attempt.deadline?.cancel()
        attempt.deadline = null
        emit(state.copy(phase = "READY", message = "扫码隧道已连接", localPort = port, localToken = token))
    }

    private fun sendInner(attempt: Attempt, type: String, payload: JSONObject, channel: String) = synchronized(lock) {
        if (!accepts(attempt) || state.phase != "READY") return@synchronized
        val socket = attempt.socket ?: return@synchronized
        val cipher = attempt.cipher ?: return@synchronized
        try {
            val sealed = cipher.seal(envelope(type, payload, channel))
            val message = envelope("sealed", JSONObject().put("accessSessionId", attempt.ticket!!.sessionId)
                .put("seq", sealed.seq).put("ciphertextB64", sealed.ciphertextB64))
            if (!socket.send(message.toString())) fail(attempt, "扫码隧道发送失败")
        } catch (error: Exception) { fail(attempt, error.message ?: "扫码隧道发送失败") }
    }

    private fun accepts(attempt: Attempt): Boolean = enabled && active === attempt && generation == attempt.generation

    private fun fail(attempt: Attempt, message: String, permanent: Boolean = false) {
        if (!accepts(attempt)) return
        val safe = redactRelayMessage(message, attempt.credentials.masterKey, attempt.credentials.clientToken,
            attempt.ticket?.token.orEmpty(), state.localToken)
        releaseAttempt()
        val token = ++generation
        if (permanent) { enabled = false; ++intentVersion }
        emit(state.copy(phase = if (permanent) "ERROR" else "RECONNECTING", message = safe,
            localPort = 0, localToken = "", generation = token))
        if (permanent || !enabled || generation != token) return
        val task = schedule(2_000) { synchronized(lock) {
            if (enabled && generation == token && active == null) { retry = null; startAttempt() }
        } }
        if (enabled && generation == token && active == null) retry = task else task.cancel()
    }

    private fun stopResources() {
        ++intentVersion
        enabled = false
        retry?.cancel()
        retry = null
        releaseAttempt()
    }

    private fun releaseAttempt() {
        val attempt = active ?: return
        active = null
        listOf<() -> Unit>(
            { attempt.deadline?.cancel() }, { attempt.ticketCall?.cancel() },
            { attempt.socket?.cancel() }, { attempt.loopback?.stop() },
        ).forEach { close -> runCatching(close).onFailure { log("Relay resource close failed") } }
        attempt.cipher = null
    }

    private fun emit(next: DshRelayNativeState) {
        state = next
        for (listener in listeners.toList()) {
            if (state !== next) break
            runCatching { listener(next) }.onFailure { log("Relay state listener failed") }
        }
    }

    private fun envelope(type: String, payload: JSONObject, channel: String? = null): JSONObject =
        JSONObject().put("v", 1).put("type", type).put("id", UUID.randomUUID().toString()).put("ts", System.currentTimeMillis())
            .apply { if (channel != null) put("channel", channel) }.put("payload", payload)
}
