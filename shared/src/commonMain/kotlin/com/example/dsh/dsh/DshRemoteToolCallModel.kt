package com.example.dsh.dsh

import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

/** Tool-name dispatch used only by the remote Web-compatible timeline. */
internal enum class DshRemoteToolKind {
    GENERIC,
    BASH,
    READ,
    FILE_MUTATION,
    SEARCH,
    WEB,
    SKILL,
    ASK_QUESTION,
    TODO,
}

/**
 * One remote tool call after the call/result pair has been folded together.
 * The model deliberately keeps presentation data separate from DshMessage so
 * the local engine can continue using its legacy message path unchanged.
 */
internal data class DshRemoteToolCallModel(
    val callId: String,
    val toolName: String,
    val kind: DshRemoteToolKind,
    val title: String,
    val summary: String,
    val input: String,
    val body: String,
    val output: String = "",
    val error: String? = null,
    val running: Boolean = true,
    val cardType: DshToolCardType = DshToolCardType.GENERIC,
    val filePath: String? = null,
    val todoDone: Int = 0,
    val todoTotal: Int = 0,
    val todoActive: String? = null,
    val todoActiveExtra: Int = 0,
    val questionAnswered: Int = 0,
    val questionTotal: Int = 0,
)

internal fun DshRemoteToolCallModel.toRemoteMessage(key: String): DshMessage = DshMessage(
    id = key,
    role = DshMessageRole.TOOL,
    content = if (kind == DshRemoteToolKind.SKILL) output else body.ifEmpty { listOfNotNull(input, output).joinToString("\n\n") },
    toolName = title,
    toolCardType = cardType,
    toolRunning = running,
    toolError = error != null,
    toolCallId = callId,
    remoteTool = this,
)

internal object DshRemoteToolCallModels {
    fun fromHistoryCall(entry: JSONObject): DshRemoteToolCallModel? {
        val event = dshWireEvent(entry)
        if (event.optString("type") != "tool/call") return null
        return fromCall(event.optJSONObject("data") ?: return null, dshWireView(entry))
    }

    fun fromLiveCall(raw: JSONObject): DshRemoteToolCallModel? {
        val event = dshWireEvent(raw)
        if (event.optString("type") != "tool/call") return null
        return fromCall(event.optJSONObject("data") ?: return null, dshWireView(raw))
    }

    fun settleHistoryResult(
        previous: DshRemoteToolCallModel?,
        entry: JSONObject,
    ): DshRemoteToolCallModel? {
        val event = dshWireEvent(entry)
        if (event.optString("type") != "tool/result") return null
        return settle(previous, event.optJSONObject("data") ?: return null, dshWireView(entry))
    }

    fun settleLiveResult(
        previous: DshRemoteToolCallModel?,
        raw: JSONObject,
    ): DshRemoteToolCallModel? {
        val event = dshWireEvent(raw)
        if (event.optString("type") != "tool/result") return null
        return settle(previous, event.optJSONObject("data") ?: return null, dshWireView(raw))
    }

    private fun fromCall(data: JSONObject, view: JSONObject?): DshRemoteToolCallModel {
        val name = data.optString("name").ifEmpty { "工具" }
        val callId = data.optString("callId")
        val input = toolInputSummary(data.opt("arguments"))
        val argumentsObject = data.optJSONObject("arguments")
        val kind = kindFor(name)
        val card = toolCardType(view)
        val filePath = filePath(kind, input, argumentsObject)
        val title = callTitle(kind, name, view)
        val summary = callSummary(kind, name, input, argumentsObject, view, filePath)
        val body = callBody(kind, input, view)
        val todo = todoSummary(input, argumentsObject)
        return DshRemoteToolCallModel(
            callId = callId,
            toolName = name,
            kind = kind,
            title = title,
            summary = summary,
            input = input,
            body = body,
            running = true,
            cardType = card,
            filePath = filePath,
            todoDone = todo.done,
            todoTotal = todo.total,
            todoActive = todo.active,
            todoActiveExtra = todo.extra,
        )
    }

    private fun settle(
        previous: DshRemoteToolCallModel?,
        data: JSONObject,
        view: JSONObject?,
    ): DshRemoteToolCallModel? {
        val result = resultPayload(data)
        val callId = result.callId
        val base = previous ?: DshRemoteToolCallModel(
            callId = callId,
            toolName = "工具",
            kind = DshRemoteToolKind.GENERIC,
            title = "工具",
            summary = callId.ifEmpty { "工具" },
            input = "",
            body = "",
            running = false,
        )
        // A present generic result is authoritative: write/edit errors and
        // background shell acknowledgements intentionally fall back to the
        // generic card. Only an absent result view inherits the call view.
        val card = view?.let(::toolCardType) ?: base.cardType
        val title = view?.optString("title")?.takeIf { it.isNotEmpty() } ?: base.title
        val body = when (base.kind) {
            DshRemoteToolKind.ASK_QUESTION -> dshAskReadableBody(base.input, result.output)
                .ifEmpty { resultBody(base.kind, card, view, result.output) }
            else -> resultBody(base.kind, card, view, result.output)
        }
        val todo = if (base.kind == DshRemoteToolKind.TODO) {
            TodoSummary(base.todoDone, base.todoTotal, base.todoActive, base.todoActiveExtra)
        } else TodoSummary()
        val question = if (base.kind == DshRemoteToolKind.ASK_QUESTION) {
            answerSummary(result.output)
        } else AnswerSummary()
        return base.copy(
            callId = callId.ifEmpty { base.callId },
            title = title,
            summary = settledSummary(base, title, result.output, question),
            body = body,
            output = result.output,
            error = if (result.isError) result.output.ifEmpty { "工具执行失败" } else null,
            running = false,
            cardType = card,
            todoDone = todo.done,
            todoTotal = todo.total,
            todoActive = todo.active,
            todoActiveExtra = todo.extra,
            questionAnswered = question.answered,
            questionTotal = question.total,
        )
    }

    private fun settledSummary(
        base: DshRemoteToolCallModel,
        title: String,
        output: String,
        question: AnswerSummary,
    ): String = when (base.kind) {
        DshRemoteToolKind.ASK_QUESTION -> when {
            question.total > 0 -> "已回答 ${question.answered}/${question.total}"
            else -> "已完成"
        }
        DshRemoteToolKind.TODO -> todoLabel(base.todoDone, base.todoTotal, base.todoActive, base.todoActiveExtra)
        DshRemoteToolKind.SEARCH -> title.takeIf { it.isNotEmpty() && title != base.title }
            ?: base.summary
        DshRemoteToolKind.BASH,
        DshRemoteToolKind.READ,
        DshRemoteToolKind.FILE_MUTATION,
        DshRemoteToolKind.WEB,
        DshRemoteToolKind.SKILL,
        DshRemoteToolKind.GENERIC -> base.summary.ifEmpty { title.ifEmpty { output.lineSequence().firstOrNull().orEmpty() } }
    }

    private fun callTitle(kind: DshRemoteToolKind, name: String, view: JSONObject?): String = when (kind) {
        DshRemoteToolKind.BASH -> "Bash"
        DshRemoteToolKind.READ -> "Read"
        DshRemoteToolKind.FILE_MUTATION -> if (name.equals("write", true)) "Write" else "Edit"
        DshRemoteToolKind.SEARCH -> if (name.equals("glob", true)) "Glob" else "Grep"
        DshRemoteToolKind.WEB -> if (name.equals("web_fetch", true)) "Fetch" else "Search"
        DshRemoteToolKind.SKILL -> "Skill"
        DshRemoteToolKind.ASK_QUESTION -> "Ask"
        DshRemoteToolKind.TODO -> "Todo"
        DshRemoteToolKind.GENERIC -> view?.optString("title")?.takeIf { it.isNotEmpty() } ?: name
    }

    private fun callSummary(
        kind: DshRemoteToolKind,
        name: String,
        input: String,
        argumentsObject: JSONObject?,
        view: JSONObject?,
        path: String?,
    ): String = when (kind) {
        DshRemoteToolKind.BASH -> view?.optString("description")?.takeIf { it.isNotEmpty() }
            ?: inputString(input, argumentsObject, "description", "command") ?: input.firstLine()
        DshRemoteToolKind.READ -> path ?: inputString(input, argumentsObject, "url") ?: input.firstLine()
        DshRemoteToolKind.FILE_MUTATION -> path ?: input.firstLine()
        DshRemoteToolKind.SEARCH -> inputString(input, argumentsObject, "query", "pattern") ?: input.firstLine()
        DshRemoteToolKind.WEB -> inputString(input, argumentsObject, "query", "url") ?: input.firstLine()
        DshRemoteToolKind.SKILL -> inputString(input, argumentsObject, "name") ?: input.firstLine()
        DshRemoteToolKind.ASK_QUESTION -> "等待回答"
        DshRemoteToolKind.TODO -> {
            val todo = todoSummary(input, argumentsObject)
            todoLabel(todo.done, todo.total, todo.active, todo.extra)
        }
        DshRemoteToolKind.GENERIC -> input.firstLine()
    }

    private fun callBody(kind: DshRemoteToolKind, input: String, view: JSONObject?): String = when (kind) {
        DshRemoteToolKind.BASH -> listOfNotNull(
            view?.optString("description")?.takeIf { it.isNotEmpty() },
            view?.optString("cwd")?.takeIf { it.isNotEmpty() },
            input.takeIf { it.isNotEmpty() },
        ).joinToString("\n")
        DshRemoteToolKind.FILE_MUTATION -> diffBody(view ?: JSONObject()).ifEmpty { input }
        else -> input
    }

    private fun resultBody(
        kind: DshRemoteToolKind,
        card: DshToolCardType,
        view: JSONObject?,
        fallback: String,
    ): String = when (card) {
        DshToolCardType.TERMINAL -> view?.optString("output")?.takeIf { it.isNotEmpty() } ?: fallback
        DshToolCardType.READ -> readBody(view ?: JSONObject()).ifEmpty { fallback }
        DshToolCardType.DIFF -> diffBody(view ?: JSONObject()).ifEmpty { fallback }
        DshToolCardType.SEARCH -> {
            val structured = searchBody(view ?: JSONObject())
            if (view?.optBoolean("truncated") == true && fallback.isNotEmpty()) {
                listOfNotNull(structured.takeIf { it.isNotEmpty() }, fallback).joinToString("\n\n")
            } else structured.ifEmpty { fallback }
        }
        DshToolCardType.WEB -> remoteWebBody(view ?: JSONObject()).ifEmpty { fallback }
        else -> fallback
    }.ifEmpty { fallback }.let { body ->
        if (kind == DshRemoteToolKind.TODO || kind == DshRemoteToolKind.ASK_QUESTION || kind == DshRemoteToolKind.SKILL) fallback else body
    }

    private fun resultPayload(data: JSONObject): ResultPayload {
        val message = data.optJSONObject("message")
        val block = message?.optJSONArray("content")?.optJSONObject(0)
        val callId = block?.optString("toolCallId")?.takeIf { it.isNotEmpty() }
            ?: message?.optJSONObject("source")?.optString("callId")?.takeIf { it.isNotEmpty() }
            ?: data.optString("callId")
        val content = block?.opt("content") ?: data.opt("content")
        val output = toolOutputSummary(content)
        val isError = block?.optBoolean("isError") ?: data.optBoolean("isError")
        return ResultPayload(callId, output, isError)
    }

    private data class ResultPayload(val callId: String, val output: String, val isError: Boolean)
    private data class TodoSummary(val done: Int = 0, val total: Int = 0, val active: String? = null, val extra: Int = 0)
    private data class AnswerSummary(val answered: Int = 0, val total: Int = 0)

    private fun todoSummary(input: String, arguments: JSONObject?): TodoSummary {
        if (arguments != null) return todoSummary(arguments.optJSONArray("todos"))
        val root = parseJson(input) as? JSONObject ?: return TodoSummary()
        val todos = root.optJSONArray("todos") ?: return TodoSummary()
        var done = 0
        val active = mutableListOf<String>()
        for (index in 0 until todos.length()) {
            val item = todos.optJSONObject(index) ?: continue
            val status = item.optString("status")
            if (status == "completed" || status == "done") done++
            else item.optString("content").takeIf { it.isNotEmpty() }?.let { active += it }
        }
        return TodoSummary(done, todos.length(), active.firstOrNull(), (active.size - 1).coerceAtLeast(0))
    }

    private fun todoSummary(todos: JSONArray?): TodoSummary {
        if (todos == null) return TodoSummary()
        var done = 0
        val active = mutableListOf<String>()
        for (index in 0 until todos.length()) {
            val item = todos.optJSONObject(index) ?: continue
            val status = item.optString("status")
            if (status == "completed" || status == "done") done++
            else item.optString("content").takeIf { it.isNotEmpty() }?.let { active += it }
        }
        return TodoSummary(done, todos.length(), active.firstOrNull(), (active.size - 1).coerceAtLeast(0))
    }

    private fun answerSummary(output: String): AnswerSummary {
        val root = dshExtractJsonObject(output) ?: return AnswerSummary()
        val answers = root.optJSONArray("answers") ?: return AnswerSummary()
        var answered = 0
        for (index in 0 until answers.length()) {
            val answer = answers.optJSONObject(index) ?: continue
            val selected = answer.optJSONArray("selected")
            val custom = answer.optString("custom")
            if ((selected != null && selected.length() > 0) || custom.isNotEmpty()) answered++
        }
        return AnswerSummary(answered, answers.length())
    }

    private fun kindFor(name: String): DshRemoteToolKind = when (name.lowercase()) {
        "bash", "pwsh" -> DshRemoteToolKind.BASH
        "read" -> DshRemoteToolKind.READ
        "edit", "write" -> DshRemoteToolKind.FILE_MUTATION
        "grep", "glob" -> DshRemoteToolKind.SEARCH
        "web_search", "web_fetch" -> DshRemoteToolKind.WEB
        "skill" -> DshRemoteToolKind.SKILL
        "ask_user_question" -> DshRemoteToolKind.ASK_QUESTION
        "todo_write" -> DshRemoteToolKind.TODO
        else -> DshRemoteToolKind.GENERIC
    }

    private fun filePath(kind: DshRemoteToolKind, input: String, arguments: JSONObject?): String? {
        if (kind != DshRemoteToolKind.READ && kind != DshRemoteToolKind.FILE_MUTATION) return null
        return inputString(input, arguments, "path", "file_path")
    }

    private fun inputString(input: String, arguments: JSONObject?, vararg keys: String): String? {
        if (arguments != null) {
            keys.forEach { key -> arguments.optString(key).takeIf { it.isNotEmpty() }?.let { return it.firstLine() } }
            return null
        }
        val root = parseJson(input) as? JSONObject ?: return null
        keys.forEach { key -> root.optString(key).takeIf { it.isNotEmpty() }?.let { return it.firstLine() } }
        return null
    }

    private fun parseJson(input: String): Any? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null
        return runCatching {
            if (trimmed.startsWith("[")) JSONArray(trimmed) else JSONObject(trimmed)
        }.getOrNull()
    }

    private fun todoLabel(done: Int, total: Int, active: String?, extra: Int): String {
        if (total <= 0) return "更新任务"
        return buildString {
            append("$done/$total 已完成")
            active?.let { append(" · ").append(it) }
            if (extra > 0) append(" +$extra")
        }
    }

    private fun String.firstLine(): String = lineSequence().firstOrNull().orEmpty()
}

internal fun dshWireEvent(root: JSONObject): JSONObject = root.optJSONObject("event") ?: root

/** Unwrap both history's {view:{view}} and live's {view:{for,view}} forms. */
internal fun dshWireView(root: JSONObject): JSONObject? {
    val wrapper = root.optJSONObject("view") ?: return null
    return wrapper.optJSONObject("view") ?: wrapper
}

/** Tool-card variant of [webBody]: answer + one source per paragraph, for the expanded card body. */
private fun remoteWebBody(view: JSONObject): String = when (view.optString("kind")) {
    "fetch" -> "${view.optString("url")}\nHTTP ${view.optInt("statusCode")}"
    else -> {
        val sources = view.optJSONArray("sources") ?: JSONArray()
        buildString {
            view.optString("answer").takeIf { it.isNotEmpty() }?.let { appendLine(it).appendLine() }
            for (index in 0 until sources.length()) {
                val source = sources.optJSONObject(index) ?: continue
                val title = source.optString("title").ifEmpty { source.optString("url") }
                val url = source.optString("url")
                appendLine(title)
                if (url.isNotEmpty() && url != title) appendLine(url)
                appendLine()
            }
        }.trim()
    }
}

internal fun dshAskReadableBody(input: String, output: String): String {
    val answersRoot = dshExtractJsonObject(output) ?: return ""
    val answers = answersRoot.optJSONArray("answers") ?: return ""
    if (answers.length() == 0) return ""
    val questions = dshExtractJsonObject(input)?.optJSONArray("questions")
    return buildString {
        for (index in 0 until answers.length()) {
            val answer = answers.optJSONObject(index) ?: continue
            val id = answer.optString("id")
            val prompt = dshAskPrompt(questions, id, index)
            val selected = buildString {
                val values = answer.optJSONArray("selected")
                if (values != null) {
                    for (item in 0 until values.length()) {
                        if (isNotEmpty()) append("、")
                        append(values.optString(item))
                    }
                }
            }
            val custom = answer.optString("custom")
            val value = listOf(selected, custom).filter { it.isNotEmpty() }.joinToString(" · ")
            if (prompt.isNotEmpty() && value.isNotEmpty()) appendLine("$prompt：$value")
            else if (value.isNotEmpty()) appendLine(value)
            else if (prompt.isNotEmpty()) appendLine(prompt)
        }
    }.trim()
}

private fun dshAskPrompt(questions: JSONArray?, id: String, index: Int): String {
    if (questions == null) return id
    for (itemIndex in 0 until questions.length()) {
        val item = questions.optJSONObject(itemIndex) ?: continue
        if (id.isNotEmpty() && item.optString("id") == id) {
            return item.optString("prompt").ifEmpty { item.optString("question") }.ifEmpty { id }
        }
    }
    questions.optJSONObject(index)?.let { item ->
        return item.optString("prompt").ifEmpty { item.optString("question") }.ifEmpty { id }
    }
    return id
}

private fun dshExtractJsonObject(raw: String): JSONObject? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    fun parse(text: String) = runCatching { JSONObject(text) }.getOrNull()
    parse(trimmed)?.let { return it }
    val start = trimmed.indexOf('{')
    val end = trimmed.lastIndexOf('}')
    if (start >= 0 && end > start) return parse(trimmed.substring(start, end + 1))
    return null
}
