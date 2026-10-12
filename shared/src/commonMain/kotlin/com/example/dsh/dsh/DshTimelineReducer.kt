package com.example.dsh.dsh

/** All timeline row changes go through this pure boundary, including stream settlement. */
internal sealed class DshTimelineChange {
    data class Snapshot(val rows: List<DshMessage>) : DshTimelineChange()
    data class Append(val rows: List<DshMessage>) : DshTimelineChange()
    data class Update(val row: DshMessage) : DshTimelineChange()
    data class Remove(val id: String) : DshTimelineChange()
}

internal fun dshReduceTimeline(current: List<DshMessage>, change: DshTimelineChange): List<DshMessage> = when (change) {
    is DshTimelineChange.Snapshot -> change.rows.distinctBy { it.id }
    is DshTimelineChange.Append -> {
        val ids = current.mapTo(mutableSetOf()) { it.id }
        current + change.rows.filter { ids.add(it.id) }
    }
    is DshTimelineChange.Update -> current.map { if (it.id == change.row.id) change.row else it }
    is DshTimelineChange.Remove -> current.filterNot { it.id == change.id }
}
