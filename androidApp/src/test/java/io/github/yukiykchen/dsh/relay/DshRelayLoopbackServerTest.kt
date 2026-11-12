package io.github.yukiykchen.dsh.relay

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class DshRelayLoopbackServerTest {
    @Test
    fun realHttpRoundTripIsChunkedAndDoesNotForwardTheLocalBearerToken() {
        val requests = LinkedBlockingQueue<JSONObject>()
        lateinit var gateway: DshRelayLoopbackServer
        gateway = DshRelayLoopbackServer("local-token", { type, payload, channel ->
            if (type == "http_req") {
                requests.add(payload)
                gateway.onInner("http_res", JSONObject().put("status", 200).put("headers", JSONObject().put("Content-Type", "text/plain"))
                    .put("bodyB64", Base64.getEncoder().encodeToString("ok".toByteArray())).put("final", true), channel)
            }
        }, warning = {})
        gateway.start()
        val http = OkHttpClient.Builder().callTimeout(3, TimeUnit.SECONDS).build()
        try {
            http.newCall(Request.Builder().url("http://127.0.0.1:${gateway.port}/canary?x=1")
                .header("Authorization", "Bearer local-token").build()).execute().use {
                assertEquals(200, it.code)
                assertEquals("ok", it.body!!.string())
            }
            val forwarded = requireNotNull(requests.poll(1, TimeUnit.SECONDS))
            assertEquals("/canary?x=1", forwarded.getString("path"))
            assertFalse(forwarded.getJSONObject("headers").has("authorization"))
            assertFalse(forwarded.getJSONObject("headers").has("host"))
        } finally { gateway.stop(); closeClient(http) }
    }

    @Test
    fun stopClosesHeaderReadersAndHttpResponseWaitersWithoutWaitingForTheirTimeout() {
        val requestEntered = CountDownLatch(1)
        val gateway = DshRelayLoopbackServer("local-token", { _, _, _ -> requestEntered.countDown() }, warning = {})
        gateway.start()
        val slowHeader = socket(gateway.port)
        val waitingHttp = socket(gateway.port)
        try {
            waitingHttp.getOutputStream().write("GET /wait HTTP/1.1\r\nAuthorization: Bearer local-token\r\n\r\n".toByteArray())
            assertTrue(requestEntered.await(2, TimeUnit.SECONDS))
            awaitCondition { gateway.activePeerCount == 2 }
            gateway.stop()
            assertDisconnected(slowHeader)
            assertDisconnected(waitingHttp)
            awaitCondition { gateway.activePeerCount == 0 }
            assertFalse(gateway.isListening())
        } finally { gateway.stop(); slowHeader.close(); waitingHttp.close() }
    }

    @Test
    fun earlyWebSocketFramesStayInOrderAndFollowTheUpgradeResponse() {
        lateinit var gateway: DshRelayLoopbackServer
        gateway = DshRelayLoopbackServer("local-token", { type, _, channel ->
            if (type == "ws_open") {
                gateway.onInner("ws_frame", frame("first"), channel)
                gateway.onInner("ws_open_ok", JSONObject(), channel)
                gateway.onInner("ws_frame", frame("second"), channel)
            }
        }, warning = {})
        gateway.start()
        socket(gateway.port).use { peer ->
            try {
                peer.getOutputStream().write(upgrade().toByteArray())
                val input = BufferedInputStream(peer.getInputStream())
                assertTrue(headers(input).startsWith("HTTP/1.1 101"))
                assertEquals("first", DshWebSocketFrames.readFrame(input)!!.payload.toString(Charsets.UTF_8))
                assertEquals("second", DshWebSocketFrames.readFrame(input)!!.payload.toString(Charsets.UTF_8))
                gateway.stop()
                assertDisconnected(peer)
                awaitCondition { gateway.activePeerCount == 0 }
            } finally { gateway.stop() }
        }
    }

    @Test
    fun fragmentedBinaryMessagesReachTheHostOnceAndPingsAreAnsweredLocally() {
        val forwarded = mutableListOf<JSONObject>()
        val frameSent = CountDownLatch(1)
        lateinit var gateway: DshRelayLoopbackServer
        gateway = DshRelayLoopbackServer("local-token", { type, payload, channel ->
            when (type) {
                "ws_open" -> gateway.onInner("ws_open_ok", JSONObject(), channel)
                "ws_frame" -> { forwarded.add(payload); frameSent.countDown() }
            }
        }, warning = {})
        gateway.start()
        socket(gateway.port).use { peer ->
            try {
                val output = peer.getOutputStream()
                output.write(upgrade().toByteArray())
                val input = BufferedInputStream(peer.getInputStream())
                headers(input)
                val first = DshWebSocketFrames.encodeMasked(2, byteArrayOf(1, 2), byteArrayOf(1, 2, 3, 4))
                first[0] = 2
                output.write(first)
                output.write(DshWebSocketFrames.encodeMasked(9, byteArrayOf(7), byteArrayOf(1, 2, 3, 4)))
                output.write(DshWebSocketFrames.encodeMasked(0, byteArrayOf(3), byteArrayOf(1, 2, 3, 4)))
                output.flush()
                val pong = DshWebSocketFrames.readFrame(input)!!
                assertEquals(10, pong.opcode)
                assertArrayEquals(byteArrayOf(7), pong.payload)
                assertTrue(frameSent.await(2, TimeUnit.SECONDS))
                assertEquals(2, forwarded.single().getInt("opcode"))
                assertArrayEquals(byteArrayOf(1, 2, 3), Base64.getDecoder().decode(forwarded.single().getString("dataB64")))
            } finally { gateway.stop() }
        }
    }

    @Test
    fun oversizedNegativeAndChunkedBodiesNeverReachTheHost() {
        val forwarded = AtomicInteger(0)
        val gateway = DshRelayLoopbackServer("local-token", { _, _, _ -> forwarded.incrementAndGet() }, warning = {})
        gateway.start()
        try {
            for ((header, expected) in listOf("Content-Length: 2097153" to 413, "Content-Length: -1" to 400,
                "Transfer-Encoding: chunked" to 400, "Content-Length: abc" to 400)) {
                socket(gateway.port).use { peer ->
                    peer.getOutputStream().write("POST /bad HTTP/1.1\r\nAuthorization: Bearer local-token\r\n$header\r\n\r\n".toByteArray())
                    assertTrue(headers(BufferedInputStream(peer.getInputStream())).startsWith("HTTP/1.1 $expected"))
                }
            }
            assertEquals(0, forwarded.get())
        } finally { gateway.stop() }
    }

    @Test
    fun truncatedBodiesAreNotForwardedAsPartialRequests() {
        val forwarded = AtomicInteger(0)
        val gateway = DshRelayLoopbackServer("local-token", { _, _, _ -> forwarded.incrementAndGet() }, warning = {})
        gateway.start()
        socket(gateway.port).use { peer ->
            try {
                peer.getOutputStream().write("POST /bad HTTP/1.1\r\nAuthorization: Bearer local-token\r\nContent-Length: 100\r\n\r\nabc".toByteArray())
                peer.shutdownOutput()
                assertEquals(-1, peer.getInputStream().read())
                assertEquals(0, forwarded.get())
            } finally { gateway.stop() }
        }
    }

    @Test
    fun missingHostResponseProducesAGatewayTimeoutAndReleasesThePeer() {
        val gateway = DshRelayLoopbackServer("local-token", { _, _, _ -> }, responseTimeoutMs = 50, warning = {})
        gateway.start()
        val http = OkHttpClient.Builder().callTimeout(3, TimeUnit.SECONDS).build()
        try {
            http.newCall(Request.Builder().url("http://127.0.0.1:${gateway.port}/wait")
                .header("Authorization", "Bearer local-token").build()).execute().use { assertEquals(504, it.code) }
            awaitCondition { gateway.activePeerCount == 0 }
        } finally { gateway.stop(); closeClient(http) }
    }

    @Test
    fun unauthenticatedSlowPeersCannotCreateAnUnboundedNumberOfWorkers() {
        val gateway = DshRelayLoopbackServer("local-token", { _, _, _ -> }, warning = {})
        gateway.start()
        val peers = mutableListOf<Socket>()
        try {
            repeat(64) { peers.add(socket(gateway.port)) }
            awaitCondition { gateway.activePeerCount == 64 }
            socket(gateway.port).use { assertDisconnected(it) }
            assertEquals(64, gateway.activePeerCount)
            gateway.stop()
            awaitCondition { gateway.activePeerCount == 0 }
        } finally { gateway.stop(); peers.forEach { it.close() } }
    }

    private fun socket(port: Int) = Socket().apply { connect(InetSocketAddress("127.0.0.1", port), 2_000); soTimeout = 3_000 }
    private fun assertDisconnected(socket: Socket) {
        try { assertEquals(-1, socket.getInputStream().read()) } catch (_: SocketException) {
            // EOF and reset are both terminal; SocketTimeoutException must fail the test.
        }
    }
    private fun frame(text: String) = JSONObject().put("opcode", 1).put("dataB64", Base64.getEncoder().encodeToString(text.toByteArray()))
    private fun upgrade() = "GET /events HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer local-token\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: MDEyMzQ1Njc4OWFiY2RlZg==\r\nSec-WebSocket-Version: 13\r\n\r\n"
    private fun headers(input: BufferedInputStream): String {
        val bytes = ByteArrayOutputStream()
        var tail = 0
        while (true) {
            val next = input.read()
            check(next >= 0) { "missing HTTP header" }
            bytes.write(next)
            tail = (tail shl 8) or next
            if (tail == 0x0d0a0d0a) return bytes.toString("ISO-8859-1")
        }
    }
    private fun closeClient(client: OkHttpClient) { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow() }
    private fun awaitCondition(condition: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!condition() && System.nanoTime() < end) Thread.sleep(5)
        assertTrue(condition())
    }
}
