package io.github.yukiykchen.dsh.feature.home

import com.tencent.kuikly.core.base.PagerScope
import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.reactive.handler.observableList
import com.tencent.kuikly.core.timer.setTimeout
import io.github.yukiykchen.dsh.data.DshHostClient
import io.github.yukiykchen.dsh.model.DshQueueItem

/** Prompt queue dock for the active session: list, inline edit, remove and steer. */

class DshQueueController(
    private val ctx: DshHomeContext,
    private val postToPage: (() -> Unit) -> Unit = { setTimeout(ctx.pagerId, 0, it) },
) : PagerScope by ctx {
    val items by observableList<DshQueueItem>()
    var expanded by observable(false)
    var actionBusy by observable(false)
        private set
    var editingId by observable("")
        private set
    var editingText by observable("")
        private set
    var error by observable("")
        private set
    private val requests = DshControllerRequestScope(ctx)
    private var snapshotClient: DshHostClient? = null
    private var snapshotSessionId = ""
    private var snapshotGeneration = -1L

    fun refresh() {
        if (requests.closed) return
        val client = ctx.hostClient
        val generation = client?.currentConnectionState()?.generation ?: -1L
        if (snapshotClient !== client || snapshotSessionId != ctx.activeSessionId || snapshotGeneration != generation) {
            reset()
        }
        snapshotClient = client
        snapshotSessionId = ctx.activeSessionId
        snapshotGeneration = generation
        if (client == null) { reset(); return }
        val next = client.queue(ctx.activeSessionId)
        if (items.toList() != next) {
            items.clear()
            items.addAll(next)
        }
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
        if (actionBusy || requests.closed) return
        refresh()
        val item = items.firstOrNull { it.id == itemId } ?: return
        val text = item.text ?: return
        expanded = true
        editingId = itemId
        editingText = text
        error = ""
    }
    fun save(itemId: String) {
        if (actionBusy || requests.closed) return
        refresh()
        val text = editingText.trim()
        if (itemId != editingId || text.isEmpty()) return
        performUpdate(itemId, JSONObject().apply {
            put("kind", "edit")
            put("content", JSONArray().apply { put(JSONObject().apply { put("type", "text"); put("text", text) }) })
        }) {
            // Keep edits typed while the previous snapshot was being saved.
            if (editingId == itemId && editingText.trim() == text) cancelEdit()
        }
    }
    fun updateEditingText(text: String) {
        if (requests.closed || editingId.isEmpty()) return
        editingText = text
        error = ""
    }
    fun cancelEdit() {
        editingId = ""
        editingText = ""
        error = ""
    }
    fun remove(itemId: String) = update(itemId, JSONObject().apply { put("kind", "remove") })
    fun steer(itemId: String) = update(itemId, JSONObject().apply { put("kind", "steer") })

    fun update(itemId: String, action: JSONObject) = performUpdate(itemId, action) {}

    private fun performUpdate(itemId: String, action: JSONObject, onSuccess: () -> Unit) {
        if (actionBusy || requests.closed) return
        refresh()
        if (items.none { it.id == itemId }) return
        val request = requests.begin(ctx.activeSessionId) ?: return
        actionBusy = true
        error = ""
        request.client.updateQueue(
            sessionId = ctx.activeSessionId,
            itemId = itemId,
            action = action,
        ) { _, failure ->
            postToPage {
                if (!requests.owns(request)) return@postToPage
                actionBusy = false
                if (!requests.accepts(request)) return@postToPage
                if (failure != null) {
                    error = failure.message
                    return@postToPage
                }
                onSuccess()
                refresh()
            }
        }
    }

    private fun reset() {
        requests.invalidate()
        actionBusy = false
        expanded = false
        items.clear()
        cancelEdit()
    }

    fun dispose() {
        reset()
        requests.close()
        snapshotClient = null
    }
}
