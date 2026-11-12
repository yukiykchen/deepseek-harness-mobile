package io.github.yukiykchen.dsh.feature.home

import com.tencent.kuikly.core.base.*
import com.tencent.kuikly.core.reactive.collection.ObservableList
import com.tencent.kuikly.core.reactive.handler.*
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.views.*
import com.tencent.kuikly.core.views.List
import io.github.yukiykchen.dsh.framework.DshStreamLog
import io.github.yukiykchen.dsh.framework.DshDiagnostics
import io.github.yukiykchen.dsh.model.isRuntimeContextSnapshot
import io.github.yukiykchen.dsh.model.DshMessage
import io.github.yukiykchen.dsh.model.dshMessagesVisuallyEqual

/** Replace a visible message list only when its projected content changes. */
fun replaceMessagesIfChanged(
    messages: ObservableList<DshMessage>,
    next: List<DshMessage>,
    streaming: Boolean,
    force: Boolean,
    sessionId: String,
    sessionStore: DshSessionMessageStore,
) {
    val filtered = next.filterNot { it.isRuntimeContextSnapshot() }
    if (streaming && !force) {
        DshStreamLog.i(
            "ui.replace-messages deferred-during-stream from=${messages.size} to=${filtered.size}",
        )
        return
    }
    val current = messages.toList()
    if (current == filtered) return
    if (dshMessagesVisuallyEqual(current, filtered)) {
        DshStreamLog.i(
            "ui.replace-messages skip-visual-equal from=${current.size} force=$force",
        )
        return
    }
    val remount = current.isEmpty() && filtered.isNotEmpty()
    DshStreamLog.i(
        "ui.replace-messages from=${current.size} to=${filtered.size} streaming=$streaming force=$force remount=$remount message=${DshDiagnostics.preview(filtered.lastOrNull()?.content.orEmpty())}",
    )
    applyMessagesInPlace(messages, filtered)
    sessionStore.put(sessionId, messages)
    if (remount) sessionStore.remount(sessionId)
}

/** Mutate an observable list in place so Kuikly does not remount every row. */
fun applyMessagesInPlace(
    messages: ObservableList<DshMessage>,
    next: List<DshMessage>,
) {
    val shared = minOf(messages.size, next.size)
    for (index in 0 until shared) {
        if (messages[index] != next[index]) messages[index] = next[index]
    }
    when {
        next.size < messages.size -> {
            for (index in messages.lastIndex downTo next.size) {
                messages.removeAt(index)
            }
        }
        next.size > messages.size -> {
            messages.addAll(next.subList(messages.size, next.size))
        }
    }
}
