package com.example.dsh.dsh

import com.tencent.kuikly.core.base.PagerScope
import com.tencent.kuikly.core.base.ViewRef
import com.tencent.kuikly.core.directives.scrollToPosition
import com.tencent.kuikly.core.manager.PagerManager
import com.tencent.kuikly.core.timer.setTimeout
import com.tencent.kuikly.core.views.DivView
import com.tencent.kuikly.core.views.ListContentView
import com.tencent.kuikly.core.views.ListView
import com.tencent.kuikly.core.views.ScrollParams

private const val SCROLL_SETTLE_ATTEMPTS = 6
private val SCROLL_SETTLE_DELAYS_MS = intArrayOf(0, 16, 32, 64, 120, 200)
private const val FOLLOW_LIST_SLACK_PX = 72f

/**
 * Scroll position of the mounted conversation lists.
 *
 * Owns the list/row view refs, "follow the tail" (auto-scroll while new
 * content arrives until the user drags away) and the settle loops that keep
 * re-applying an offset while Markdown and LazyLoop finish laying out.
 * A new scroll request bumps a generation so older settle loops stop.
 */
internal class DshConversationScroller(
    private val ctx: DshHomeContext,
    /** Rows of the active session, as rendered. */
    private val activeMessages: () -> List<DshMessage>,
    /** Id of the assistant row currently streaming, or "". */
    private val liveMessageId: () -> String,
) : DshHomeContext by ctx {
    private val scrollers = mutableMapOf<String, ViewRef<ListView<*, *>>>()
    private val rows = mutableMapOf<String, ViewRef<DivView>>()
    private var settleGeneration = 0

    var followTail = true
        private set

    fun bindScroller(sessionId: String, ref: ViewRef<ListView<*, *>>) {
        scrollers[sessionId] = ref
    }

    fun bindRow(sessionId: String, messageId: String, ref: ViewRef<DivView>) {
        rows[rowKey(sessionId, messageId)] = ref
    }

    fun forget(sessionId: String) {
        scrollers.remove(sessionId)
    }

    fun hasScroller(sessionId: String): Boolean = scrollers[sessionId]?.view != null

    /** Ask a mounted list to build the cells now inside its visible rect. */
    fun refresh(sessionId: String) {
        val list = scrollers[sessionId]?.view ?: return
        (list.contentView as? ListContentView)?.createRenderViewsOnVisibleRect()
    }

    /** Like [refresh] for the active list, after forcing a re-measure. */
    fun realizeVisible() {
        val scroller = scrollers[activeSessionId]?.view ?: return
        val content = scroller.contentView as? ListContentView ?: return
        content.flexNode.markDirty()
        content.createRenderViewsOnVisibleRect()
    }

    fun pinTail() {
        followTail = true
    }

    fun scrollToEnd() {
        if (!followTail) return
        val generation = ++settleGeneration
        ensureLiveCell()
        realizeVisible()
        afterLayout { settleToEnd(generation, 0) }
    }

    fun scrollToMessage(messageId: String) {
        val generation = ++settleGeneration
        afterLayout { settleToMessage(messageId, generation, 0) }
    }

    fun onUserScroll(params: ScrollParams) {
        val maxOffset = (params.contentHeight - params.viewHeight).coerceAtLeast(0f)
        val nearBottom = params.offsetY >= maxOffset - FOLLOW_LIST_SLACK_PX
        if (nearBottom) {
            followTail = true
            return
        }
        if (params.isDragging) {
            followTail = false
            settleGeneration += 1
        }
    }

    /**
     * vforLazy only creates items inside `[currentStart, currentEnd)`. Appending
     * the first assistant after the list was mounted with a single user bubble
     * lands at `currentEnd`. `setContentOffset` is a no-op when content is
     * shorter than the viewport (new session, first turn), so the cell never
     * appears until the user drags. `scrollToPosition` is what actually builds it.
     */
    fun ensureLiveCell() {
        if (!followTail) return
        val id = liveMessageId()
        if (id.isEmpty()) return
        if (rows[rowKey(activeSessionId, id)]?.view != null) return
        val list = scrollers[activeSessionId]?.view ?: return
        val messages = activeMessages()
        val index = messages.indexOfFirst { it.id == id }
        if (index < 0) return
        DshStreamLog.i("ui.realize-live-cell id=$id index=$index size=${messages.size}")
        list.scrollToPosition(index, 0f, false)
    }

    /**
     * Markdown and LazyLoop can add/layout children over several frames.
     * Re-apply the bottom offset while that burst settles, otherwise the first
     * offset is calculated from a shorter content height and the user sees the
     * list walk down a few screens after launch.
     */
    private fun settleToEnd(generation: Int, attempt: Int) {
        if (generation != settleGeneration || !followTail) return
        ensureLiveCell()
        realizeVisible()
        applyEndOffset()
        if (attempt >= SCROLL_SETTLE_ATTEMPTS) return
        setTimeout(pagerId, SCROLL_SETTLE_DELAYS_MS[attempt]) {
            afterLayout { settleToEnd(generation, attempt + 1) }
        }
    }

    private fun settleToMessage(messageId: String, generation: Int, attempt: Int) {
        if (generation != settleGeneration) return
        val rowY = rows[rowKey(activeSessionId, messageId)]?.view?.flexNode?.layoutFrame?.y
        if (rowY != null) {
            scrollers[activeSessionId]?.view?.setContentOffset(0f, rowY.coerceAtLeast(0f), animated = false)
        }
        if (attempt >= SCROLL_SETTLE_ATTEMPTS) return
        setTimeout(pagerId, SCROLL_SETTLE_DELAYS_MS[attempt]) {
            afterLayout { settleToMessage(messageId, generation, attempt + 1) }
        }
    }

    private fun applyEndOffset() {
        if (!followTail) return
        val scroller = scrollers[activeSessionId]?.view ?: return
        val contentHeight = scroller.contentView?.flexNode?.layoutFrame?.height ?: return
        val viewportHeight = scroller.flexNode?.layoutFrame?.height ?: return
        scroller.setContentOffset(0f, (contentHeight - viewportHeight).coerceAtLeast(0f), animated = false)
    }

    private fun afterLayout(task: () -> Unit) {
        PagerManager.getPager(pagerId).addTaskWhenPagerUpdateLayoutFinish(task)
    }

    private fun rowKey(sessionId: String, messageId: String) = "$sessionId:$messageId"
}
