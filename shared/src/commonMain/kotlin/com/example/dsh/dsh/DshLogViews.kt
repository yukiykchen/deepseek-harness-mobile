package com.example.dsh.dsh

import com.tencent.kuikly.core.base.*
import com.tencent.kuikly.core.base.attr.ImageUri
import com.tencent.kuikly.core.directives.vfor
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.reactive.collection.ObservableList
import com.tencent.kuikly.core.views.Image
import com.tencent.kuikly.core.views.Input
import com.tencent.kuikly.core.views.Modal
import com.tencent.kuikly.core.views.Scroller
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View

/** How many rows the sheet paints at once, so opening it never competes with a live turn. */
internal const val DSH_LOG_RENDER_LIMIT = 200

/**
 * The log centre (Task 6). Records are a snapshot taken when the sheet opens or when
 * Refresh is tapped: nothing here re-renders per frame, which is what keeps the
 * conversation page smooth while a turn streams.
 */
internal fun ViewContainer<*, *>.DshLogCenterModal(
    records: () -> ObservableList<DshLogRecord>,
    typeChips: () -> ObservableList<DshLogTypeChip>,
    total: () -> Int,
    matched: () -> Int,
    now: () -> Long,
    minLevel: () -> DshLogLevel,
    eventType: () -> String,
    sessionScoped: () -> Boolean,
    windowMs: () -> Long,
    sessionLabel: () -> String,
    onQueryChange: (String) -> Unit,
    onSelectLevel: (DshLogLevel) -> Unit,
    onSelectType: (String) -> Unit,
    onToggleSession: () -> Unit,
    onSelectWindow: (Long) -> Unit,
    onOpenRecord: (DshLogRecord) -> Unit,
    onRefresh: () -> Unit,
    onExport: () -> Unit,
    onClear: () -> Unit,
    onClose: () -> Unit,
) {
    Modal(inWindow = true) {
        attr {
            absolutePositionAllZero()
            flexDirectionColumn()
            justifyContentFlexEnd()
            backgroundColor(theme.maskLight)
        }
        View {
            attr { flex(1f) }
            event { click { onClose() } }
        }
        View {
            attr {
                height((pagerData.pageViewHeight * 0.88f).coerceAtMost(720f))
                flexDirectionColumn()
                padding(18f)
                borderRadius(20f)
                backgroundColor(theme.surface)
            }
            View {
                attr { height(34f); flexDirectionRow(); alignItemsCenter() }
                Text { attr { text("Logs"); flex(1f); fontSize(19f); fontWeightBold(); color(theme.textPrimary) } }
                DshLogHeaderButton("Refresh") { onRefresh() }
                DshLogHeaderButton("Export") { onExport() }
                DshLogHeaderButton("Clear") { onClear() }
                View {
                    attr { size(32f, 32f); marginLeft(2f); allCenter() }
                    Image { attr { src(ImageUri.commonAssets("x.svg")); size(20f, 20f); tintColor(theme.textSecondary) } }
                    DshHitButton { onClose() }
                }
            }
            Text {
                attr {
                    text(
                        "Connection, RPC and conversation frames the app observed. Kept in memory " +
                            "only (newest ${DshLogCenter.CAPACITY}), redacted, and never including " +
                            "message text or attachment bytes.",
                    )
                    marginTop(4f)
                    fontSize(11f)
                    lineHeight(16f)
                    color(theme.textMuted)
                }
            }
            View {
                attr {
                    height(40f)
                    marginTop(10f)
                    paddingLeft(12f)
                    paddingRight(12f)
                    justifyContentCenter()
                    borderRadius(10f)
                    backgroundColor(theme.inputBackground)
                    border(Border(1f, BorderStyle.SOLID, theme.inputBorder))
                }
                Input {
                    attr {
                        height(38f)
                        fontSize(14f)
                        color(theme.textPrimary)
                        placeholder("Search summary, session or ref")
                        placeholderColor(theme.placeholder)
                    }
                    event { textDidChange { onQueryChange(it.text) } }
                }
            }
            Scroller {
                attr { height(36f); marginTop(8f); flexDirectionRow() }
                vfor({ typeChips() }) { chip ->
                    View {
                        attr {
                            height(28f)
                            marginRight(6f)
                            paddingLeft(10f)
                            paddingRight(10f)
                            allCenter()
                            borderRadius(14f)
                            backgroundColor(
                                if (eventType() == chip.eventType) theme.accentSoft else theme.surfaceSunken,
                            )
                            border(
                                Border(
                                    1f,
                                    BorderStyle.SOLID,
                                    if (eventType() == chip.eventType) theme.accentBorder else theme.border,
                                ),
                            )
                        }
                        Text {
                            attr {
                                text("${chip.label} ${chip.count}")
                                fontSize(11f)
                                color(if (eventType() == chip.eventType) theme.accent else theme.textSecondary)
                            }
                        }
                        event { click { onSelectType(chip.eventType) } }
                    }
                }
            }
            View {
                attr {
                    height(34f)
                    marginTop(8f)
                    flexDirectionRow()
                    borderRadius(8f)
                    backgroundColor(theme.surfaceSunken)
                    padding(3f)
                }
                DshLogLevel.values().forEach { level ->
                    DshLogSegment(level.label, { minLevel() == level }) { onSelectLevel(level) }
                }
            }
            View {
                attr { height(30f); marginTop(8f); flexDirectionRow(); alignItemsCenter() }
                View {
                    attr {
                        height(26f)
                        paddingLeft(10f)
                        paddingRight(10f)
                        allCenter()
                        borderRadius(13f)
                        backgroundColor(if (sessionScoped()) theme.accentSoft else theme.surfaceSunken)
                        border(
                            Border(
                                1f,
                                BorderStyle.SOLID,
                                if (sessionScoped()) theme.accentBorder else theme.border,
                            ),
                        )
                    }
                    Text {
                        attr {
                            text(sessionLabel())
                            lines(1)
                            fontSize(11f)
                            color(if (sessionScoped()) theme.accent else theme.textSecondary)
                        }
                    }
                    event { click { onToggleSession() } }
                }
                View { attr { flex(1f) } }
                DshLogWindowChip("All", 0L, windowMs, onSelectWindow)
                DshLogWindowChip("5 min", 300_000L, windowMs, onSelectWindow)
                DshLogWindowChip("1 h", 3_600_000L, windowMs, onSelectWindow)
            }
            Text {
                attr {
                    text(
                        when {
                            total() == 0 -> "No records yet"
                            matched() > records().size -> "${records().size} of ${matched()} matching records · newest first"
                            else -> "${matched()} of ${total()} records · newest first"
                        },
                    )
                    marginTop(10f)
                    fontSize(11f)
                    color(theme.textMuted)
                }
            }
            vif({ records().isEmpty() }) {
                View {
                    attr { flex(1f); allCenter(); padding(16f) }
                    Text {
                        attr {
                            text(if (total() == 0) "Nothing has been recorded yet" else "No records match")
                            fontSize(14f)
                            color(theme.textMuted)
                        }
                    }
                    Text {
                        attr {
                            text(
                                if (total() == 0) {
                                    "Connect to a Host and send a turn; connection, RPC and stream frames land here."
                                } else {
                                    "Lower the level, widen the time range, or clear the search."
                                },
                            )
                            marginTop(6f)
                            fontSize(11f)
                            lineHeight(16f)
                            textAlignCenter()
                            color(theme.textMuted)
                        }
                    }
                }
            }
            vif({ records().isNotEmpty() }) {
                Scroller {
                    attr { flex(1f); marginTop(6f) }
                    vfor({ records() }) { record ->
                        DshLogRow(record, now) { onOpenRecord(record) }
                    }
                }
            }
        }
    }
}

private fun ViewContainer<*, *>.DshLogRow(
    record: DshLogRecord,
    now: () -> Long,
    onOpen: () -> Unit,
) {
    View {
        attr {
            marginBottom(6f)
            paddingTop(8f)
            paddingBottom(8f)
            paddingLeft(10f)
            paddingRight(10f)
            flexDirectionColumn()
            borderRadius(9f)
            backgroundColor(theme.surfaceRaised)
        }
        View {
            attr { flexDirectionRow(); alignItemsCenter() }
            Text {
                attr {
                    text(record.level.short)
                    width(30f)
                    fontSize(10f)
                    fontWeightBold()
                    color(dshLogLevelColor(record.level, theme))
                }
            }
            Text {
                attr {
                    text(record.eventType)
                    flex(1f)
                    lines(1)
                    fontSize(12f)
                    fontWeightMedium()
                    color(theme.textPrimary)
                }
            }
            Text {
                attr {
                    text(DshLogFormat.age(record.timeMs, now()))
                    marginLeft(6f)
                    fontSize(10f)
                    color(theme.textMuted)
                }
            }
        }
        Text {
            attr {
                text(record.summary)
                marginTop(3f)
                lines(2)
                fontSize(11f)
                lineHeight(15f)
                color(theme.textSecondary)
            }
        }
        event { click { onOpen() } }
    }
}

/** One record in full, which is also exactly what Copy puts on the clipboard. */
internal fun ViewContainer<*, *>.DshLogDetailModal(
    record: () -> DshLogRecord?,
    now: () -> Long,
    onCopy: () -> Unit,
    onClose: () -> Unit,
) {
    vif({ record() != null }) {
        Modal(inWindow = true) {
            attr { absolutePositionAllZero(); allCenter(); backgroundColor(theme.mask); padding(20f) }
            View {
                attr {
                    width(pagerData.pageViewWidth - 40f)
                    maxWidth(440f)
                    // A fixed height so the body Scroller has something to fill; a Scroller
                    // under maxHeight alone collapses to nothing.
                    height((pagerData.pageViewHeight * 0.52f).coerceAtMost(430f))
                    flexDirectionColumn()
                    padding(20f)
                    borderRadius(16f)
                    backgroundColor(theme.surface)
                }
                View {
                    attr { height(30f); flexDirectionRow(); alignItemsCenter() }
                    Text {
                        attr {
                            text(record()?.eventType.orEmpty())
                            flex(1f)
                            lines(1)
                            fontSize(16f)
                            fontWeightBold()
                            color(theme.textPrimary)
                        }
                    }
                    Text {
                        attr {
                            text(record()?.level?.label.orEmpty())
                            marginRight(8f)
                            fontSize(11f)
                            fontWeightMedium()
                            color(record()?.level?.let { dshLogLevelColor(it, theme) } ?: theme.textMuted)
                        }
                    }
                    View {
                        attr { size(30f, 30f); allCenter() }
                        Image {
                            attr {
                                src(ImageUri.commonAssets("x.svg"))
                                size(18f, 18f)
                                tintColor(theme.textSecondary)
                            }
                        }
                        DshHitButton { onClose() }
                    }
                }
                Scroller {
                    attr {
                        flex(1f)
                        marginTop(12f)
                        padding(10f)
                        borderRadius(10f)
                        backgroundColor(theme.codeBackground)
                    }
                    Text {
                        attr {
                            text(record()?.let { DshLogFormat.detail(it, now()) }.orEmpty())
                            fontSize(11f)
                            lineHeight(17f)
                            color(theme.codeText)
                        }
                    }
                }
                View {
                    attr { height(40f); marginTop(14f); flexDirectionRow() }
                    View {
                        attr {
                            flex(1f); marginRight(8f); allCenter()
                            borderRadius(10f); backgroundColor(theme.accentFill)
                        }
                        Text {
                            attr {
                                text("Copy")
                                fontSize(14f)
                                fontWeightMedium()
                                color(theme.textOnAccent)
                            }
                        }
                        DshHitButton { onCopy() }
                    }
                    View {
                        attr {
                            flex(1f); marginLeft(8f); allCenter()
                            borderRadius(10f); backgroundColor(theme.surfaceSunken)
                        }
                        Text { attr { text("Close"); fontSize(14f); color(theme.textSecondary) } }
                        DshHitButton { onClose() }
                    }
                }
            }
        }
    }
}

private fun ViewContainer<*, *>.DshLogHeaderButton(label: String, onClick: () -> Unit) {
    View {
        attr {
            height(28f)
            marginLeft(6f)
            paddingLeft(10f)
            paddingRight(10f)
            allCenter()
            borderRadius(8f)
            backgroundColor(theme.surfaceSunken)
        }
        Text { attr { text(label); fontSize(11f); fontWeightMedium(); color(theme.accent) } }
        event { click { onClick() } }
    }
}

private fun ViewContainer<*, *>.DshLogSegment(
    label: String,
    selected: () -> Boolean,
    onSelect: () -> Unit,
) {
    View {
        attr {
            flex(1f)
            height(28f)
            allCenter()
            borderRadius(6f)
            backgroundColor(if (selected()) theme.surface else Color(0x00FFFFFF))
        }
        Text {
            attr {
                text(label)
                fontSize(12f)
                color(if (selected()) theme.accent else theme.textSecondary)
            }
        }
        event { click { onSelect() } }
    }
}

private fun ViewContainer<*, *>.DshLogWindowChip(
    label: String,
    value: Long,
    current: () -> Long,
    onSelect: (Long) -> Unit,
) {
    View {
        attr {
            height(26f)
            marginLeft(6f)
            paddingLeft(10f)
            paddingRight(10f)
            allCenter()
            borderRadius(13f)
            backgroundColor(if (current() == value) theme.accentSoft else theme.surfaceSunken)
            border(
                Border(
                    1f,
                    BorderStyle.SOLID,
                    if (current() == value) theme.accentBorder else theme.border,
                ),
            )
        }
        Text {
            attr {
                text(label)
                fontSize(11f)
                color(if (current() == value) theme.accent else theme.textSecondary)
            }
        }
        event { click { onSelect(value) } }
    }
}

private fun dshLogLevelColor(level: DshLogLevel, palette: DshPalette): Color = when (level) {
    DshLogLevel.DEBUG -> palette.textMuted
    DshLogLevel.INFO -> palette.accent
    DshLogLevel.WARN -> palette.warningText
    DshLogLevel.ERROR -> palette.dangerText
}
