package io.github.yukiykchen.dsh.data

import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import io.github.yukiykchen.dsh.model.DshHostConnection
import io.github.yukiykchen.dsh.model.DshHostRuntimePhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DshHostConnectionRuntimeTest {
    @Test
    fun queuedCallsWaitForBothBaselinesAndUseSubmissionSnapshot() {
        val f = Fixture()
        val payload = JSONObject().apply { put("text", "original") }
        var completed = 0
        f.runtime.call("test", payload) { _, _, _ -> completed++ }
        payload.put("text", "mutated")
        f.transport.sockets.forEach { it.open() }
        f.transport.requests.single().reply()
        f.transport.requests.last { it.method == "workspace.list" }.reply()
        assertFalse(f.runtime.productReady)
        assertFalse(f.transport.requests.any { it.method == "test" })
        f.transport.requests.last { it.method == "session.list" }.reply()
        val request = f.transport.requests.last()
        assertEquals("original", request.body.optJSONObject("payload")?.optString("text"))
        request.reply()
        assertEquals(1, completed)
    }

    @Test
    fun callsDuringReconnectDelayAreDispatchedOnTheNextConnection() {
        val f = Fixture()
        f.transport.completeHandshake()
        f.transport.sockets.first().disconnect()
        assertEquals(DshHostRuntimePhase.RECONNECTING, f.runtime.currentState().phase)
        val waitingGeneration = f.runtime.currentState().generation
        var completed = 0
        f.runtime.call("during-delay", JSONObject()) { _, error, _ ->
            assertEquals(null, error)
            completed++
        }
        f.runTimer(1_000)
        f.transport.completeHandshake()
        assertEquals(waitingGeneration, f.runtime.currentState().generation)
        val request = f.transport.requests.last { it.method == "during-delay" }
        request.reply()
        assertEquals(1, completed)
    }

    @Test
    fun invalidationSettlesInflightRequestsOnceAndIgnoresLateResponses() {
        val f = Fixture()
        f.transport.completeHandshake()
        val errors = mutableListOf<String>()
        f.runtime.call("test", JSONObject()) { _, error, _ -> errors.add(error?.code ?: "success") }
        val request = f.transport.requests.last()
        f.transport.sockets.first().disconnect()
        assertEquals(listOf("generation-cancelled"), errors)
        request.reply()
        assertEquals(listOf("generation-cancelled"), errors)
    }

    @Test
    fun cancellingAnInflightRpcSuppressesItsCallback() {
        val f = Fixture()
        f.transport.completeHandshake()
        var completed = 0
        val call = f.runtime.call("test", JSONObject()) { _, _, _ -> completed++ }
        val request = f.transport.requests.last()
        call.cancel()
        request.reply()
        f.runtime.stop()
        assertEquals(0, completed)
    }

    @Test
    fun synchronousPostFailureSettlesTheRequestAndDoesNotPoisonLaterCalls() {
        val f = Fixture()
        f.transport.completeHandshake()
        f.transport.onPost = { error("bridge failure") }
        val errors = mutableListOf<String>()
        f.runtime.call("test", JSONObject()) { _, error, _ -> errors.add(error?.code ?: "success") }
        f.transport.onPost = {}
        f.runtime.call("next", JSONObject()) { _, error, _ -> errors.add(error?.code ?: "success") }
        f.transport.requests.last().reply()
        f.runtime.stop()
        assertEquals(listOf("transport-0", "success"), errors)
    }

    @Test
    fun stopSettlesQueuedRequestsAndReportsClosedSockets() {
        val f = Fixture()
        val errors = mutableListOf<String>()
        f.runtime.call("test", JSONObject()) { _, error, _ -> errors.add(error?.code.orEmpty()) }
        f.runtime.stop()
        f.runtime.stop()
        assertEquals(listOf("cancelled"), errors)
        assertTrue(f.transport.sockets.all { it.closed })
        assertFalse(f.runtime.currentState().muxOpen)
        assertFalse(f.runtime.currentState().hostOpen)
    }

    @Test
    fun repeatedOpenEventsDoNotDuplicateHandshakeRequests() {
        val f = Fixture()
        f.transport.sockets.forEach { it.open() }
        f.transport.sockets.forEach { it.open() }
        assertEquals(1, f.transport.requests.count { it.method == "host.describe" })
    }

    @Test
    fun emptyBaselineCannotMarkTheProductReady() {
        val f = Fixture()
        f.transport.sockets.forEach { it.open() }
        f.transport.requests.single().reply()
        f.transport.requests.last { it.method == "workspace.list" }.reply("null")
        f.transport.requests.last { it.method == "session.list" }.reply()
        assertFalse(f.runtime.productReady)
        assertEquals(DshHostRuntimePhase.RECONNECTING, f.phases.last())
    }

    @Test
    fun synchronousConnectFailureClosesTheReturnedHandleAndDoesNotOpenAnotherSocket() {
        val transport = FakeHostTransport().apply { onConnect = { it.disconnect() } }
        val f = Fixture(transport)
        assertEquals(1, transport.sockets.size)
        assertTrue(transport.sockets.single().closed)
        assertEquals(DshHostRuntimePhase.RECONNECTING, f.phases.last())
    }

    @Test
    fun handshakeTimeoutRetriesButCannotRestartAStoppedRuntime() {
        val f = Fixture()
        f.runTimer(30_000)
        assertEquals(DshHostRuntimePhase.RECONNECTING, f.phases.last())
        f.runtime.stop()
        f.runTimer(1_000)
        assertEquals(2, f.transport.sockets.size)
    }

    private class Fixture(val transport: FakeHostTransport = FakeHostTransport()) {
        val phases = mutableListOf<DshHostRuntimePhase>()
        val timers = mutableListOf<Pair<Int, () -> Unit>>()
        val runtime = DshHostConnectionRuntime(
            transport = transport,
            connection = DshHostConnection("http://localhost"),
            pagerId = "test-page",
            onFrame = {},
            onState = { phases.add(it.phase) },
            schedule = { delay, callback -> timers.add(delay to callback) },
        )
        init { runtime.start() }
        fun runTimer(delay: Int) {
            val index = timers.indexOfFirst { it.first == delay }
            timers.removeAt(index).second()
        }
    }
}
