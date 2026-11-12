package io.github.yukiykchen.dsh.ui

import com.tencent.kuikly.core.base.*
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ComposeAttr
import com.tencent.kuikly.core.base.ComposeEvent
import com.tencent.kuikly.core.base.ComposeView
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.base.attr.ImageUri
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.reactive.handler.*
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.views.*
import com.tencent.kuikly.core.views.Image
import com.tencent.kuikly.core.views.Input
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View
import io.github.yukiykchen.dsh.model.DshGoalSnapshot

class DshGoalBarView : ComposeView<DshGoalBarAttr, ComposeEvent>() {
    var editing by observable(false)
    var draft by observable("")

    override fun createAttr(): DshGoalBarAttr = DshGoalBarAttr()
    override fun createEvent(): ComposeEvent = ComposeEvent()

    override fun body(): ViewBuilder {
        val ctx = this
        return {
            vif({ ctx.attr.snapshot != null }) {
                val goal = ctx.attr.snapshot ?: return@vif
                View {
                    attr {
                        marginBottom(8f)
                        minHeight(38f)
                        flexDirectionRow()
                        alignItemsCenter()
                        padding(8f, 10f, 8f, 10f)
                        borderRadius(8f)
                        backgroundColor(Color(0xFFF7F9FB))
                        border(Border(1f, BorderStyle.SOLID, Color(0xFFDCE5EC)))
                    }
                    Image {
                        attr { src(ImageUri.commonAssets("goal.svg")); size(14f, 14f) }
                    }
                    Text {
                        attr {
                            text(when (goal.phase) {
                                "active" -> "目标进行中"
                                "paused" -> "目标已暂停"
                                "blocked" -> "目标受阻"
                                else -> goal.phase
                            })
                            marginLeft(6f)
                            fontSize(12f)
                            fontWeightMedium()
                            color(Color(if (goal.phase == "blocked") 0xFFC23B3B else 0xFF536777))
                        }
                    }
                    vif({ !ctx.editing }) {
                        Text {
                            attr {
                                text(goal.objective)
                                flex(1f)
                                marginLeft(8f)
                                lines(2)
                                fontSize(12f)
                                color(Color(0xFF354653))
                            }
                        }
                    }
                    vif({ ctx.editing }) {
                        Input {
                            attr {
                                flex(1f)
                                height(30f)
                                marginLeft(8f)
                                fontSize(12f)
                                text(ctx.draft)
                            }
                            event { textDidChange { ctx.draft = it.text } }
                        }
                        Text {
                            attr {
                                text(if (ctx.attr.busy) "处理中" else "保存")
                                marginLeft(8f)
                                fontSize(12f)
                                color(Color(0xFF2F7D4F))
                            }
                            event {
                                click {
                                    if (!ctx.attr.busy && ctx.draft.trim().isNotEmpty()) {
                                        ctx.attr.onEdit(ctx.draft.trim()) { success -> if (success) ctx.editing = false }
                                    }
                                }
                            }
                        }
                        Text {
                            attr {
                                text("取消")
                                marginLeft(8f)
                                fontSize(12f)
                                color(Color(0xFF7A838A))
                            }
                            event { click { if (!ctx.attr.busy) ctx.editing = false } }
                        }
                    }
                    vif({ goal.blockedReason.isNotEmpty() }) {
                        Text {
                            attr {
                                text(goal.blockedReason)
                                marginLeft(6f)
                                lines(1)
                                fontSize(11f)
                                color(Color(0xFFC23B3B))
                            }
                        }
                    }
                    vif({ ctx.attr.error.isNotEmpty() }) {
                        Text {
                            attr {
                                text(ctx.attr.error)
                                marginLeft(6f)
                                fontSize(11f)
                                color(Color(0xFFC23B3B))
                            }
                        }
                    }
                    vif({ !ctx.editing && goal.phase == "active" }) {
                        Text {
                            attr {
                                text(if (ctx.attr.busy) "处理中" else "暂停")
                                marginLeft(8f)
                                fontSize(12f)
                                color(Color(0xFF667687))
                            }
                            event { click { if (!ctx.attr.busy) ctx.attr.onPause() } }
                        }
                    }
                    vif({ !ctx.editing && goal.phase == "paused" }) {
                        Text {
                            attr {
                                text(if (ctx.attr.busy) "处理中" else "恢复")
                                marginLeft(8f)
                                fontSize(12f)
                                color(Color(0xFF2F7D4F))
                            }
                            event { click { if (!ctx.attr.busy) ctx.attr.onResume() } }
                        }
                    }
                    vif({ !ctx.editing }) {
                    Text {
                        attr {
                            text("清除")
                            marginLeft(8f)
                            fontSize(12f)
                            color(Color(0xFFB14646))
                        }
                        event { click { if (!ctx.attr.busy) ctx.attr.onClear() } }
                    }
                    }
                    vif({ !ctx.editing }) {
                    Text {
                        attr {
                            text("编辑")
                            marginLeft(8f)
                            fontSize(12f)
                            color(Color(0xFF4176E6))
                        }
                        event { click { if (!ctx.attr.busy) { ctx.draft = goal.objective; ctx.editing = true } } }
                    }
                    }
                }
            }
        }
    }
}

class DshGoalBarAttr : ComposeAttr() {
    var snapshot: DshGoalSnapshot? by observable(null)
    var busy: Boolean by observable(false)
    var error: String by observable("")
    var onEdit: (String, (Boolean) -> Unit) -> Unit by observable({ _, _ -> })
    var onPause: () -> Unit by observable({})
    var onResume: () -> Unit by observable({})
    var onClear: () -> Unit by observable({})
}

fun ViewContainer<*, *>.DshGoalBar(init: DshGoalBarView.() -> Unit) {
    addChild(DshGoalBarView(), init)
}
