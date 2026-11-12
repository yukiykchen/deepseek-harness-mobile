package io.github.yukiykchen.dsh.data

import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import io.github.yukiykchen.dsh.model.DshMessage
import io.github.yukiykchen.dsh.model.DshContextCatalogEntry
import io.github.yukiykchen.dsh.model.DshContextSection
import io.github.yukiykchen.dsh.model.DshContextRecall
import io.github.yukiykchen.dsh.model.DshContextInstruction
import io.github.yukiykchen.dsh.model.DshPendingQuestion
import io.github.yukiykchen.dsh.model.DshQuestionDraft
import io.github.yukiykchen.dsh.model.DshSkill
import io.github.yukiykchen.dsh.model.DshGoalSnapshot

fun isRemoteCatalogInvalidationEvent(event: String): Boolean = event in setOf(
    "commands/change",
    "skills/change",
    "agent-preset/selected",
    "settings/document-updated",
    "credentials/updated",
    "llm/adapters-updated",
)

fun parseGoalProjection(raw: String): DshGoalSnapshot? {
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

fun String.dshLooksLikeJson(): Boolean {
    val value = trimStart()
    return value.startsWith("{") || value.startsWith("[")
}
fun String.dshReasoningSummary(running: Boolean): String {
    val visible = trimEnd()
    val newline = indexOf('\n')
    if (running) {
        val lastNewline = visible.lastIndexOf('\n')
        return if (lastNewline < 0) visible else visible.substring(lastNewline + 1)
    }
    return if (newline < 0) visible else substring(0, newline)
}
fun contextCatalogEntries(source: JSONObject?): List<DshContextCatalogEntry> {
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
fun contextSections(source: JSONObject?): List<DshContextSection> {
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
fun contextRecalls(source: JSONObject?): List<DshContextRecall> {
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
fun contextInstructions(source: JSONObject?): List<DshContextInstruction> {
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
fun contextRelaySender(source: JSONObject?): String {
    if (source?.optString("form") != "relay") return ""
    return source.optString("senderSessionId").takeIf { it.isNotEmpty() } ?: ""
}
fun boundedContextText(text: String): String {
    if (text.length <= 20_000) return text
    return text.take(20_000) + "\n… 共 ${text.length} 字符"
}
fun buildQuestionAnswer(
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
fun DshMessage.contextCanExpand(): Boolean {
    return content.isNotEmpty() ||
        contextCatalog.isNotEmpty() ||
        contextSections.isNotEmpty() ||
        contextRecalls.isNotEmpty() ||
        contextInstructions.isNotEmpty() ||
        contextRelaySender.isNotEmpty()
}
