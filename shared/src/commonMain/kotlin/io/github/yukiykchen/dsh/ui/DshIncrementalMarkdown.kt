package io.github.yukiykchen.dsh.ui

import com.tencent.kuikly.core.views.List
import com.tencent.kuiklybase.parser.MarkdownParseResult
import com.tencent.kuiklybase.parser.parseMarkdown
import com.tencent.kuiklybase.streaming.MarkdownBlock
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import io.github.yukiykchen.dsh.framework.DshStreamLog
import io.github.yukiykchen.dsh.framework.DshDiagnostics

/**
 * One parsed block with its own AST snapshot. Sealed slices keep the parse from
 * the frame they froze; the live slice is replaced every flush.
 */

data class DshMarkdownSlice(
    val id: String,
    val blockIndex: Int,
    val blockContent: String,
    val parseResult: MarkdownParseResult,
    val node: ASTNode,
) {
    fun toBlock(): MarkdownBlock = MarkdownBlock(
        id = id,
        blockContent = blockContent,
        blockIndex = blockIndex,
    )
}

/**
 * Streamdown-style wrapper around IntelliJ Markdown: freeze completed blocks,
 * parse only the unsealed tail. Fence closing is display-only and never seals.
 */

class DshIncrementalMarkdownState(
    val parse: (String) -> MarkdownParseResult = { parseMarkdown(it) },
) {
    val sealed = mutableListOf<DshMarkdownSlice>()
    var live: DshMarkdownSlice? = null
    var sealedPrefix: String = ""
    var lastRaw: String = ""
    var lastStreaming: Boolean = false
    var parseCount: Int = 0
        private set
    val sealedCharCount: Int
        get() = sealedPrefix.length
    fun slice(id: String): DshMarkdownSlice? {
        if (live?.id == id) return live
        return sealed.firstOrNull { it.id == id }
    }
    fun reset() {
        if (sealed.isNotEmpty() || live != null || lastRaw.isNotEmpty() || parseCount > 0) {
            DshStreamLog.i(
                "parse.reset droppedSealed=${sealed.size} hadLive=${live != null} parseCount=$parseCount sealedChars=${sealedPrefix.length}",
            )
        }
        sealed.clear()
        live = null
        sealedPrefix = ""
        lastRaw = ""
        lastStreaming = false
        parseCount = 0
    }
    fun update(raw: String, streaming: Boolean, force: Boolean = false): List<MarkdownBlock>? {
        val input = if (raw.isEmpty() && streaming) DshStreamingMarkdown.PLACEHOLDER else raw
        if (!force && input == lastRaw && streaming == lastStreaming) {
            DshStreamLog.i("parse.skip identical streaming=$streaming chars=${input.length}")
            return null
        }

        if (input.isBlank() && !streaming) {
            DshStreamLog.i("parse.skip blank-end")
            reset()
            lastRaw = input
            lastStreaming = streaming
            return emptyList()
        }
        val resync = resyncReason(input)
        if (resync != null) {
            DshStreamLog.i(
                "parse.resync reason=$resync droppedSealed=${sealed.size} sealedChars=${sealedPrefix.length} full=${input.length}",
            )
            sealed.clear()
            live = null
            sealedPrefix = ""
        }
        val tailRaw = input.substring(sealedPrefix.length)
        val toParse = if (streaming) DshStreamingMarkdown.closeOpenFence(tailRaw) else tailRaw
        val fenceClosed = streaming && toParse.length != tailRaw.length
        if (toParse.isBlank()) {
            live = null
            lastRaw = input
            lastStreaming = streaming
            DshStreamLog.i(
                "parse.flush streaming=$streaming force=$force full=${input.length} sealedChars=${sealedPrefix.length} tail=0 parsed=0 parse#=$parseCount freeze=0 sealedBlocks=${sealed.size} live=none",
            )
            return emit()
        }

        parseCount++
        val sealedBefore = sealed.size
        val sealedCharsBefore = sealedPrefix.length
        val result = parse(toParse)
        val nodes = blockNodes(result)
        if (nodes.isEmpty()) {
            live = null
            lastRaw = input
            lastStreaming = streaming
            DshStreamLog.i(
                "parse.flush streaming=$streaming force=$force full=${input.length} sealedChars=${sealedPrefix.length} tail=${tailRaw.length} parsed=${toParse.length} parse#=$parseCount freeze=0 nodes=0 live=none parsed=${DshDiagnostics.preview(toParse)}",
            )
            return emit()
        }
        val freezeCount = if (streaming) nodes.lastIndex else nodes.size
        val newlySealed = mutableListOf<String>()
        if (freezeCount > 0) {
            val liveStart = if (freezeCount < nodes.size) {
                nodes[freezeCount].startOffset
            } else {
                toParse.length
            }
            for (i in 0 until freezeCount) {
                val node = nodes[i]
                val content = result.content.substring(node.startOffset, node.endOffset)
                val id = "s${sealed.size}"
                sealed += DshMarkdownSlice(
                    id = id,
                    blockIndex = sealed.size,
                    blockContent = content,
                    parseResult = result,
                    node = node,
                )
                newlySealed += "$id:${DshStreamLog.blockKind(content)}:${content.length}"
            }
            sealedPrefix += tailRaw.substring(0, minOf(liveStart, tailRaw.length))
        }

        live = if (freezeCount < nodes.size) {
            val node = nodes[freezeCount]
            DshMarkdownSlice(
                id = LIVE_ID,
                blockIndex = sealed.size,
                blockContent = result.content.substring(node.startOffset, node.endOffset),
                parseResult = result,
                node = node,
            )
        } else {
            null
        }

        lastRaw = input
        lastStreaming = streaming
        val liveSlice = live
        val ratio = if (input.isEmpty()) 0 else (toParse.length * 100) / input.length
        DshStreamLog.i(
            "parse.flush streaming=$streaming force=$force full=${input.length} " +
                "sealedChars=$sealedCharsBefore→${sealedPrefix.length} tail=${tailRaw.length} " +
                "parsed=${toParse.length}($ratio% of full) fenceClosed=$fenceClosed parse#=$parseCount " +
                "nodes=${nodes.size} freeze=$freezeCount sealed=${sealedBefore}→${sealed.size} " +
                "newSealed=[${newlySealed.joinToString(",")}] " +
                "live=${liveSlice?.let { "${DshStreamLog.blockKind(it.blockContent)}:${it.blockContent.length}" } ?: "none"} " +
                "parsed=${DshDiagnostics.preview(toParse)} live=${DshDiagnostics.preview(liveSlice?.blockContent ?: "")}",
        )
        return emit()
    }

    fun emit(): List<MarkdownBlock> =
        (sealed + listOfNotNull(live)).map { it.toBlock() }

    fun resyncReason(input: String): String? {
        if (sealedPrefix.isEmpty()) return null
        if (!input.startsWith(sealedPrefix)) return "prefix-mismatch"
        if (LINK_DEF.containsMatchIn(input.substring(sealedPrefix.length))) return "link-definition"
        return null
    }

    companion object {
        const val LIVE_ID = "live"

        val LINK_DEF = Regex("""^\s*\[[^\]\n]+]:""", RegexOption.MULTILINE)

        fun blockNodes(result: MarkdownParseResult): List<ASTNode> =
            result.node.children.filter { child ->
                child.type != MarkdownTokenTypes.EOL
            }
    }
}
