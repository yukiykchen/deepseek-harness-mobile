package io.github.yukiykchen.dsh.feature.home

import com.tencent.kuikly.core.base.*
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.layout.FlexAlign
import com.tencent.kuikly.core.reactive.handler.*
import com.tencent.kuikly.core.views.*
import com.tencent.kuikly.core.views.Image
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View
import io.github.yukiykchen.dsh.ui.DshMarkdown
import io.github.yukiykchen.dsh.ui.iconAsset
import io.github.yukiykchen.dsh.ui.DshStreamingMarkdown
import io.github.yukiykchen.dsh.ui.DshDisclosureRow
import io.github.yukiykchen.dsh.model.DshMessageRole
import io.github.yukiykchen.dsh.model.DshMessage
import io.github.yukiykchen.dsh.model.DshToolCardType
import io.github.yukiykchen.dsh.model.DshRemoteToolKind
import io.github.yukiykchen.dsh.model.dshAskReadableBody
import io.github.yukiykchen.dsh.data.dshLooksLikeJson
import io.github.yukiykchen.dsh.data.dshReasoningSummary
import io.github.yukiykchen.dsh.data.boundedContextText
import io.github.yukiykchen.dsh.data.contextCanExpand

fun ViewContainer<*, *>.DshMessageRow(
    message: DshMessage,
    pageStreaming: () -> Boolean,
    isExpanded: () -> Boolean,
    onToggle: () -> Unit,
    isBodyExpanded: () -> Boolean = { false },
    onToggleBody: () -> Unit = {},
    isJsonNodeExpanded: (String) -> Boolean = { false },
    onToggleJsonNode: (String) -> Unit = {},
    onCopyToolContent: (String) -> Unit = {},
    attachmentDataUrl: (String) -> String? = { null },
    contentProvider: (() -> String)? = null,
) {
    if (message.hidden) return
    val isUser = message.role == DshMessageRole.USER
    val isError = message.role == DshMessageRole.ERROR
    val renderedContent = contentProvider?.invoke() ?: message.content
    if (
        message.role == DshMessageRole.ASSISTANT &&
        !message.isReasoning &&
        pageStreaming() &&
        renderedContent.isEmpty()
    ) {
        return
    }
    if (message.isContextInjection) {
        View {
            attr {
                width(pagerData.pageViewWidth - 36f)
                marginBottom(12f)
            }
            DshDisclosureRow {
                attr {
                    title = "上下文注入"
                    iconAsset = "context.svg"
                    summary = message.toolName.orEmpty()
                    body = if (message.contextCatalog.isNotEmpty()) {
                        message.contextCatalog.joinToString("\n") { "${it.name}\n${it.description}" }
                    } else if (message.contextSections.isNotEmpty()) {
                        message.contextSections.joinToString("\n\n") {
                            "${it.title}\n${boundedContextText(it.body)}"
                        }
                    } else if (message.contextRecalls.isNotEmpty()) {
                        message.contextRecalls.joinToString("\n") {
                            "${it.label} · 保留 ${it.retainedMessages} · 省略 ${it.omittedMessages}${if (it.truncated) " · 已截断" else ""}"
                        } + "\n\n" + boundedContextText(message.contextBody)
                    } else if (message.contextInstructions.isNotEmpty()) {
                        message.contextInstructions.joinToString("\n") { "${it.path} · ${it.action}" } +
                            "\n\n" + boundedContextText(message.contextBody)
                    } else if (message.contextRelaySender.isNotEmpty()) {
                        "来自 ${message.contextRelaySender}\n\n${boundedContextText(message.contextBody)}"
                    } else {
                        boundedContextText(message.contextBody)
                    }
                    open = isExpanded()
                    expandable = message.contextCanExpand()
                    this.onToggle = onToggle
                    bodyExpanded = isBodyExpanded()
                    this.onToggleBody = onToggleBody
                    maxBodyLines = 8
                }
            }
        }
        return
    }
    if (message.attachmentId != null) {
        val dataUrl = attachmentDataUrl(message.attachmentId)
        View {
            attr {
                width((pagerData.pageViewWidth - 36f).coerceAtLeast(0f))
                height(220f)
                marginBottom(12f)
                borderRadius(8f)
                backgroundColor(Color(0xFFF6F8FA))
                border(Border(1f, BorderStyle.SOLID, Color(0xFFE4E8EC)))
                justifyContentCenter()
                alignItemsCenter()
            }
            if (dataUrl != null) {
                Image {
                    attr {
                        src(dataUrl)
                        width((pagerData.pageViewWidth - 40f).coerceAtLeast(0f))
                        height(216f)
                        resizeCover()
                    }
                }
            } else {
                Text {
                    attr {
                        text("图片加载中")
                        fontSize(12f)
                        color(Color(0xFF7A838A))
                    }
                }
            }
        }
        return
    }
    if (message.isReasoning) {
        View {
            attr {
                width(pagerData.pageViewWidth - 36f)
                marginBottom(12f)
            }
            DshDisclosureRow {
                attr {
                    title = "Think"
                    iconAsset = "think.svg"
                    summary = message.content.dshReasoningSummary(message.streaming)
                    body = message.content
                    open = isExpanded()
                    expandable = message.content.isNotEmpty()
                    this.onToggle = onToggle
                    bodyExpanded = isBodyExpanded()
                    this.onToggleBody = onToggleBody
                    maxBodyLines = 8
                }
            }
        }
        return
    }
    if (message.remoteTool?.kind == DshRemoteToolKind.SKILL) {
        val remoteTool = message.remoteTool
        View {
            attr {
                width((pagerData.pageViewWidth - 36f).coerceAtLeast(0f))
                marginBottom(12f)
            }
            DshDisclosureRow {
                attr {
                    title = "Skill"
                    iconAsset = "tool-skill.svg"
                    summary = remoteTool.summary
                    errorSummary = message.toolError
                    body = message.content
                    open = isExpanded()
                    expandable = message.content.isNotEmpty()
                    this.onToggle = onToggle
                    bodyExpanded = isBodyExpanded()
                    this.onToggleBody = onToggleBody
                    maxBodyLines = 8
                    chrome = true
                    running = message.toolRunning
                }
            }
        }
        return
    }
    if (message.role == DshMessageRole.TOOL) {
        val remoteTool = message.remoteTool
        val isRemoteSpecial = remoteTool?.kind == DshRemoteToolKind.ASK_QUESTION ||
            remoteTool?.kind == DshRemoteToolKind.TODO
        val rawBody = remoteTool?.output?.takeIf { it.isNotEmpty() }
            ?: remoteTool?.body?.takeIf { it.isNotEmpty() }
            ?: remoteTool?.input?.takeIf { it.isNotEmpty() }
            ?: message.content
        val toolBody = if (remoteTool?.kind == DshRemoteToolKind.ASK_QUESTION) {
            dshAskReadableBody(remoteTool.input, rawBody).ifEmpty { "已回答" }
        } else {
            rawBody
        }
        val trimmedBody = toolBody.trimStart()
        val isJson = !isRemoteSpecial &&
            (trimmedBody.startsWith("{") || trimmedBody.startsWith("["))
        val cardLabel = remoteTool?.title ?: when (message.toolCardType) {
            DshToolCardType.TERMINAL -> "Bash"
            DshToolCardType.READ -> "Read"
            DshToolCardType.DIFF -> "Diff"
            DshToolCardType.SEARCH -> "Search"
            DshToolCardType.WEB -> "Web"
            DshToolCardType.JSON -> "JSON"
            DshToolCardType.GENERIC -> message.toolName ?: "工具"
        }
        val summary = remoteTool?.summary?.takeUnless { it.dshLooksLikeJson() }
            ?: if (remoteTool?.kind == DshRemoteToolKind.ASK_QUESTION) "已完成" else
                toolBody.lineSequence().firstOrNull().orEmpty().takeUnless { it.dshLooksLikeJson() }.orEmpty()
        View {
            attr {
                width((pagerData.pageViewWidth - 36f).coerceAtLeast(0f))
                marginBottom(12f)
            }
            DshDisclosureRow {
                attr {
                    title = if (cardLabel.dshLooksLikeJson()) (remoteTool?.toolName ?: "工具") else cardLabel
                    iconAsset = remoteTool?.iconAsset() ?: message.toolCardType.iconAsset()
                    this.summary = summary
                    errorSummary = message.toolError
                    body = if (isJson) "" else toolBody
                    jsonContent = if (isJson) toolBody else ""
                    open = isExpanded()
                    expandable = true
                    this.onToggle = onToggle
                    bodyExpanded = isBodyExpanded()
                    this.onToggleBody = onToggleBody
                    maxBodyLines = 8
                    this.isJsonNodeExpanded = isJsonNodeExpanded
                    this.onToggleJsonNode = onToggleJsonNode
                    chrome = true
                    running = message.toolRunning
                }
            }
        }
        return
    }
        View {
            attr {
                flexDirectionColumn()
                alignItems(if (isUser) FlexAlign.FLEX_END else FlexAlign.FLEX_START)
                marginBottom(18f)
        }
        Text {
            attr {
                text(when (message.role) {
                    DshMessageRole.USER -> "你"
                    DshMessageRole.TOOL -> message.toolName ?: "工具"
                    DshMessageRole.ERROR -> "错误"
                    DshMessageRole.ASSISTANT -> "DeepSeek"
                })
                fontSize(11f)
                color(Color(if (isError) 0xFFC23B3B else 0xFF84939D))
                marginBottom(5f)
            }
        }
        View {
            attr {
                if (!isUser && !isError) {
                    width((pagerData.pageViewWidth - 36f).coerceAtMost(620f).coerceAtLeast(0f))
                }
                maxWidth(620f)
                padding(if (isUser) 10f else 0f, if (isUser) 14f else 0f, if (isUser) 10f else 0f, if (isUser) 14f else 0f)
                borderRadius(if (isUser) 18f else 0f)
                backgroundColor(Color(
                    when {
                        isUser -> 0xFFEDF3FE
                        isError -> 0xFFFFEEEE
                        else -> 0x00FFFFFF
                    },
                ))
            }
            if (isUser || isError) {
                Text {
                    attr {
                        text(message.content)
                        lines(Int.MAX_VALUE)
                        fontSize(15f)
                        color(Color(if (isUser) 0xFF34415B else 0xFFB53232))
                    }
                }
            } else {
                View {
                    attr {
                        flexDirectionColumn()
                    }
                    DshMarkdown {
                        attr {
                            contentWidth = (pagerData.pageViewWidth - 36f).coerceAtLeast(0f)
                            val raw = contentProvider?.invoke() ?: message.content
                            val live = pageStreaming()
                            content = raw
                            liveContent = contentProvider
                            streamingProvider = pageStreaming
                            streaming = live
                            darkMode = false
                        }
                    }
                    vif({ pageStreaming() && (contentProvider?.invoke() ?: message.content).isNotEmpty() }) {
                        Text {
                            attr {
                                text(DshStreamingMarkdown.CURSOR)
                                fontSize(14f)
                                color(Color(0xFF4176E6))
                                marginTop(2f)
                            }
                        }
                    }
                }
            }
        }
    }
}
