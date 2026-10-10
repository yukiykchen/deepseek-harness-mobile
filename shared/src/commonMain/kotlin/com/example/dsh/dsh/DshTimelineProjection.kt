package com.example.dsh.dsh

import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

/*
 * Host data → conversation rows.
 *
 * Pure functions: the page decides where a row goes (dedupe, insert order,
 * attachment downloads); these only decide what the row looks like.
 */

/** One `session.webTimeline` history item as a conversation row. */
internal fun DshWebTimelineItem.toMessage(): DshMessage = when (kind) {
    DshWebTimelineItem.Kind.USER -> DshMessage(key, DshMessageRole.USER, text)
    DshWebTimelineItem.Kind.ASSISTANT -> DshMessage(key, DshMessageRole.ASSISTANT, text)
    DshWebTimelineItem.Kind.REASONING -> DshMessage(key, DshMessageRole.ASSISTANT, text, isReasoning = true)
    DshWebTimelineItem.Kind.IMAGE -> DshMessage(key, DshMessageRole.ASSISTANT, "", attachmentId = attachmentId)
    DshWebTimelineItem.Kind.UNKNOWN_BLOCK -> dshUnknownBlockMessage(key, text)
    DshWebTimelineItem.Kind.ERROR -> DshMessage(key, DshMessageRole.ERROR, text)
    DshWebTimelineItem.Kind.CONTEXT -> dshContextMessage(key, text, sourceLabel, source)
    DshWebTimelineItem.Kind.TOOL -> remoteTool?.toRemoteMessage(key) ?: DshMessage(
        key,
        DshMessageRole.TOOL,
        cardBody.ifEmpty { listOfNotNull(input, output).joinToString("\n\n") },
        toolName = cardTitle.ifEmpty { toolName ?: "工具" },
        toolCardType = cardType,
        toolRunning = running,
        toolError = error != null,
    )
}

/**
 * A live `user/message` that was injected by the runtime (memory, instructions,
 * relay) rather than typed by the user. Returns null for user-authored input,
 * malformed payloads and empty text.
 */
internal fun dshContextInjectionMessage(event: DshRawSessionEvent): DshMessage? {
    val data = event.wireData() ?: return null
    val source = data.optJSONObject("source") ?: return null
    if (source.optString("kind") == "user") return null
    val content = data.optJSONArray("content") ?: return null
    val text = buildString {
        for (index in 0 until content.length()) {
            val block = content.optJSONObject(index) ?: continue
            if (block.optString("type") == "text") append(block.optString("text"))
        }
    }.trim()
    if (text.isEmpty()) return null
    return dshContextMessage("context-${event.seq}", text, contextSummary(source), source)
}

/**
 * Rows for the non-streamed blocks of a live `assistant/message`: images and
 * block types this app does not know. Text, reasoning and tool calls arrive
 * through the stream and tool events instead. Null when the payload is malformed.
 */
internal fun dshAssistantBlockMessages(event: DshRawSessionEvent): List<DshMessage>? {
    val data = event.wireData() ?: return null
    val blocks = (data.optJSONObject("message") ?: data).optJSONArray("content") ?: return null
    val rows = mutableListOf<DshMessage>()
    for (index in 0 until blocks.length()) {
        val block = blocks.optJSONObject(index) ?: continue
        when (block.optString("type")) {
            "image" -> {
                val attachmentId = block.optJSONObject("attachment")?.optString("attachmentId").orEmpty()
                if (attachmentId.isEmpty()) continue
                rows += DshMessage(
                    id = "image-${event.seq}-$index",
                    role = DshMessageRole.ASSISTANT,
                    content = "",
                    attachmentId = attachmentId,
                )
            }
            "text", "reasoning", "tool-call" -> Unit
            else -> rows += dshUnknownBlockMessage("block-${event.seq}-$index", block.toString())
        }
    }
    return rows
}

/** Tool call id a live `tool/result` event settles, or "" when it has none. */
internal fun dshToolResultCallId(payload: JSONObject): String {
    val data = dshWireEvent(payload).optJSONObject("data") ?: return ""
    val message = data.optJSONObject("message")
    val resultBlock = message?.optJSONArray("content")?.optJSONObject(0)
    return resultBlock?.optString("toolCallId")
        ?: message?.optJSONObject("source")?.optString("callId")
        ?: data.optString("callId")
}

private fun DshRawSessionEvent.wireData(): JSONObject? {
    val payload = runCatching { JSONObject(raw) }.getOrNull() ?: return null
    return dshWireEvent(payload).optJSONObject("data")
}

private fun dshUnknownBlockMessage(id: String, raw: String) = DshMessage(
    id,
    DshMessageRole.TOOL,
    raw,
    toolName = "未知内容块",
    toolCardType = DshToolCardType.JSON,
)

private fun dshContextMessage(id: String, text: String, label: String, source: JSONObject?) = DshMessage(
    id = id,
    role = DshMessageRole.TOOL,
    content = text,
    toolName = label,
    isContextInjection = true,
    contextBody = text,
    contextForm = source?.optString("form").orEmpty(),
    contextCatalog = source?.let(::contextCatalogEntries).orEmpty(),
    contextSections = source?.let(::contextSections).orEmpty(),
    contextRecalls = source?.let(::contextRecalls).orEmpty(),
    contextInstructions = source?.let(::contextInstructions).orEmpty(),
    contextRelaySender = source?.let(::contextRelaySender).orEmpty(),
)
