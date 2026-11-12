package io.github.yukiykchen.dsh.ui

import com.tencent.kuikly.core.base.*
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ComposeAttr
import com.tencent.kuikly.core.base.ComposeEvent
import com.tencent.kuikly.core.base.ComposeView
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.directives.vfor
import com.tencent.kuikly.core.reactive.handler.*
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.views.*
import com.tencent.kuikly.core.views.Input
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View
import io.github.yukiykchen.dsh.model.DshPendingQuestionOption
import io.github.yukiykchen.dsh.model.DshPendingQuestion

class DshQuestionFlowView : ComposeView<DshQuestionFlowAttr, ComposeEvent>() {
    override fun createAttr(): DshQuestionFlowAttr = DshQuestionFlowAttr()
    override fun createEvent(): ComposeEvent = ComposeEvent()

    override fun body(): ViewBuilder {
        val ctx = this
        val item = ctx.attr.question?.questions?.getOrNull(ctx.attr.index)
        return {
            vif({ item == null && ctx.attr.error.isNotEmpty() }) {
                Text {
                    attr {
                        text(ctx.attr.error)
                        marginBottom(10f)
                        fontSize(12f)
                        color(Color(0xFFC23B3B))
                    }
                }
            }
            vif({ item != null }) {
                val current = item ?: return@vif
                View {
                    attr {
                        marginLeft(4f)
                        marginRight(4f)
                        marginBottom(10f)
                        flexDirectionColumn()
                        padding(14f, 14f, 14f, 14f)
                        borderRadius(16f)
                        backgroundColor(Color.WHITE)
                        border(Border(1f, BorderStyle.SOLID, Color(0xFFE6EAF0)))
                    }
                    View {
                        attr {
                            alignSelfFlexStart()
                            padding(3f, 8f, 3f, 8f)
                            borderRadius(6f)
                            backgroundColor(Color(0xFFEEF3FA))
                        }
                        Text {
                            attr {
                                text(current.header.ifEmpty { "需要你选择" })
                                fontSize(11f)
                                fontWeightMedium()
                                color(Color(0xFF5B6B82))
                            }
                        }
                    }
                    Text {
                        attr {
                            text(current.question)
                            marginTop(10f)
                            fontSize(16f)
                            fontWeightMedium()
                            lineHeight(23f)
                            color(Color(0xFF1F2933))
                        }
                    }
                    vif({ current.detail.isNotEmpty() }) {
                        Text {
                            attr {
                                text(current.detail)
                                marginTop(6f)
                                fontSize(13f)
                                lineHeight(19f)
                                color(Color(0xFF6B7785))
                            }
                        }
                    }
                    vfor({ ctx.attr.options }) { option ->
                        val selected = ctx.attr.selected.contains(option.label)
                        View {
                            attr {
                                marginTop(8f)
                                flexDirectionRow()
                                alignItemsFlexStart()
                                padding(10f, 12f, 10f, 12f)
                                borderRadius(12f)
                                backgroundColor(Color(if (selected) 0xFFEFF5FF else 0xFFF7F9FB))
                                border(Border(
                                    1f,
                                    BorderStyle.SOLID,
                                    Color(if (selected) 0xFFB7D0F5 else 0xFFE8EDF2),
                                ))
                            }
                                View {
                                    attr {
                                        size(18f, 18f)
                                        marginTop(2f)
                                        borderRadius(9f)
                                        border(Border(
                                            1.5f,
                                            BorderStyle.SOLID,
                                            Color(if (selected) 0xFF4176E6 else 0xFFC5CDD6),
                                        ))
                                        backgroundColor(Color(if (selected) 0xFF4176E6 else 0x00FFFFFF))
                                        justifyContentCenter()
                                        alignItemsCenter()
                                    }
                                    View {
                                        attr {
                                            size(if (selected) 6f else 0f, if (selected) 6f else 0f)
                                            borderRadius(3f)
                                            backgroundColor(Color.WHITE)
                                        }
                                    }
                                }
                            View {
                                attr {
                                    flex(1f)
                                    marginLeft(10f)
                                    flexDirectionColumn()
                                }
                                Text {
                                    attr {
                                        text(option.label)
                                        fontSize(14f)
                                        fontWeightMedium()
                                        color(Color(0xFF243140))
                                    }
                                }
                                vif({ option.description.isNotEmpty() }) {
                                    Text {
                                        attr {
                                            text(option.description)
                                            marginTop(3f)
                                            fontSize(12f)
                                            lineHeight(17f)
                                            color(Color(0xFF6B7785))
                                        }
                                    }
                                }
                            }
                            DshTapTarget { if (!ctx.attr.busy) ctx.attr.onToggleOption(option.label) }
                        }
                    }
                    View {
                        attr {
                            height(40f)
                            marginTop(10f)
                            paddingLeft(12f)
                            paddingRight(12f)
                            borderRadius(10f)
                            backgroundColor(Color(0xFFF7F9FB))
                            border(Border(1f, BorderStyle.SOLID, Color(0xFFE8EDF2)))
                            justifyContentCenter()
                        }
                        Input {
                            attr {
                                height(36f)
                                placeholder("也可以自己写答案")
                                placeholderColor(Color(0xFF9AA6B2))
                                fontSize(13f)
                                color(Color(0xFF243140))
                                text(ctx.attr.custom)
                                editable(!ctx.attr.busy)
                            }
                            event { textDidChange { ctx.attr.onCustomChange(it.text) } }
                        }
                    }
                    vif({ ctx.attr.error.isNotEmpty() }) {
                        Text {
                            attr {
                                text(ctx.attr.error)
                                marginTop(8f)
                                fontSize(12f)
                                color(Color(0xFFC23B3B))
                            }
                        }
                    }
                    View {
                        attr {
                            height(40f)
                            marginTop(12f)
                            flexDirectionRow()
                            alignItemsCenter()
                            zIndex(2)
                        }
                        Text {
                            attr {
                                text("${ctx.attr.index + 1} / ${ctx.attr.question?.questions?.size ?: 1}")
                                flex(1f)
                                fontSize(12f)
                                color(Color(0xFF8A96A3))
                            }
                        }
                        vif({ ctx.attr.index > 0 }) {
                            Text {
                                attr {
                                    text("上一题")
                                    marginRight(12f)
                                    fontSize(13f)
                                    color(Color(0xFF4176E6))
                                }
                                event { click { if (!ctx.attr.busy) ctx.attr.onNavigate(-1) } }
                            }
                        }
                        vif({ ctx.attr.index < (ctx.attr.question?.questions?.size ?: 1) - 1 }) {
                            Text {
                                attr {
                                    text("下一题")
                                    marginRight(12f)
                                    fontSize(13f)
                                    color(Color(0xFF4176E6))
                                }
                                event { click { if (!ctx.attr.busy) ctx.attr.onNavigate(1) } }
                            }
                        }
                        View {
                            attr {
                                height(32f)
                                paddingLeft(12f)
                                paddingRight(12f)
                                marginRight(8f)
                                borderRadius(8f)
                                justifyContentCenter()
                                alignItemsCenter()
                            }
                            Text {
                                attr {
                                    text("跳过")
                                    fontSize(13f)
                                    color(Color(0xFF7A838A))
                                }
                            }
                            DshTapTarget { if (!ctx.attr.busy) ctx.attr.onSkip() }
                        }
                        View {
                            attr {
                                height(32f)
                                paddingLeft(16f)
                                paddingRight(16f)
                                borderRadius(8f)
                                backgroundColor(Color(if (ctx.attr.busy) 0xFFB7C8FE else 0xFF4176E6))
                                justifyContentCenter()
                                alignItemsCenter()
                            }
                            Text {
                                attr {
                                    text(if (ctx.attr.busy) "提交中" else "提交")
                                    fontSize(13f)
                                    fontWeightMedium()
                                    color(Color.WHITE)
                                }
                            }
                            DshTapTarget { if (!ctx.attr.busy) ctx.attr.onSubmit() }
                        }
                    }
                }
            }
        }
    }
}

class DshQuestionFlowAttr : ComposeAttr() {
    var question: DshPendingQuestion? by observable(null)
    var index: Int by observable(0)
    var options: com.tencent.kuikly.core.reactive.collection.ObservableList<DshPendingQuestionOption> by observable(
        com.tencent.kuikly.core.reactive.collection.ObservableList(),
    )
    var selected: com.tencent.kuikly.core.reactive.collection.ObservableList<String> by observable(
        com.tencent.kuikly.core.reactive.collection.ObservableList(),
    )
    var custom: String by observable("")
    var error: String by observable("")
    var busy: Boolean by observable(false)
    var onToggleOption: (String) -> Unit by observable({})
    var onCustomChange: (String) -> Unit by observable({})
    var onNavigate: (Int) -> Unit by observable({})
    var onSkip: () -> Unit by observable({})
    var onSubmit: () -> Unit by observable({})
}

fun ViewContainer<*, *>.DshQuestionFlow(init: DshQuestionFlowView.() -> Unit) {
    addChild(DshQuestionFlowView(), init)
}

fun ViewContainer<*, *>.DshApprovalPanel(init: DshApprovalPanelView.() -> Unit) {
    addChild(DshApprovalPanelView(), init)
}
