package io.github.yukiykchen.dsh.data

import kotlin.test.Test
import kotlin.test.assertEquals

class DshHostClientStreamTest {
    @Test
    fun synchronousCommandCompletionDoesNotLeaveAnActiveStream() {
        val transport = FakeHostTransport()
        val client = createTestHostClient(transport)
        transport.completeHandshake()
        transport.onPost = { if (it.method == "session.prompt") it.reply("""{"command":{"text":"done"}}""") }
        val completed = mutableListOf<String>()
        val handle = client.streamReply("test-page", "s1", "prompt", {}, { completed.add(it) }, {})
        handle.cancel()
        assertEquals(listOf("done"), completed)
        assertEquals(0, transport.requests.count { it.method == "session.cancel" })
        client.stop()
    }

    @Test
    fun explicitUnknownRpcIdCannotFeedOrFinishAnotherLocalPrompt() {
        val transport = FakeHostTransport()
        val client = createTestHostClient(transport)
        transport.completeHandshake()
        val deltas = mutableListOf<String>()
        var completed = 0
        client.streamReply("test-page", "s1", "prompt", { deltas.add(it) }, { completed++ }, {})
        val rpcId = transport.requests.last().body.optString("rpcId")
        transport.sockets.first().frame(chunk(1, "another-rpc", "wrong"))
        transport.sockets.first().frame(end(2, "another-rpc"))
        assertEquals(emptyList(), deltas)
        assertEquals(0, completed)
        transport.sockets.first().frame(chunk(3, rpcId, "correct"))
        transport.sockets.first().frame(end(4, rpcId))
        assertEquals(listOf("correct"), deltas)
        assertEquals(1, completed)
        client.stop()
    }

    @Test
    fun adoptedStreamReceivesHostRpcIdsButAnOlderHandleCannotCancelItsReplacement() {
        val transport = FakeHostTransport()
        val client = createTestHostClient(transport)
        transport.completeHandshake()
        val old = client.adoptLiveStream("s1", { _, _ -> }, {}, {})
        val deltas = mutableListOf<String>()
        val current = client.adoptLiveStream("s1", { text, _ -> deltas.add(text) }, {}, {})
        old.cancel()
        assertEquals(0, transport.requests.count { it.method == "session.cancel" })
        transport.sockets.first().frame(chunk(1, "host-prompt-rpc", "live"))
        assertEquals(listOf("live"), deltas)
        current.cancel()
        assertEquals(1, transport.requests.count { it.method == "session.cancel" })
        client.stop()
    }

    @Test
    fun duplicateMuxSequenceDeliversTheDeltaOnlyOnce() {
        val transport = FakeHostTransport()
        val client = createTestHostClient(transport)
        transport.completeHandshake()
        val deltas = mutableListOf<String>()
        client.streamReply("test-page", "s1", "probe", { deltas.add(it) }, {}, {})
        val rpcId = transport.requests.last().body.optString("rpcId")
        val frame = chunk(20, rpcId, "once")
        transport.sockets.first().frame(frame)
        transport.sockets.first().frame(frame)
        assertEquals(listOf("once"), deltas)
        client.stop()
    }

    private fun chunk(seq: Int, rpcId: String, text: String) =
        """{"payload":{"type":"session/event","sessionId":"s1","event":{"seq":$seq,"type":"assistant/chunk","data":{"source":{"rpcId":"$rpcId"},"chunk":{"type":"text-delta","text":"$text"}}}}}"""

    private fun end(seq: Int, rpcId: String) =
        """{"payload":{"type":"session/event","sessionId":"s1","event":{"seq":$seq,"type":"turn/end","data":{"source":{"rpcId":"$rpcId"}}}}}"""
}
