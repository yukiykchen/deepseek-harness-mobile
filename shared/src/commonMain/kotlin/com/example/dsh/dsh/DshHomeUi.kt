package com.example.dsh.dsh

import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.reactive.collection.ObservableList
import com.tencent.kuikly.core.views.View

internal fun syncVisibleSessions(
    source: ObservableList<DshSession>,
    dest: ObservableList<DshSession>,
) {
    val next = source.toList().filterNot { it.blank }
    dest.diffUpdate(next) { old, new -> old.id == new.id }
    val count = minOf(dest.size, next.size)
    for (index in 0 until count) {
        if (dest[index] != next[index]) {
            dest[index] = next[index]
        }
    }
}

internal fun ViewContainer<*, *>.DshHitButton(onClick: () -> Unit) {
    View {
        attr {
            absolutePositionAllZero()
            backgroundColor(Color(0x00000000))
        }
        event { click { onClick() } }
    }
}

internal fun isConnectionReadyLabel(label: String): Boolean {
    return label.startsWith("已连接") ||
        label.endsWith("已连接") ||
        label.endsWith("已就绪") ||
        label == "连接成功"
}

internal fun isReconnectLabel(label: String): Boolean {
    return label == "远程连接重建中" ||
        label == "扫码连接重建中" ||
        label == "扫码连接重试中" ||
        label == "本地 DSH 连接重建中"
}

internal fun topBarConnectingText(label: String): String {
    val value = label.trim()
    if (value.isEmpty()) return "连接中"
    return value
}

internal const val COMPOSER_HEIGHT = 142f

/** Extra composer height while images are staged for the next turn. */
internal const val ATTACHMENT_STRIP_HEIGHT = 88f

/** Extra composer height while the image-source menu is open. */
internal const val ATTACHMENT_MENU_HEIGHT = 78f

/** Extra composer height while a staged image carries a rejection message. */
internal const val ATTACHMENT_ERROR_HEIGHT = 20f
internal const val CHAT_INITIAL_RENDER_COUNT = 48
internal const val CHAT_MAX_RENDERED_MESSAGES = 128
