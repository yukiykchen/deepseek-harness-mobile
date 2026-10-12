package com.example.dsh.dsh

import com.tencent.kuikly.core.base.PagerScope
import com.tencent.kuikly.core.reactive.collection.ObservableList
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.timer.setTimeout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * In-memory message lists of every known session, backed by the local store.
 *
 * - One [ObservableList] per session id. The active session's list is the
 *   page's `messages`, so writes to it show up in whichever panel renders it.
 * - A session is *ready* once its disk snapshot was read (or there is no
 *   local store). Switching to a session that is not ready waits for it.
 * - Disk reads run on one background coroutine because the SQLite driver is
 *   shared and must not be queried concurrently. A disk snapshot only fills an
 *   empty list: a host history response or a new local prompt that arrived
 *   first wins.
 * - [epochFor] changes when a list goes from empty to filled. LazyLoop picks
 *   its visible range from the first items it sees, so the panel remounts.
 */
internal class DshSessionMessageStore(
    scope: PagerScope,
    private val localStore: () -> DshLocalStore?,
    private val connectionId: () -> String,
    /** Blank "new session" home pages never take a disk snapshot. */
    private val isBlank: (sessionId: String) -> Boolean,
    private val listener: Listener,
    private val protectedSessions: () -> Set<String> = { emptySet() },
) : PagerScope by scope {
    interface Listener {
        /** [sessionId] became ready after a disk read (whether or not it had rows). */
        fun onDiskLoaded(sessionId: String, preload: Boolean, scrollToEndAfterLoad: Boolean)

        /** [sessionId] became ready without a disk read because there is no local store. */
        fun onReady(sessionId: String)
    }

    private val states = linkedMapOf<String, ObservableList<DshMessage>>()
    private val ready = mutableSetOf<String>()
    private val pendingReads = mutableSetOf<String>()
    private val rowRevisions = mutableMapOf<String, Long>()
    private val epochs = mutableMapOf<String, Int>()
    private var epochRevision by observable(0)
    private val readScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var preloadSequence = 0
    private var closed = false
    private var scopeId = ""
    private var scopeRevision = 0L
    private val storageTasks = Channel<() -> Unit>(Channel.UNLIMITED)

    init {
        readScope.launch {
            try {
                for (task in storageTasks) runCatching { task() }.onFailure {
                    DshStreamLog.i("cache.task-failed message=${it.message}")
                }
            }
            finally { readScope.cancel() }
        }
    }

    private fun ensureScope() {
        val next = connectionId()
        if (scopeId == next) return
        scopeId = next
        scopeRevision++
        states.clear()
        ready.clear()
        pendingReads.clear()
        epochs.clear()
        rowRevisions.clear()
        epochRevision++
    }

    private fun trimCache() {
        val retained = protectedSessions()
        while (states.size > MAX_CACHED_SESSIONS || states.values.sumOf { rows -> rows.sumOf { it.content.length.toLong() } } > MAX_CACHED_TEXT_CHARS) {
            val evicted = states.keys.firstOrNull { it !in retained } ?: return
            states.remove(evicted)
            ready.remove(evicted)
            epochs.remove(evicted)
            rowRevisions.remove(evicted)
            pendingReads.remove(evicted)
        }
    }


    /** The list for [sessionId], created empty (and optionally read from disk) on first use. */
    fun state(
        sessionId: String,
        loadFromDisk: Boolean = true,
        scrollToEndAfterLoad: Boolean = true,
    ): ObservableList<DshMessage> {
        ensureScope()
        states.remove(sessionId)?.let {
            states[sessionId] = it
            return it
        }
        val state = ObservableList<DshMessage>()
        states[sessionId] = state
        trimCache()
        if (loadFromDisk) loadFromDisk(sessionId, scrollToEndAfterLoad)
        return state
    }

    /** The only writer of the reactive timeline list. Its identity stays stable. */
    fun dispatch(sessionId: String, change: DshTimelineChange) {
        val rows = state(sessionId, loadFromDisk = false)
        rowRevisions[sessionId] = (rowRevisions[sessionId] ?: 0) + 1
        val next = dshReduceTimeline(rows.toList(), change)
        val needsRemount = rows.isEmpty() && next.isNotEmpty()
        val shared = minOf(rows.size, next.size)
        for (index in 0 until shared) if (rows[index] != next[index]) rows[index] = next[index]
        while (rows.size > next.size) rows.removeAt(rows.lastIndex)
        if (rows.size < next.size) rows.addAll(next.subList(rows.size, next.size))
        if (needsRemount) remount(sessionId)
        trimCache()
    }

    fun append(sessionId: String, row: DshMessage) = dispatch(sessionId, DshTimelineChange.Append(listOf(row)))
    fun update(sessionId: String, row: DshMessage) = dispatch(sessionId, DshTimelineChange.Update(row))
    fun remove(sessionId: String, id: String) = dispatch(sessionId, DshTimelineChange.Remove(id))
    fun replace(sessionId: String, rows: List<DshMessage>) = dispatch(sessionId, DshTimelineChange.Snapshot(rows))

    /** Snapshot on the UI thread; serialize the write with all cache reads. */
    fun persist(sessionId: String) {
        val store = localStore() ?: return
        ensureScope()
        val snapshot = states[sessionId]?.toList() ?: return
        val capturedScope = scopeId
        storageTasks.trySend { runCatching { store.replaceMessages(capturedScope, sessionId, snapshot) } }
    }

    fun persistSessions(sessions: List<DshSession>) {
        val store = localStore() ?: return
        ensureScope()
        val snapshot = sessions.toList()
        val capturedScope = scopeId
        storageTasks.trySend { runCatching { store.replaceSessions(capturedScope, snapshot) } }
    }

    fun isReady(sessionId: String): Boolean {
        ensureScope()
        return ready.contains(sessionId)
    }

    fun markReady(sessionId: String) {
        ensureScope()
        ready.add(sessionId)
    }

    /** Drop a session the host no longer lists. */
    fun forget(sessionId: String) {
        states.remove(sessionId)
        ready.remove(sessionId)
        pendingReads.remove(sessionId)
        epochs.remove(sessionId)
        rowRevisions.remove(sessionId)
    }

    fun epochFor(sessionId: String): Int {
        epochRevision
        return epochs[sessionId] ?: 0
    }

    fun remount(sessionId: String) {
        epochs[sessionId] = (epochs[sessionId] ?: 0) + 1
        epochRevision += 1
    }

    fun loadFromDisk(sessionId: String, scrollToEndAfterLoad: Boolean = true, preload: Boolean = false) {
        ensureScope()
        if (closed) return
        val store = localStore()
        if (store == null) {
            ready.add(sessionId)
            listener.onReady(sessionId)
            return
        }
        val target = states[sessionId] ?: return
        if (!pendingReads.add(sessionId)) return
        val capturedRowsRevision = rowRevisions[sessionId] ?: 0
        val capturedScope = scopeId
        val revision = scopeRevision
        val queuedAt = TimeSource.Monotonic.markNow()
        dshPerfLog("sessionRead.queued:$sessionId", queuedAt)
        storageTasks.trySend {
            dshPerfLog("sessionRead.coroutine.started:$sessionId", queuedAt)
            read(store, capturedScope, revision, capturedRowsRevision, target, sessionId, "sessionRead", queuedAt) {
                listener.onDiskLoaded(sessionId, preload = preload, scrollToEndAfterLoad = scrollToEndAfterLoad)
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
        ++preloadSequence
        sessionIds.take(MAX_CACHED_SESSIONS).forEach { sessionId ->
            state(sessionId, loadFromDisk = false)
            if (!isReady(sessionId)) loadFromDisk(sessionId, scrollToEndAfterLoad = false, preload = true)
        }
    }

    fun close() {
        closed = true
        scopeRevision++
        storageTasks.close()
        // Drain already captured snapshots before terminating the cache worker.
    }

    /** Runs on [readScope]; hops back to the UI thread to apply the rows. */
    private fun read(
        store: DshLocalStore,
        capturedScope: String,
        revision: Long,
        capturedRowsRevision: Long,
        target: ObservableList<DshMessage>,
        sessionId: String,
        trace: String,
        queuedAt: TimeMark,
        onApplied: () -> Unit,
    ) {
        val readStartedAt = TimeSource.Monotonic.markNow()
        dshPerfLog("$trace.sqlite.begin:$sessionId", queuedAt)
        val loaded = runCatching { store.loadMessages(capturedScope, sessionId) }
            .getOrDefault(emptyList())
            .filterNot { it.isRuntimeContextSnapshot() }
        val queryFinishedAt = TimeSource.Monotonic.markNow()
        val queryMs = readStartedAt.elapsedNow().inWholeMilliseconds
        dshPerfLog("$trace.sqlite.end:$sessionId messages=${loaded.size} query=${queryMs}ms", queuedAt)
        if (closed) return
        setTimeout(pagerId, 0) {
            if (closed || connectionId() != capturedScope || scopeId != capturedScope || scopeRevision != revision || states[sessionId] !== target) return@setTimeout
            pendingReads.remove(sessionId)
            val state = target
            ready.add(sessionId)
            val uiWaitMs = queryFinishedAt.elapsedNow().inWholeMilliseconds
            dshPerfLog("$trace.ui.callback:$sessionId uiWait=${uiWaitMs}ms", queuedAt)
            dshPerfLog(
                "sessionData.disk.done:$sessionId messages=${loaded.size} query=${queryMs}ms uiWait=${uiWaitMs}ms",
                readStartedAt,
            )
            if ((rowRevisions[sessionId] ?: 0) == capturedRowsRevision && state.isEmpty() && loaded.isNotEmpty() && !isBlank(sessionId)) {
                replace(sessionId, loaded)
                dshPerfLog("sessionData.ui.applied:$sessionId messages=${loaded.size}")
            }
            onApplied()
        }
    }

    private companion object {
        const val MAX_CACHED_SESSIONS = 16
        const val MAX_CACHED_TEXT_CHARS = 2_000_000L
    }
}
