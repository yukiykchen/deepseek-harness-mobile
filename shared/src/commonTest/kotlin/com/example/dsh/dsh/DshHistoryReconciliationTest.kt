package com.example.dsh.dsh

import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals

class DshHistoryReconciliationTest {
    private fun event(seq: Int) = DshRawSessionEvent(seq, "assistant/chunk", """{"event":{"seq":$seq,"type":"assistant/chunk","data":{}}}""")

    private fun sequences(events: JSONArray): List<Int> = (0 until events.length()).map {
        dshWireEvent(events.optJSONObject(it) ?: JSONObject()).optInt("seq", -1)
    }

    @Test
    fun historyWinsItsPrefixAndNewerLiveEventsSurviveInSequenceOrder() {
        val history = JSONArray("""[{"event":{"seq":4}},{"event":{"seq":6}}]""")
        assertEquals(listOf(4, 6, 7, 8), sequences(dshReconcileHistory(history, listOf(event(8), event(6), event(7), event(7)), 2)))
        assertEquals(listOf(4, 6), sequences(dshReconcileHistory(history, listOf(event(5)), 2)))
    }

    @Test
    fun emptySnapshotKeepsOnlyEventsReceivedAfterTheRequestStarted() {
        assertEquals(listOf(11), sequences(dshReconcileHistory(JSONArray(), listOf(event(8), event(10), event(11)), 10)))
    }
    @Test
    fun partialTextStaysBeforeToolsAndFinalMessageReplacesItsPlaceholder() {
        val events = JSONArray("""[
          {"event":{"seq":1,"type":"assistant/chunk","data":{"turn":1,"step":0,"chunk":{"type":"text-delta","text":"before"}}}},
          {"event":{"seq":2,"type":"tool/call","data":{"callId":"call","name":"Bash","arguments":"ls"}}},
          {"event":{"seq":3,"type":"assistant/chunk","data":{"turn":1,"step":1,"chunk":{"type":"text-delta","text":"after"}}}}
        ]""")
        val partial = DshWebTimelineParser.parseWebTimeline(events)
        assertEquals(listOf("partial-1:0", "tool-2", "partial-1:1"), partial.map { it.key })
        events.put(JSONObject("""{"event":{"seq":4,"type":"assistant/message","data":{"turn":1,"step":0,"message":{"content":[{"type":"text","text":"before"}]}}}}"""))
        val final = DshWebTimelineParser.parseWebTimeline(events)
        assertEquals(listOf(DshWebTimelineItem.Kind.ASSISTANT, DshWebTimelineItem.Kind.TOOL, DshWebTimelineItem.Kind.ASSISTANT), final.map { it.kind })
        assertEquals(listOf("before", "after"), final.filter { it.kind == DshWebTimelineItem.Kind.ASSISTANT }.map { it.text })
    }

    @Test
    fun partialReasoningSurvivesHistoryReconciliation() {
        val events = JSONArray("""[{"event":{"seq":1,"type":"assistant/chunk","data":{"turn":1,"step":0,"chunk":{"type":"reasoning-delta","text":"thinking"}}}}]""")
        assertEquals(DshWebTimelineItem.Kind.REASONING, DshWebTimelineParser.parseWebTimeline(events).single().kind)
    }

}
