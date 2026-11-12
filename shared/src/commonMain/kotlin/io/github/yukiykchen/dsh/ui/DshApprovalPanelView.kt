package io.github.yukiykchen.dsh.ui

import com.tencent.kuikly.core.base.*
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ComposeAttr
import com.tencent.kuikly.core.base.ComposeEvent
import com.tencent.kuikly.core.base.ComposeView
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.reactive.handler.*
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.views.*
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View
import io.github.yukiykchen.dsh.model.DshPendingApproval

class DshApprovalPanelView : ComposeView<DshApprovalPanelAttr, ComposeEvent>() {
    override fun createAttr(): DshApprovalPanelAttr = DshApprovalPanelAttr()
    override fun createEvent(): ComposeEvent = ComposeEvent()

    override fun body(): ViewBuilder {
        val ctx = this
        return {
            vif({ ctx.attr.approval != null }) {
                val approval = ctx.attr.approval ?: return@vif
                View {
                    attr {
                        marginLeft(4f)
                        marginRight(4f)
                        marginBottom(10f)
                        flexDirectionColumn()
                        padding(14f, 14f, 14f, 14f)
                        borderRadius(16f)
                        backgroundColor(Color.WHITE)
                        border(Border(1f, BorderStyle.SOLID, Color(0xFFE8E1C8)))
                    }
                    View {
                        attr {
                            alignSelfFlexStart()
                            padding(3f, 8f, 3f, 8f)
                            borderRadius(6f)
                            backgroundColor(Color(0xFFFFF4D6))
                        }
                        Text {
                            attr {
                                text("等待审批")
                                fontSize(11f)
                                fontWeightMedium()
                                color(Color(0xFF8A6A16))
                            }
                        }
                    }
                    Text {
                        attr {
                            text(approval.reason ?: "需要使用 ${approval.toolName}")
                            marginTop(10f)
                            fontSize(16f)
                            fontWeightMedium()
                            lineHeight(23f)
                            color(Color(0xFF1F2933))
                        }
                    }
                    vif({ approval.command != null }) {
                        View {
                            attr {
                                marginTop(8f)
                                padding(10f, 12f, 10f, 12f)
                                borderRadius(10f)
                                backgroundColor(Color(0xFFF7F9FB))
                            }
                            Text {
                                attr {
                                    text(approval.command ?: "")
                                    fontSize(12f)
                                    lineHeight(18f)
                                    fontFamily("monospace")
                                    color(Color(0xFF5C6570))
                                }
                            }
                        }
                    }
                    View {
                        attr {
                            height(40f)
                            marginTop(12f)
                            flexDirectionRow()
                            justifyContentFlexEnd()
                            alignItemsCenter()
                        }
                        View {
                            attr {
                                height(32f)
                                paddingLeft(14f)
                                paddingRight(14f)
                                borderRadius(8f)
                                justifyContentCenter()
                                alignItemsCenter()
                            }
                            Text {
                                attr {
                                    text(if (ctx.attr.busy) "处理中" else "拒绝")
                                    fontSize(13f)
                                    color(Color(0xFFB14646))
                                }
                            }
                            DshTapTarget { if (!ctx.attr.busy) ctx.attr.onAnswer("rejected") }
                        }
                        View {
                            attr {
                                height(32f)
                                marginLeft(8f)
                                paddingLeft(14f)
                                paddingRight(14f)
                                borderRadius(8f)
                                backgroundColor(Color(if (ctx.attr.busy) 0xFFC8D7A8 else 0xFF2F7D4F))
                                justifyContentCenter()
                                alignItemsCenter()
                            }
                            Text {
                                attr {
                                    text(if (ctx.attr.busy) "处理中" else "允许一次")
                                    fontSize(13f)
                                    fontWeightMedium()
                                    color(Color.WHITE)
                                }
                            }
                            DshTapTarget { if (!ctx.attr.busy) ctx.attr.onAnswer("allowed-once") }
                        }
                    }
                }
            }
        }
    }
}

class DshApprovalPanelAttr : ComposeAttr() {
    var approval: DshPendingApproval? by observable(null)
    var busy: Boolean by observable(false)
    var onAnswer: (String) -> Unit by observable({})
}
