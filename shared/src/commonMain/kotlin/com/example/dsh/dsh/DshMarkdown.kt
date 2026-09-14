package com.example.dsh.dsh

import com.tencent.kuikly.core.base.Border
import com.tencent.kuikly.core.base.BorderStyle
import com.tencent.kuikly.core.base.ComposeAttr
import com.tencent.kuikly.core.base.ComposeEvent
import com.tencent.kuikly.core.base.ComposeView
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.directives.vbind
import com.tencent.kuikly.core.directives.vfor
import com.tencent.kuikly.core.reactive.ReactiveObserver
import com.tencent.kuikly.core.reactive.handler.*
import com.tencent.kuikly.core.timer.setTimeout
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View
import com.tencent.kuiklybase.KuiklyStreamingMarkdown
import com.tencent.kuiklybase.config.FontWeight
import com.tencent.kuiklybase.config.MarkdownColors
import com.tencent.kuiklybase.config.MarkdownConfig
import com.tencent.kuiklybase.config.MarkdownDimens
import com.tencent.kuiklybase.config.MarkdownTypography
import com.tencent.kuiklybase.config.TextStyleConfig
import com.tencent.kuiklybase.streaming.MarkdownBlock
import com.tencent.kuiklybase.streaming.MarkdownStreamingState

/** DSH theme wrapper around KuiklyMarkdown's streaming renderer. */
internal class DshMarkdownView : ComposeView<DshMarkdownAttr, ComposeEvent>() {
    private val streamingState = MarkdownStreamingState()
    private var blockList by observableList<MarkdownBlock>()
    private var blockCount by observable(0)
    private var treeEpoch by observable(0)
    private var liveKey by observable("")
    private var lastContent = ""
    private var lastStreaming = false
    private var pendingContent = ""
    private var pendingStreaming = false
    private var flushScheduled = false
    private var lastDarkMode: Boolean? = null
    private val paletteObserver = Any()

    override fun createAttr(): DshMarkdownAttr = DshMarkdownAttr()

    override fun createEvent(): ComposeEvent = ComposeEvent()

    override fun body(): ViewBuilder {
        val ctx = this
        return {
            View {
                attr {
                    if (ctx.attr.contentWidth > 0f) {
                        width(ctx.attr.contentWidth)
                    }
                }
                // vforLazy will not create later siblings of a nested vfor
                // after the row is already built (paragraph stays, code fence
                // never mounts). Remount when the block count changes so
                // KuiklyStreamingMarkdown can paint every block, including
                // the component's own codeFence.
                vbind({ ctx.treeEpoch }) {
                    vfor({ ctx.blockList }) { block ->
                        View {
                            attr {
                                if (ctx.attr.contentWidth > 0f) {
                                    width(ctx.attr.contentWidth)
                                }
                            }
                            vbind({
                                val live = block.blockIndex == ctx.blockCount - 1
                                if (live) ctx.liveKey else block.id
                            }) {
                                // KuiklyStreamingMarkdown paints a block as one opaque
                                // unit, so a standalone `$$...$$` gets a DSH-owned view
                                // instead — which is also what "laid out independently"
                                // asks for. Inline formulas are substituted before the
                                // parser runs, in flushBlocksUpdate.
                                val formula = DshLatex.blockFormula(block.blockContent)
                                if (formula == null) {
                                    KuiklyStreamingMarkdown(
                                        state = ctx.streamingState,
                                        block = block,
                                        config = ctx.markdownConfig(),
                                    )
                                } else {
                                    DshLatexBlock(formula, ctx.attr.contentWidth)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun viewDidLoad() {
        super.viewDidLoad()
        ReactiveObserver.bindValueChange(this) {
            val live = attr.liveContent
            val content = live?.invoke() ?: attr.content
            val streaming = attr.streamingProvider?.invoke() ?: attr.streaming
            ReactiveObserver.addLazyTaskUtilEndCollectDependency {
                scheduleBlocksUpdate(content, streaming)
            }
        }
        // markdownConfig() is read inside the vbind creator, which is not reactive.
        // Remount the block tree when the palette flips so the new config is applied.
        ReactiveObserver.bindValueChange(paletteObserver) {
            val dark = attr.darkMode
            ReactiveObserver.addLazyTaskUtilEndCollectDependency {
                if (lastDarkMode != dark) {
                    lastDarkMode = dark
                    treeEpoch += 1
                }
            }
        }
    }

    override fun viewWillUnload() {
        ReactiveObserver.unbindValueChange(paletteObserver)
        ReactiveObserver.unbindValueChange(this)
        super.viewWillUnload()
    }

    private fun scheduleBlocksUpdate(content: String, streaming: Boolean) {
        pendingContent = content
        pendingStreaming = streaming
        if (!streaming || lastContent.isEmpty() || lastContent == DshStreamingMarkdown.PLACEHOLDER) {
            flushBlocksUpdate()
            return
        }
        if (flushScheduled) return
        flushScheduled = true
        setTimeout(pagerId, DshStreamingMarkdown.FRAME_MS) {
            flushScheduled = false
            flushBlocksUpdate()
        }
    }

    private fun flushBlocksUpdate() {
        val content = pendingContent
        val streaming = pendingStreaming
        if (content == lastContent && streaming == lastStreaming) return
        val endingStream = lastStreaming && !streaming
        if (content.isEmpty() && lastContent.isNotEmpty()) {
            DshStreamLog.i(
                "render.skip empty-wipe streaming=$streaming prevChars=${lastContent.length} prevBlocks=${blockList.size}",
            )
            lastStreaming = streaming
            return
        }
        if (
            lastContent.isNotEmpty() &&
            content.length < lastContent.length &&
            lastContent.startsWith(content)
        ) {
            DshStreamLog.i(
                "render.skip shrink streaming=$streaming prevChars=${lastContent.length} nextChars=${content.length}",
            )
            lastStreaming = streaming
            return
        }
        if (streaming && !lastStreaming) {
            DshStreamLog.i("render.stream-start chars=${content.length}")
            streamingState.reset()
            blockList.clear()
            blockCount = 0
            liveKey = ""
            treeEpoch += 1
        }
        lastContent = content
        lastStreaming = streaming
        val input = if (content.isEmpty() && streaming) DshStreamingMarkdown.PLACEHOLDER else content
        val toParse = if (streaming) DshStreamingMarkdown.closeOpenFence(input) else input
        val next = streamingState.update(DshLatex.substituteInline(toParse), force = !streaming)
        if (next == null) {
            DshStreamLog.i("render.skip parser-null streaming=$streaming chars=${content.length}")
            return
        }
        val previousCount = blockList.size
        blockList.diffUpdate(next) { old, new -> old.id == new.id }
        val countChanged = blockCount != blockList.size
        if (countChanged) {
            blockCount = blockList.size
        }
        val newLiveKey = next.lastOrNull()?.id.orEmpty()
        if (liveKey != newLiveKey) {
            liveKey = newLiveKey
        }
        if (countChanged || endingStream) {
            treeEpoch += 1
        }
        flexNode.markDirty()
        DshStreamLog.i(
            "render.apply streaming=$streaming uiBlocks=$previousCount→${blockList.size} ${DshStreamLog.blocks(next)}",
        )
    }

    private fun markdownConfig(): MarkdownConfig {
        val dark = attr.darkMode
        val text = if (dark) 0xFFF5F6F7 else 0xFF1F1F23
        return MarkdownConfig(
            colors = MarkdownColors(
                text = text,
                codeBackground = if (dark) 0xFF242528 else 0xFFF9FAFB,
                inlineCodeBackground = if (dark) 0xFF34363A else 0xFFEBEEF2,
                dividerColor = if (dark) 0xFF45474B else 0xFFE5E5E5,
                tableBackground = if (dark) 0xFF202124 else 0xFFFAFAFA,
                blockQuoteBar = if (dark) 0xFF858990 else 0xFFA2A4A8,
                blockQuoteBackground = if (dark) 0xFF242528 else 0xFFF5F6F7,
                linkColor = if (dark) 0xFF78A4F8 else 0xFF4176E6,
                // KuiklyMarkdown 1.0.6 paints inline code spans with this same colour and
                // drops their background chip, so it has to contrast with the message
                // surface. Fenced blocks therefore cannot run a theme of their own here;
                // the independent code theme covers the DSH tool listings instead.
                codeText = text,
            ),
            typography = MarkdownTypography(
                text = TextStyleConfig(fontSize = 15f, color = text, lineHeight = 23f),
                paragraph = TextStyleConfig(fontSize = 15f, color = text, lineHeight = 23f),
                code = TextStyleConfig(fontSize = 13f, fontFamily = "monospace", lineHeight = 19f),
                inlineCode = TextStyleConfig(fontSize = 13f, fontFamily = "monospace"),
                h1 = TextStyleConfig(fontSize = 24f, fontWeight = FontWeight.Bold, color = text, lineHeight = 30f),
                h2 = TextStyleConfig(fontSize = 20f, fontWeight = FontWeight.Bold, color = text, lineHeight = 26f),
                h3 = TextStyleConfig(fontSize = 18f, fontWeight = FontWeight.SemiBold, color = text, lineHeight = 24f),
                h4 = TextStyleConfig(fontSize = 16f, fontWeight = FontWeight.SemiBold, color = text, lineHeight = 22f),
                h5 = TextStyleConfig(fontSize = 15f, fontWeight = FontWeight.SemiBold, color = text, lineHeight = 21f),
                h6 = TextStyleConfig(fontSize = 15f, fontWeight = FontWeight.SemiBold, color = text, lineHeight = 21f),
                quote = TextStyleConfig(fontSize = 15f, color = if (dark) 0xFFB7BBC2 else 0xFF61666D, lineHeight = 22f),
                ordered = TextStyleConfig(fontSize = 15f, color = text, lineHeight = 23f),
                bullet = TextStyleConfig(fontSize = 15f, color = text, lineHeight = 23f),
                list = TextStyleConfig(fontSize = 15f, color = text, lineHeight = 23f),
                table = TextStyleConfig(fontSize = 13f, color = text, lineHeight = 19f),
                textLink = TextStyleConfig(fontSize = 15f, color = if (dark) 0xFF78A4F8 else 0xFF4176E6, lineHeight = 23f),
            ),
            dimens = MarkdownDimens(
                dividerThickness = 1f,
                codeBackgroundCornerSize = 8f,
                blockQuoteThickness = 3f,
                blockQuoteCornerSize = 6f,
                tableCellWidth = 136f,
                tableCellPadding = 10f,
                tableCornerSize = 8f,
            ),
            codeHighlightDarkTheme = dark,
            codeHighlightEnabled = true,
            padding = com.tencent.kuiklybase.config.MarkdownPadding(
                block = 6f,
                list = 6f,
                listItemTop = 2f,
                listItemBottom = 2f,
                listIndent = 18f,
                codeBlock = 12f,
                blockQuotePaddingLeft = 12f,
                blockQuoteBarPaddingLeft = 4f,
                blockQuoteTextVertical = 8f,
            ),
        )
    }
}

/**
 * A standalone `$$...$$` formula on its own row. Rows come from [DshLatex.renderLines],
 * which yields one line for an expression and one per row for a matrix; a formula the
 * converter cannot render shows its LaTeX source instead of failing.
 */
internal fun ViewContainer<*, *>.DshLatexBlock(source: String, contentWidth: Float) {
    val rendered = DshLatex.renderLines(source)
    View {
        attr {
            flexDirectionColumn()
            alignItemsCenter()
            if (contentWidth > 0f) width(contentWidth)
            marginTop(6f)
            marginBottom(6f)
            paddingTop(12f)
            paddingBottom(12f)
            borderRadius(8f)
            backgroundColor(theme.codeBackground)
            border(Border(1f, BorderStyle.SOLID, theme.border))
        }
        Text {
            attr {
                text(rendered.lines.joinToString("\n"))
                fontSize(if (rendered.fallback) 13f else 16f)
                lineHeight(if (rendered.fallback) 19f else 26f)
                fontFamily("monospace")
                color(if (rendered.fallback) theme.codeTextMuted else theme.codeText)
                textAlignCenter()
            }
        }
    }
}

internal class DshMarkdownAttr : ComposeAttr() {
    var contentWidth: Float by observable(0f)
    var content: String by observable("")
    var streaming: Boolean by observable(false)
    var darkMode: Boolean by observable(false)
    var liveContent: (() -> String)? = null
    var streamingProvider: (() -> Boolean)? = null
}

internal fun ViewContainer<*, *>.DshMarkdown(init: DshMarkdownView.() -> Unit) {
    addChild(DshMarkdownView(), init)
}
