package com.example.dsh.dsh

import com.tencent.kuikly.core.module.NetworkModule
import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.timer.setTimeout

/** Browser Host paths mirrored by the native client. */
internal object DshHostProtocol {
    const val API_PREFIX = "/api"
    const val MUX_EVENTS_PATH = "$API_PREFIX/events.mux"
    const val HOST_EVENTS_PATH = "$API_PREFIX/events.host"
    const val HOST_DESCRIBE = "host.describe"
    const val HOST_LIST_DIRECTORY = "host.listDirectory"
    const val HOST_CREATE_DIRECTORY = "host.createDirectory"
    const val WORKSPACE_LIST = "workspace.list"
    const val WORKSPACE_CREATE = "workspace.create"
    const val WORKSPACE_RENAME = "workspace.rename"
    const val WORKSPACE_DELETE = "workspace.delete"
    const val WORKSPACE_INSERT_BEFORE = "workspace.insertBefore"
    const val SESSION_LIST = "session.list"
    const val SESSION_CREATE = "session.create"
    const val SESSION_HISTORY = "session.history"
    const val SESSION_MODELS = "session.models"
    const val SESSION_SELECT_MODEL = "session.selectModel"
    const val SESSION_PROMPT = "session.prompt"
    const val SESSION_CANCEL = "session.cancel"
    const val SESSION_UPDATE_QUEUE = "session.updateQueue"
    const val SESSION_RENAME = "session.rename"
    const val SESSION_FORK = "session.fork"
    const val SESSION_ATTACHMENT = "session.attachment"
    const val WORKSPACE_ARCHIVE_SESSION = "workspace.archiveSession"
    const val SETTINGS_DESCRIBE = "settings.describe"
    const val CREDENTIALS_DESCRIBE = "credentials.describe"
    const val CREDENTIALS_SET = "credentials.set"
    const val LLM_PROVIDERS = "llm.providers"
    const val SKILL_LIST = "skill.list"
    const val AGENT_PRESET_LIST = "agentPreset.list"
    const val GOAL_EDIT = "goal.edit"
    const val GOAL_PAUSE = "goal.pause"
    const val GOAL_RESUME = "goal.resume"
    const val GOAL_CLEAR = "goal.clear"
    const val RESPOND_PATH = "$API_PREFIX/respond"
    const val SESSION_EXPORT_PATH = "$API_PREFIX/session.export"

}

internal data class DshHostConnection(val baseUrl: String, val token: String = "")

internal object DshWebTimelineParser {
    fun parseWebTimeline(events: JSONArray): List<DshWebTimelineItem> {
        val result = mutableListOf<DshWebTimelineItem>()
        val toolModels = mutableMapOf<String, DshRemoteToolCallModel>()
        val partials = linkedMapOf<String, StringBuilder>()
        for (index in 0 until events.length()) {
            val entry = events.optJSONObject(index) ?: continue
            val event = entry.optJSONObject("event") ?: entry
            val seq = event.optInt("seq", index)
            val type = event.optString("type")
            val data = event.optJSONObject("data") ?: continue
            when (type) {
                "user/message" -> {
                    val text = textFromBlocks(data.optJSONArray("content"))
                    if (text.isEmpty()) continue
                    val source = data.optJSONObject("source")
                    val sourceKind = source?.optString("kind").orEmpty()
                    if (sourceKind == "user") {
                        result += DshWebTimelineItem("user-$seq", DshWebTimelineItem.Kind.USER, text)
                    } else {
                        result += DshWebTimelineItem(
                            key = "context-$seq",
                            kind = DshWebTimelineItem.Kind.CONTEXT,
                            text = text,
                            sourceLabel = contextSummary(source),
                            source = source,
                        )
                    }
                }
                "assistant/message" -> {
                    val message = data.optJSONObject("message") ?: data
                    val key = "${data.optInt("turn")}:${data.optInt("step")}"
                    val blocks = message.optJSONArray("content") ?: JSONArray()
                    appendAssistantBlocks(result, seq, blocks)
                    partials.remove(key)
                }
                "assistant/chunk" -> {
                    val key = "${data.optInt("turn")}:${data.optInt("step")}"
                    val chunk = data.optJSONObject("chunk") ?: JSONObject()
                    val text = chunk.optString("text").ifEmpty { chunk.optString("delta") }
                    if (text.isNotEmpty() &&
                        chunk.optString("type") in setOf("", "text", "text-delta", "text_delta")
                    ) {
                        partials.getOrPut(key) { StringBuilder() }.append(text)
                    }
                }
                "tool/call" -> {
                    val remoteTool = DshRemoteToolCallModels.fromHistoryCall(entry) ?: continue
                    if (remoteTool.callId.isNotEmpty()) toolModels[remoteTool.callId] = remoteTool
                    result += DshWebTimelineItem(
                        key = "tool-$seq",
                        kind = DshWebTimelineItem.Kind.TOOL,
                        toolName = remoteTool.toolName,
                        input = remoteTool.input,
                        running = remoteTool.running,
                        callId = remoteTool.callId,
                        callSeq = seq,
                        cardType = remoteTool.cardType,
                        cardTitle = remoteTool.title,
                        cardBody = remoteTool.body,
                        remoteTool = remoteTool,
                    )
                }
                "tool/result" -> {
                    val message = data.optJSONObject("message")
                    val resultBlock = message?.optJSONArray("content")?.optJSONObject(0)
                    val callId = resultBlock?.optString("toolCallId")
                        ?: message?.optJSONObject("source")?.optString("callId")
                        ?: data.optString("callId")
                    val previous = toolModels[callId]
                    val settled = DshRemoteToolCallModels.settleHistoryResult(previous, entry) ?: continue
                    if (settled.callId.isNotEmpty()) toolModels[settled.callId] = settled
                    val call = result.lastOrNull {
                        it.kind == DshWebTimelineItem.Kind.TOOL &&
                            it.callSeq < seq &&
                            (settled.callId.isEmpty() || it.callId == settled.callId)
                    }
                    if (call != null) {
                        val callIndex = result.indexOf(call)
                        result[callIndex] = call.copy(
                            toolName = settled.toolName,
                            input = settled.input,
                            output = settled.output,
                            running = settled.running,
                            error = settled.error,
                            cardType = settled.cardType,
                            cardTitle = settled.title,
                            cardBody = settled.body,
                            remoteTool = settled,
                        )
                    } else {
                        result += DshWebTimelineItem(
                            key = "tool-$seq",
                            kind = DshWebTimelineItem.Kind.TOOL,
                            toolName = settled.toolName,
                            input = settled.input,
                            output = settled.output,
                            running = settled.running,
                            callId = settled.callId,
                            callSeq = seq,
                            error = settled.error,
                            cardType = settled.cardType,
                            cardTitle = settled.title,
                            cardBody = settled.body,
                            remoteTool = settled,
                        )
                    }
                }
                "turn/end" -> {
                    data.optJSONObject("reason")?.optJSONObject("error")?.optString("message")
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { result += DshWebTimelineItem("turn-error-$seq", DshWebTimelineItem.Kind.ERROR, it) }
                }
            }
        }
        partials.forEach { (key, text) ->
            if (text.isNotEmpty()) {
                result += DshWebTimelineItem(
                    key = "partial-$key",
                    kind = DshWebTimelineItem.Kind.ASSISTANT,
                    text = text.toString(),
                )
            }
        }
        return result.filterNot { it.kind == DshWebTimelineItem.Kind.USER && it.isRuntimeContextSnapshot() }
    }
}

private fun DshWebTimelineItem.isRuntimeContextSnapshot(): Boolean {
    return text.startsWith("Current runtime context. This snapshot supersedes earlier runtime-context snapshots.")
}

internal fun contextSourceLabel(source: JSONObject?): String {
    if (source == null) return "未知来源"
    val kind = source.optString("kind")
    return when (kind) {
        "skill-invocation" -> source.optString("name").takeIf { it.isNotEmpty() } ?: kind
        "plugin" -> source.optString("plugin").takeIf { it.isNotEmpty() } ?: kind
        "session-reference" -> sourceLabels(source, "references", "label").takeIf { it.isNotEmpty() } ?: kind
        "agent-instructions" -> sourceLabels(source, "changes", "path").takeIf { it.isNotEmpty() } ?: kind
        else -> kind.takeIf { it.isNotEmpty() } ?: source.optString("name").takeIf { it.isNotEmpty() } ?: "未知来源"
    }
}

internal fun contextSummary(source: JSONObject?): String {
    if (source?.optString("form") == "notice") {
        source.optString("summary").takeIf { it.isNotEmpty() }?.let { return it }
    }
    return contextSourceLabel(source)
}

private fun sourceLabels(source: JSONObject, member: String, field: String): String {
    val values = source.optJSONArray(member) ?: return ""
    val labels = mutableListOf<String>()
    for (index in 0 until values.length()) {
        val value = values.optJSONObject(index) ?: continue
        val label = value.optString(field).takeIf { it.isNotEmpty() } ?: continue
        if (!labels.contains(label)) labels += label
    }
    return labels.joinToString(", ")
}

internal fun toolInputSummary(value: Any?): String = when (value) {
    null -> ""
    is String -> value
    else -> value.toString()
}

internal fun toolOutputSummary(value: Any?): String = when (value) {
    null -> ""
    is String -> value
    is JSONArray -> textFromBlocks(value)
    else -> value.toString()
}

internal fun textFromBlocks(blocks: JSONArray?): String {
    if (blocks == null) return ""
    return buildString {
        for (index in 0 until blocks.length()) {
            val block = blocks.optJSONObject(index) ?: continue
            if (block.optString("type") == "text") append(block.optString("text"))
        }
    }
}

internal fun appendAssistantBlocks(
    result: MutableList<DshWebTimelineItem>,
    seq: Int,
    blocks: JSONArray?,
) {
    if (blocks == null) return
    for (blockIndex in 0 until blocks.length()) {
        val block = blocks.optJSONObject(blockIndex) ?: continue
        when (block.optString("type")) {
            "text" -> block.optString("text").takeIf { it.isNotEmpty() }?.let {
                result += DshWebTimelineItem("text-$seq-$blockIndex", DshWebTimelineItem.Kind.ASSISTANT, it)
            }
            "reasoning" -> block.optString("text").takeIf { it.isNotEmpty() }?.let {
                result += DshWebTimelineItem(
                    "reasoning-$seq-$blockIndex",
                    DshWebTimelineItem.Kind.REASONING,
                    it,
                )
            }
            "image" -> block.optJSONObject("attachment")?.optString("attachmentId")
                ?.takeIf { it.isNotEmpty() }
                ?.let {
                    result += DshWebTimelineItem(
                        "image-$seq-$blockIndex",
                        DshWebTimelineItem.Kind.IMAGE,
                        attachmentId = it,
                    )
                }
            "tool-call" -> Unit
            else -> result += DshWebTimelineItem(
                "block-$seq-$blockIndex",
                DshWebTimelineItem.Kind.UNKNOWN_BLOCK,
                block.toString(),
            )
        }
    }
}
