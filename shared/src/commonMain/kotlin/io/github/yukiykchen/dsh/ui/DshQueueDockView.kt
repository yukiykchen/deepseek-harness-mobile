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
import com.tencent.kuikly.core.directives.vfor
import com.tencent.kuikly.core.reactive.handler.*
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.views.*
import com.tencent.kuikly.core.views.Image
import com.tencent.kuikly.core.views.Input
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View
import io.github.yukiykchen.dsh.model.DshQueueItem

class DshQueueDockView : ComposeView<DshQueueDockAttr, ComposeEvent>() {
    override fun createAttr(): DshQueueDockAttr = DshQueueDockAttr()
    override fun createEvent(): ComposeEvent = ComposeEvent()

    override fun body(): ViewBuilder {
        val ctx = this
        val items = ctx.attr.items
        val expanded = ctx.attr.expanded || items.size <= 1 || ctx.attr.editingId.isNotEmpty() || ctx.attr.actionBusy
        return {
            vif({ items.isNotEmpty() }) {
                View {
                    attr {
                        marginBottom(8f)
                        flexDirectionColumn()
                        padding(8f)
                        borderRadius(10f)
                        backgroundColor(Color(0xFFF7F9FB))
                        border(Border(1f, BorderStyle.SOLID, Color(0xFFE1E7ED)))
                    }
                    vif({ items.size > 1 }) {
                        View {
                            attr {
                                height(30f)
                                flexDirectionRow()
                                alignItemsCenter()
                            }
                            event { click { if (!ctx.attr.actionBusy && ctx.attr.editingId.isEmpty()) ctx.attr.onToggle() } }
                            Text {
                                attr {
                                    text("队列 · ${items.size}")
                                    flex(1f)
                                    fontSize(13f)
                                    color(Color(0xFF39424A))
                                }
                            }
                            Image {
                                attr {
                                    src(ImageUri.commonAssets("chevron-down.svg"))
                                    size(14f, 14f)
                                    transform(Rotate(if (expanded) 0f else -90f))
                                }
                            }
                        }
                    }
                    vif({ expanded }) {
                        vfor({ ctx.attr.items }) { item ->
                            View {
                                attr {
                                    minHeight(40f)
                                    flexDirectionRow()
                                    alignItemsCenter()
                                }
                                vif({ ctx.attr.editingId != item.id }) {
                                    Text {
                                        attr {
                                            text(item.preview)
                                            flex(1f)
                                            lines(1)
                                            fontSize(13f)
                                            color(Color(0xFF4A545C))
                                        }
                                    }
                                    Text {
                                        attr {
                                            text("编辑")
                                            marginLeft(8f)
                                            fontSize(12f)
                                            color(Color(0xFF4176E6))
                                        }
                                        event { click { if (!ctx.attr.actionBusy) ctx.attr.onEdit(item.id) } }
                                    }
                                    Text {
                                        attr {
                                            text("删除")
                                            marginLeft(10f)
                                            fontSize(12f)
                                            color(Color(0xFFD25A5A))
                                        }
                                        event { click { if (!ctx.attr.actionBusy) ctx.attr.onRemove(item.id) } }
                                    }
                                    Text {
                                        attr {
                                            text("转向")
                                            marginLeft(10f)
                                            fontSize(12f)
                                            color(Color(if (ctx.attr.running) 0xFF4176E6 else 0xFFA4ADB3))
                                        }
                                        event { click { if (ctx.attr.running && !ctx.attr.actionBusy) ctx.attr.onSteer(item.id) } }
                                    }
                                }
                                vif({ ctx.attr.editingId == item.id }) {
                                    Input {
                                        attr {
                                            flex(1f)
                                            height(32f)
                                            text(ctx.attr.editingText)
                                            fontSize(13f)
                                            placeholder("编辑队列消息")
                                            placeholderColor(Color(0xFF91A0AA))
                                        }
                                        event { textDidChange { ctx.attr.onEditingTextChange(it.text) } }
                                    }
                                    Text {
                                        attr {
                                            text("保存")
                                            marginLeft(8f)
                                            fontSize(12f)
                                            color(Color(0xFF2F7D4F))
                                        }
                                        event { click { if (!ctx.attr.actionBusy) ctx.attr.onSaveEdit(item.id) } }
                                    }
                                    Text {
                                        attr {
                                            text("取消")
                                            marginLeft(10f)
                                            fontSize(12f)
                                            color(Color(0xFF7A838A))
                                        }
                                        event { click { if (!ctx.attr.actionBusy) ctx.attr.onCancelEdit() } }
                                    }
                                }
                            }
                        }
                    }
                    vif({ ctx.attr.error.isNotEmpty() }) {
                        Text {
                            attr {
                                text(ctx.attr.error)
                                marginTop(4f)
                                fontSize(12f)
                                color(Color(0xFFD25A5A))
                                lines(3)
                            }
                        }
                    }
                }
            }
        }
    }
}

class DshQueueDockAttr : ComposeAttr() {
    var items: com.tencent.kuikly.core.reactive.collection.ObservableList<DshQueueItem> by observable(
        com.tencent.kuikly.core.reactive.collection.ObservableList(),
    )
    var expanded: Boolean by observable(false)
    var running: Boolean by observable(false)
    var editingId: String by observable("")
    var editingText: String by observable("")
    var actionBusy: Boolean by observable(false)
    var error: String by observable("")
    var onToggle: () -> Unit by observable({})
    var onEdit: (String) -> Unit by observable({})
    var onEditingTextChange: (String) -> Unit by observable({})
    var onSaveEdit: (String) -> Unit by observable({})
    var onCancelEdit: () -> Unit by observable({})
    var onRemove: (String) -> Unit by observable({})
    var onSteer: (String) -> Unit by observable({})
}

fun ViewContainer<*, *>.DshQueueDock(init: DshQueueDockView.() -> Unit) {
    addChild(DshQueueDockView(), init)
}
