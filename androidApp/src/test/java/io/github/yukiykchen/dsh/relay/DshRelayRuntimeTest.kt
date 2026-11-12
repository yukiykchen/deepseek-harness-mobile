package io.github.yukiykchen.dsh.relay

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class DshRelayRuntimeTest {
    @Test
    fun stoppedTicketCannotOpenASocketOrPublishANewState() {
        val f = RelayRuntimeFixture()
        f.runtime.connect()
        val ticket = f.transport.tickets.single()
        f.runtime.disconnect()
        val stopped = f.runtime.current()
        ticket.succeed("old")
        ticket.fail()
        assertTrue(ticket.cancelled)
        assertTrue(f.transport.sockets.isEmpty())
        assertSame(stopped, f.runtime.current())
    }

    @Test
    fun stoppingBeforeConnectSetupFinishesInvalidatesItsIntent() {
        val f = RelayRuntimeFixture()
        val intent = f.runtime.beginConnect()
        f.runtime.disconnect()
        f.runtime.connect(intent)
        assertTrue(f.transport.tickets.isEmpty())
        assertFalse(f.runtime.isEnabled())
    }

    @Test
    fun oldLoopbackCannotEncryptOrSendOnTheNextSocket() {
        val f = RelayRuntimeFixture()
        val oldSocket = f.ready("old")
        val oldServer = f.servers.single()
        oldSocket.closed()
        val next = f.ready("next")
        val before = next.sent.size
        oldServer.send("http_req", JSONObject(), "old-channel")
        assertTrue(oldServer.stopped)
        assertEquals(before, next.sent.size)
        f.servers.last().send("http_req", JSONObject().put("path", "/new"), "new-channel")
        val payload = JSONObject(next.sent.last()).getJSONObject("payload")
        assertEquals("next", payload.getString("accessSessionId"))
        assertEquals("0", payload.getString("seq"))
        f.runtime.disconnect()
    }

    @Test
    fun oldSocketAndAlreadyDequeuedRetryCannotReplaceTheNewConnection() {
        val f = RelayRuntimeFixture()
        val old = f.ready("old")
        old.closed()
        val retry = f.tasks.last { it.delay == 2_000L }
        f.runtime.networkAvailable()
        val next = f.ready("next")
        val state = f.runtime.current()
        val count = f.transport.tickets.size
        old.open()
        old.message(f.hello(old, "old").toString())
        old.closed()
        retry.forceRun()
        assertTrue(retry.cancelled)
        assertSame(state, f.runtime.current())
        assertEquals(count, f.transport.tickets.size)
        assertFalse(next.cancelled)
        f.runtime.disconnect()
    }

    @Test
    fun repeatedServerHelloNeverResetsCipherSequencesOrReplacesTheLoopback() {
        val f = RelayRuntimeFixture()
        val socket = f.ready()
        val server = f.servers.single()
        val host = f.hostCipher(socket, "session-1")
        server.send("http_req", JSONObject(), "first")
        var payload = JSONObject(socket.sent.last()).getJSONObject("payload")
        assertEquals("0", payload.getString("seq"))
        host.open(SealedPayload(payload.getString("seq"), payload.getString("ciphertextB64")))
        socket.message(f.hello(socket, "session-1").toString())
        server.send("http_req", JSONObject(), "second")
        payload = JSONObject(socket.sent.last()).getJSONObject("payload")
        assertEquals("1", payload.getString("seq"))
        host.open(SealedPayload(payload.getString("seq"), payload.getString("ciphertextB64")))
        assertEquals(1, f.servers.size)
        f.runtime.disconnect()
    }

    @Test
    fun deadlineCancelsUnfinishedHandshakeButCannotKillAReadyConnection() {
        val f = RelayRuntimeFixture()
        f.runtime.connect()
        val oldDeadline = f.tasks.last()
        oldDeadline.forceRun()
        assertEquals("RECONNECTING", f.runtime.current().phase)
        assertTrue(f.transport.tickets.first().cancelled)
        f.ready("next")
        val deadline = f.tasks.last { it.delay == 30_000L }
        assertTrue(deadline.cancelled)
        deadline.forceRun()
        assertEquals("READY", f.runtime.current().phase)
        f.runtime.disconnect()
    }

    @Test
    fun synchronousCallbacksBeforeHandlesReturnStillReleaseAllResources() {
        val f = RelayRuntimeFixture()
        f.transport.onTicket = { it.succeed("sync") }
        f.transport.onSocket = { it.open(); it.closed() }
        f.runtime.connect()
        assertEquals("RECONNECTING", f.runtime.current().phase)
        assertTrue(f.transport.tickets.single().cancelled)
        assertTrue(f.transport.sockets.single().cancelled)
        f.runtime.disconnect()
    }

    @Test
    fun failedOutboundSendDisposesTheCurrentAttemptAndSchedulesOneRetry() {
        val f = RelayRuntimeFixture()
        val socket = f.ready()
        socket.acceptsSend = false
        f.servers.single().send("http_req", JSONObject(), "channel")
        socket.closed()
        assertEquals("RECONNECTING", f.runtime.current().phase)
        assertEquals(1, f.tasks.count { it.delay == 2_000L })
        assertTrue(f.servers.single().stopped)
        f.runtime.disconnect()
    }

    @Test
    fun pendingPairingCannotRestoreCredentialsAfterForgetOrANewerPairing() {
        val f = RelayRuntimeFixture()
        val credentials = f.store.credentials!!
        val old = f.runtime.beginPairing()
        f.runtime.forget()
        assertFalse(f.runtime.completePairing(old, credentials))
        assertNull(f.store.credentials)
        val first = f.runtime.beginPairing()
        val second = f.runtime.beginPairing()
        assertFalse(f.runtime.completePairing(first, credentials))
        assertTrue(f.runtime.completePairing(second, credentials.copy(hostId = "new-host")))
        assertEquals(1, f.store.saves)
        assertEquals("new-host", f.runtime.current().hostId)
    }

    @Test
    fun reentrantStopDoesNotDeliverReadyAfterStoppedToOtherListeners() {
        val f = RelayRuntimeFixture()
        val phases = mutableListOf<String>()
        f.runtime.addListener { if (it.phase == "READY") f.runtime.disconnect() }
        f.runtime.addListener { phases.add(it.phase) }
        f.ready()
        assertEquals("STOPPED", phases.last())
        assertFalse(phases.contains("READY"))
    }

    @Test
    fun concurrentConnectsOnlyCreateOnePendingTicket() {
        val f = RelayRuntimeFixture()
        val threads = (0 until 12).map { Thread { f.runtime.connect() }.also { it.start() } }
        threads.forEach { it.join(2_000); assertFalse(it.isAlive) }
        assertEquals(1, f.transport.tickets.size)
        f.runtime.disconnect()
    }

    @Test
    fun blockingLoopbackDeliveryCannotPreventDisconnectFromClosingThePeers() {
        val f = RelayRuntimeFixture()
        val socket = f.ready()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        f.servers.single().onDelivery = { entered.countDown(); release.await(3, TimeUnit.SECONDS) }
        val sealed = f.hostCipher(socket, "session-1").seal(JSONObject().put("type", "http_res").put("channel", "ch")
            .put("payload", JSONObject()))
        val message = JSONObject().put("type", "sealed").put("payload", JSONObject().put("seq", sealed.seq).put("ciphertextB64", sealed.ciphertextB64))
        val reader = Thread { socket.message(message.toString()) }.also { it.start() }
        val stopped = CountDownLatch(1)
        var stopper: Thread? = null
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            stopper = Thread { f.runtime.disconnect(); stopped.countDown() }.also { it.start() }
            assertTrue(stopped.await(1, TimeUnit.SECONDS))
            assertTrue(f.servers.single().stopped)
        } finally {
            release.countDown()
            reader.join(2_000)
            stopper?.join(2_000)
            f.runtime.disconnect()
        }
    }

    @Test
    fun malformedServerProofCannotStartALoopback() {
        val f = RelayRuntimeFixture()
        f.runtime.connect()
        f.transport.tickets.last().succeed("bad-proof")
        val socket = f.transport.sockets.last()
        socket.open()
        socket.message(JSONObject().put("type", "server_hello").put("payload", JSONObject().put("serverRandomB64", f.serverRandom)
            .put("serverProofB64", "invalid")).toString())
        assertEquals("ERROR", f.runtime.current().phase)
        assertFalse(f.runtime.isEnabled())
        assertEquals(0, f.tasks.count { it.delay == 2_000L })
        assertTrue(f.servers.isEmpty())
        f.runtime.disconnect()
    }

    @Test
    fun revokedTicketDoesNotKeepRetryingOrRetainAnEnabledRuntime() {
        val f = RelayRuntimeFixture()
        f.runtime.connect()
        f.transport.tickets.single().callback(Result.failure(DshRelayHttpError(403, "pairing revoked")))
        assertEquals("ERROR", f.runtime.current().phase)
        assertFalse(f.runtime.isEnabled())
        f.runtime.networkAvailable()
        assertEquals(1, f.transport.tickets.size)
        assertEquals(0, f.tasks.count { it.delay == 2_000L })
        f.runtime.disconnect()
    }

    @Test
    fun failuresRedactCredentialsAndBoundThePublicMessage() {
        val f = RelayRuntimeFixture()
        val socket = f.ready()
        val credentials = f.store.credentials!!
        val local = f.runtime.current().localToken
        socket.closed("${credentials.masterKey} ${credentials.clientToken} test-ticket $local " + "x".repeat(2_000))
        val message = f.runtime.current().message
        assertTrue(message.length <= 512)
        assertFalse(message.contains(credentials.masterKey))
        assertFalse(message.contains(credentials.clientToken))
        assertFalse(message.contains("test-ticket"))
        assertFalse(message.contains(local))
        assertFalse(credentials.toString().contains(credentials.masterKey))
        assertFalse(credentials.toString().contains(credentials.clientToken))
        assertFalse(DshRelayTicket("session", "test-ticket", "ws://localhost", "host").toString().contains("test-ticket"))
        f.runtime.disconnect()
    }

    @Test
    fun removedListenerNoLongerReceivesStateChanges() {
        val f = RelayRuntimeFixture()
        var notifications = 0
        val listener: (DshRelayNativeState) -> Unit = { notifications++ }
        f.runtime.addListener(listener)
        f.runtime.removeListener(listener)
        f.runtime.connect()
        f.runtime.disconnect()
        assertEquals(1, notifications)
    }
}
