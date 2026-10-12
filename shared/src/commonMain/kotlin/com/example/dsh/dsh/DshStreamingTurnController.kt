package com.example.dsh.dsh

import com.tencent.kuikly.core.reactive.handler.observable


/**
 * The assistant turn that is currently being painted in the active session.
 *
 * A live turn is an ordered sequence of rows: an optional reasoning row, then
 * text segments split by tool cards (`root`, `root-segment-1`, ...). Text
 * deltas are buffered and flushed at most every 16 ms;
 * while a segment is live its row keeps `content = ""` and DshMarkdown reads
 * [liveContent] instead, so LazyLoop never has to rebuild the cell per token.
 *
 * A turn is either sent from this device ([send]) or adopted from a run the
 * host already has in flight ([resumeFromHistory]). Both end in [settle].
 */
internal class DshStreamingTurnController(
    private val ctx: DshHomeContext,
    private val sessionStore: DshSessionMessageStore,
    scroller: DshConversationScroller,
    private val listener: Listener,
) : DshHomeContext by ctx {
    private val render = DshStreamingRenderAdapter(ctx, scroller)

    private fun messages(): List<DshMessage> = sessionStore.state(activeSessionId, loadFromDisk = false).toList()

    interface Listener {
        fun isConnectionActive(): Boolean
        fun onStatusLabel(label: String)
        /** [streaming] or [stopButtonVisible] may have changed. */
        fun onActivityChanged()
        fun persist(sessionId: String)
    }

    var streaming by observable(false)
        private set
    var stopButtonVisible by observable(false)
        private set
    /** Id of the text row receiving deltas, or "" between segments. */
    var liveId by observable("")
        private set
    /** Text of the live segment, read by DshMarkdown while [liveId] streams. */
    var liveContent by observable("")
        private set

    // The root id guards callbacks from an old request; [liveId] points at the
    // current text segment between ordered tool cards.
    private var rootId = ""
    private var segment = 0
    // Last completed assistant when the current prompt was sent. Resync must
    // not graft the new stream onto that bubble.
    private var anchorAssistantId = ""
    private var reasoningId = ""
    private var reasoningContent = ""
    private val pendingDelta = StringBuilder()
    private var flushScheduled = false
    private var handle: DshStreamHandle? = null
    private var observerRevision = 0L

    /** A prompt from this device is painting; a host "running" resync would only flicker. */
    fun isLocalPromptInFlight(): Boolean = streaming && rootId.isNotEmpty()

    fun send(client: DshHostClient, sessionId: String, prompt: String, assistantId: String) {
        val revision = ++observerRevision
        val rows = messages()
        val turnReasoningId = "$assistantId-reasoning"
        anchorAssistantId = rows.lastOrNull(::dshIsLiveAssistantText)?.id.orEmpty()
        liveId = ""
        rootId = assistantId
        segment = 0
        reasoningId = turnReasoningId
        reasoningContent = ""
        liveContent = ""
        pendingDelta.setLength(0)
        flushScheduled = false
        streaming = true
        stopButtonVisible = true
        listener.onStatusLabel("正在生成")
        listener.onActivityChanged()
        handle = client.streamReply(
            pagerId = pagerId,
            sessionId = sessionId,
            prompt = prompt,
            onDelta = { delta, isReasoning ->
                if (revision != observerRevision || activeSessionId != sessionId) return@streamReply
                if (isReasoning) queueReasoningDelta(turnReasoningId, delta)
                else queueAssistantDelta(assistantId, delta)
            },
            onComplete = { result -> if (revision == observerRevision && activeSessionId == sessionId) complete(sessionId, result) },
            onError = { error -> if (revision == observerRevision && activeSessionId == sessionId) fail(sessionId, error, "ui.prompt-interrupt") },
        )
    }

    /** Stop button: keep what arrived so far and mark the row as stopped. */
    fun stop() {
        handle?.cancel()
        handle = null
        flush()
        ensureSegment()
        val stoppedContent = liveContent + "\n\n*已停止*"
        dshSessionRenderLog("stream.stop.begin session=$activeSessionId messages=${messages().size} chars=${stoppedContent.length}")
        settle(DshMessageRole.ASSISTANT, stoppedContent)
        listener.persist(activeSessionId)
        listener.onStatusLabel("已连接")
        dshSessionRenderLog("stream.stop.state-finalized session=$activeSessionId messages=${messages().size}")
    }

    /** Drop the stream without a final row; the next session must not receive its deltas. */
    fun cancelForSessionSwitch() {
        observerRevision++
        if (!streaming && !stopButtonVisible) {
            handle?.detach()
            handle = null
            release()
            return
        }
        handle?.detach()
        handle = null
        val partial = liveContent + pendingDelta.toString()
        if (liveId.isNotEmpty()) updateLiveRow(partial, streaming = false)
        finalizeReasoning()
        liveId = ""
        rootId = ""
        segment = 0
        reasoningId = ""
        reasoningContent = ""
        pendingDelta.setLength(0)
        liveContent = ""
        flushScheduled = false
        anchorAssistantId = ""
        streaming = false
        stopButtonVisible = false
        listener.onActivityChanged()
    }

    /** Engine stopped or disconnected. */
    fun detach() = cancelForSessionSwitch()

    /** Close the current text row immediately before the next tool card. */
    fun splitBeforeTool() {
        if (!streaming || rootId.isEmpty()) return
        flush()
        val rows = messages()
        val id = liveId
        if (id.isNotEmpty()) {
            val index = rows.indexOfFirst { it.id == id }
            if (index >= 0) {
                val current = rows[index]
                val text = current.content.ifEmpty { liveContent }
                if (text.isEmpty()) {
                    sessionStore.remove(activeSessionId, id)
                } else {
                    sessionStore.update(activeSessionId, current.copy(content = text, streaming = false))
                    render.realizeVisible()
                }
            }
        }
        liveId = ""
        liveContent = ""
        segment += 1
        pendingDelta.setLength(0)
        flushScheduled = false
    }

    /** Host finished the run while we were not attached; settle on the history tail if there is one. */
    fun finishFromHistory(sessionId: String, snapshotApplied: Boolean = true) {
        if (!(streaming || stopButtonVisible)) return
        if (snapshotApplied) {
            pendingDelta.setLength(0)
            flushScheduled = false
            rebindReasoningFromHistory()
            if (rebindToHistoryTail()) settle(DshMessageRole.ASSISTANT, liveContent)
            else release()
        } else {
            flush()
            // No authoritative snapshot arrived: retain the live buffer rather than rebinding an empty placeholder.
            if (liveId.isNotEmpty()) settle(DshMessageRole.ASSISTANT, liveContent)
            else {
                finalizeReasoning()
                release()
            }
        }
        listener.persist(sessionId)
        hostClient?.detachLiveStreams(sessionId)
        handle = null
    }

    /** Host is still running this session: continue painting its turn from the history tail. */
    fun resumeFromHistory(sessionId: String, reason: String) {
        if (sessionId != activeSessionId) return
        val rows = messages()
        rebindReasoningFromHistory()
        val rebound = rebindToHistoryTail()
        if (rebound) {
            streaming = true
            stopButtonVisible = true
            listener.onStatusLabel("正在生成")
            val index = rows.indexOfFirst { it.id == liveId }
            if (index >= 0) {
                sessionStore.update(activeSessionId, rows[index].copy(streaming = true))
            }
        } else {
            if (rootId.isEmpty()) {
                rootId = "assistant-adopted-${rows.size}"
            }
            val liveStillPresent = liveId.isNotEmpty() && rows.any { it.id == liveId }
            if (!liveStillPresent) {
                val kept = liveContent + pendingDelta.toString()
                pendingDelta.setLength(0)
                liveId = ""
                segment = 0
                liveContent = ""
                if (kept.isNotEmpty()) {
                    ensureSegment()
                    liveContent = kept
                    updateLiveRow(kept, streaming = true)
                }
            }
            streaming = true
            stopButtonVisible = true
            listener.onStatusLabel("正在生成")
        }
        attachAdopted(sessionId)
        listener.onActivityChanged()
        DshStreamLog.i(
            "ui.resync.resume reason=$reason rebound=$rebound id=${liveId.ifEmpty { rootId }} chars=${liveContent.length}",
        )
    }

    private fun attachAdopted(sessionId: String) {
        val client = hostClient ?: return
        val revision = ++observerRevision
        handle = client.adoptLiveStream(
            sessionId = sessionId,
            onDelta = { delta, isReasoning ->
                if (revision != observerRevision || !listener.isConnectionActive() || activeSessionId != sessionId) return@adoptLiveStream
                if (isReasoning) {
                    val id = reasoningId.ifEmpty { "$rootId-reasoning" }
                    if (reasoningId.isEmpty()) reasoningId = id
                    queueReasoningDelta(id, delta)
                } else {
                    if (rootId.isEmpty()) {
                        rootId = "assistant-adopted-${messages().size}"
                    }
                    queueAssistantDelta(rootId, delta)
                }
            },
            onComplete = { result -> if (revision == observerRevision && activeSessionId == sessionId) complete(sessionId, result) },
            onError = { error -> if (revision == observerRevision && activeSessionId == sessionId) fail(sessionId, error, "ui.adopt-interrupt") },
        )
    }

    private fun complete(sessionId: String, result: String) {
        if (!listener.isConnectionActive()) return
        flush()
        if (liveId.isEmpty() && result.isNotEmpty()) ensureSegment()
        // A turn may contain several assistant text blocks separated by tool
        // calls. The current segment already contains the final block; using
        // the turn-wide result here would move all earlier text into this row.
        val completedContent = liveContent.ifEmpty { result }
        DshStreamLog.i(
            "ui.complete session=$sessionId resultChars=${result.length} liveChars=${liveContent.length} preview='${DshStreamLog.preview(completedContent)}'",
        )
        settle(DshMessageRole.ASSISTANT, completedContent)
        listener.persist(sessionId)
        listener.onStatusLabel("已连接")
        handle = null
    }

    private fun fail(sessionId: String, error: String, interruptTag: String) {
        if (!listener.isConnectionActive()) return
        if (dshIsTransportInterrupt("", error)) {
            // The socket dropped; the reconnect resync decides how the turn ends.
            DshStreamLog.i("$interruptTag session=$sessionId message='${DshStreamLog.preview(error)}'")
            return
        }
        flush()
        ensureSegment()
        DshStreamLog.i("ui.error session=$sessionId message='${DshStreamLog.preview(error)}'")
        settle(DshMessageRole.ERROR, error)
        listener.persist(sessionId)
        listener.onStatusLabel("已连接")
        handle = null
    }

    private fun rebindReasoningFromHistory() {
        val rows = messages()
        val userIndex = rows.indexOfLast { it.role == DshMessageRole.USER }
        val reasoning = rows.drop(userIndex + 1).lastOrNull { it.isReasoning }
        reasoningId = reasoning?.id.orEmpty()
        reasoningContent = reasoning?.content.orEmpty()
    }

    private fun rebindToHistoryTail(): Boolean {
        val live = dshHistoryTailToResume(messages().toList(), anchorAssistantId) ?: return false
        liveId = live.id
        rootId = live.id
        segment = 0
        liveContent = live.content
        pendingDelta.setLength(0)
        flushScheduled = false
        return true
    }

    private fun queueAssistantDelta(id: String, delta: String) {
        if (delta.isEmpty()) return
        if (!streaming || rootId != id) return
        ensureSegment()
        pendingDelta.append(delta)
        val firstPaint = liveContent.isEmpty()
        if (flushScheduled && !firstPaint) return
        flushScheduled = true
        val revision = observerRevision
        render.scheduleFlush(firstPaint, { revision == observerRevision }) {
            flushScheduled = false
            flush()
        }
    }

    private fun queueReasoningDelta(id: String, delta: String) {
        if (delta.isEmpty() || reasoningId != id) return
        val rows = messages()
        reasoningContent += delta
        val index = rows.indexOfFirst { it.id == id }
        if (index >= 0) {
            sessionStore.update(activeSessionId, rows[index].copy(content = reasoningContent, streaming = true, isReasoning = true))
        } else {
            sessionStore.append(activeSessionId, DshMessage(id, DshMessageRole.ASSISTANT, reasoningContent, streaming = true, isReasoning = true))
        }
        render.realizeVisible()
        render.followTail()
    }

    private fun flush() {
        if (liveId.isEmpty() || pendingDelta.isEmpty()) return
        liveContent += pendingDelta.toString()
        pendingDelta.setLength(0)
        DshStreamLog.i("ui.flush id=$liveId chars=${liveContent.length} preview='${DshStreamLog.preview(liveContent)}'")
        // Keep the ObservableList row stable while tokens arrive. `rows[i] =
        // copy()` is remove+add; LazyLoop treats an append at currentEnd as
        // "behind the visible range" and will not build the cell until scroll.
        insertLiveRow()
        render.ensureLiveCell()
        render.refresh(activeSessionId)
        render.followTail()
    }

    /**
     * Start a new text row lazily after a tool card so the next delta is
     * placed after that card instead of being appended to the old row.
     */
    private fun ensureSegment() {
        if (liveId.isNotEmpty()) return
        if (rootId.isEmpty()) return
        liveId = if (segment == 0) rootId else "$rootId-segment-$segment"
        if (liveContent.isEmpty() && pendingDelta.isEmpty()) {
            // Inserting an empty assistant into a brand-new List (only the user
            // bubble) is "add behind currentEnd". LazyLoop will not build that
            // cell until a real scroll, and DshMessageRow also skips mounting
            // Markdown when the first paint is empty. Wait for the first flush.
            return
        }
        insertLiveRow()
    }

    private fun insertLiveRow() {
        val id = liveId
        val rows = messages()
        if (id.isEmpty() || rows.any { it.id == id }) return
        // Keep content empty until settle. The first-flush snapshot must not
        // become the display source; DshMarkdown reads the live buffer.
        sessionStore.append(activeSessionId, DshMessage(id, DshMessageRole.ASSISTANT, "", streaming = true))
        render.ensureLiveCell()
    }

    private fun updateLiveRow(content: String, streaming: Boolean) {
        val rows = messages()
        val index = rows.indexOfFirst { it.id == liveId }
        if (index < 0) return
        sessionStore.update(activeSessionId, rows[index].copy(content = content, streaming = streaming, isReasoning = false))
        if (index >= rows.size - 1) render.realizeVisible()
    }

    private fun finalizeReasoning() {
        if (reasoningId.isEmpty()) return
        val rows = messages()
        val index = rows.indexOfFirst { it.id == reasoningId }
        if (index >= 0) sessionStore.update(activeSessionId, rows[index].copy(streaming = false, isReasoning = true))
    }

    /** Write the final text into the live row and leave streaming mode. */
    private fun settle(role: DshMessageRole, content: String) {
        val id = liveId
        if (id.isEmpty()) {
            release()
            return
        }
        val rows = messages()
        val sessionId = activeSessionId
        val revision = observerRevision
        val finalContent = content.ifEmpty { liveContent }
        finalizeReasoning()
        val index = rows.indexOfFirst { it.id == id }
        if (index >= 0) {
            sessionStore.update(activeSessionId, rows[index].copy(role = role, content = finalContent, streaming = false))
        } else {
            sessionStore.append(activeSessionId, DshMessage(id, role, finalContent, streaming = false))
        }
        render.realizeVisible()
        DshStreamLog.i(
            "ui.settle id=$id role=$role index=$index chars=${finalContent.length} preview='${DshStreamLog.preview(finalContent)}'",
        )
        reasoningId = ""
        reasoningContent = ""
        pendingDelta.setLength(0)
        stopButtonVisible = false
        streaming = false
        liveContent = finalContent
        listener.onActivityChanged()
        // Protocol ownership ends now. The renderer may keep the final text until layout.
        rootId = ""
        segment = 0
        anchorAssistantId = ""
        render.settle(sessionId, { revision == observerRevision && activeSessionId == sessionId }) {
            if (!streaming && liveId == id) {
                val stored = messages().firstOrNull { it.id == id }?.content.orEmpty()
                if (stored.length >= finalContent.length) {
                    liveId = ""
                    if (liveContent == finalContent) liveContent = ""
                }
            }
        }
    }

    private fun release() {
        liveId = ""
        rootId = ""
        segment = 0
        anchorAssistantId = ""
        reasoningId = ""
        reasoningContent = ""
        pendingDelta.setLength(0)
        streaming = false
        stopButtonVisible = false
        liveContent = ""
        listener.onActivityChanged()
    }

}
