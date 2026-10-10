package com.example.dsh.dsh

import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DshTimelineProjectionTest {
    @Test
    fun textItemsKeepRoleAndKey() {
        val user = DshWebTimelineItem("u1", DshWebTimelineItem.Kind.USER, text = "hi").toMessage()
        assertEquals(DshMessage("u1", DshMessageRole.USER, "hi"), user)
        val reasoning = DshWebTimelineItem("r1", DshWebTimelineItem.Kind.REASONING, text = "think").toMessage()
        assertEquals(DshMessageRole.ASSISTANT, reasoning.role)
        assertTrue(reasoning.isReasoning)
        val error = DshWebTimelineItem("e1", DshWebTimelineItem.Kind.ERROR, text = "boom").toMessage()
        assertEquals(DshMessageRole.ERROR, error.role)
    }

    @Test
    fun imageItemBecomesEmptyAssistantRowWithAttachment() {
        val row = DshWebTimelineItem("i1", DshWebTimelineItem.Kind.IMAGE, attachmentId = "att-1").toMessage()
        assertEquals(DshMessageRole.ASSISTANT, row.role)
        assertEquals("", row.content)
        assertEquals("att-1", row.attachmentId)
    }

    @Test
    fun unknownBlockBecomesJsonCard() {
        val row = DshWebTimelineItem("b1", DshWebTimelineItem.Kind.UNKNOWN_BLOCK, text = "{\"type\":\"x\"}").toMessage()
        assertEquals(DshMessageRole.TOOL, row.role)
        assertEquals("未知内容块", row.toolName)
        assertEquals(DshToolCardType.JSON, row.toolCardType)
    }

    @Test
    fun toolItemWithoutRemoteModelFallsBackToInputAndOutput() {
        val row = DshWebTimelineItem(
            "t1",
            DshWebTimelineItem.Kind.TOOL,
            toolName = "bash",
            input = "ls",
            output = "a.txt",
            error = "exit 1",
            running = true,
        ).toMessage()
        assertEquals("ls\n\na.txt", row.content)
        assertEquals("bash", row.toolName)
        assertTrue(row.toolRunning)
        assertTrue(row.toolError)
    }

    @Test
    fun toolItemPrefersCardTitleAndBody() {
        val row = DshWebTimelineItem(
            "t2",
            DshWebTimelineItem.Kind.TOOL,
            toolName = "read",
            input = "x",
            cardTitle = "读取 a.kt",
            cardBody = "body",
            cardType = DshToolCardType.READ,
        ).toMessage()
        assertEquals("body", row.content)
        assertEquals("读取 a.kt", row.toolName)
        assertEquals(DshToolCardType.READ, row.toolCardType)
        assertEquals(false, row.toolError)
    }

    @Test
    fun contextItemCarriesSourceForm() {
        val source = JSONObject().apply {
            put("kind", "memory")
            put("form", "notice")
        }
        val row = DshWebTimelineItem(
            "c1",
            DshWebTimelineItem.Kind.CONTEXT,
            text = "remember",
            sourceLabel = "记忆",
            source = source,
        ).toMessage()
        assertTrue(row.isContextInjection)
        assertEquals("记忆", row.toolName)
        assertEquals("remember", row.contextBody)
        assertEquals("notice", row.contextForm)
    }

    @Test
    fun liveContextInjectionJoinsTextBlocks() {
        val event = event(
            7,
            """{"event":{"data":{"source":{"kind":"memory"},"content":[
                {"type":"text","text":" first "},{"type":"image"},{"type":"text","text":"second "}
            ]}}}""",
        )
        val row = dshContextInjectionMessage(event)!!
        assertEquals("context-7", row.id)
        assertEquals("first second", row.content)
        assertTrue(row.isContextInjection)
    }

    @Test
    fun liveUserMessageIsNotContextInjection() {
        val event = event(1, """{"data":{"source":{"kind":"user"},"content":[{"type":"text","text":"hi"}]}}""")
        assertNull(dshContextInjectionMessage(event))
        assertNull(dshContextInjectionMessage(event(2, """{"data":{"source":{"kind":"memory"},"content":[]}}""")))
        assertNull(dshContextInjectionMessage(event(3, "not json")))
    }

    @Test
    fun assistantBlocksKeepImagesAndUnknownBlocksOnly() {
        val event = event(
            4,
            """{"data":{"message":{"content":[
                {"type":"text","text":"streamed"},
                {"type":"image","attachment":{"attachmentId":"att-9"}},
                {"type":"image","attachment":{}},
                {"type":"tool-call"},
                {"type":"chart","x":1}
            ]}}}""",
        )
        val rows = dshAssistantBlockMessages(event)!!
        assertEquals(listOf("image-4-1", "block-4-4"), rows.map { it.id })
        assertEquals("att-9", rows[0].attachmentId)
        assertEquals("未知内容块", rows[1].toolName)
        assertNull(dshAssistantBlockMessages(event(5, """{"data":{}}""")))
    }

    @Test
    fun toolResultCallIdPrefersResultBlock() {
        assertEquals(
            "call-1",
            dshToolResultCallId(JSONObject("""{"data":{"message":{"content":[{"toolCallId":"call-1"}]}}}""")),
        )
        assertEquals(
            "call-2",
            dshToolResultCallId(JSONObject("""{"event":{"data":{"message":{"source":{"callId":"call-2"}}}}}""")),
        )
        assertEquals("call-3", dshToolResultCallId(JSONObject("""{"data":{"callId":"call-3"}}""")))
        assertEquals("", dshToolResultCallId(JSONObject("""{"type":"x"}""")))
    }

    private fun event(seq: Int, raw: String) = DshRawSessionEvent(seq, "user/message", raw)
}
