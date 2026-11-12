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
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View
import io.github.yukiykchen.dsh.model.DshPendingQuestionOption
import io.github.yukiykchen.dsh.model.DshPendingQuestion

class DshQuestionPanelView : ComposeView<DshQuestionPanelAttr, ComposeEvent>() {
    override fun createAttr(): DshQuestionPanelAttr = DshQuestionPanelAttr()
    override fun createEvent(): ComposeEvent = ComposeEvent()

    override fun body(): ViewBuilder {
        val ctx = this
        return {
            vif({ ctx.attr.question != null }) {
                val question = ctx.attr.question ?: return@vif
                val item = question.questions.firstOrNull()
                vif({ item != null }) {
val current = item ?: return@vif
                    View {
                        attr {
                            marginBottom(8f)
                            flexDirectionColumn()
                            padding(10f)
                            borderRadius(12f)
                            backgroundColor(Color(0xFFF6F9FF))
                            border(Border(1f, BorderStyle.SOLID, Color(0xFFC7D9F2)))
                        }
                        Text {
                            attr {
                                text(current.header.ifEmpty { "需要你的回答" })
                                fontSize(12f)
                                color(Color(0xFF5D6F86))
                            }
                        }
                        Text {
                            attr {
                                text(current.question)
                                marginTop(4f)
                                fontSize(15f)
                                fontWeightMedium()
                                color(Color(0xFF2A384A))
                            }
                        }
                        vif({ current.detail.isNotEmpty() }) {
                            Text {
                                attr {
                                    text(current.detail)
                                    marginTop(4f)
                                    fontSize(12f)
                                    lineHeight(18f)
                                    color(Color(0xFF667687))
                                }
                            }
                        }
                        vfor({ ctx.attr.options }) { option ->
                            View {
                                attr {
                                    height(34f)
                                    marginTop(6f)
                                    paddingLeft(8f)
                                    paddingRight(8f)
                                    borderRadius(8f)
                                    backgroundColor(Color(0xFFEDF3FB))
                                    justifyContentCenter()
                                }
                                Text {
                                    attr {
                                        text(option.label)
                                        fontSize(13f)
                                        color(Color(0xFF324A66))
                                    }
                                }
                                event { click { ctx.attr.onToggleOption(option.label) } }
                            }
                        }
                        Text {
                            attr {
                                text(if (ctx.attr.busy) "提交中" else "提交")
                                height(34f)
                                marginTop(8f)
                                textAlignCenter()
                                fontSize(14f)
                                color(Color(0xFF2F6F4F))
                            }
                            event { click { if (!ctx.attr.busy) ctx.attr.onSubmit() } }
                        }
                    }
                }
            }
        }
    }
}

class DshQuestionPanelAttr : ComposeAttr() {
    var question: DshPendingQuestion? by observable(null)
    var options: com.tencent.kuikly.core.reactive.collection.ObservableList<DshPendingQuestionOption> by observable(
        com.tencent.kuikly.core.reactive.collection.ObservableList(),
    )
    var selected: com.tencent.kuikly.core.reactive.collection.ObservableList<String> by observable(
        com.tencent.kuikly.core.reactive.collection.ObservableList(),
    )
    var custom: String by observable("")
    var busy: Boolean by observable(false)
    var onToggleOption: (String) -> Unit by observable({})
    var onSubmit: () -> Unit by observable({})
}

fun ViewContainer<*, *>.DshQuestionPanel(init: DshQuestionPanelView.() -> Unit) {
    addChild(DshQuestionPanelView(), init)
}

fun ViewContainer<*, *>.DshTapTarget(onClick: () -> Unit) {
    View {
        attr {
            absolutePositionAllZero()
            zIndex(2)
            backgroundColor(Color(0x00000000))
        }
        event { click { onClick() } }
    }
}
