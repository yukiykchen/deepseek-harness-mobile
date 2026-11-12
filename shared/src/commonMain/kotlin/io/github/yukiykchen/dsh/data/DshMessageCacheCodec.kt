package io.github.yukiykchen.dsh.data

import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import io.github.yukiykchen.dsh.model.DshContextCatalogEntry
import io.github.yukiykchen.dsh.model.DshContextInstruction
import io.github.yukiykchen.dsh.model.DshContextRecall
import io.github.yukiykchen.dsh.model.DshContextSection
import io.github.yukiykchen.dsh.model.DshMessage
import io.github.yukiykchen.dsh.model.DshMessageRole
import io.github.yukiykchen.dsh.model.DshToolCardType
import io.github.yukiykchen.dsh.model.DshRemoteToolCallModel
import io.github.yukiykchen.dsh.model.DshRemoteToolKind

internal const val DSH_MESSAGE_CACHE_VERSION = 1

internal fun DshMessage.toCacheJson(): String = JSONObject().apply {
    put("v", DSH_MESSAGE_CACHE_VERSION)
    put("id", id)
    put("role", role.name)
    put("content", content)
    put("streaming", streaming)
    put("toolName", toolName)
    put("hidden", hidden)
    put("toolCardType", toolCardType.name)
    put("toolRunning", toolRunning)
    put("toolError", toolError)
    put("isContextInjection", isContextInjection)
    put("contextForm", contextForm)
    put("contextBody", contextBody)
    put("contextCatalog", JSONArray().apply {
        contextCatalog.forEach { put(JSONObject().put("name", it.name).put("description", it.description)) }
    })
    put("contextSections", JSONArray().apply {
        contextSections.forEach { put(JSONObject().put("title", it.title).put("body", it.body)) }
    })
    put("contextRecalls", JSONArray().apply {
        contextRecalls.forEach { put(JSONObject().put("label", it.label).put("retainedMessages", it.retainedMessages).put("omittedMessages", it.omittedMessages).put("truncated", it.truncated)) }
    })
    put("contextInstructions", JSONArray().apply {
        contextInstructions.forEach { put(JSONObject().put("path", it.path).put("action", it.action)) }
    })
    put("contextRelaySender", contextRelaySender)
    put("isReasoning", isReasoning)
    put("attachmentId", attachmentId)
    put("toolCallId", toolCallId)
    remoteTool?.let { put("remoteTool", it.toCacheJson()) }
}.toString()

internal fun dshMessageFromCache(raw: String): DshMessage {
    val value = JSONObject(raw)
    return DshMessage(
        id = value.optString("id"),
        role = DshMessageRole.valueOf(value.optString("role")),
        content = value.optString("content"),
        streaming = value.optBoolean("streaming"),
        toolName = value.optString("toolName").takeIf { it.isNotEmpty() },
        hidden = value.optBoolean("hidden"),
        toolCardType = runCatching { DshToolCardType.valueOf(value.optString("toolCardType")) }.getOrDefault(DshToolCardType.GENERIC),
        toolRunning = value.optBoolean("toolRunning"),
        toolError = value.optBoolean("toolError"),
        isContextInjection = value.optBoolean("isContextInjection"),
        contextForm = value.optString("contextForm"),
        contextBody = value.optString("contextBody"),
        contextCatalog = value.optJSONArray("contextCatalog").objects().map {
            DshContextCatalogEntry(it.optString("name"), it.optString("description"))
        },
        contextSections = value.optJSONArray("contextSections").objects().map {
            DshContextSection(it.optString("title"), it.optString("body"))
        },
        contextRecalls = value.optJSONArray("contextRecalls").objects().map {
            DshContextRecall(it.optString("label"), it.optInt("retainedMessages"), it.optInt("omittedMessages"), it.optBoolean("truncated"))
        },
        contextInstructions = value.optJSONArray("contextInstructions").objects().map {
            DshContextInstruction(it.optString("path"), it.optString("action"))
        },
        contextRelaySender = value.optString("contextRelaySender"),
        isReasoning = value.optBoolean("isReasoning"),
        attachmentId = value.optString("attachmentId").takeIf { it.isNotEmpty() },
        toolCallId = value.optString("toolCallId"),
        remoteTool = value.optJSONObject("remoteTool")?.let(::remoteToolFromCache),
    )
}

private fun DshRemoteToolCallModel.toCacheJson(): JSONObject = JSONObject().apply {
    put("kind", kind.name)
    put("callId", callId)
    put("toolName", toolName)
    put("title", title)
    put("summary", summary)
    put("body", body)
    put("input", input)
    put("output", output)
    put("error", error)
    put("running", running)
    put("cardType", cardType.name)
    put("filePath", filePath)
    put("todoDone", todoDone)
    put("todoTotal", todoTotal)
    put("todoActive", todoActive)
    put("todoActiveExtra", todoActiveExtra)
    put("questionAnswered", questionAnswered)
    put("questionTotal", questionTotal)
}

private fun remoteToolFromCache(value: JSONObject): DshRemoteToolCallModel = DshRemoteToolCallModel(
    kind = runCatching { DshRemoteToolKind.valueOf(value.optString("kind")) }.getOrDefault(DshRemoteToolKind.GENERIC),
    callId = value.optString("callId"),
    toolName = value.optString("toolName"),
    title = value.optString("title"),
    summary = value.optString("summary"),
    body = value.optString("body"),
    input = value.optString("input"),
    output = value.optString("output"),
    error = value.optString("error"),
    running = value.optBoolean("running"),
    cardType = runCatching { DshToolCardType.valueOf(value.optString("cardType")) }.getOrDefault(DshToolCardType.GENERIC),
    filePath = value.optString("filePath").takeIf { it.isNotEmpty() },
    todoDone = value.optInt("todoDone"),
    todoTotal = value.optInt("todoTotal"),
    todoActive = value.optString("todoActive").takeIf { it.isNotEmpty() },
    todoActiveExtra = value.optInt("todoActiveExtra"),
    questionAnswered = value.optInt("questionAnswered"),
    questionTotal = value.optInt("questionTotal"),
)

private fun JSONArray?.objects(): List<JSONObject> = buildList {
    if (this@objects == null) return@buildList
    for (index in 0 until length()) optJSONObject(index)?.let(::add)
}
