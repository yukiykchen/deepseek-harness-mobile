package io.github.yukiykchen.dsh.platform

import kotlin.test.Test
import kotlin.test.assertEquals

class DshHttpClientTest {
    @Test
    fun webSocketUrlRewritesHttpSchemes() {
        assertEquals("ws://127.0.0.1:3080/api/events.mux", dshWebSocketUrl("http://127.0.0.1:3080/api/events.mux"))
        assertEquals("wss://host/events", dshWebSocketUrl("https://host/events"))
        assertEquals("ws://already", dshWebSocketUrl("ws://already"))
    }
}