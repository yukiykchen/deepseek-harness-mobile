package io.github.yukiykchen.dsh.feature.home

import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import io.github.yukiykchen.dsh.data.dshAssistantBlockMessages
import io.github.yukiykchen.dsh.data.dshContextInjectionMessage
import io.github.yukiykchen.dsh.data.dshToolResultCallId
import io.github.yukiykchen.dsh.model.DshMessage
import io.github.yukiykchen.dsh.model.DshMessageRole
import io.github.yukiykchen.dsh.model.DshRawSessionEvent
import io.github.yukiykchen.dsh.model.DshRemoteToolCallModels
import io.github.yukiykchen.dsh.model.toRemoteMessage

/** Applies live Host events without owning the page's session or scroll state. */
internal class DshLiveEventPresenter(
    private val messages: () -> MutableList<DshMessage>,
    private val splitBeforeTool: () -> Unit,
    private val refresh: () -> Unit,
    private val scrollToEnd: () -> Unit,
    private val loadAttachment: (String) -> Unit,
) {
    fun showRunningTool(event: DshRawSessionEvent) {
        val payload = runCatching { JSONObject(event.raw) }.getOrNull() ?: return
        val model = DshRemoteToolCallModels.fromLiveCall(payload) ?: return
        val rows = messages()
        val id = "tool-${event.seq}"
        if (rows.any { it.id == id }) return
        // Seal the assistant block before its tool card to preserve Host event order.
        splitBeforeTool()
        rows.add(model.toRemoteMessage(id))
        refresh()
        scrollToEnd()
    }

    fun showContextInjection(event: DshRawSessionEvent) {
        val message = dshContextInjectionMessage(event) ?: return
        val rows = messages()
        if (rows.any { it.id == message.id }) return
        rows.add(message)
        scrollToEnd()
    }

    fun showAssistantBlocks(event: DshRawSessionEvent) {
        val blocks = dshAssistantBlockMessages(event) ?: return
        val rows = messages()
        for (row in blocks) {
            if (rows.none { it.id == row.id }) rows.add(row)
            row.attachmentId?.let(loadAttachment)
        }
        scrollToEnd()
    }

    fun settleRunningTool(event: DshRawSessionEvent) {
        val payload = runCatching { JSONObject(event.raw) }.getOrNull() ?: return
        val callId = dshToolResultCallId(payload)
        if (callId.isEmpty()) return
        val rows = messages()
        val index = rows.indexOfFirst { it.role == DshMessageRole.TOOL && it.toolCallId == callId }
        if (index < 0) return
        val previous = rows[index].remoteTool ?: return
        val model = DshRemoteToolCallModels.settleLiveResult(previous, payload) ?: return
        rows[index] = model.toRemoteMessage(rows[index].id)
    }
}
