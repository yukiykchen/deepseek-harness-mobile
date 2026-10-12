package com.example.dsh.dsh

import com.tencent.kuikly.core.manager.PagerManager
import com.tencent.kuikly.core.timer.setTimeout

/** Kuikly timing and cell realization stay outside the turn's protocol state. */
internal class DshStreamingRenderAdapter(
    private val ctx: DshHomeContext,
    private val scroller: DshConversationScroller,
) {
    fun realizeVisible() = scroller.realizeVisible()
    fun followTail() = scroller.scrollToEnd()
    fun ensureLiveCell() = scroller.ensureLiveCell()
    fun refresh(sessionId: String) = scroller.refresh(sessionId)

    fun scheduleFlush(firstPaint: Boolean, isCurrent: () -> Boolean, flush: () -> Unit) {
        setTimeout(ctx.pagerId, if (firstPaint) 0 else 16) { if (isCurrent()) flush() }
    }

    fun settle(sessionId: String, isCurrent: () -> Boolean, releaseDisplay: () -> Unit) {
        afterLayout {
            if (!isCurrent()) return@afterLayout
            releaseDisplay()
            scroller.refresh(sessionId)
            setTimeout(ctx.pagerId, 16) {
                if (!isCurrent()) return@setTimeout
                afterLayout { if (isCurrent()) scroller.refresh(sessionId) }
            }
        }
    }

    private fun afterLayout(task: () -> Unit) {
        PagerManager.getPager(ctx.pagerId).addTaskWhenPagerUpdateLayoutFinish(task)
    }
}
