package com.example.dsh.dsh

import kotlin.test.Test
import kotlin.test.assertEquals

class DshTimelineReducerTest {
    @Test
    fun toolSplitsKeepTheirOrderAndOnlyTheMatchingRowSettles() {
        val text = DshMessage("text", DshMessageRole.ASSISTANT, "before")
        val tool = DshMessage("tool", DshMessageRole.TOOL, "pending", toolRunning = true)
        val tail = DshMessage("tail", DshMessageRole.ASSISTANT, "after", streaming = true)
        val initial = dshReduceTimeline(emptyList(), DshTimelineChange.Append(listOf(text, tool, tail)))
        val settled = dshReduceTimeline(initial, DshTimelineChange.Update(tool.copy(content = "done", toolRunning = false)))
        assertEquals(listOf("text", "tool", "tail"), settled.map { it.id })
        assertEquals("after", settled.last().content)
        assertEquals("pending", initial[1].content)
    }

    @Test
    fun duplicateAppendsAreIdempotentAndHostSnapshotsReplaceLocalIdentity() {
        val optimistic = DshMessage("local-user", DshMessageRole.USER, "hello")
        val current = dshReduceTimeline(emptyList(), DshTimelineChange.Append(listOf(optimistic, optimistic)))
        assertEquals(listOf(optimistic), current)
        assertEquals(current, dshReduceTimeline(current, DshTimelineChange.Append(listOf(optimistic))))
        val confirmed = optimistic.copy(id = "host-42")
        assertEquals(listOf(confirmed), dshReduceTimeline(current, DshTimelineChange.Snapshot(listOf(confirmed))))
        assertEquals(emptyList(), dshReduceTimeline(current, DshTimelineChange.Snapshot(emptyList())))
    }
}
