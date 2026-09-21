package com.example.dsh.dsh

import com.tencent.kuikly.core.base.*
import com.tencent.kuikly.core.base.attr.ImageUri
import com.tencent.kuikly.core.views.Image
import com.tencent.kuikly.core.views.Modal
import com.tencent.kuikly.core.views.Scroller
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View

// ===== 设置列表页 =====
// 全屏 Modal，顶部返回 + 标题，下方分组列表。
// 颜色硬编码，对齐 fork 主题 token。

private const val SETTINGS_SCREEN_MARGIN = 16f
private const val SETTINGS_CARD_PADDING = 16f
private const val SETTINGS_CARD_RADIUS = 12f
private const val SETTINGS_CARD_GAP = 24f
private const val SETTINGS_TITLE_GAP = 8f
private const val SETTINGS_ROW_HEIGHT = 52f

// 颜色常量（对齐 fork 主题 token：nb75/nb00/nb1000/nb700/nb600/borderL1/deepseek500/red600）
private val COLOR_BG_CANVAS = Color(0xFFF1F3F5)
private val COLOR_BG_CARD = Color(0xFFFFFFFF)
private val COLOR_LABEL_PRIMARY = Color(0xFF0F1115)
private val COLOR_LABEL_SECONDARY = Color(0xFF61666B)
private val COLOR_LABEL_TERTIARY = Color(0xFF81858C)
private val COLOR_BORDER = Color(0x0A000000)
private val COLOR_ACCENT = Color(0xFF4176E6)
private val COLOR_ERROR = Color(0xFFEC1313)

internal fun ViewContainer<*, *>.DshSettingsPage(
    connectionModeLabel: () -> String,
    onClose: () -> Unit,
    onOpenConnection: () -> Unit,
) {
    Modal(inWindow = true) {
        attr {
            absolutePositionAllZero()
            backgroundColor(COLOR_BG_CANVAS)
        }
        // 顶部栏
        View {
            attr {
                height(pagerData.statusBarHeight + 52f)
                paddingTop(pagerData.statusBarHeight)
                flexDirectionRow()
                alignItemsCenter()
                paddingLeft(SETTINGS_SCREEN_MARGIN)
                paddingRight(SETTINGS_SCREEN_MARGIN - 4f)
                backgroundColor(COLOR_BG_CANVAS)
            }
            View {
                attr { flex(1f); flexDirectionRow(); alignItemsCenter() }
                View {
                    attr { size(40f, 40f); borderRadius(20f); backgroundColor(Color(0x00000000)); allCenter() }
                    Image { attr { src(ImageUri.commonAssets("chevron-left.svg")); size(20f, 20f); tintColor(COLOR_LABEL_PRIMARY) } }
                    event { click { onClose() } }
                }
            }
            Text { attr { text("设置"); fontSize(17f); fontWeightBold(); color(COLOR_LABEL_PRIMARY) } }
            View { attr { flex(1f); flexDirectionRow(); justifyContentFlexEnd(); alignItemsCenter() } }
        }
        // 内容区
        Scroller {
            attr {
                flex(1f)
                width(pagerData.pageViewWidth)
                backgroundColor(COLOR_BG_CANVAS)
            }
            // 账户
            DshSettingsCardGroupTitle("账户")
            DshSettingsCard {
                DshSettingsRow("icon-link16.svg", "连接设置", connectionModeLabel, onOpenConnection, showDivider = true)
                DshSettingsRow("icon-data16.svg", "模型", { "" }, {}, showDivider = false)
            }

            // 权限
            DshSettingsCardGroupTitle("权限")
            DshSettingsCard {
                DshSettingsRow("permission-write.svg", "工作区权限", { "" }, {}, showDivider = false)
            }

            // 应用
            DshSettingsCardGroupTitle("应用")
            DshSettingsCard {
                DshSettingsRow("icon-globe14.svg", "语言", { "" }, {}, showDivider = true)
                DshSettingsRow("icon-followsystem16.svg", "外观", { "" }, {}, showDivider = true)
                DshSettingsRow("personalize.svg", "个性化", { "" }, {}, showDivider = true)
                DshSettingsRow("icon-agentpreset16.svg", "Agent 预设", { "" }, {}, showDivider = true)
                DshSettingsRow("log.svg", "日志", { "" }, {}, showDivider = true)
                DshSettingsRow("icon-plugin16.svg", "Host 插件", { "" }, {}, showDivider = false)
            }

            // 关于
            DshSettingsCardGroupTitle("关于")
            DshSettingsCard {
                DshSettingsRow("icon-refresh16.svg", "电脑端 DSH 版本", { "" }, {}, showDivider = false)
            }

            // 断开连接
            DshSettingsCard(topMargin = SETTINGS_CARD_GAP) {
                DshSettingsRow("", "断开连接", { "" }, {}, danger = true, showChevron = false, showDivider = false)
            }

            Text {
                attr {
                    text("设置同步至电脑端 DSH（~/.dsh/settings.yaml）")
                    marginTop(20f)
                    marginBottom(28f)
                    marginLeft(SETTINGS_SCREEN_MARGIN + SETTINGS_CARD_PADDING)
                    marginRight(SETTINGS_SCREEN_MARGIN)
                    fontSize(11f)
                    color(COLOR_LABEL_TERTIARY)
                }
            }
        }
    }
}

// ===== 卡片容器 =====
private fun ViewContainer<*, *>.DshSettingsCard(
    topMargin: Float = 0f,
    content: ViewContainer<*, *>.() -> Unit,
) {
    View {
        attr {
            marginTop(topMargin)
            marginLeft(SETTINGS_SCREEN_MARGIN)
            marginRight(SETTINGS_SCREEN_MARGIN)
            flexDirectionColumn()
            borderRadius(SETTINGS_CARD_RADIUS)
            backgroundColor(COLOR_BG_CARD)
        }
        content()
    }
}

// ===== 分组标题 =====
private fun ViewContainer<*, *>.DshSettingsCardGroupTitle(title: String) {
    Text {
        attr {
            text(title)
            marginTop(SETTINGS_CARD_GAP)
            marginBottom(SETTINGS_TITLE_GAP)
            marginLeft(SETTINGS_SCREEN_MARGIN + SETTINGS_CARD_PADDING)
            fontSize(13f)
            color(COLOR_LABEL_TERTIARY)
        }
    }
}

// ===== 设置行 =====
private fun ViewContainer<*, *>.DshSettingsRow(
    icon: String,
    title: String,
    value: () -> String,
    onClick: () -> Unit,
    danger: Boolean = false,
    showChevron: Boolean = true,
    showDivider: Boolean = true,
) {
    View {
        attr {
            height(SETTINGS_ROW_HEIGHT)
            flexDirectionRow()
            alignItemsCenter()
            paddingLeft(SETTINGS_CARD_PADDING)
            paddingRight(SETTINGS_CARD_PADDING)
            backgroundColor(Color(0x00000000))
        }
        if (icon.isNotEmpty()) {
            Image {
                attr {
                    src(ImageUri.commonAssets(icon))
                    size(20f, 20f)
                    tintColor(if (danger) COLOR_ERROR else COLOR_LABEL_SECONDARY)
                }
            }
        }
        Text {
            attr {
                text(title)
                flex(1f)
                if (icon.isNotEmpty()) marginLeft(12f)
                fontSize(14f)
                color(if (danger) COLOR_ERROR else COLOR_LABEL_PRIMARY)
            }
        }
        if (value().isNotEmpty()) {
            Text {
                attr {
                    text(value())
                    fontSize(13f)
                    color(COLOR_LABEL_TERTIARY)
                }
            }
        }
        if (showChevron) {
            Image {
                attr {
                    src(ImageUri.commonAssets("chevron-right.svg"))
                    size(16f, 16f)
                    marginLeft(6f)
                    tintColor(if (danger) COLOR_ERROR else COLOR_LABEL_TERTIARY)
                }
            }
        }
        event { click { onClick() } }
    }
    if (showDivider) {
        View {
            attr {
                height(1f)
                marginLeft(SETTINGS_CARD_PADDING)
                marginRight(SETTINGS_CARD_PADDING)
                backgroundColor(COLOR_BORDER)
            }
        }
    }
}
