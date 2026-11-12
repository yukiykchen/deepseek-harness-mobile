package io.github.yukiykchen.dsh.feature.home

import com.tencent.kuikly.core.base.PagerScope
import com.tencent.kuikly.core.reactive.handler.observableList
import io.github.yukiykchen.dsh.data.toMessage
import io.github.yukiykchen.dsh.framework.DshStreamLog
import io.github.yukiykchen.dsh.framework.DshDiagnostics
import io.github.yukiykchen.dsh.model.DshMessage
import io.github.yukiykchen.dsh.model.DshSkill

/** History and skill requests have independent lifetimes but share the owning page's session scope. */
internal class DshHomeHistoryController(
    ctx: DshHomeContext,
    private val onTimeline: (String, List<DshMessage>, Boolean, Boolean) -> Unit,
    private val onFailure: (String, Boolean) -> Unit,
) : PagerScope by ctx {
    val skills by observableList<DshSkill>()
    private val historyRequests = DshControllerRequestScope(ctx)
    private val skillRequests = DshControllerRequestScope(ctx)

    fun loadTimeline(
        sessionId: String,
        scrollToEnd: Boolean = true,
        forceReplace: Boolean = false,
        afterApply: () -> Unit = {},
    ) {
        val request = historyRequests.begin(sessionId) ?: return
        request.client.loadWebTimeline(sessionId, { items ->
            if (!historyRequests.accepts(request)) return@loadWebTimeline
            onTimeline(sessionId, items.map { it.toMessage() }, scrollToEnd, forceReplace)
            // Applying history can trigger navigation or another request synchronously.
            if (historyRequests.accepts(request)) afterApply()
        }, { error ->
            if (!historyRequests.accepts(request)) return@loadWebTimeline
            DshStreamLog.i("ui.history-fail session=$sessionId error=${DshDiagnostics.redactedError(error)}")
            onFailure(sessionId, forceReplace)
            if (historyRequests.accepts(request)) afterApply()
        })
    }

    fun loadSkills(sessionId: String) {
        if (skillRequests.closed) return
        skills.clear()
        val request = skillRequests.begin(sessionId) ?: return
        request.client.loadSkills(sessionId, { loaded ->
            if (!skillRequests.accepts(request)) return@loadSkills
            skills.clear()
            skills.addAll(loaded)
        })
    }

    fun invalidateHistory() = historyRequests.invalidate()

    fun dispose() {
        historyRequests.close()
        skillRequests.close()
        skills.clear()
    }
}
