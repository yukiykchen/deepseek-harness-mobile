package com.example.dsh.dsh

import com.example.dsh.theme.DshThemePreference
import com.example.dsh.theme.theme
import com.example.dsh.theme.tokens
import com.tencent.kuikly.core.base.Border
import com.tencent.kuikly.core.base.BorderStyle
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.base.attr.ImageUri
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.views.Image
import com.tencent.kuikly.core.views.Modal
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View

internal fun ViewContainer<*, *>.DshAppearanceModal(
    onSelect: (DshThemePreference) -> Unit,
    onClose: () -> Unit,
) {
    Modal(inWindow = true) {
        attr {
            absolutePositionAllZero()
            allCenter()
            paddingLeft(20f)
            paddingRight(20f)
            backgroundColor(tokens.scrim)
        }
        View {
            attr {
                absolutePositionAllZero()
                backgroundColor(Color.TRANSPARENT)
            }
            event { click { onClose() } }
        }
        View {
            attr {
                width(pagerData.pageViewWidth - 40f)
                maxWidth(420f)
                flexDirectionColumn()
                padding(24f)
                borderRadius(18f)
                backgroundColor(tokens.surface)
            }
            View {
                attr { height(32f); flexDirectionRow(); alignItemsCenter() }
                Text {
                    attr {
                        text("外观")
                        flex(1f)
                        fontSize(20f)
                        fontWeightBold()
                        color(tokens.primaryText)
                    }
                }
                View {
                    attr { size(32f, 32f); allCenter() }
                    Image {
                        attr {
                            src(ImageUri.commonAssets("x.svg"))
                            size(20f, 20f)
                            tintColor(tokens.icon)
                        }
                    }
                    event { click { onClose() } }
                }
            }
            Text {
                attr {
                    text("主题模式")
                    marginTop(14f)
                    fontSize(13f)
                    color(tokens.secondaryText)
                }
            }
            View {
                attr {
                    marginTop(8f)
                    flexDirectionColumn()
                    borderRadius(12f)
                    border(Border(1f, BorderStyle.SOLID, tokens.divider))
                    backgroundColor(tokens.surfaceVariant)
                    padding(4f)
                }
                DshThemePreference.entries.forEachIndexed { index, preference ->
                    DshAppearanceOption(
                        preference = preference,
                        marginTop = if (index == 0) 0f else 2f,
                        onSelect = { onSelect(preference) },
                    )
                }
            }
        }
    }
}

private fun ViewContainer<*, *>.DshAppearanceOption(
    preference: DshThemePreference,
    marginTop: Float,
    onSelect: () -> Unit,
) {
    View {
        attr {
            minHeight(48f)
            marginTop(marginTop)
            flexDirectionRow()
            alignItemsCenter()
            paddingLeft(12f)
            paddingRight(12f)
            paddingTop(10f)
            paddingBottom(10f)
            borderRadius(9f)
            backgroundColor(if (theme.preference == preference) tokens.surface else Color.TRANSPARENT)
        }
        View {
            attr {
                size(18f, 18f)
                borderRadius(9f)
                allCenter()
                border(
                    Border(
                        if (theme.preference == preference) 5f else 1.5f,
                        BorderStyle.SOLID,
                        if (theme.preference == preference) tokens.primary else tokens.dividerStrong,
                    ),
                )
                backgroundColor(tokens.surface)
            }
        }
        View {
            attr { flex(1f); marginLeft(12f); flexDirectionColumn() }
            Text {
                attr {
                    text(preference.label)
                    fontSize(15f)
                    fontWeightMedium()
                    color(tokens.primaryText)
                }
            }
            vif({ preference == DshThemePreference.SYSTEM && theme.preference == DshThemePreference.SYSTEM }) {
                Text {
                    attr {
                        text(if (theme.isDark) "当前：深色" else "当前：浅色")
                        marginTop(2f)
                        fontSize(12f)
                        color(tokens.tertiaryText)
                    }
                }
            }
        }
        event { click { onSelect() } }
    }
}
