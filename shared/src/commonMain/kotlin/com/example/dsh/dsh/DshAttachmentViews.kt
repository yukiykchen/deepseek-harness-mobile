package com.example.dsh.dsh

import com.tencent.kuikly.core.base.Border
import com.tencent.kuikly.core.base.BorderStyle
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.base.attr.ImageUri
import com.tencent.kuikly.core.directives.vfor
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.directives.velse
import com.tencent.kuikly.core.reactive.collection.ObservableList
import com.tencent.kuikly.core.views.Image
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View

/** One image source in the composer's attachment menu. */
internal fun ViewContainer<*, *>.DshAttachmentSourceRow(
    icon: String,
    title: String,
    hint: String,
    onClick: () -> Unit,
) {
    View {
        attr {
            height(32f)
            flexDirectionRow()
            alignItemsCenter()
            paddingLeft(8f)
        }
        Image {
            attr {
                src(ImageUri.commonAssets(icon))
                size(17f, 17f)
                marginRight(8f)
                tintColor(theme.textSecondary)
            }
        }
        Text { attr { text(title); fontSize(14f); color(theme.textSecondary) } }
        View { attr { flex(1f) } }
        Text { attr { text(hint); fontSize(11f); color(theme.textMuted) } }
        DshHitButton(onClick)
    }
}

/**
 * Thumbnails of the images staged for the next turn, with their upload state.
 *
 * Each tile shows the picked image, a remove button, and a state badge. A failed
 * tile is tappable to retry; the reason the Host (or the local prevalidation) gave
 * is printed under the strip so it is readable without opening anything.
 */
internal fun ViewContainer<*, *>.DshAttachmentStrip(
    attachments: () -> ObservableList<DshStagedAttachment>,
    notice: () -> String,
    onRemove: (String) -> Unit,
    onRetry: (String) -> Unit,
    onPreview: (String) -> Unit,
) {
    vif({ attachments().isNotEmpty() }) {
        View {
            attr {
                height(ATTACHMENT_STRIP_HEIGHT - 8f)
                marginBottom(8f)
                flexDirectionRow()
                alignItemsCenter()
            }
            vfor({ attachments() }) { staged ->
                View {
                    attr {
                        size(72f, 72f)
                        marginRight(8f)
                        borderRadius(10f)
                        backgroundColor(theme.surfaceSunken)
                        border(
                            Border(
                                1f,
                                BorderStyle.SOLID,
                                if (staged.state == DshAttachmentState.FAILED) theme.danger else theme.border,
                            ),
                        )
                        justifyContentFlexEnd()
                        alignItemsCenter()
                    }
                    // An image rejected on size never got decoded, so there is nothing to
                    // preview — the state overlay and the caption carry the tile instead.
                    vif({ staged.image.base64.isNotEmpty() }) {
                        Image {
                            attr {
                                absolutePosition(0f, 0f, 0f, 0f)
                                borderRadius(10f)
                                src(staged.dataUrl)
                                resizeCover()
                            }
                        }
                    }
                    // A rejected image is dimmed so the reason line below reads as the
                    // explanation for this tile rather than for the whole strip.
                    vif({ staged.state != DshAttachmentState.PENDING }) {
                        View {
                            attr {
                                absolutePosition(0f, 0f, 0f, 0f)
                                borderRadius(10f)
                                backgroundColor(theme.mask)
                                allCenter()
                            }
                            Text {
                                attr {
                                    text(dshAttachmentStateLabel(staged.state))
                                    fontSize(11f)
                                    fontWeightMedium()
                                    color(theme.textOnAccent)
                                }
                            }
                        }
                    }
                    View {
                        attr {
                            width(72f)
                            height(14f)
                            allCenter()
                            backgroundColor(theme.mask)
                        }
                        Text {
                            attr {
                                text(dshAttachmentSizeLabel(staged))
                                width(70f)
                                lines(1)
                                textAlignCenter()
                                fontSize(8f)
                                color(theme.textOnAccent)
                            }
                        }
                    }
                    DshHitButton {
                        if (staged.state == DshAttachmentState.FAILED) onRetry(staged.localId)
                        else onPreview(staged.localId)
                    }
                    View {
                        attr {
                            absolutePosition(top = 0f, right = 0f)
                            size(24f, 24f)
                            allCenter()
                        }
                        View {
                            attr {
                                size(18f, 18f)
                                borderRadius(9f)
                                allCenter()
                                backgroundColor(theme.mask)
                            }
                            Image {
                                attr {
                                    src(ImageUri.commonAssets("x.svg"))
                                    size(11f, 11f)
                                    tintColor(theme.textOnAccent)
                                }
                            }
                        }
                        DshHitButton { onRemove(staged.localId) }
                    }
                }
            }
        }
    }
    vif({ notice().isNotEmpty() }) {
        Text {
            attr {
                text(notice())
                height(ATTACHMENT_ERROR_HEIGHT - 4f)
                marginBottom(4f)
                lines(1)
                fontSize(11f)
                color(theme.dangerText)
            }
        }
    }
}

/**
 * Images a user turn carried, as thumbnails inside the bubble. Bytes come from
 * `session/attachment` keyed by `attachmentId`, so a reloaded conversation restores
 * them without the App ever caching Base64 in its own message store.
 */
internal fun ViewContainer<*, *>.DshBubbleAttachments(
    attachments: List<DshImageAttachmentRef>,
    dataUrl: (String) -> String?,
    onOpen: (DshImageAttachmentRef) -> Unit,
) {
    View {
        attr {
            flexDirectionRow()
            flexWrapWrap()
            justifyContentFlexEnd()
        }
        attachments.forEach { ref ->
            View {
                attr {
                    size(96f, 96f)
                    marginLeft(6f)
                    marginBottom(6f)
                    borderRadius(10f)
                    backgroundColor(theme.surfaceSunken)
                    border(Border(1f, BorderStyle.SOLID, theme.border))
                    allCenter()
                }
                // Reactive: a bubble is built before `session/attachment` answers, so the
                // preview lookup must re-run when the bytes land.
                vif({ dataUrl(ref.previewKey) != null }) {
                    Image {
                        attr {
                            absolutePosition(0f, 0f, 0f, 0f)
                            borderRadius(10f)
                            src(dataUrl(ref.previewKey).orEmpty())
                            resizeCover()
                        }
                    }
                }
                velse {
                    Text {
                        attr {
                            text(dshAttachmentRefLabel(ref))
                            width(88f)
                            lines(3)
                            textAlignCenter()
                            fontSize(9f)
                            color(theme.textMuted)
                        }
                    }
                }
                DshHitButton { onOpen(ref) }
            }
        }
    }
}

/** Full-screen viewer for one attachment (Task 3 bonus 1). */
internal fun ViewContainer<*, *>.DshAttachmentViewer(
    ref: () -> DshImageAttachmentRef?,
    dataUrl: (String) -> String?,
    onClose: () -> Unit,
) {
    // A square frame keeps `resizeContain` from opening a gap between the image and its
    // caption for the shapes a chat attachment usually has.
    val page = getPager().pageData
    val side = (page.pageViewWidth - 48f)
        .coerceAtMost(page.pageViewHeight - 200f)
        .coerceAtLeast(0f)
    vif({ ref() != null }) {
            View {
            attr {
                absolutePositionAllZero()
                zIndex(60)
                backgroundColor(theme.mask)
                flexDirectionColumn()
                justifyContentCenter()
                alignItemsCenter()
            }
            event { click { onClose() } }
            vif({ dataUrl(ref()?.previewKey.orEmpty()) != null }) {
                Image {
                    attr {
                        src(dataUrl(ref()?.previewKey.orEmpty()).orEmpty())
                        size(side, side)
                        resizeContain()
                    }
                }
            }
            velse {
                View {
                    attr {
                        size(side, side)
                        allCenter()
                    }
                    Text {
                        attr {
                            text("Loading image…")
                            fontSize(13f)
                            color(theme.textOnAccent)
                        }
                    }
                }
            }
            Text {
                attr {
                    text(ref()?.let(::dshAttachmentRefLabel).orEmpty())
                    width(side)
                    marginTop(14f)
                    lines(2)
                    textAlignCenter()
                    fontSize(12f)
                    color(theme.textOnAccent)
                }
            }
        }
    }
}

internal fun dshAttachmentStateLabel(state: DshAttachmentState): String = when (state) {
    DshAttachmentState.PENDING -> ""
    DshAttachmentState.UPLOADING -> "Sending…"
    DshAttachmentState.SENT -> "Sent"
    DshAttachmentState.FAILED -> "Failed · retry"
}

/** Kept dense: the caption band is only as wide as a 72 px tile. */
private fun dshAttachmentSizeLabel(staged: DshStagedAttachment): String {
    val dimensions = if (staged.image.width > 0 && staged.image.height > 0) {
        "${staged.image.width}×${staged.image.height}"
    } else {
        staged.image.mediaType.substringAfterLast('/').uppercase()
    }
    return "$dimensions·${dshFormatBytes(staged.image.bytes).replace(" ", "")}"
}

/** Name, pixel size and byte count of a durable reference, for placeholders and captions. */
internal fun dshAttachmentRefLabel(ref: DshImageAttachmentRef): String = buildString {
    append(ref.name?.takeIf { it.isNotEmpty() } ?: ref.mediaType)
    if (ref.width > 0 && ref.height > 0) append("\n${ref.width}×${ref.height}")
    if (ref.bytes > 0) append(" · ${dshFormatBytes(ref.bytes)}")
}
