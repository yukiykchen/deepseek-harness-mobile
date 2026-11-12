package io.github.yukiykchen.dsh.ui

import com.tencent.kuiklybase.parser.parseMarkdown
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DshIncrementalMarkdownTest {
    @Test
    fun growingParagraphStaysLiveAndDoesNotSeal() {
        val parsed = mutableListOf<String>()
        val state = capturingState(parsed)
        val first = assertNotNull(state.update("Hello", streaming = true))
        assertEquals(1, first.size)
        assertEquals(DshIncrementalMarkdownState.LIVE_ID, first.single().id)
        assertEquals(0, state.sealedCharCount)
        val second = assertNotNull(state.update("Hello world", streaming = true))
        assertEquals(1, second.size)
        assertEquals(DshIncrementalMarkdownState.LIVE_ID, second.single().id)
        assertEquals(listOf("Hello", "Hello world"), parsed)
    }

    @Test
    fun completedBlockFreezesAndLaterFlushesParseOnlyTheTail() {
        val parsed = mutableListOf<String>()
        val state = capturingState(parsed)

        assertNotNull(state.update("Hello", streaming = true))
        val split = assertNotNull(state.update("Hello\n\n# Title", streaming = true))
        assertEquals(2, split.size)
        assertEquals("s0", split[0].id)
        assertEquals(DshIncrementalMarkdownState.LIVE_ID, split[1].id)
        assertTrue(state.sealedCharCount > 0)

        parsed.clear()
        val tail = assertNotNull(state.update("Hello\n\n# Title grows", streaming = true))
        assertEquals(2, tail.size)
        assertEquals("s0", tail[0].id)
        assertEquals(1, parsed.size)
        assertEquals("# Title grows", parsed.single())
        assertTrue(!parsed.single().startsWith("Hello"))
    }

    @Test
    fun unclosedFenceNeverSealsEvenWhenDisplayClosesIt() {
        val parsed = mutableListOf<String>()
        val state = capturingState(parsed)
        val raw = "```kotlin\nval x = 1"
        val blocks = assertNotNull(state.update(raw, streaming = true))
        assertEquals(1, blocks.size)
        assertEquals(DshIncrementalMarkdownState.LIVE_ID, blocks.single().id)
        assertEquals(0, state.sealedCharCount)
        assertEquals(DshStreamingMarkdown.closeOpenFence(raw), parsed.single())

        parsed.clear()
        val more = assertNotNull(state.update("$raw\nval y = 2", streaming = true))
        assertEquals(1, more.size)
        assertEquals(0, state.sealedCharCount)
        assertTrue(parsed.single().endsWith("```"))
    }

    @Test
    fun closedFenceThenNewBlockFreezesTheCodeBlock() {
        val state = DshIncrementalMarkdownState()
        val raw = "```kotlin\nval x = 1\n```\n\nNext"
        val blocks = assertNotNull(state.update(raw, streaming = true))
        assertEquals(2, blocks.size)
        assertEquals("s0", blocks[0].id)
        assertTrue(blocks[0].blockContent.contains("val x = 1"))
        assertEquals(DshIncrementalMarkdownState.LIVE_ID, blocks[1].id)
    }

    @Test
    fun listStaysOneLiveBlockUntilBlankLine() {
        val state = DshIncrementalMarkdownState()
        val one = assertNotNull(state.update("- a\n- b", streaming = true))
        assertEquals(1, one.size)
        assertEquals(0, state.sealedCharCount)
        val split = assertNotNull(state.update("- a\n- b\n\nAfter", streaming = true))
        assertEquals(2, split.size)
        assertEquals("s0", split[0].id)
        assertTrue(split[0].blockContent.contains("- a"))
        assertEquals(DshIncrementalMarkdownState.LIVE_ID, split[1].id)
    }

    @Test
    fun prefixRewriteReparsesFromScratch() {
        val parsed = mutableListOf<String>()
        val state = capturingState(parsed)
        assertNotNull(state.update("Hello\n\n# A", streaming = true))
        parsed.clear()
        val rewritten = assertNotNull(state.update("Rewritten\n\n# A", streaming = true))
        assertEquals("s0", rewritten[0].id)
        assertTrue(parsed.first().startsWith("Rewritten"))
    }

    @Test
    fun streamEndFreezesTheLastBlockWithoutAFakeFence() {
        val parsed = mutableListOf<String>()
        val state = capturingState(parsed)
        assertNotNull(state.update("Hello", streaming = true))
        parsed.clear()
        val done = assertNotNull(state.update("Hello", streaming = false, force = true))
        assertEquals(1, done.size)
        assertEquals("s0", done.single().id)
        assertEquals(listOf("Hello"), parsed)
        assertEquals(null, state.slice(DshIncrementalMarkdownState.LIVE_ID))
    }

    @Test
    fun identicalFlushIsSkipped() {
        val state = DshIncrementalMarkdownState()
        assertNotNull(state.update("Hello", streaming = true))
        assertEquals(null, state.update("Hello", streaming = true))
    }

    fun capturingState(parsed: MutableList<String>): DshIncrementalMarkdownState =
        DshIncrementalMarkdownState { text ->
            parsed += text
            parseMarkdown(text)
        }
}