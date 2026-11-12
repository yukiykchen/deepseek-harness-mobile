package io.github.yukiykchen.dsh.feature.home

import com.tencent.kuikly.core.base.*
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.base.attr.ImageUri
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.directives.vfor
import com.tencent.kuikly.core.reactive.collection.ObservableList
import com.tencent.kuikly.core.reactive.handler.*
import com.tencent.kuikly.core.views.*
import com.tencent.kuikly.core.views.Image
import com.tencent.kuikly.core.views.Scroller
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View
import com.tencent.kuikly.core.views.compose.Button
import io.github.yukiykchen.dsh.ui.isConnectionReadyLabel
import io.github.yukiykchen.dsh.ui.topBarConnectingText
import io.github.yukiykchen.dsh.model.DshSession

fun ViewContainer<*, *>.DshTopBar(
    title: () -> String,
    connection: () -> String,
) {
    View {
        attr {
            height(58f)
            flexDirectionRow()
            alignItemsCenter()
            paddingLeft(12f)
            paddingRight(14f)
            backgroundColor(Color.WHITE)
            borderBottom(Border(1f, BorderStyle.SOLID, Color(0xFFEBEEF2)))
        }
        View {
            attr { size(38f, 38f); allCenter() }
            Image {
                attr {
                    src(ImageUri.commonAssets("menu.svg"))
                    size(26f, 26f)
                }
            }
        }
        Text {
            attr {
                text(title())
                marginLeft(10f)
                flex(1f)
                fontSize(17f)
                fontWeightMedium()
                color(Color(0xFF0F1115))
                lines(1)
            }
        }
        View {
            attr {
val ready = isConnectionReadyLabel(connection())
                height(22f)
                marginLeft(8f)
                paddingLeft(8f)
                paddingRight(8f)
                borderRadius(11f)
                backgroundColor(Color(if (ready) 0xFFE8F7EE else 0xFFF3F5F7))
                justifyContentCenter()
                alignItemsCenter()
            }
            Text {
                attr {
val ready = isConnectionReadyLabel(connection())
                    text(if (ready) "已连接" else topBarConnectingText(connection()))
                    fontSize(11f)
                    lines(1)
                    color(Color(if (ready) 0xFF1F8A4C else 0xFF6B7785))
                }
            }
        }
    }
}

fun ViewContainer<*, *>.DshSessionRail(
    sessions: () -> ObservableList<DshSession>,
    activeId: () -> String,
    compact: Boolean,
    onSelect: (String) -> Unit,
) {
    View {
        attr {
            if (compact) {
                height(92f)
                flexDirectionRow()
            } else {
                width(236f)
                flexDirectionColumn()
            }
            backgroundColor(Color(0xFFF7F7F8))
            padding(14f)
        }
        Text {
            attr {
                text("会话")
                fontSize(13f)
                color(Color(0xFF6F7378))
                marginBottom(9f)
            }
        }
        if (compact) {
            Scroller {
                attr {
                    flex(1f)
                    flexDirectionRow()
                }
                vfor({ sessions() }) { session ->
                    DshSessionButton(session, activeId() == session.id, onSelect)
                }
            }
        } else {
            Scroller {
                attr { flex(1f) }
                vfor({ sessions() }) { session ->
                    DshSessionButton(session, activeId() == session.id, onSelect)
                }
            }
        }
    }
}

fun ViewContainer<*, *>.DshSessionButton(
    session: DshSession,
    active: Boolean,
    onSelect: (String) -> Unit,
) {
    Button {
        attr {
            height(48f)
            width(if (active) 220f else 220f)
            marginBottom(4f)
            borderRadius(7f)
            backgroundColor(Color(if (active) 0xFFE4EDFD else 0x00000000))
            titleAttr {
                text(session.title)
                color(Color(if (active) 0xFF4176E6 else 0xFF3E4247))
                fontSize(13f)
            }
        }
        event { click { onSelect(session.id) } }
    }
}

fun ViewContainer<*, *>.DshSessionDetailsPanel(
    title: () -> String,
    cwd: () -> String,
    modelLabel: () -> String,
    agentPreset: () -> String,
    running: () -> Boolean,
    queueCount: () -> Int,
    jobCount: () -> Int,
) {
    View {
        attr {
            width(280f)
            height(pagerData.pageViewHeight)
            flexDirectionColumn()
            padding(16f)
            backgroundColor(Color(0xFFF7F9FA))
            border(Border(1f, BorderStyle.SOLID, Color(0xFFE5E8EB)))
        }
        Text {
            attr {
                text("Session")
                fontSize(12f)
                color(Color(0xFF7A8790))
            }
        }
        Text {
            attr {
                text(title())
                marginTop(6f)
                fontSize(17f)
                fontWeightSemiBold()
                color(Color(0xFF1F2933))
                lines(2)
            }
        }
        View {
            attr {
                height(1f)
                marginTop(14f)
                backgroundColor(Color(0xFFE5E8EB))
            }
        }
        DshDetailRow("状态", if (running()) "运行中" else "空闲")
        DshDetailRow("模型", modelLabel())
        vif({ agentPreset().isNotEmpty() }) {
            DshDetailRow("Agent Preset", agentPreset())
        }
        DshDetailRow("队列", "${queueCount()} 条")
        DshDetailRow("后台任务", "${jobCount()} 个")
        vif({ cwd().isNotEmpty() }) {
            DshDetailRow("目录", cwd())
        }
    }
}

fun ViewContainer<*, *>.DshDetailRow(
    label: String,
    value: String,
) {
    View {
        attr {
            minHeight(44f)
            marginTop(10f)
            flexDirectionColumn()
            justifyContentCenter()
        }
        Text {
            attr {
                text(label)
                fontSize(11f)
                color(Color(0xFF8B9298))
            }
        }
        Text {
            attr {
                text(value)
                marginTop(2f)
                fontSize(13f)
                color(Color(0xFF343E47))
                lines(2)
            }
        }
    }
}
