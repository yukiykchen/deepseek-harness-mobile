package io.github.yukiykchen.dsh.data

import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import io.github.yukiykchen.dsh.model.DshHostStore
import io.github.yukiykchen.dsh.model.DshJobItem
import io.github.yukiykchen.dsh.model.DshPendingApproval
import io.github.yukiykchen.dsh.model.DshPendingQuestion
import io.github.yukiykchen.dsh.model.dshWireEvent
import io.github.yukiykchen.dsh.model.DshPendingQuestionItem
import io.github.yukiykchen.dsh.model.DshPendingQuestionOption
import io.github.yukiykchen.dsh.model.DshQueueItem
import io.github.yukiykchen.dsh.model.DshSession
import io.github.yukiykchen.dsh.model.DshWorkspaceGroup
import io.github.yukiykchen.dsh.model.textFromBlocks

/** Read-only projections of the Host store; no transport calls or UI state. */
internal class DshHostStoreQueries(private val store: DshHostStore) {
    fun queue(sessionId: String): List<DshQueueItem> {
        val raw = store.queueSnapshots[sessionId] ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val message = item.optJSONObject("message") ?: JSONObject()
                val text = textFromBlocks(message.optJSONArray("content"))
                add(DshQueueItem(
                    id = item.optString("id"),
                    placement = item.optString("placement"),
                    preview = text.lineSequence().firstOrNull().orEmpty(),
                    text = text.takeIf { it.isNotEmpty() },
                ))
            }
        }.filter { it.id.isNotEmpty() && it.placement == "queued" }
    }

    fun pendingInteractions(sessionId: String): Pair<DshPendingApproval?, DshPendingQuestion?> {
        var approval: DshPendingApproval? = null
        var question: DshPendingQuestion? = null
        store.pendingInteractions.forEach { (rpcId, raw) ->
            val payload = runCatching { JSONObject(raw) }.getOrNull() ?: return@forEach
            if (payload.optString("sessionId") != sessionId) return@forEach
            when (payload.optString("type")) {
                "approval/requested" -> approval = DshPendingApproval(
                    rpcId = rpcId,
                    sessionId = sessionId,
                    approvalId = payload.optString("approvalId"),
                    toolName = payload.optString("toolName"),
                    callId = payload.optString("callId").takeIf { it.isNotEmpty() },
                    reason = payload.optString("reason").takeIf { it.isNotEmpty() },
                    command = approvalCommand(sessionId, payload.optString("callId")),
                )
                "question/requested" -> {
                    val questions = payload.optJSONArray("questions") ?: JSONArray()
                    question = DshPendingQuestion(
                        rpcId = rpcId,
                        sessionId = sessionId,
                        questions = (0 until questions.length()).mapNotNull { index ->
                            val item = questions.optJSONObject(index) ?: return@mapNotNull null
                            val options = item.optJSONArray("options") ?: JSONArray()
                            DshPendingQuestionItem(
                                id = item.optString("id"),
                                question = item.optString("question"),
                                header = item.optString("header"),
                                detail = item.optString("detail"),
                                options = (0 until options.length()).mapNotNull { optionIndex ->
                                    val option = options.optJSONObject(optionIndex) ?: return@mapNotNull null
                                    DshPendingQuestionOption(
                                        label = option.optString("label"),
                                        description = option.optString("description"),
                                    )
                                },
                                multiSelect = item.optBoolean("multiSelect") || item.optBoolean("multi_select"),
                            )
                        },
                    )
                }
            }
        }
        return approval to question
    }

    fun jobs(sessionId: String): List<DshJobItem> {
        val raw = store.jobSnapshots[sessionId] ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("id")
            if (id.isEmpty()) return@mapNotNull null
            DshJobItem(
                id = id,
                kind = item.optString("kind"),
                label = item.optString("label"),
                status = item.optString("status"),
                detail = item.optString("detail"),
                startedAt = item.optLong("startedAt"),
                finishedAt = item.optString("finishedAt").takeIf { it.isNotEmpty() }?.toLongOrNull(),
            )
        }
    }

    fun workspaceGroups(): List<DshWorkspaceGroup> {
        val workspaces = runCatching { JSONArray(store.workspaceBaseline) }.getOrNull() ?: JSONArray()
        val archived = store.archivedSessionIds
        val sessionById = store.sessions.values
            .filterNot { it.blank || archived.contains(it.id) }
            .associateBy { it.id }
        val grouped = mutableSetOf<String>()
        val groups = (0 until workspaces.length()).mapNotNull { index ->
            val workspace = workspaces.optJSONObject(index) ?: return@mapNotNull null
            val workspaceId = workspace.optString("workspaceId")
            if (workspaceId.isEmpty()) return@mapNotNull null
            val sessionIds = workspace.optJSONArray("sessionIds") ?: JSONArray()
            val sessions = (0 until sessionIds.length()).mapNotNull { sessionIndex ->
                val sessionId = sessionIds.optString(sessionIndex)
                sessionId?.takeIf { it.isNotEmpty() }?.let(grouped::add)
                sessionById[sessionId]
            }
            DshWorkspaceGroup(
                workspaceId = workspaceId,
                title = workspace.optString("title").ifEmpty { workspaceId },
                path = workspace.optString("path"),
                sessions = sessions,
            )
        }
        val ungrouped = sessionById.values.filterNot { grouped.contains(it.id) }
        return if (ungrouped.isEmpty()) groups else groups + DshWorkspaceGroup(
            workspaceId = "",
            title = "未归类",
            path = "",
            sessions = ungrouped,
        )
    }

    fun workspaceIdForSession(sessionId: String): String? {
        val workspaces = runCatching { JSONArray(store.workspaceBaseline) }.getOrNull() ?: JSONArray()
        for (index in 0 until workspaces.length()) {
            val workspace = workspaces.optJSONObject(index) ?: continue
            val sessionIds = workspace.optJSONArray("sessionIds") ?: continue
            for (sessionIndex in 0 until sessionIds.length()) {
                if (sessionIds.optString(sessionIndex) == sessionId) {
                    return workspace.optString("workspaceId").takeIf { it.isNotEmpty() }
                }
            }
        }
        return null
    }

    fun blankSessionInWorkspace(workspaceId: String?): DshSession? {
        if (workspaceId == null) return store.sessions.values.firstOrNull { it.blank && it.cwd.isEmpty() }
        val workspaces = runCatching { JSONArray(store.workspaceBaseline) }.getOrNull() ?: JSONArray()
        for (index in 0 until workspaces.length()) {
            val workspace = workspaces.optJSONObject(index) ?: continue
            if (workspace.optString("workspaceId") != workspaceId) continue
            val sessionIds = workspace.optJSONArray("sessionIds") ?: continue
            for (sessionIndex in 0 until sessionIds.length()) {
                val sessionId = sessionIds.optString(sessionIndex)
                val session = store.sessions[sessionId] ?: continue
                if (session.blank) return session
            }
        }
        return null
    }

    private fun approvalCommand(sessionId: String, callId: String): String? {
        if (callId.isEmpty()) return null
        val events = store.sessionEvents[sessionId] ?: return null
        events.forEach { event ->
            if (event.type != "tool/call") return@forEach
            val payload = runCatching { JSONObject(event.raw) }.getOrNull() ?: return@forEach
            val data = dshWireEvent(payload).optJSONObject("data") ?: return@forEach
            if (data.optString("callId") != callId) return@forEach
            val arguments = data.opt("arguments") ?: return@forEach
            return when (arguments) {
                is String -> arguments
                else -> {
                    val obj = arguments as? JSONObject
                    obj?.optString("command")?.takeIf { it.isNotEmpty() } ?: arguments.toString()
                }
            }
        }
        return null
    }
}
