package io.github.yukiykchen.dsh.relay

import org.json.JSONObject

internal class RelayRuntimeFixture {
    val master = SealedTunnelCrypto.encodeBase64Url(ByteArray(32) { it.toByte() })
    val serverRandom = SealedTunnelCrypto.encodeBase64Url(ByteArray(32) { (it + 1).toByte() })
    val store = Store(DshRelayCredentials(master, "test-client-token", "host-1", "Host", "http://localhost"))
    val transport = Transport()
    val tasks = mutableListOf<Task>()
    val servers = mutableListOf<Loopback>()
    val runtime = DshRelayRuntime(store, transport, { _, send -> Loopback(send, 9000 + servers.size).also { servers.add(it) } },
        { delay, action -> Task(delay, action).also { tasks.add(it) } })

    fun ready(session: String = "session-1"): Socket {
        runtime.connect()
        transport.tickets.last().succeed(session)
        val socket = transport.sockets.last()
        socket.open()
        socket.message(hello(socket, session).toString())
        return socket
    }

    fun hello(socket: Socket, session: String): JSONObject {
        val random = JSONObject(socket.sent.first()).getJSONObject("payload").getString("clientRandomB64")
        return JSONObject().put("type", "server_hello").put("payload", JSONObject().put("serverRandomB64", serverRandom)
            .put("serverProofB64", SealedTunnelCrypto.serverProof(master, session, random, serverRandom)))
    }

    fun hostCipher(socket: Socket, session: String): SecureCipher {
        val random = JSONObject(socket.sent.first()).getJSONObject("payload").getString("clientRandomB64")
        return SealedTunnelCrypto.createHostCipher(master, session, random, serverRandom)
    }

    class Store(var credentials: DshRelayCredentials?) : DshRelayCredentialStore {
        var saves = 0
        override fun load() = credentials
        override fun save(credentials: DshRelayCredentials) { this.credentials = credentials; saves++ }
        override fun clear() { credentials = null }
    }
    class TicketCall(val callback: (Result<DshRelayTicket>) -> Unit) : DshRelayCancellation {
        var cancelled = false
        override fun cancel() { cancelled = true }
        fun succeed(session: String) { callback(Result.success(DshRelayTicket(session, "test-ticket", "ws://localhost", "host-1"))) }
        fun fail() { callback(Result.failure(IllegalStateException("ticket failed"))) }
    }
    class Transport : DshRelayTransport {
        val tickets = mutableListOf<TicketCall>()
        val sockets = mutableListOf<Socket>()
        var onTicket: (TicketCall) -> Unit = {}
        var onSocket: (Socket) -> Unit = {}
        override fun ticket(credentials: DshRelayCredentials, callback: (Result<DshRelayTicket>) -> Unit): DshRelayCancellation =
            TicketCall(callback).also { tickets.add(it); onTicket(it) }
        override fun open(ticket: DshRelayTicket, listener: DshRelayTransport.Listener): DshRelaySocket =
            Socket(listener).also { sockets.add(it); onSocket(it) }
    }
    class Socket(private val listener: DshRelayTransport.Listener) : DshRelaySocket {
        val sent = mutableListOf<String>()
        var cancelled = false
        var acceptsSend = true
        fun open() = listener.onOpen(this)
        fun message(text: String) = listener.onMessage(this, text)
        fun closed(message: String = "closed") = listener.onClosed(this, message)
        override fun send(text: String): Boolean { if (!acceptsSend || cancelled) return false; sent.add(text); return true }
        override fun cancel() { cancelled = true }
    }
    class Loopback(val send: (String, JSONObject, String) -> Unit, override var port: Int) : DshRelayLoopback {
        var stopped = false
        var onDelivery: () -> Unit = {}
        val received = mutableListOf<String>()
        override fun start() = port
        override fun isListening() = !stopped
        override fun stop() { stopped = true; port = 0 }
        override fun onInner(type: String, payload: JSONObject, channel: String) {
            onDelivery()
            if (!stopped) received.add(type)
        }
    }
    class Task(val delay: Long, val action: () -> Unit) : DshRelayCancellation {
        var cancelled = false
        override fun cancel() { cancelled = true }
        fun forceRun() = action() // Simulate a callback already dequeued when cancellation happens.
    }
}
