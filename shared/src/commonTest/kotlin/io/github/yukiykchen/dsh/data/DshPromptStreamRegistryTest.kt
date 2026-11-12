package io.github.yukiykchen.dsh.data

import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DshPromptStreamRegistryTest {
    @Test
    fun chunksAndTurnEndAccumulateBeforeCompletingTheLocalPrompt() {
        val registry = DshPromptStreamRegistry()
        val deltas = mutableListOf<String>()
        val completions = mutableListOf<String>()
        registry.put(DshPromptStreamRegistry.ActiveStream("s1", "rpc-1", { text, _ -> deltas.add(text) }, { completions.add(it) }, {}))
        assertTrue(registry.applyEvent(chunk("s1", "rpc-1", "hello "), "s1"))
        assertTrue(registry.applyEvent(chunk("s1", "rpc-1", "world"), "s1"))
        assertTrue(registry.applyEvent(turnEnd("s1", "rpc-1"), "s1"))
        assertEquals(listOf("hello ", "world"), deltas)
        assertEquals(listOf("hello world"), completions)
        assertFalse(registry.applyEvent(turnEnd("s1", "rpc-1"), "s1"))
    }

    @Test
    fun detachSessionDropsAllStreamsForOnlyThatSession() {
        val registry = DshPromptStreamRegistry()
        registry.put(DshPromptStreamRegistry.ActiveStream("s1", "rpc-1", { _, _ -> }, {}, {}))
        registry.put(DshPromptStreamRegistry.ActiveStream("s1", "adopted-s1", { _, _ -> }, {}, {}))
        registry.put(DshPromptStreamRegistry.ActiveStream("s2", "rpc-2", { _, _ -> }, {}, {}))
        assertEquals(2, registry.detachSession("s1"))
        assertFalse(registry.applyEvent(chunk("s1", "rpc-1", "stale"), "s1"))
        assertTrue(registry.applyEvent(chunk("s2", "rpc-2", "live"), "s2"))
    }

    @Test
    fun turnEndReportsHostFailureWithoutCompleting() {
        val registry = DshPromptStreamRegistry()
        val failures = mutableListOf<String>()
        var completed = 0
        registry.put(DshPromptStreamRegistry.ActiveStream("s1", "rpc-1", { _, _ -> }, { completed++ }, { failures.add(it) }))
        assertTrue(registry.applyEvent(turnEnd("s1", "rpc-1", "host failed"), "s1"))
        assertEquals(listOf("host failed"), failures)
        assertEquals(0, completed)
    }

    private fun chunk(sessionId: String, rpcId: String, text: String) = JSONObject("""{"sessionId":"$sessionId","event":{"type":"assistant/chunk","data":{"source":{"rpcId":"$rpcId"},"chunk":{"type":"text-delta","text":"$text"}}}}""")

    private fun turnEnd(sessionId: String, rpcId: String, error: String? = null) = JSONObject(
        """{"sessionId":"$sessionId","event":{"type":"turn/end","data":{"source":{"rpcId":"$rpcId"}${error?.let { ""","reason":{"error":{"message":"$it"}}""" } ?: ""}}}}""",
    )
}
