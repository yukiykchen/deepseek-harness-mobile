package io.github.yukiykchen.dsh.ui

import com.tencent.kuikly.core.base.*
import com.tencent.kuikly.core.base.ComposeAttr
import com.tencent.kuikly.core.base.ComposeEvent
import com.tencent.kuikly.core.base.ComposeView
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.directives.vfor
import com.tencent.kuikly.core.directives.vforLazy
import com.tencent.kuikly.core.directives.vbind
import com.tencent.kuikly.core.reactive.ReactiveObserver
import com.tencent.kuikly.core.reactive.handler.*
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.reactive.handler.observableList
import com.tencent.kuikly.core.timer.setTimeout
import com.tencent.kuikly.core.views.*
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
import io.github.yukiykchen.dsh.framework.DshStreamLog
import io.github.yukiykchen.dsh.framework.DshDiagnostics
import io.github.yukiykchen.dsh.framework.setTimeout

/** DSH theme wrapper around KuiklyMarkdown's streaming renderer. */

class DshMarkdownView : ComposeView<DshMarkdownAttr, ComposeEvent>() {
    val streamingState = MarkdownStreamingState()
    var blockList by observableList<MarkdownBlock>()
    var blockCount by observable(0)
    var treeEpoch by observable(0)
    var liveKey by observable("")
    var lastContent = ""
    var lastStreaming = false
    var pendingContent = ""
    var pendingStreaming = false
    var flushScheduled = false

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
                                KuiklyStreamingMarkdown(
                                    state = ctx.streamingState,
                                    block = block,
                                    config = ctx.markdownConfig(),
                                )
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
    }

    override fun viewWillUnload() {
        ReactiveObserver.unbindValueChange(this)
        super.viewWillUnload()
    }

    fun scheduleBlocksUpdate(content: String, streaming: Boolean) {
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

    fun flushBlocksUpdate() {
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
        val next = streamingState.update(toParse, force = !streaming)
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
            "render.apply streaming=$streaming uiBlocks=$previousCount→${blockList.size} ${DshDiagnostics.blocks(next)}",
        )
    }

    fun markdownConfig(): MarkdownConfig {
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

class DshMarkdownAttr : ComposeAttr() {
    var contentWidth: Float by observable(0f)
    var content: String by observable("")
    var streaming: Boolean by observable(false)
    var darkMode: Boolean by observable(false)
    var liveContent: (() -> String)? = null
    var streamingProvider: (() -> Boolean)? = null
}

fun ViewContainer<*, *>.DshMarkdown(init: DshMarkdownView.() -> Unit) {
    addChild(DshMarkdownView(), init)
}
