package io.github.yukiykchen.dsh.ui

import com.tencent.kuikly.core.base.*
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ComposeAttr
import com.tencent.kuikly.core.base.ComposeEvent
import com.tencent.kuikly.core.base.ComposeView
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.base.Rotate
import com.tencent.kuikly.core.base.attr.ImageUri
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.reactive.handler.*
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.views.*
import com.tencent.kuikly.core.views.Image
import com.tencent.kuikly.core.views.Scroller
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View

/** The shared compact disclosure chrome used by context and tool rows. */

class DshDisclosureRowView : ComposeView<DshDisclosureRowAttr, ComposeEvent>() {
    override fun createAttr(): DshDisclosureRowAttr = DshDisclosureRowAttr()
    override fun createEvent(): ComposeEvent = ComposeEvent()

    override fun body(): ViewBuilder {
        val ctx = this
        return {
            View {
                attr {
                    flex(1f)
                    flexDirectionColumn()
                    if (ctx.attr.chrome) {
                        padding(8f, 10f, 8f, 10f)
                        borderRadius(8f)
                        backgroundColor(
                            Color(
                                when {
                                    ctx.attr.errorSummary -> 0xFFFFF6F6
                                    ctx.attr.running -> 0xFFF7FCFA
                                    else -> 0xFFFCFDFE
                                },
                            ),
                        )
                        border(Border(1f, BorderStyle.SOLID, Color(0xFFE4E8EC)))
                    }
                }
                View {
                    attr {
                        height(36f)
                        flexDirectionRow()
                        alignItemsCenter()
                    }
                    vif({ ctx.attr.iconAsset.isNotEmpty() }) {
                        Image {
                            attr {
                                src(ImageUri.commonAssets(ctx.attr.iconAsset))
                                size(14f, 14f)
                            }
                        }
                    }
                    Image {
                        attr {
                            src(ImageUri.commonAssets("chevron-down.svg"))
                            size(14f, 14f)
                            marginLeft(if (ctx.attr.iconAsset.isNotEmpty()) 4f else 0f)
                            transform(Rotate(if (ctx.attr.open) 0f else -90f))
                        }
                    }
                    Text {
                        attr {
                            text(ctx.attr.title)
                            marginLeft(7f)
                            fontSize(13f)
                            fontWeightMedium()
                            color(Color(0xFF2F3A44))
                        }
                    }
                    vif({ ctx.attr.summary.isNotEmpty() }) {
                        Text {
                            attr {
                                text("·")
                                marginLeft(7f)
                                marginRight(7f)
                                fontSize(13f)
                                color(Color(0xFF9AA4AC))
                            }
                        }
                        Text {
                            attr {
                            text(ctx.attr.summary)
                                flex(1f)
                                lines(1)
                                fontSize(13f)
                                color(Color(if (ctx.attr.errorSummary) 0xFFB14646 else 0xFF727B83))
                            }
                        }
                    }
                    vif({ ctx.attr.summary.isEmpty() }) {
                        View { attr { flex(1f) } }
                    }
                    DshTapTarget {
                        if (ctx.attr.expandable) {
                            ctx.attr.open = !ctx.attr.open
                            ctx.attr.onToggle()
                        }
                    }
                }
                vif({ ctx.attr.open }) {
                    View {
                        attr {
                            marginTop(6f)
                            marginBottom(8f)
                            flexDirectionColumn()
                        }
                        vif({ ctx.attr.jsonContent.isNotEmpty() }) {
                            DshJsonTree {
                                attr {
                                    content = ctx.attr.jsonContent
                                    this.isExpanded = ctx.attr.isJsonNodeExpanded
                                    this.onToggle = ctx.attr.onToggleJsonNode
                                }
                            }
                        }
                        vif({ ctx.attr.jsonContent.isEmpty() && ctx.attr.body.isNotEmpty() }) {
                            DshLongText {
                                attr {
                                    content = ctx.attr.body
                                    expanded = ctx.attr.bodyExpanded
                                    maxLines = ctx.attr.maxBodyLines
                                    error = ctx.attr.errorSummary
                                    this.onToggle = {
                                        ctx.attr.bodyExpanded = !ctx.attr.bodyExpanded
                                        ctx.attr.onToggleBody()
                                    }
                                }
                            }
                        }
                        vif({ ctx.attr.jsonContent.isEmpty() && ctx.attr.body.isEmpty() }) {
                            Text {
                                attr {
                                    text("暂无输出")
                                    fontSize(12f)
                                    color(Color(0xFF8A9399))
                                    margin(10f)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

class DshDisclosureRowAttr : ComposeAttr() {
    var title: String by observable("")
    var summary: String by observable("")
    var body: String by observable("")
    var iconAsset: String by observable("")
    var errorSummary: Boolean by observable(false)
    var open: Boolean by observable(false)
    var expandable: Boolean by observable(false)
    var onToggle: () -> Unit by observable({})
    var bodyExpanded: Boolean by observable(false)
    var onToggleBody: () -> Unit by observable({})
    var maxBodyLines: Int by observable(8)
    var jsonContent: String by observable("")
    var isJsonNodeExpanded: (String) -> Boolean by observable({ false })
    var onToggleJsonNode: (String) -> Unit by observable({})
    var chrome: Boolean by observable(false)
    var running: Boolean by observable(false)
}

/** Second-level disclosure for long terminal/read/diff bodies. */

class DshLongTextView : ComposeView<DshLongTextAttr, ComposeEvent>() {
    override fun createAttr(): DshLongTextAttr = DshLongTextAttr()
    override fun createEvent(): ComposeEvent = ComposeEvent()

    override fun body(): ViewBuilder {
        val ctx = this
        val expanded = ctx.attr.expanded
        val hidden = ctx.attr.content.lineSequence().count() - ctx.attr.maxLines
        val capped = hidden > 0 && !expanded
        val joined = if (expanded) {
            ctx.attr.content
        } else {
            ctx.attr.content.dshCollapsedLines(ctx.attr.maxLines).joinToString("\n")
        }
        return {
            View {
                attr {
                    flexDirectionColumn()
                    borderRadius(8f)
                    backgroundColor(Color(0xFFF9FAFB))
                    border(Border(1f, BorderStyle.SOLID, Color(0xFFE4E8EC)))
                    padding(8f)
                }
                Scroller {
                    attr {
                        height(
                            when {
                                ctx.attr.maxHeight > 0f -> ctx.attr.maxHeight.coerceAtMost(280f)
                                expanded -> 280f
                                else -> (ctx.attr.maxLines * 18f).coerceAtMost(280f)
                            },
                        )
                    }
                    Text {
                        attr {
                            text(joined)
                            fontSize(12f)
                            lineHeight(18f)
                            fontFamily("monospace")
                            color(Color(if (ctx.attr.error) 0xFFB53232 else 0xFF333B42))
                        }
                    }
                }
                vif({ capped }) {
                    View {
                        attr {
                            height(20f)
                            marginTop(6f)
                            justifyContentCenter()
                        }
                        Text {
                            attr {
                                text("… 其余 $hidden 行")
                                fontSize(12f)
                                color(Color(0xFF4176E6))
                            }
                        }
                        DshTapTarget {
                            ctx.attr.expanded = true
                            ctx.attr.onToggle()
                        }
                    }
                }
                vif({ expanded && hidden > 0 }) {
                    View {
                        attr {
                            height(20f)
                            marginTop(6f)
                            justifyContentCenter()
                        }
                        Text {
                            attr {
                                text("收起")
                                fontSize(12f)
                                color(Color(0xFF4176E6))
                            }
                        }
                        DshTapTarget {
                            ctx.attr.expanded = false
                            ctx.attr.onToggle()
                        }
                    }
                }
            }
        }
    }
}

class DshLongTextAttr : ComposeAttr() {
    var content: String by observable("")
    var expanded: Boolean by observable(false)
    var maxLines: Int by observable(16)
    var maxHeight: Float by observable(0f)
    var error: Boolean by observable(false)
    var onToggle: () -> Unit by observable({})
}

fun ViewContainer<*, *>.DshLongText(init: DshLongTextView.() -> Unit) {
    addChild(DshLongTextView(), init)
}

fun ViewContainer<*, *>.DshDisclosureRow(init: DshDisclosureRowView.() -> Unit) {
    addChild(DshDisclosureRowView(), init)
}
