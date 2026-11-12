package io.github.yukiykchen.dsh.feature.home

import com.tencent.kuikly.core.base.*
import com.tencent.kuikly.core.base.Animation
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.Translate
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.base.attr.ImageUri
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.directives.vfor
import com.tencent.kuikly.core.reactive.collection.ObservableList
import com.tencent.kuikly.core.reactive.handler.*
import com.tencent.kuikly.core.views.*
import com.tencent.kuikly.core.views.Image
import com.tencent.kuikly.core.views.Modal
import com.tencent.kuikly.core.views.Scroller
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View
import io.github.yukiykchen.dsh.ui.DshWordmark
import io.github.yukiykchen.dsh.model.DshWorkspaceGroup
import io.github.yukiykchen.dsh.model.DshModelOption

fun ViewContainer<*, *>.DshSessionDrawer(
    workspaceGroups: () -> ObservableList<DshWorkspaceGroup>,
    activeId: () -> String,
    animated: () -> Boolean,
    onClose: () -> Unit,
    onOpenSettings: () -> Unit,
    onNewSession: () -> Unit,
    onSelect: (String) -> Unit,
) {
    Modal(inWindow = true) {
        attr {
            absolutePositionAllZero()
            flexDirectionRow()
            backgroundColor(Color(0x00000000))
        }
        View {
            attr {
                width((pagerData.pageViewWidth - 44f).coerceAtMost(340f))
                height(pagerData.pageViewHeight)
                flexDirectionColumn()
                paddingTop(pagerData.statusBarHeight + 10f)
                paddingLeft(14f)
                paddingRight(14f)
                paddingBottom(18f)
                backgroundColor(Color(0xFFF9FAFB))
                transform(Translate(if (animated()) 0f else -1f, 0f))
                animation(Animation.easeOut(0.24f), animated())
            }
            View {
                attr {
                    height(48f)
                    flexDirectionRow()
                    alignItemsCenter()
                }
                DshWordmark()
                View { attr { flex(1f) } }
                View {
                    attr { size(38f, 38f); allCenter() }
                    Image { attr { src(ImageUri.commonAssets("x.svg")); size(22f, 22f) } }
                    event { click { onClose() } }
                }
            }
            View {
                attr {
                    height(42f)
                    marginTop(8f)
                    flexDirectionRow()
                    alignItemsCenter()
                    paddingLeft(12f)
                    paddingRight(12f)
                    borderRadius(9f)
                    backgroundColor(Color(0xFFF1F3F5))
                }
                Image { attr { src(ImageUri.commonAssets("plus.svg")); size(20f, 20f) } }
                Text {
                    attr {
                        text("新会话")
                        marginLeft(10f)
                        fontSize(14f)
                        fontWeightMedium()
                        color(Color(0xFF32373C))
                    }
                }
                event { click { onNewSession() } }
            }
            View {
                attr {
                    height(42f)
                    marginTop(8f)
                    flexDirectionRow()
                    alignItemsCenter()
                    paddingLeft(12f)
                    paddingRight(12f)
                    borderRadius(9f)
                    backgroundColor(Color(0x00000000))
                }
                Image { attr { src(ImageUri.commonAssets("sliders.svg")); size(20f, 20f) } }
                Text {
                    attr {
                        text("设置")
                        marginLeft(10f)
                        fontSize(14f)
                        fontWeightMedium()
                        color(Color(0xFF555D64))
                    }
                }
                event { click { onOpenSettings() } }
            }
            Text {
                attr {
                    text("会话")
                    marginTop(20f)
                    marginBottom(8f)
                    fontSize(12f)
                    color(Color(0xFF8B9298))
                }
            }
            Scroller {
                attr { flex(1f) }
                vfor({ workspaceGroups() }) { group ->
                    View {
                        attr {
                            marginTop(10f)
                            marginBottom(6f)
                            flexDirectionColumn()
                        }
                        Text {
                            attr {
                                text(group.title + if (group.path.isEmpty()) "" else " · ${group.path}")
                                lines(1)
                                fontSize(12f)
                                fontWeightMedium()
                                color(Color(0xFF7A838A))
                            }
                        }
                        group.sessions.forEach { session ->
                            DshSessionDrawerRow(
                                title = session.title,
                                subtitle = if (session.cwd.isEmpty()) group.title else session.cwd,
                                active = activeId() == session.id,
                                running = session.running,
                                onSelect = { onSelect(session.id) },
                            )
                        }
                    }
                }
            }
        }
        View {
            attr {
                flex(1f)
                height(pagerData.pageViewHeight)
            }
            event { click { onClose() } }
        }
    }
}

fun ViewContainer<*, *>.DshSessionDrawerRow(
    title: String,
    subtitle: String,
    active: Boolean,
    running: Boolean,
    onSelect: () -> Unit,
) {
    View {
        attr {
            height(48f)
            marginBottom(4f)
            flexDirectionRow()
            alignItemsCenter()
            paddingLeft(12f)
            paddingRight(10f)
            borderRadius(9f)
            backgroundColor(Color(if (active) 0xFFE3E6EA else 0x00FFFFFF))
        }
        View {
            attr {
                size(7f, 7f)
                borderRadius(4f)
                backgroundColor(Color(if (running) 0xFF4176E6 else 0xFFADB2B8))
            }
        }
        View {
            attr {
                flex(1f)
                marginLeft(10f)
                flexDirectionColumn()
                justifyContentCenter()
            }
            Text {
                attr {
                    text(title)
                    lines(1)
                    fontSize(14f)
                    color(Color(0xFF2B3136))
                }
            }
            Text {
                attr {
                    text(subtitle)
                    lines(1)
                    marginTop(2f)
                    fontSize(10f)
                    color(Color(0xFF969DA3))
                }
            }
        }
        event { click { onSelect() } }
    }
}

fun ViewContainer<*, *>.DshModelPicker(
    options: () -> ObservableList<DshModelOption>,
    busy: () -> Boolean,
    error: () -> String,
    onClose: () -> Unit,
    onSelect: (DshModelOption) -> Unit,
) {
    Modal(inWindow = true) {
        attr {
            absolutePositionAllZero()
            flexDirectionColumn()
            justifyContentFlexEnd()
            backgroundColor(Color(0x55000000))
        }
        View {
            attr { flex(1f) }
            event { click { onClose() } }
        }
        View {
            attr {
                height((pagerData.pageViewHeight * 0.62f).coerceAtMost(540f))
                flexDirectionColumn()
                padding(18f)
                borderRadius(20f)
                backgroundColor(Color.WHITE)
            }
            View {
                attr { height(40f); flexDirectionRow(); alignItemsCenter() }
                Text {
                    attr {
                        text("选择模型")
                        fontSize(18f)
                        fontWeightBold()
                        color(Color(0xFF252B30))
                    }
                }
                View { attr { flex(1f) } }
                View {
                    attr { size(36f, 36f); allCenter() }
                    Image { attr { src(ImageUri.commonAssets("x.svg")); size(21f, 21f) } }
                    event { click { onClose() } }
                }
            }
            vif({ error().isNotEmpty() }) {
                Text {
                    attr {
                        text(error())
                        marginTop(6f)
                        marginBottom(6f)
                        fontSize(12f)
                        color(Color(0xFFBF3535))
                    }
                }
            }
            vif({ busy() && options().isEmpty() }) {
                Text {
                    attr {
                        text("正在加载模型...")
                        marginTop(24f)
                        fontSize(14f)
                        color(Color(0xFF7D858C))
                    }
                }
            }
            Scroller {
                attr { flex(1f); marginTop(8f) }
                vfor({ options() }) { option ->
                    View {
                        attr {
                            minHeight(58f)
                            marginBottom(6f)
                            flexDirectionRow()
                            alignItemsCenter()
                            padding(10f, 12f, 10f, 12f)
                            borderRadius(10f)
                            backgroundColor(Color(if (option.selected) 0xFFF0F3FA else 0xFFF8F8F9))
                        }
                        View {
                            attr { flex(1f); flexDirectionColumn() }
                            Text {
                                attr {
                                    text(option.name)
                                    fontSize(14f)
                                    fontWeightMedium()
                                    color(Color(0xFF2C3237))
                                }
                            }
                            Text {
                                attr {
                                    text(option.providerName + if (option.description.isEmpty()) "" else " · ${option.description}")
                                    marginTop(3f)
                                    lines(1)
                                    fontSize(11f)
                                    color(Color(0xFF8B939A))
                                }
                            }
                        }
                        if (option.selected) {
                            Text { attr { text("✓"); fontSize(17f); color(Color(0xFF4176E6)) } }
                        }
                        event { click { if (!busy()) onSelect(option) } }
                    }
                }
            }
        }
    }
}
