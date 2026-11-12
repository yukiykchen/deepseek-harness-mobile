package io.github.yukiykchen.dsh.relay

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class DshRelayIntegrationTest {
    @Test
    fun productionTransportAndGatewayCompleteARealSealedHttpRoundTrip() {
        val relay = MockWebServer()
        val http = OkHttpClient.Builder().callTimeout(3, TimeUnit.SECONDS).build()
        val tunnel = OkHttpClient.Builder().callTimeout(0, TimeUnit.SECONDS).build()
        val ready = CountDownLatch(1)
        val fixture = RelayRuntimeFixture()
        var host: SecureCipher? = null
        val failures = mutableListOf<Throwable>()
        val hostListener = object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val message = JSONObject(text)
                    val payload = message.getJSONObject("payload")
                    when (message.getString("type")) {
                        "client_hello" -> {
                            val random = payload.getString("clientRandomB64")
                            assertEquals(SealedTunnelCrypto.clientProof(fixture.master, "live-session", random), payload.getString("clientProofB64"))
                            host = SealedTunnelCrypto.createHostCipher(fixture.master, "live-session", random, fixture.serverRandom)
                            webSocket.send(JSONObject().put("type", "server_hello").put("payload", JSONObject()
                                .put("serverRandomB64", fixture.serverRandom)
                                .put("serverProofB64", SealedTunnelCrypto.serverProof(fixture.master, "live-session", random, fixture.serverRandom))).toString())
                        }
                        "sealed" -> {
                            val opened = host!!.open(SealedPayload(payload.getString("seq"), payload.getString("ciphertextB64")))
                            assertEquals("http_req", opened.getString("type"))
                            assertEquals("/canary", opened.getJSONObject("payload").getString("path"))
                            assertFalse(opened.getJSONObject("payload").getJSONObject("headers").has("authorization"))
                            val answer = JSONObject().put("type", "http_res").put("channel", opened.getString("channel"))
                                .put("payload", JSONObject().put("status", 200).put("final", true)
                                    .put("bodyB64", Base64.getEncoder().encodeToString("sealed-ok".toByteArray())))
                            val sealed = host!!.seal(answer)
                            webSocket.send(JSONObject().put("type", "sealed").put("payload", JSONObject()
                                .put("seq", sealed.seq).put("ciphertextB64", sealed.ciphertextB64)).toString())
                        }
                    }
                } catch (error: Throwable) { synchronized(failures) { failures.add(error) }; webSocket.cancel() }
            }
        }
        relay.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/access-ticket" -> {
                    assertEquals("Bearer test-client-token", request.getHeader("Authorization"))
                    MockResponse().setBody("""{"accessSessionId":"live-session","ticket":"test-ticket","hostId":"host-1"}""")
                }
                "/client-tunnel" -> MockResponse().withWebSocketUpgrade(hostListener).addHeader("Sec-WebSocket-Protocol", "dsh-e2ee-v1")
                else -> MockResponse().setResponseCode(404)
            }
        }
        relay.start()
        val store = RelayRuntimeFixture.Store(fixture.store.credentials!!.copy(relayOrigin = relay.url("/").toString().trimEnd('/')))
        val runtime = DshRelayRuntime(store, DshOkHttpRelayTransport("test", http, tunnel),
            loopbackFactory = { token, send -> DshRelayLoopbackServer(token, send, warning = {}) })
        runtime.addListener { if (it.phase == "READY") ready.countDown() }
        try {
            runtime.connect()
            assertTrue(ready.await(3, TimeUnit.SECONDS))
            val state = runtime.current()
            http.newCall(Request.Builder().url("http://127.0.0.1:${state.localPort}/canary")
                .header("Authorization", "Bearer ${state.localToken}").build()).execute().use {
                assertEquals(200, it.code)
                assertEquals("sealed-ok", it.body!!.string())
            }
            synchronized(failures) { assertTrue(failures.toString(), failures.isEmpty()) }
        } finally {
            runtime.disconnect()
            closeClient(http); closeClient(tunnel)
            relay.shutdown()
        }
    }

    @Test
    fun cancellingARealPendingTicketCannotResurrectTheStoppedRuntime() {
        val relay = MockWebServer()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val completed = CountDownLatch(1)
        relay.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                entered.countDown()
                release.await(3, TimeUnit.SECONDS)
                return MockResponse().setBody("""{"accessSessionId":"old-session","ticket":"test-ticket"}""")
            }
        }
        relay.start()
        val http = OkHttpClient.Builder().callTimeout(3, TimeUnit.SECONDS).build()
        val tunnel = OkHttpClient()
        val native = DshOkHttpRelayTransport("test", http, tunnel)
        val observed = object : DshRelayTransport by native {
            override fun ticket(credentials: DshRelayCredentials, callback: (Result<DshRelayTicket>) -> Unit): DshRelayCancellation =
                native.ticket(credentials) { result -> try { callback(result) } finally { completed.countDown() } }
        }
        val f = RelayRuntimeFixture()
        val store = RelayRuntimeFixture.Store(f.store.credentials!!.copy(relayOrigin = relay.url("/").toString().trimEnd('/')))
        val runtime = DshRelayRuntime(store, observed)
        try {
            runtime.connect()
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            runtime.disconnect()
            val stopped = runtime.current()
            release.countDown()
            assertTrue(completed.await(2, TimeUnit.SECONDS))
            assertSame(stopped, runtime.current())
            assertEquals("STOPPED", stopped.phase)
            assertEquals(1, relay.requestCount)
        } finally {
            release.countDown()
            runtime.disconnect()
            closeClient(http); closeClient(tunnel)
            relay.shutdown()
        }
    }

    private fun closeClient(client: OkHttpClient) { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow() }
}
