package io.github.yukiykchen.dsh.feature.home

import com.tencent.kuikly.core.base.*
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.base.attr.ImageUri
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.reactive.handler.*
import com.tencent.kuikly.core.views.*
import com.tencent.kuikly.core.views.Image
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View
import io.github.yukiykchen.dsh.model.dshTurnStatusLabel
import io.github.yukiykchen.dsh.model.dshFormatTurnDuration

fun ViewContainer<*, *>.DshTurnStatus(
    visible: () -> Boolean,
    reconnecting: () -> Boolean,
    elapsedMs: () -> Long,
) {
    vif({ visible() }) {
        View {
            attr {
                flexDirectionRow()
                alignItemsCenter()
                height(26f)
                marginTop(4f)
                marginBottom(8f)
            }
            Text {
                attr {
                    text(dshTurnStatusLabel(reconnecting()))
                    fontSize(14f)
                    fontWeightBold()
                    color(Color(TURN_STATUS_BLUE))
                }
            }
            vif({ elapsedMs() >= TURN_STATUS_CLOCK_AFTER_MS }) {
                Text {
                    attr {
                        text(dshFormatTurnDuration(elapsedMs()))
                        fontSize(13f)
                        color(Color(0xFF8A9399))
                        marginLeft(8f)
                    }
                }
            }
        }
    }
}

const val TURN_STATUS_BLUE = 0xFF4D6BFE

const val TURN_STATUS_CLOCK_AFTER_MS = 15_000L

fun ViewContainer<*, *>.DshNewSessionHome() {
    View {
        attr {
            absolutePositionAllZero()
            allCenter()
            zIndex(2)
            paddingLeft(28f)
            paddingRight(28f)
        }
        event {
            click { }
        }
        View {
            attr {
                flexDirectionColumn()
                alignItemsCenter()
            }
            Image {
                attr {
                    src(ImageUri.commonAssets("fish.svg"))
                    size(56f, 56f)
                }
            }
            View {
                attr {
                    marginTop(16f)
                    flexDirectionRow()
                    alignItemsCenter()
                }
                Text {
                    attr {
                        text("探索未至之境")
                        fontSize(26f)
                        fontWeightBold()
                        color(Color(0xFF1B1F24))
                    }
                }
                View {
                    attr {
                        marginLeft(8f)
                        paddingLeft(8f)
                        paddingRight(8f)
                        height(22f)
                        allCenter()
                        borderRadius(11f)
                        backgroundColor(Color(0xFFE8F1FF))
                    }
                    Text {
                        attr {
                            text("预览版")
                            fontSize(11f)
                            fontWeightMedium()
                            color(Color(0xFF4176E6))
                        }
                    }
                }
            }
        }
    }
}
