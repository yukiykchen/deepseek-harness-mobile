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

/**
 * The Host's plugin inventory (Task 5).
 *
 * `pluginInventory/list` is read-only, so by default this sheet shows status and offers
 * a refresh and nothing else. When the optional companion plugin in `host-plugin/` is
 * installed it advertises which entries it will let the phone control, and only those
 * gain enable / disable / reload. Controls are never shown speculatively.
 */
internal fun ViewContainer<*, *>.DshPluginsModal(
    loading: () -> Boolean,
    error: () -> String,
    total: () -> Int,
    failedCount: () -> Int,
    rows: () -> ObservableList<DshPluginEntry>,
    chips: () -> ObservableList<DshPluginStatusChip>,
    brokenPresets: () -> ObservableList<String>,
    statusFilter: () -> DshPluginStatus?,
    expandedId: () -> String,
    query: () -> String,
    presetsFor: (DshPluginEntry) -> List<String>,
    controllableIds: () -> Set<String>,
    controlBusyId: () -> String,
    onQueryChange: (String) -> Unit,
    onSelectStatus: (DshPluginStatus?) -> Unit,
    onToggleExpand: (String) -> Unit,
    onControl: (String, String) -> Unit,
    onRefresh: () -> Unit,
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
                height((pagerData.pageViewHeight * 0.82f).coerceAtMost(660f))
                flexDirectionColumn()
                padding(18f)
                borderRadius(20f)
                backgroundColor(theme.surface)
            }
            View {
                attr { height(34f); flexDirectionRow(); alignItemsCenter() }
                Text { attr { text("Plugins"); flex(1f); fontSize(19f); fontWeightBold(); color(theme.textPrimary) } }
                View {
                    attr {
                        height(30f)
                        paddingLeft(12f)
                        paddingRight(12f)
                        allCenter()
                        borderRadius(8f)
                        backgroundColor(theme.surfaceSunken)
                    }
                    Text {
                        attr {
                            text(if (loading()) "Refreshing…" else "Refresh")
                            fontSize(12f)
                            fontWeightMedium()
                            color(if (loading()) theme.textDisabled else theme.accent)
                        }
                    }
                    DshHitButton { if (!loading()) onRefresh() }
                }
                View {
                    attr { size(32f, 32f); marginLeft(4f); allCenter() }
                    Image { attr { src(ImageUri.commonAssets("x.svg")); size(20f, 20f); tintColor(theme.textSecondary) } }
                    DshHitButton { onClose() }
                }
            }
            Text {
                attr {
                    text(
                        if (controllableIds().isEmpty()) {
                            "Loaded by the remote DSH Host. The Host's plugin inventory is read-only, " +
                                "so there is nothing to enable or disable here."
                        } else {
                            "Loaded by the remote DSH Host. The dsh-mobile-admin plugin is installed, " +
                                "so an entry can be enabled, disabled or reloaded — open one to see. " +
                                "Modules carrying the connection or session state stay read-only."
                        },
                    )
                    marginTop(4f)
                    fontSize(11f)
                    lineHeight(16f)
                    color(theme.textMuted)
                }
            }

            vif({ !loading() && error().isEmpty() && total() > 0 }) {
                View {
                    attr {
                        height(42f)
                        marginTop(12f)
                        paddingLeft(12f)
                        paddingRight(12f)
                        justifyContentCenter()
                        borderRadius(10f)
                        backgroundColor(theme.inputBackground)
                        border(Border(1f, BorderStyle.SOLID, theme.inputBorder))
                    }
                    Input {
                        attr {
                            height(40f)
                            fontSize(14f)
                            color(theme.textPrimary)
                            // The field is rebuilt whenever the sheet re-renders — which
                            // now happens when the confirmation modal closes — so the
                            // text has to come from state, or the box goes blank while
                            // the list stays filtered.
                            text(query())
                            placeholder("Search by module or entry id")
                            placeholderColor(theme.placeholder)
                        }
                        event { textDidChange { onQueryChange(it.text) } }
                    }
                }
                Scroller {
                    attr { height(38f); marginTop(10f); flexDirectionRow() }
                    vfor({ chips() }) { chip ->
                        View {
                            attr {
                                height(30f)
                                marginRight(6f)
                                paddingLeft(11f)
                                paddingRight(11f)
                                flexDirectionRow()
                                alignItemsCenter()
                                borderRadius(15f)
                                backgroundColor(
                                    if (statusFilter() == chip.status) theme.accentSoft else theme.surfaceSunken,
                                )
                                border(
                                    Border(
                                        1f,
                                        BorderStyle.SOLID,
                                        if (statusFilter() == chip.status) theme.accentBorder else theme.border,
                                    ),
                                )
                            }
                            Text {
                                attr {
                                    text("${chip.label} ${chip.count}")
                                    fontSize(12f)
                                    color(
                                        if (statusFilter() == chip.status) theme.accent else theme.textSecondary,
                                    )
                                }
                            }
                            event { click { onSelectStatus(chip.status) } }
                        }
                    }
                }
                Text {
                    attr {
                        text(
                            when {
                                rows().size != total() -> "${rows().size} of ${total()} plugins"
                                failedCount() == 0 -> "${total()} plugins"
                                else -> "${total()} plugins · ${failedCount()} failed"
                            },
                        )
                        marginTop(10f)
                        fontSize(11f)
                        color(theme.textMuted)
                    }
                }
            }

            // The broken-preset note belongs to the snapshot on screen, so it goes away
            // with the list while a fetch is in flight or has failed.
            vif({ !loading() && error().isEmpty() && brokenPresets().isNotEmpty() }) {
                View {
                    attr {
                        marginTop(10f)
                        padding(10f)
                        flexDirectionColumn()
                        borderRadius(10f)
                        backgroundColor(theme.warningSoft)
                        border(Border(1f, BorderStyle.SOLID, theme.warningBorder))
                    }
                    Text {
                        attr {
                            text("Agent presets the Host could not read")
                            fontSize(11f)
                            fontWeightMedium()
                            color(theme.warningText)
                        }
                    }
                    vfor({ brokenPresets() }) { line ->
                        Text {
                            attr {
                                text(line)
                                marginTop(4f)
                                fontSize(11f)
                                lineHeight(16f)
                                color(theme.textSecondary)
                            }
                        }
                    }
                }
            }

            vif({ loading() }) {
                View {
                    attr { flex(1f); allCenter() }
                    Text { attr { text("Reading plugins from the Host…"); fontSize(14f); color(theme.textMuted) } }
                }
            }
            vif({ !loading() && error().isNotEmpty() }) {
                View {
                    attr { flex(1f); allCenter(); padding(16f) }
                    Text {
                        attr {
                            text("Could not read the plugin list")
                            fontSize(15f)
                            fontWeightMedium()
                            color(theme.textPrimary)
                        }
                    }
                    Text {
                        attr {
                            text(error())
                            marginTop(8f)
                            fontSize(12f)
                            lineHeight(17f)
                            textAlignCenter()
                            color(theme.dangerText)
                        }
                    }
                    View {
                        attr {
                            height(38f)
                            marginTop(14f)
                            paddingLeft(20f)
                            paddingRight(20f)
                            allCenter()
                            borderRadius(10f)
                            backgroundColor(theme.accentFill)
                        }
                        Text {
                            attr {
                                text("Retry")
                                fontSize(14f)
                                fontWeightMedium()
                                color(theme.textOnAccent)
                            }
                        }
                        DshHitButton { onRefresh() }
                    }
                }
            }
            vif({ !loading() && error().isEmpty() && total() == 0 }) {
                View {
                    attr { flex(1f); allCenter(); padding(16f) }
                    Text {
                        attr {
                            text("The Host reported no plugins")
                            fontSize(14f)
                            color(theme.textMuted)
                        }
                    }
                    Text {
                        attr {
                            text("Its plugin loader has no entry a trusted client may read.")
                            marginTop(6f)
                            fontSize(11f)
                            lineHeight(16f)
                            textAlignCenter()
                            color(theme.textMuted)
                        }
                    }
                }
            }
            vif({ !loading() && error().isEmpty() && total() > 0 && rows().isEmpty() }) {
                View {
                    attr { flex(1f); allCenter(); padding(16f) }
                    Text { attr { text("No plugins match"); fontSize(14f); color(theme.textMuted) } }
                    Text {
                        attr {
                            text("Clear the search or pick another status.")
                            marginTop(6f)
                            fontSize(11f)
                            textAlignCenter()
                            color(theme.textMuted)
                        }
                    }
                }
            }
            vif({ !loading() && error().isEmpty() && rows().isNotEmpty() }) {
                Scroller {
                    attr { flex(1f); marginTop(10f) }
                    vfor({ rows() }) { entry ->
                        DshPluginCard(
                            entry = entry,
                            expanded = { expandedId() == entry.entryId },
                            presets = { presetsFor(entry) },
                            onToggle = { onToggleExpand(entry.entryId) },
                            controllable = { controllableIds().contains(entry.entryId) },
                            busy = { controlBusyId() == entry.entryId },
                            onControl = { action -> onControl(entry.entryId, action) },
                        )
                    }
                }
            }
        }
    }
}

private fun ViewContainer<*, *>.DshPluginControlButton(
    label: String,
    busy: () -> Boolean,
    onClick: () -> Unit,
) {
    View {
        attr {
            height(32f)
            marginRight(8f)
            paddingLeft(14f)
            paddingRight(14f)
            allCenter()
            borderRadius(8f)
            backgroundColor(theme.surfaceSunken)
            opacity(if (busy()) 0.5f else 1f)
        }
        Text {
            attr {
                text(if (busy()) "Working…" else label)
                fontSize(13f)
                fontWeightMedium()
                color(theme.accent)
            }
        }
        event { click { if (!busy()) onClick() } }
    }
}

/** One inventory row; tapping it opens the Host's full configuration for that plugin. */
private fun ViewContainer<*, *>.DshPluginCard(
    entry: DshPluginEntry,
    expanded: () -> Boolean,
    presets: () -> List<String>,
    onToggle: () -> Unit,
    controllable: () -> Boolean,
    busy: () -> Boolean,
    onControl: (String) -> Unit,
) {
    val status = DshPluginCatalog.status(entry)
    View {
        attr {
            marginBottom(8f)
            padding(12f)
            flexDirectionColumn()
            borderRadius(12f)
            backgroundColor(theme.surfaceRaised)
            border(
                Border(
                    1f,
                    BorderStyle.SOLID,
                    if (status == DshPluginStatus.FAILED) theme.danger else theme.border,
                ),
            )
        }
        View {
            attr { flexDirectionRow(); alignItemsCenter() }
            Text {
                attr {
                    text(DshPluginCatalog.shortModuleName(entry.moduleName))
                    flex(1f)
                    lines(1)
                    fontSize(14f)
                    fontWeightMedium()
                    color(theme.textPrimary)
                }
            }
            View {
                attr {
                    height(22f)
                    marginLeft(8f)
                    paddingLeft(8f)
                    paddingRight(8f)
                    flexDirectionRow()
                    alignItemsCenter()
                    borderRadius(11f)
                    backgroundColor(theme.surfaceSunken)
                }
                View {
                    attr {
                        size(6f, 6f)
                        marginRight(5f)
                        borderRadius(3f)
                        backgroundColor(dshPluginStatusColor(status, theme))
                    }
                }
                Text {
                    attr {
                        text(status.label)
                        fontSize(11f)
                        color(dshPluginStatusColor(status, theme))
                    }
                }
            }
            Text {
                attr {
                    text(if (expanded()) "▾" else "▸")
                    marginLeft(8f)
                    fontSize(12f)
                    color(theme.textMuted)
                }
            }
        }
        Text {
            attr {
                text(entry.entryId)
                marginTop(3f)
                lines(1)
                fontSize(11f)
                color(theme.textMuted)
            }
        }
        vif({ expanded() }) {
            View {
                attr { height(1f); marginTop(10f); backgroundColor(theme.divider) }
            }
            DshDetailRow("Module", entry.moduleName)
            DshDetailRow("Configuration", DshPluginCatalog.configurationSummary(entry, presets()))
            // Controls exist only when the companion Host plugin is installed and says
            // this entry is safe to touch; criterion 5 forbids buttons that cannot work.
            vif({ controllable() }) {
                View {
                    attr { flexDirectionRow(); marginTop(10f) }
                    if (entry.enabled) {
                        DshPluginControlButton("Disable", busy) { onControl("disable") }
                        DshPluginControlButton("Reload", busy) { onControl("reload") }
                    } else {
                        DshPluginControlButton("Enable", busy) { onControl("enable") }
                    }
                }
            }
            DshDetailRow(
                "Lifecycle status",
                entry.fiberPhase?.let { "$it — ${status.label}" } ?: "No live root fiber",
            )
            if (status == DshPluginStatus.FAILED) {
                View {
                    attr {
                        marginTop(10f)
                        padding(10f)
                        flexDirectionColumn()
                        borderRadius(10f)
                        backgroundColor(theme.toolCardErrorBackground)
                    }
                    Text {
                        attr {
                            text("Failure summary")
                            fontSize(11f)
                            fontWeightMedium()
                            color(theme.dangerText)
                        }
                    }
                    Text {
                        attr {
                            text(DshPluginCatalog.failureSummary(entry))
                            marginTop(4f)
                            fontSize(12f)
                            lineHeight(17f)
                            color(theme.textSecondary)
                        }
                    }
                    Text {
                        attr {
                            text(DshPluginCatalog.FAILURE_NOTE)
                            marginTop(6f)
                            fontSize(11f)
                            lineHeight(16f)
                            color(theme.textMuted)
                        }
                    }
                }
            }
        }
        event { click { onToggle() } }
    }
}

private fun dshPluginStatusColor(status: DshPluginStatus, palette: DshPalette): Color = when (status) {
    DshPluginStatus.FAILED -> palette.dangerText
    DshPluginStatus.ACTIVE -> palette.success
    DshPluginStatus.LOADING, DshPluginStatus.UNLOADING -> palette.accent
    DshPluginStatus.PENDING -> palette.warningText
    DshPluginStatus.IDLE, DshPluginStatus.DISABLED -> palette.textMuted
}
