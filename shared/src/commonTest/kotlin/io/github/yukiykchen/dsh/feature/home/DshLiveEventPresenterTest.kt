package io.github.yukiykchen.dsh.feature.home

import io.github.yukiykchen.dsh.model.DshMessage
import io.github.yukiykchen.dsh.model.DshMessageRole
import io.github.yukiykchen.dsh.model.DshRawSessionEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DshLiveEventPresenterTest {
    private var rows = mutableListOf<DshMessage>()
    private val operations = mutableListOf<String>()
    private val presenter = DshLiveEventPresenter(
        messages = { rows },
        splitBeforeTool = { operations.add("split") },
        refresh = { operations.add("refresh") },
        scrollToEnd = { operations.add("scroll") },
        loadAttachment = { operations.add("attachment:$it") },
    )

    @Test
    fun toolEventsKeepOrderingAndSettleInPlaceWithoutDuplicateCards() {
        val call = event(1, "tool/call", """{"callId":"c1","name":"shell","arguments":{"command":"pwd"}}""")
        presenter.showRunningTool(call)
        presenter.showRunningTool(call)
        assertEquals(listOf("split", "refresh", "scroll"), operations)
        assertEquals("tool-1", rows.single().id)
        assertTrue(rows.single().remoteTool!!.running)
        presenter.settleRunningTool(event(2, "tool/result", """{"callId":"c1","content":[{"type":"text","text":"/work"}]}"""))
        assertEquals("tool-1", rows.single().id)
        assertFalse(rows.single().remoteTool!!.running)
        assertTrue(rows.single().remoteTool!!.body.contains("/work"))
    }

    @Test
    fun contextIsDeduplicatedAndUsesCurrentSessionList() {
        val context = event(3, "user/message", """{"source":{"kind":"context","form":"snapshot"},"content":[{"type":"text","text":"context"}]}""")
        presenter.showContextInjection(context)
        presenter.showContextInjection(context)
        assertEquals(1, rows.size)
        val previous = rows
        rows = mutableListOf()
        presenter.showContextInjection(context)
        assertEquals(1, rows.size)
        assertEquals(1, previous.size)
        assertEquals(listOf("scroll", "scroll"), operations)
    }

    @Test
    fun assistantImagesLoadAttachmentsAndUnknownToolResultsDoNotMutateRows() {
        presenter.showAssistantBlocks(event(4, "assistant/message", """{"content":[{"type":"text","text":"streamed elsewhere"},{"type":"image","attachment":{"attachmentId":"image-1"}}]}"""))
        assertEquals(DshMessageRole.ASSISTANT, rows.single().role)
        assertEquals("image-1", rows.single().attachmentId)
        assertEquals(listOf("attachment:image-1", "scroll"), operations)
        val before = rows.toList()
        presenter.settleRunningTool(event(5, "tool/result", """{"callId":"unknown"}"""))
        assertEquals(before, rows)
    }

    private fun event(seq: Int, type: String, data: String) =
        DshRawSessionEvent(seq, type, """{"type":"$type","data":$data}""")
}
