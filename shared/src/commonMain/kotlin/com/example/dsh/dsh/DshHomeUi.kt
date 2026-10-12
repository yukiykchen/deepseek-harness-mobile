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

internal fun topBarConnectingText(label: String): String {
    val value = label.trim()
    if (value.isEmpty()) return "连接中"
    return value
}

internal const val CHAT_INITIAL_RENDER_COUNT = 48
internal const val CHAT_MAX_RENDERED_MESSAGES = 128
