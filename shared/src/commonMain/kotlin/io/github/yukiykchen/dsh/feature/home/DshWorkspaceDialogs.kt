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
import com.tencent.kuikly.core.views.Input
import com.tencent.kuikly.core.views.Modal
import com.tencent.kuikly.core.views.Scroller
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View
import io.github.yukiykchen.dsh.ui.DshHitButton
import io.github.yukiykchen.dsh.model.DshDirectoryEntry

fun ViewContainer<*, *>.DshWorkspaceBrowserModal(
    path: () -> String,
    home: () -> String,
    entries: () -> ObservableList<DshDirectoryEntry>,
    busy: () -> Boolean,
    error: () -> String,
    newName: () -> String,
    onDirectorySelect: (String) -> Unit,
    onNewNameChange: (String) -> Unit,
    onCreateDirectory: () -> Unit,
    onAdopt: () -> Unit,
    onClose: () -> Unit,
) {
    Modal(inWindow = true) {
        attr {
            absolutePositionAllZero()
            allCenter()
            paddingLeft(20f)
            paddingRight(20f)
            backgroundColor(Color(0x66000000))
        }
        View {
            attr {
                width(pagerData.pageViewWidth - 40f)
                maxWidth(560f)
                maxHeight(pagerData.pageViewHeight - 80f)
                flexDirectionColumn()
                padding(18f)
                borderRadius(16f)
                backgroundColor(Color.WHITE)
            }
            View {
                attr { height(36f); flexDirectionRow(); alignItemsCenter() }
                Text {
                    attr {
                        text(if (path().isEmpty()) home() else path())
                        flex(1f)
                        lines(1)
                        fontSize(17f)
                        fontWeightBold()
                        color(Color(0xFF1F2933))
                    }
                }
                View { attr { size(32f, 32f); allCenter() }; Image { attr { src(ImageUri.commonAssets("x.svg")); size(20f, 20f) } }; DshHitButton { onClose() } }
            }
            Scroller {
                attr {
                    flex(1f)
                    marginTop(12f)
                    borderRadius(8f)
                    backgroundColor(Color(0xFFF7F9FA))
                }
                vfor({ entries() }) { entry ->
                    View {
                        attr {
                            height(42f)
                            flexDirectionRow()
                            alignItemsCenter()
                            paddingLeft(10f)
                            paddingRight(10f)
                        }
                        Text {
                            attr {
                                text(entry.name)
                                flex(1f)
                                lines(1)
                                fontSize(14f)
                                color(Color(0xFF343E47))
                            }
                        }
                        event { click { if (!busy()) onDirectorySelect(entry.path) } }
                    }
                }
            }
            vif({ error().isNotEmpty() }) {
                Text { attr { text(error()); marginTop(8f); fontSize(12f); color(Color(0xFFBF3535)) } }
            }
            Input {
                attr {
                    height(38f)
                    marginTop(10f)
                    fontSize(14f)
                    placeholder("新目录名称")
                    placeholderColor(Color(0xFF98A1A9))
                }
                event { textDidChange { onNewNameChange(it.text) } }
            }
            View {
                attr { height(42f); marginTop(12f); flexDirectionRow(); justifyContentFlexEnd() }
                Text {
                    attr {
                        text(if (busy()) "处理中..." else "新建目录")
                        width(88f)
                        height(38f)
                        textAlignCenter()
                        fontSize(13f)
                        color(Color(0xFF7A838A))
                    }
                    event { click { if (!busy()) onCreateDirectory() } }
                }
                Text {
                    attr {
                        text(if (busy()) "处理中..." else "使用此目录")
                        width(112f)
                        height(38f)
                        marginLeft(8f)
                        textAlignCenter()
                        fontSize(13f)
                        color(Color(0xFF4176E6))
                    }
                    event { click { if (!busy()) onAdopt() } }
                }
            }
        }
    }
}

/** Centered white card over a dimmed window, shared by the small workspace dialogs. */

fun ViewContainer<*, *>.DshDialogCard(content: ViewContainer<*, *>.() -> Unit) {
    Modal(inWindow = true) {
        attr {
            absolutePositionAllZero()
            allCenter()
            paddingLeft(20f)
            paddingRight(20f)
            backgroundColor(Color(0x66000000))
        }
        View {
            attr {
                width(pagerData.pageViewWidth - 40f)
                maxWidth(420f)
                padding(20f)
                borderRadius(16f)
                backgroundColor(Color.WHITE)
            }
            content()
        }
    }
}

fun ViewContainer<*, *>.DshDialogActions(
    confirmLabel: () -> String,
    confirmWidth: Float,
    confirmColor: Long,
    busy: () -> Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    View {
        attr { height(40f); marginTop(18f); flexDirectionRow(); justifyContentFlexEnd() }
        Text {
            attr { text("取消"); width(78f); height(38f); textAlignCenter(); fontSize(14f); color(Color(0xFF7A838A)) }
            event { click { onCancel() } }
        }
        Text {
            attr { text(confirmLabel()); width(confirmWidth); height(38f); marginLeft(8f); textAlignCenter(); fontSize(14f); color(Color(confirmColor)) }
            event { click { if (!busy()) onConfirm() } }
        }
    }
}

fun ViewContainer<*, *>.DshDialogError(error: () -> String) {
    vif({ error().isNotEmpty() }) {
        Text { attr { text(error()); marginTop(8f); fontSize(12f); color(Color(0xFFBF3535)) } }
    }
}

fun ViewContainer<*, *>.DshWorkspaceRenameModal(
    draft: () -> String,
    busy: () -> Boolean,
    error: () -> String,
    onDraftChange: (String) -> Unit,
    onSave: () -> Unit,
    onClose: () -> Unit,
) {
    DshDialogCard {
        Text { attr { text("重命名工作区"); fontSize(18f); fontWeightBold(); color(Color(0xFF1F2933)) } }
        Input {
            attr {
                height(38f)
                marginTop(14f)
                fontSize(14f)
                placeholder("工作区名称")
                placeholderColor(Color(0xFF98A1A9))
                text(draft())
            }
            event { textDidChange { onDraftChange(it.text) } }
        }
        DshDialogError(error)
        DshDialogActions(
            confirmLabel = { if (busy()) "保存中..." else "保存" },
            confirmWidth = 78f,
            confirmColor = 0xFF4176E6,
            busy = busy,
            onConfirm = onSave,
            onCancel = onClose,
        )
    }
}

fun ViewContainer<*, *>.DshWorkspaceDeleteModal(
    busy: () -> Boolean,
    error: () -> String,
    onConfirm: () -> Unit,
    onClose: () -> Unit,
) {
    DshDialogCard {
        Text { attr { text("删除工作区注册?"); fontSize(18f); fontWeightBold(); color(Color(0xFF1F2933)) } }
        Text {
            attr {
                text("只会从列表移除注册，不会删除目录、会话或日志。")
                marginTop(8f)
                fontSize(13f)
                lineHeight(20f)
                color(Color(0xFF68737D))
            }
        }
        DshDialogError(error)
        DshDialogActions(
            confirmLabel = { if (busy()) "删除中..." else "删除注册" },
            confirmWidth = 112f,
            confirmColor = 0xFFD25A5A,
            busy = busy,
            onConfirm = onConfirm,
            onCancel = onClose,
        )
    }
}
