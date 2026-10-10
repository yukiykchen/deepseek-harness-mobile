package com.example.dsh.dsh

import com.tencent.kuikly.core.base.PagerScope
import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.reactive.handler.observableList
import com.tencent.kuikly.core.timer.setTimeout

/** Prompt queue dock for the active session: list, inline edit, remove and steer. */
internal class DshQueueController(private val ctx: DshHomeContext) : PagerScope by ctx {
    val items by observableList<DshQueueItem>()
    var expanded by observable(false)
    var actionBusy by observable(false)
        private set
    var editingId by observable("")
        private set
    var editingText by observable("")
        private set

    fun refresh() {
        val client = ctx.hostClient ?: return
        val next = client.queue(ctx.activeSessionId)
        items.clear()
        items.addAll(next)
        if (next.isEmpty()) {
            expanded = false
            cancelEdit()
        } else if (editingId.isNotEmpty() && next.none { it.id == editingId }) {
            cancelEdit()
        }
    }

    fun toggle() {
        expanded = !expanded
    }

    fun edit(itemId: String) {
        val item = items.firstOrNull { it.id == itemId } ?: return
        val text = item.text ?: return
        expanded = true
        editingId = itemId
        editingText = text
    }

    fun save(itemId: String) {
        val client = ctx.hostClient ?: return
        val text = editingText.trim()
        if (actionBusy || itemId != editingId || text.isEmpty()) return
        actionBusy = true
        client.updateQueue(
            sessionId = ctx.activeSessionId,
            itemId = itemId,
            action = JSONObject().apply {
                put("kind", "edit")
                put("content", JSONArray().apply { put(JSONObject().apply { put("type", "text"); put("text", text) }) })
            },
        ) { _, _ ->
            setTimeout(pagerId, 0) {
                actionBusy = false
                cancelEdit()
                refresh()
            }
        }
    }

    fun updateEditingText(text: String) {
        editingText = text
    }

    fun cancelEdit() {
        editingId = ""
        editingText = ""
    }

    fun remove(itemId: String) = update(itemId, JSONObject().apply { put("kind", "remove") })

    fun steer(itemId: String) = update(itemId, JSONObject().apply { put("kind", "steer") })

    private fun update(itemId: String, action: JSONObject) {
        val client = ctx.hostClient ?: return
        if (actionBusy) return
        actionBusy = true
        client.updateQueue(
            sessionId = ctx.activeSessionId,
            itemId = itemId,
            action = action,
        ) { _, _ ->
            setTimeout(pagerId, 0) {
                actionBusy = false
                refresh()
            }
        }
    }
}
