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
import com.tencent.kuikly.core.views.List
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View
import io.github.yukiykchen.dsh.model.DshJobItem

class DshJobsPanelView : ComposeView<DshJobsPanelAttr, ComposeEvent>() {
    override fun createAttr(): DshJobsPanelAttr = DshJobsPanelAttr()
    override fun createEvent(): ComposeEvent = ComposeEvent()

    override fun body(): ViewBuilder {
        val ctx = this
        val snapshot = ctx.attr.jobs.toList()
        val ordered = dshOrderedJobs(snapshot)
        val liveCount = ordered.count { it.status == "running" || it.status == "stopping" }
        return {
            vif({ ctx.attr.jobs.isNotEmpty() }) {
                View {
                    attr {
                        marginBottom(8f)
                        flexDirectionColumn()
                        padding(8f)
                        borderRadius(10f)
                        backgroundColor(Color(0xFFF8FAFB))
                        border(Border(1f, BorderStyle.SOLID, Color(0xFFDEE5EA)))
                    }
                    View {
                        attr { height(30f); flexDirectionRow(); alignItemsCenter() }
                        event { click { ctx.attr.onToggle() } }
                        Text {
                            attr {
                                text(if (liveCount > 0) "后台任务 · $liveCount 运行中" else "后台任务 · ${snapshot.size}")
                                flex(1f)
                                fontSize(13f)
                                color(Color(0xFF39424A))
                            }
                        }
                        Image {
                            attr {
                                src(ImageUri.commonAssets("chevron-down.svg"))
                                size(14f, 14f)
                                transform(Rotate(if (ctx.attr.expanded) 0f else -90f))
                            }
                        }
                    }
                    vif({ ctx.attr.expanded }) {
                    vfor({ ctx.attr.jobs }) { job ->
                        View {
                            attr {
                                minHeight(46f)
                                marginTop(6f)
                                flexDirectionColumn()
                                padding(6f)
                                borderRadius(8f)
                                backgroundColor(Color(0xFFF1F5F8))
                            }
                            View {
                                attr { flexDirectionRow(); alignItemsCenter() }
                                View {
                                    attr {
                                        size(7f, 7f)
                                        borderRadius(4f)
                                        backgroundColor(Color(
                                            when (job.status) {
                                                "running" -> 0xFF2F9E63
                                                "stopping" -> 0xFFD99A20
                                                "completed" -> 0xFF77848C
                                                "failed" -> 0xFFC64C4C
                                                else -> 0xFF879198
                                            },
                                        ))
                                    }
                                }
                                Text {
                                    attr {
                                        text(job.kind)
                                        marginLeft(7f)
                                        fontSize(12f)
                                        fontWeightMedium()
                                        color(Color(0xFF3C4854))
                                    }
                                }
                                Text {
                                    attr {
                                        text(job.detail.ifEmpty { dshJobStatusLabel(job.status) })
                                        marginLeft(8f)
                                        fontSize(11f)
                                        color(Color(0xFF6A757D))
                                    }
                                }
                                Text {
                                    attr {
                                        text(dshJobDuration(job, ctx.attr.now))
                                        marginLeft(8f)
                                        fontSize(11f)
                                        color(Color(0xFF7A838A))
                                    }
                                }
                            }
                            Text {
                                attr {
                                    text(job.label)
                                    marginTop(3f)
                                    lines(1)
                                    fontSize(12f)
                                    color(Color(0xFF4B5660))
                                }
                            }
                            vif({ job.detail.isNotEmpty() }) {
                                Text {
                                    attr {
                                        text(job.detail)
                                        marginTop(2f)
                                        lines(1)
                                        fontSize(11f)
                                        color(Color(0xFF727D84))
                                    }
                                }
                            }
                        }
                    }
                    }
                }
            }
        }
    }
}

fun dshOrderedJobs(jobs: List<DshJobItem>): List<DshJobItem> {
    return jobs.sortedWith(
        compareByDescending<DshJobItem> { it.status == "running" || it.status == "stopping" }
            .thenBy { if (it.status == "running" || it.status == "stopping") it.startedAt else Long.MAX_VALUE }
            .thenByDescending { it.finishedAt ?: it.startedAt },
    )
}

fun dshJobStatusLabel(status: String): String = when (status) {
    "running" -> "运行中"
    "stopping" -> "正在停止"
    "completed" -> "已完成"
    "killed" -> "已取消"
    "failed" -> "已失败"
    else -> status
}

fun dshJobDuration(job: DshJobItem, now: Long): String {
    val end = job.finishedAt ?: now.takeIf { it > 0L } ?: return "进行中"
    val seconds = ((end - job.startedAt).coerceAtLeast(0L) / 1000L).toInt()
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val rest = seconds % 60
    return when {
        hours > 0 -> "${hours}小时${minutes}分"
        minutes > 0 -> "${minutes}分${rest}秒"
        else -> "${rest}秒"
    }
}

class DshJobsPanelAttr : ComposeAttr() {
    var jobs: com.tencent.kuikly.core.reactive.collection.ObservableList<DshJobItem> by observable(
        com.tencent.kuikly.core.reactive.collection.ObservableList(),
    )
    var expanded: Boolean by observable(false)
    var now: Long by observable(0L)
    var onToggle: () -> Unit by observable({})
}

fun ViewContainer<*, *>.DshJobsPanel(init: DshJobsPanelView.() -> Unit) {
    addChild(DshJobsPanelView(), init)
}
