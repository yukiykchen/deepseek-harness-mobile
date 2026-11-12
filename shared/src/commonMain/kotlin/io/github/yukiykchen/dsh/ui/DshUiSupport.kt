package io.github.yukiykchen.dsh.ui

import io.github.yukiykchen.dsh.model.DshSkill
import com.tencent.kuikly.core.base.*
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.reactive.collection.ObservableList
import com.tencent.kuikly.core.reactive.handler.*
import com.tencent.kuikly.core.views.*
import com.tencent.kuikly.core.views.View
import io.github.yukiykchen.dsh.model.DshSession

fun visibleSkillList(
    source: ObservableList<DshSkill>,
    query: String,
    destination: ObservableList<DshSkill> = ObservableList(),
): ObservableList<DshSkill> {
    val next = source.toList().filter { it.name.startsWith(query) }
    destination.diffUpdate(next) { old, new -> old.name == new.name }
    next.forEachIndexed { index, skill ->
        if (destination[index] != skill) destination[index] = skill
    }
    return destination
}

fun syncVisibleSessions(
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

fun ViewContainer<*, *>.DshHitButton(onClick: () -> Unit) {
    View {
        attr {
            absolutePositionAllZero()
            backgroundColor(Color(0x00000000))
        }
        event { click { onClick() } }
    }
}

fun isConnectionReadyLabel(label: String): Boolean {
    return label.startsWith("已连接") ||
        label.endsWith("已连接") ||
        label.endsWith("已就绪") ||
        label == "连接成功"
}

fun isReconnectLabel(label: String): Boolean {
    return label == "远程连接重建中" ||
        label == "扫码连接重建中" ||
        label == "扫码连接重试中" ||
        label == "本地 DSH 连接重建中"
}

fun topBarConnectingText(label: String): String {
    val value = label.trim()
    if (value.isEmpty()) return "连接中"
    return value
}

const val CHAT_INITIAL_RENDER_COUNT = 48

const val CHAT_MAX_RENDERED_MESSAGES = 128
