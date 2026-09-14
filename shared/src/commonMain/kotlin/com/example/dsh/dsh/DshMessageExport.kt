package com.example.dsh.dsh

/** One fenced block lifted out of a Markdown message, for "copy code block N". */
internal data class DshCodeBlock(
    val language: String,
    val code: String,
)

/**
 * Turns conversation rows into readable text for the clipboard and the share sheet.
 *
 * Everything here is pure so it can be unit tested. The rules follow Task 2:
 * copied output keeps the original order of text, cards and tool results; structured
 * cards carry their name, status and file path; attachments carry their filename,
 * size and `attachmentId`; and no decorative UI copy leaks in.
 */
internal object DshMessageExport {
    private const val USER_LABEL = "You"
    private const val ASSISTANT_LABEL = "DeepSeek"

    /** Body text for "copy message", without the transcript heading. */
    fun message(message: DshMessage): String = when {
        message.isContextInjection -> contextBlock(message)
        message.role == DshMessageRole.TOOL -> toolBlock(message)
        message.isReasoning -> message.content.trim()
        message.attachmentId != null -> imageLine(message.attachmentId)
        else -> listOfNotNull(
            message.content.trim().takeIf { it.isNotEmpty() },
            attachmentLines(message).takeIf { it.isNotEmpty() },
        ).joinToString("\n\n")
    }

    /** The whole conversation as Markdown, in timeline order. */
    fun conversation(title: String, messages: List<DshMessage>): String = buildString {
        val heading = title.trim().ifEmpty { "DSH conversation" }
        appendLine("# $heading")
        messages.asSequence()
            .filterNot { it.hidden }
            .mapNotNull { transcriptEntry(it) }
            .forEach { entry ->
                appendLine()
                appendLine(entry)
            }
    }.trimEnd() + "\n"

    /** One transcript entry with its role heading, or null when the row carries nothing. */
    fun transcriptEntry(message: DshMessage): String? {
        if (message.hidden) return null
        val body = message(message)
        if (body.isEmpty()) return null
        val heading = when {
            message.isContextInjection -> null
            message.role == DshMessageRole.TOOL -> null
            message.isReasoning -> "## $ASSISTANT_LABEL · reasoning"
            message.role == DshMessageRole.USER -> "## $USER_LABEL"
            message.role == DshMessageRole.ERROR -> "## Error"
            else -> "## $ASSISTANT_LABEL"
        }
        return if (heading == null) body else "$heading\n\n$body"
    }

    /**
     * The same transcript as a self-contained HTML document.
     *
     * The Markdown export is the source of truth for *content*; this only changes the
     * container, so the two stay in step by construction. Styling is inlined because the
     * file has to survive being mailed or opened from Files with no network, and the
     * palette is fixed light so a printed page is not a black rectangle.
     */
    fun html(title: String, messages: List<DshMessage>): String {
        val heading = title.trim().ifEmpty { "DSH conversation" }
        val body = messages.asSequence()
            .filterNot { it.hidden }
            .mapNotNull { message -> htmlEntry(message) }
            .joinToString("\n")
        return buildString {
            appendLine("<!DOCTYPE html>")
            appendLine("<html><head><meta charset=\"utf-8\">")
            appendLine("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
            appendLine("<title>${escape(heading)}</title>")
            appendLine("<style>$HTML_STYLE</style>")
            appendLine("</head><body>")
            appendLine("<h1>${escape(heading)}</h1>")
            appendLine(body)
            appendLine("</body></html>")
        }
    }

    private fun htmlEntry(message: DshMessage): String? {
        val text = message(message)
        if (text.isEmpty()) return null
        val role = when {
            message.isContextInjection -> "context"
            message.role == DshMessageRole.TOOL -> "tool"
            message.isReasoning -> "reasoning"
            message.role == DshMessageRole.USER -> "user"
            message.role == DshMessageRole.ERROR -> "error"
            else -> "assistant"
        }
        val label = when (role) {
            "context" -> "Context"
            "tool" -> "Tool"
            "reasoning" -> "$ASSISTANT_LABEL · reasoning"
            "user" -> USER_LABEL
            "error" -> "Error"
            else -> ASSISTANT_LABEL
        }
        return "<section class=\"turn $role\"><h2>${escape(label)}</h2>${htmlBody(text)}</section>"
    }

    /**
     * Fenced blocks become `<pre>`, everything else becomes a paragraph. This is
     * deliberately not a Markdown renderer: the body already reads as plain text, and a
     * half-implemented one would reorder content the export is required to preserve.
     */
    private fun htmlBody(text: String): String = buildString {
        var inFence = false
        val paragraph = StringBuilder()
        val code = StringBuilder()
        fun flushParagraph() {
            val value = paragraph.toString().trim()
            paragraph.clear()
            if (value.isNotEmpty()) append("<p>${escape(value).replace("\n", "<br>")}</p>")
        }
        text.split('\n').forEach { line ->
            if (fenceMarker(line.trimStart()).isNotEmpty()) {
                if (inFence) {
                    append("<pre><code>${escape(code.toString().trimEnd('\n'))}</code></pre>")
                    code.clear()
                } else {
                    flushParagraph()
                }
                inFence = !inFence
                return@forEach
            }
            if (inFence) code.append(line).append('\n') else paragraph.append(line).append('\n')
        }
        if (inFence && code.isNotEmpty()) {
            append("<pre><code>${escape(code.toString().trimEnd('\n'))}</code></pre>")
        }
        flushParagraph()
    }

    private fun escape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private val HTML_STYLE = """
        body{margin:0 auto;padding:24px;max-width:760px;font:15px/1.6 -apple-system,Roboto,Helvetica,sans-serif;color:#1f1f23;background:#fff}
        h1{font-size:22px;margin:0 0 20px}
        h2{font-size:12px;letter-spacing:.06em;text-transform:uppercase;color:#6e777e;margin:0 0 6px}
        .turn{margin:0 0 20px;padding:12px 14px;border-radius:10px;background:#f7f8fa}
        .turn.user{background:#edf3fe}
        .turn.error{background:#fdeeee}
        .turn.reasoning,.turn.context{background:#fafafa;color:#61666d}
        p{margin:0 0 10px;white-space:pre-wrap}
        pre{margin:0 0 10px;padding:10px 12px;border-radius:8px;background:#f0f1f3;overflow-x:auto}
        code{font:13px/1.5 ui-monospace,Menlo,Consolas,monospace}
        @media print{.turn{background:#fff;border:1px solid #e5e5e5;break-inside:avoid}}
    """.trimIndent()

    /**
     * Fenced code blocks in document order. An unterminated fence still yields its
     * body so a block can be copied while the reply is still streaming.
     */
    fun codeBlocks(markdown: String): List<DshCodeBlock> {
        val blocks = mutableListOf<DshCodeBlock>()
        var fence: String? = null
        var language = ""
        val code = StringBuilder()
        markdown.split('\n').forEach { raw ->
            val trimmed = raw.trimStart()
            val indent = raw.length - trimmed.length
            val marker = fenceMarker(trimmed).takeIf { it.isNotEmpty() && indent <= 3 }
            if (fence == null) {
                if (marker != null) {
                    fence = marker
                    language = trimmed.drop(marker.length).trim()
                    code.clear()
                }
                return@forEach
            }
            val open = fence ?: return@forEach
            if (marker != null && marker[0] == open[0] && marker.length >= open.length &&
                trimmed.drop(marker.length).isBlank()
            ) {
                blocks += DshCodeBlock(language, code.toString().trimEnd('\n'))
                fence = null
                return@forEach
            }
            code.append(raw).append('\n')
        }
        if (fence != null && code.isNotEmpty()) {
            blocks += DshCodeBlock(language, code.toString().trimEnd('\n'))
        }
        return blocks
    }

    /** `backticks`/`tildes` run that opens or closes a fence, or "" when the line is not one. */
    private fun fenceMarker(trimmed: String): String {
        val char = trimmed.firstOrNull() ?: return ""
        if (char != '`' && char != '~') return ""
        val run = trimmed.takeWhile { it == char }
        return if (run.length >= 3) run else ""
    }

    private fun toolBlock(message: DshMessage): String {
        val tool = message.remoteTool
        val name = tool?.title ?: message.toolName ?: "Tool"
        val summary = tool?.summary.orEmpty().ifEmpty { tool?.filePath.orEmpty() }
        val status = when {
            message.toolError -> "failed"
            message.toolRunning -> "running"
            else -> "completed"
        }
        val header = buildString {
            append("## Tool · ").append(name)
            if (summary.isNotEmpty()) append(" — ").append(summary)
            append(" (").append(status).append(")")
        }
        val body = (tool?.output?.takeIf { it.isNotEmpty() }
            ?: tool?.body?.takeIf { it.isNotEmpty() }
            ?: tool?.input?.takeIf { it.isNotEmpty() }
            ?: message.content).trim()
        val error = tool?.error?.trim().orEmpty()
        return buildString {
            append(header)
            if (body.isNotEmpty()) {
                append("\n\n```\n").append(body).append("\n```")
            }
            if (error.isNotEmpty() && error != body) {
                append("\n\nError: ").append(error)
            }
        }
    }

    private fun contextBlock(message: DshMessage): String {
        val source = message.toolName.orEmpty().ifEmpty { message.contextForm }
        val header = if (source.isEmpty()) "## Context" else "## Context · $source"
        val body = when {
            message.contextCatalog.isNotEmpty() ->
                message.contextCatalog.joinToString("\n") { "${it.name}: ${it.description}" }
            message.contextSections.isNotEmpty() ->
                message.contextSections.joinToString("\n\n") { "${it.title}\n${it.body}" }
            message.contextInstructions.isNotEmpty() ->
                message.contextInstructions.joinToString("\n") { "${it.path} · ${it.action}" }
            else -> message.contextBody
        }.trim()
        return if (body.isEmpty()) header else "$header\n\n$body"
    }

    private fun attachmentLines(message: DshMessage): String =
        message.attachments.joinToString("\n") { attachmentLine(it) }

    /**
     * An image the user attached. A reference the Host has committed carries its
     * `attachmentId`; one that is still on its way out carries its status instead, so
     * an exported transcript never claims an attachment address that does not exist.
     */
    private fun attachmentLine(ref: DshImageAttachmentRef): String = buildString {
        append("[image: ")
        append(ref.name?.takeIf { it.isNotEmpty() } ?: ref.mediaType)
        if (ref.width > 0 && ref.height > 0) append(", ").append(ref.width).append("×").append(ref.height)
        if (ref.bytes > 0) append(", ").append(dshFormatBytes(ref.bytes))
        if (ref.attachmentId.isNotEmpty()) {
            append(", attachment ").append(ref.attachmentId)
        } else {
            append(", not yet stored by the Host")
        }
        append("]")
    }

    private fun imageLine(attachmentId: String): String = "[image: attachment $attachmentId]"
}
