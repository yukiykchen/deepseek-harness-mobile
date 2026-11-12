package io.github.yukiykchen.dsh.relay

import okhttp3.MediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import org.junit.Assert.*
import org.junit.Test

class DshRelayResponseTest {
    @Test
    fun smallJsonAndExactlyBoundedResponsesAreAccepted() {
        response("{}".toResponseBody()).use { assertEquals(0, readDshRelayJson(it).length()) }
        val json = "{\"x\":\"" + "a".repeat(64 * 1024 - 8) + "\"}"
        assertEquals(64 * 1024, json.length)
        response(json.toResponseBody()).use { assertEquals(64 * 1024 - 8, readDshRelayJson(it).getString("x").length) }
    }

    @Test
    fun declaredAndUnknownLengthOversizedBodiesAreRejectedBeforeJsonParsing() {
        val large = " ".repeat(64 * 1024 + 1)
        response(large.toResponseBody()).use {
            assertThrows(IllegalArgumentException::class.java) { readDshRelayJson(it) }
        }
        val unknown = object : ResponseBody() {
            private val buffer = Buffer().writeUtf8(large)
            override fun contentType(): MediaType? = null
            override fun contentLength() = -1L
            override fun source(): BufferedSource = buffer
        }
        response(unknown).use { assertThrows(IllegalArgumentException::class.java) { readDshRelayJson(it) } }
    }

    @Test
    fun authenticationFailureRetainsItsStatusForRetryPolicy() {
        response("{\"error\":\"revoked\"}".toResponseBody(), 403).use {
            val error = assertThrows(DshRelayHttpError::class.java) { readDshRelayJson(it) }
            assertEquals(403, error.status)
            assertEquals("revoked", error.message)
        }
    }

    private fun response(body: ResponseBody, status: Int = 200) = Response.Builder()
        .request(Request.Builder().url("http://localhost/").build()).protocol(Protocol.HTTP_1_1)
        .code(status).message("test").body(body).build()
}
