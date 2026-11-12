package io.github.yukiykchen.dsh.framework

import com.tencent.kuikly.core.log.KLog

/**
 * Production builds only emit structural telemetry. Debug builds may include
 * short content previews for interactive diagnosis, but never credentials.
 */
object DshDiagnostics {
    @Volatile
    var verboseLogging: Boolean = false
        private set

    fun configure(verbose: Boolean) {
        verboseLogging = verbose
    }

    fun info(tag: String, message: String) {
        KLog.i(tag, message)
    }

    /** Content previews are opt-in and are never emitted by release hosts. */
    fun preview(text: String, max: Int = 96): String {
        if (!verboseLogging) return "chars=${text.length}"
        val flat = text.replace("\r", "\\r").replace("\n", "\\n")
        return if (flat.length <= max) flat else "${flat.take(max)}…(+${flat.length - max})"
    }

    fun blocks(blocks: List<com.tencent.kuiklybase.streaming.MarkdownBlock>): String {
        if (blocks.isEmpty()) return "blockCount=0"
        if (!verboseLogging) return "blockCount=${blocks.size}"
        val items = blocks.joinToString("; ") { block ->
            val kind = blockKind(block.blockContent)
            "#${block.blockIndex} kind=$kind id=${block.id} chars=${block.blockContent.length} '${preview(block.blockContent, 48)}'"
        }
        return "blockCount=${blocks.size} [$items]"
    }

    fun blockKind(content: String): String {
        val line = content.trimStart()
        return when {
            line.startsWith("```") -> "code"
            line.startsWith("~~~") -> "code"
            line.startsWith("# ") -> "h1"
            line.startsWith("## ") -> "h2"
            line.startsWith("### ") -> "h3"
            line.startsWith("> ") -> "quote"
            line.startsWith("- ") || line.startsWith("* ") -> "list"
            line.firstOrNull()?.isDigit() == true && line.contains(". ") -> "olist"
            line.startsWith("|") -> "table"
            line.isEmpty() -> "empty"
            else -> "paragraph"
        }
    }

    fun redactedError(message: String): String = "error=${message.length}"
}
