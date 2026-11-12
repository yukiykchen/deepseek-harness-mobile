package io.github.yukiykchen.dsh.feature.home

import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import com.tencent.kuikly.core.base.*
import com.tencent.kuikly.core.reactive.collection.ObservableList
import com.tencent.kuikly.core.reactive.handler.*
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.timer.setTimeout
import com.tencent.kuikly.core.views.*
import com.tencent.kuikly.core.views.List
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineDispatcher
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import io.github.yukiykchen.dsh.framework.setTimeout
import io.github.yukiykchen.dsh.framework.dshPerfLog
import io.github.yukiykchen.dsh.framework.dshCacheFailureLog
import io.github.yukiykchen.dsh.model.isRuntimeContextSnapshot
import io.github.yukiykchen.dsh.model.DshMessage
import io.github.yukiykchen.dsh.data.DshLocalStore

/**
 * In-memory message lists of every known session, backed by the local store.
 *
 * - One [ObservableList] per session id. The active session's list is the
 *   page's `messages`, so writes to it show up in whichever panel renders it.
 * - A session is *ready* once its disk snapshot was read (or there is no
 *   local store). Switching to a session that is not ready waits for it.
 * - Disk work is serialized by the local store. Reads run on one background
 *   coroutine; a disk snapshot only fills an empty list, so a host history
 *   response or a new local prompt that arrived first wins.
 * - [epochFor] changes when a list goes from empty to filled. LazyLoop picks
 *   its visible range from the first items it sees, so the panel remounts.
 */

class DshSessionMessageStore(
    scope: PagerScope,
    private val localStore: () -> DshLocalStore?,
    private val connectionId: () -> String,
    /** Blank "new session" home pages never take a disk snapshot. */
    private val isBlank: (sessionId: String) -> Boolean,
    private val listener: Listener,
    readDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val postToPage: (() -> Unit) -> Unit = { setTimeout(scope.pagerId, 0, it) },
) : PagerScope by scope {
    interface Listener {
        /** [sessionId] became ready after a disk read (whether or not it had rows). */
        fun onDiskLoaded(sessionId: String, preload: Boolean, scrollToEndAfterLoad: Boolean)

        /** [sessionId] became ready without a disk read because there is no local store. */
        fun onReady(sessionId: String)
    }

    private val states = mutableMapOf<String, ObservableList<DshMessage>>()
    private val ready = mutableSetOf<String>()
    private val pendingReads = mutableMapOf<String, ObservableList<DshMessage>>()
    private val epochs = mutableMapOf<String, Int>()
    private var epochRevision by observable(0)
    private val readScope = CoroutineScope(SupervisorJob() + readDispatcher)
    private var preloadSequence = 0
    private var closed = false

    /** The list for [sessionId], created empty (and optionally read from disk) on first use. */
    fun state(
        sessionId: String,
        loadFromDisk: Boolean = true,
        scrollToEndAfterLoad: Boolean = true,
    ): ObservableList<DshMessage> {
        states[sessionId]?.let { return it }
        val state = ObservableList<DshMessage>()
        states[sessionId] = state
        if (loadFromDisk) loadFromDisk(sessionId, scrollToEndAfterLoad)
        return state
    }
    fun put(sessionId: String, messages: ObservableList<DshMessage>) {
        states[sessionId] = messages
    }

    /** Remember [messages] as [sessionId]'s list and write a snapshot to disk. */
    fun persist(sessionId: String, messages: ObservableList<DshMessage>) {
        val snapshot = messages.toList()
        val scopeId = connectionId()
        states[sessionId] = messages
        localStore()?.replaceMessagesAsync(scopeId, sessionId, snapshot)
    }
    fun isReady(sessionId: String): Boolean = ready.contains(sessionId)
    fun markReady(sessionId: String) {
        ready.add(sessionId)
    }

    /** Drop a session the host no longer lists. */
    fun forget(sessionId: String) {
        states.remove(sessionId)
        ready.remove(sessionId)
        pendingReads.remove(sessionId)
        epochs.remove(sessionId)
    }
    fun epochFor(sessionId: String): Int {
        epochRevision
        return epochs[sessionId] ?: 0
    }
    fun remount(sessionId: String) {
        epochs[sessionId] = (epochs[sessionId] ?: 0) + 1
        epochRevision += 1
    }
    fun loadFromDisk(sessionId: String, scrollToEndAfterLoad: Boolean = true) {
        if (closed || pendingReads.containsKey(sessionId)) return
        val store = localStore()
        if (store == null) {
            ready.add(sessionId)
            listener.onReady(sessionId)
            return
        }
        val state = states.getOrPut(sessionId) { ObservableList() }
        pendingReads[sessionId] = state
        val scopeId = connectionId()
        val queuedAt = TimeSource.Monotonic.markNow()
        dshPerfLog("sessionRead.queued:$sessionId", queuedAt)
        readScope.launch {
            dshPerfLog("sessionRead.coroutine.started:$sessionId", queuedAt)
            read(store, scopeId, sessionId, state, "sessionRead", queuedAt) {
                listener.onDiskLoaded(sessionId, preload = false, scrollToEndAfterLoad = scrollToEndAfterLoad)
            }
        }
    }

    /**
     * Warm every known conversation after the session index is available.
     * Lists are created up front but panels are not: LazyLoop initializes its
     * visible range from the initial list and may not realize the first items
     * when an empty list is populated later.
     */
    fun preload(sessionIds: List<String>) {
        if (closed) return
        val preloadId = ++preloadSequence
        val queuedAt = TimeSource.Monotonic.markNow()
        dshPerfLog("preload.$preloadId.queued sessions=${sessionIds.size}", queuedAt)
        sessionIds.forEach { state(it, loadFromDisk = false) }
        val store = localStore() ?: run {
            sessionIds.forEach {
                ready.add(it)
                listener.onReady(it)
            }
            return
        }
        val scopeId = connectionId()
        val pending = sessionIds.distinct()
            .filterNot { ready.contains(it) }
            .filterNot { pendingReads.containsKey(it) }
            .map { it to states.getValue(it) }
        pending.forEach { (id, state) -> pendingReads[id] = state }
        if (pending.isEmpty()) {
            dshPerfLog("preload.$preloadId.nothing-pending", queuedAt)
            return
        }
        dshPerfLog("preload.$preloadId.pending count=${pending.size}", queuedAt)
        readScope.launch {
            dshPerfLog("preload.$preloadId.coroutine.started", queuedAt)
            pending.forEach { (sessionId, state) ->
                read(store, scopeId, sessionId, state, "preload.$preloadId", queuedAt) {
                    listener.onDiskLoaded(sessionId, preload = true, scrollToEndAfterLoad = false)
                    dshPerfLog("preload.$preloadId.ui.applied:$sessionId", queuedAt)
                }
            }
            dshPerfLog("preload.$preloadId.coroutine.finished", queuedAt)
        }
    }
    fun close() {
        closed = true
        pendingReads.clear()
        readScope.cancel()
    }

    /** Loads through the store's serialized database queue, then applies rows on the UI thread. */
    private fun read(
        store: DshLocalStore,
        scopeId: String,
        sessionId: String,
        expectedState: ObservableList<DshMessage>,
        trace: String,
        queuedAt: TimeMark,
        onApplied: () -> Unit,
    ) {
        val readStartedAt = TimeSource.Monotonic.markNow()
        dshPerfLog("$trace.sqlite.begin:$sessionId", queuedAt)
        val loaded = runCatching { store.loadMessages(scopeId, sessionId) }
            .onFailure { dshCacheFailureLog("message-read") }
            .getOrDefault(emptyList())
            .filterNot { it.isRuntimeContextSnapshot() }
        val queryFinishedAt = TimeSource.Monotonic.markNow()
        val queryMs = readStartedAt.elapsedNow().inWholeMilliseconds
        dshPerfLog("$trace.sqlite.end:$sessionId messages=${loaded.size} query=${queryMs}ms", queuedAt)
        postToPage {
            if (closed || pendingReads[sessionId] !== expectedState) return@postToPage
            pendingReads.remove(sessionId)
            if (connectionId() != scopeId || states[sessionId] !== expectedState) return@postToPage
            val state = expectedState
            ready.add(sessionId)
            val uiWaitMs = queryFinishedAt.elapsedNow().inWholeMilliseconds
            dshPerfLog("$trace.ui.callback:$sessionId uiWait=${uiWaitMs}ms", queuedAt)
            dshPerfLog(
                "sessionData.disk.done:$sessionId messages=${loaded.size} query=${queryMs}ms uiWait=${uiWaitMs}ms",
                readStartedAt,
            )
            if (state.isEmpty() && loaded.isNotEmpty() && !isBlank(sessionId)) {
                state.addAll(loaded)
                remount(sessionId)
                dshPerfLog("sessionData.ui.applied:$sessionId messages=${loaded.size}")
            }
            onApplied()
        }
    }
}
