package com.example.dsh.dsh

import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.reactive.collection.ObservableList

/**
 * Accept either the bare launch token or the full URL `dsh web` prints
 * (`http://127.0.0.1:3080/?token=abc`), returning the token only.
 */
internal fun dshExtractLaunchToken(raw: String): String {
    val value = raw.trim()
    if (value.isEmpty()) return ""
    val marker = "token="
    val at = value.indexOf(marker)
    if (at < 0) return value.trimEnd('/', '&', '#')
    return value.substring(at + marker.length).takeWhile { it != '&' && it != '#' && !it.isWhitespace() }
}

/** Stable per-Host cache scope for direct connections: `host_port` with URL punctuation collapsed. */
internal fun dshDirectProfileId(baseUrl: String): String {
    val stripped = baseUrl.trim().removePrefix("https://").removePrefix("http://").trimEnd('/')
    val id = stripped.map { if (it.isLetterOrDigit()) it else '_' }.joinToString("")
    return id.ifEmpty { "default" }
}

internal fun visibleSkillList(source: ObservableList<DshSkill>, query: String): ObservableList<DshSkill> {
    val next = source.toList().filter { it.name.startsWith(query) }
    skillFilterCache.diffUpdate(next) { old, new -> old.name == new.name }
    return skillFilterCache
}

private val skillFilterCache = ObservableList<DshSkill>()

internal fun isRemoteCatalogInvalidationEvent(event: String): Boolean = event in setOf(
    "commands/change",
    "skills/change",
    "agent-preset/selected",
    "settings/document-updated",
    "credentials/updated",
    "llm/adapters-updated",
)

internal fun parseGoalProjection(raw: String): DshGoalSnapshot? {
    val root = runCatching { JSONObject(raw) }.getOrNull() ?: return null
    val goal = root.optJSONObject("goal") ?: return null
    val id = goal.optString("id")
    val revision = goal.optInt("revision")
    val objective = goal.optString("objective")
    val phase = goal.optString("phase")
    if (id.isEmpty() || revision <= 0 || objective.isEmpty() || phase.isEmpty() || phase == "complete") return null
    return DshGoalSnapshot(
        id = id,
        revision = revision,
        objective = objective,
        phase = phase,
        blockedReason = goal.optJSONObject("blockedReason")?.optString("message").orEmpty(),
    )
}

internal fun DshToolCardType.iconAsset(): String = when (this) {
    DshToolCardType.TERMINAL -> "tool-terminal.svg"
    DshToolCardType.READ -> "tool-read.svg"
    DshToolCardType.DIFF -> "tool-diff.svg"
    DshToolCardType.SEARCH -> "tool-search.svg"
    DshToolCardType.WEB -> "tool-web.svg"
    DshToolCardType.JSON -> "tool-json.svg"
    DshToolCardType.GENERIC -> "tool-generic.svg"
}

/** Remote tool-name semantics choose the icon even before a result view exists. */
internal fun DshRemoteToolCallModel.iconAsset(): String = when (kind) {
    DshRemoteToolKind.BASH -> "tool-terminal.svg"
    DshRemoteToolKind.READ -> "tool-read.svg"
    DshRemoteToolKind.FILE_MUTATION -> "tool-diff.svg"
    DshRemoteToolKind.SEARCH -> "tool-search.svg"
    DshRemoteToolKind.WEB -> "tool-web.svg"
    DshRemoteToolKind.SKILL -> "tool-skill.svg"
    DshRemoteToolKind.ASK_QUESTION -> "tool-ask.svg"
    DshRemoteToolKind.TODO,
    DshRemoteToolKind.GENERIC -> cardType.iconAsset()
}

internal fun String.dshLooksLikeJson(): Boolean {
    val value = trimStart()
    return value.startsWith("{") || value.startsWith("[")
}

internal fun String.dshReasoningSummary(running: Boolean): String {
    val visible = trimEnd()
    val newline = indexOf('\n')
    if (running) {
        val lastNewline = visible.lastIndexOf('\n')
        return if (lastNewline < 0) visible else visible.substring(lastNewline + 1)
    }
    return if (newline < 0) visible else substring(0, newline)
}

internal fun contextCatalogEntries(source: JSONObject?): List<DshContextCatalogEntry> {
    if (source?.optString("form") != "catalog") return emptyList()
    val entries = source.optJSONArray("entries") ?: return emptyList()
    val result = mutableListOf<DshContextCatalogEntry>()
    for (index in 0 until entries.length()) {
        val entry = entries.optJSONObject(index) ?: continue
        val name = entry.optString("name")
        if (name.isEmpty()) return emptyList()
        result += DshContextCatalogEntry(name, entry.optString("description"))
    }
    return result.take(200)
}

internal fun contextSections(source: JSONObject?): List<DshContextSection> {
    if (source?.optString("form") != "snapshot") return emptyList()
    val sections = source.optJSONArray("sections") ?: return emptyList()
    val result = mutableListOf<DshContextSection>()
    for (index in 0 until sections.length()) {
        val section = sections.optJSONObject(index) ?: continue
        val name = section.optString("name")
        if (name.isEmpty()) return emptyList()
        result += DshContextSection(name, section.optString("text"))
    }
    return result
}

internal fun contextRecalls(source: JSONObject?): List<DshContextRecall> {
    if (source?.optString("form") != "recall") return emptyList()
    val references = source.optJSONArray("references") ?: return emptyList()
    val result = mutableListOf<DshContextRecall>()
    for (index in 0 until references.length()) {
        val reference = references.optJSONObject(index) ?: continue
        val label = reference.optString("label")
        if (label.isEmpty()) return emptyList()
        result += DshContextRecall(
            label = label,
            retainedMessages = reference.optInt("retainedMessages"),
            omittedMessages = reference.optInt("omittedMessages"),
            truncated = reference.optBoolean("truncated"),
        )
    }
    return result
}

internal fun contextInstructions(source: JSONObject?): List<DshContextInstruction> {
    if (source?.optString("form") != "instructions") return emptyList()
    val changes = source.optJSONArray("changes") ?: return emptyList()
    val result = mutableListOf<DshContextInstruction>()
    for (index in 0 until changes.length()) {
        val change = changes.optJSONObject(index) ?: continue
        val path = change.optString("path")
        val action = change.optString("action")
        if (path.isEmpty() || (action != "set" && action != "replace" && action != "remove")) return emptyList()
        result += DshContextInstruction(path, action)
    }
    return result
}

internal fun contextRelaySender(source: JSONObject?): String {
    if (source?.optString("form") != "relay") return ""
    return source.optString("senderSessionId").takeIf { it.isNotEmpty() } ?: ""
}

internal fun boundedContextText(text: String): String {
    if (text.length <= 20_000) return text
    return text.take(20_000) + "\n… 共 ${text.length} 字符"
}

internal fun buildQuestionAnswer(
    question: DshPendingQuestion,
    drafts: Map<Int, DshQuestionDraft>,
): JSONObject {
    return JSONObject().apply {
        put("answers", JSONArray().apply {
            question.questions.forEachIndexed { index, item ->
                val draft = drafts[index] ?: DshQuestionDraft()
                put(JSONObject().apply {
                    put("id", item.id)
                    put("selected", JSONArray().apply { draft.selected.forEach(::put) })
                    if (!draft.skipped && draft.custom.isNotBlank()) put("custom", draft.custom.trim())
                })
            }
        })
    }
}

internal fun DshMessage.contextCanExpand(): Boolean {
    return content.isNotEmpty() ||
        contextCatalog.isNotEmpty() ||
        contextSections.isNotEmpty() ||
        contextRecalls.isNotEmpty() ||
        contextInstructions.isNotEmpty() ||
        contextRelaySender.isNotEmpty()
}
