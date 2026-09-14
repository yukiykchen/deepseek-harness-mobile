package com.example.dsh.dsh

import com.tencent.kuikly.core.module.NetworkModule
import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

/**
 * Host repository over the DSH 0.1.5 protocol. It owns the Host-wide streams
 * (`$events`, `session/control`, `workspace/follow`), one `session/follow`
 * per observed session, pending approval / question waterfalls, and the live
 * prompt streams the conversation page paints.
 */
internal class DshRemoteHostRepository(
    network: NetworkModule,
    webSocket: DshWebSocketModule,
    private val connection: DshHostConnection,
    auth: DshHostAuthenticator,
    pagerId: String,
    onState: (DshHostRuntimeState) -> Unit = {},
    private val onQueueSnapshot: (String) -> Unit = {},
    private val onJobsSnapshot: (String) -> Unit = {},
    private val onSessionStatus: (String, Boolean) -> Unit = { _, _ -> },
    private val onProjection: (String, String, String, Int) -> Unit = { _, _, _, _ -> },
    private val onSessionEvent: (String, DshRawSessionEvent) -> Unit = { _, _ -> },
    private val onRemoteEvent: (String) -> Unit = {},
    private val onPendingInteraction: (String) -> Unit = {},
    private val onSessionsChanged: () -> Unit = {},
    private val onSessionError: (String, String) -> Unit = { _, _ -> },
) : DshRepository {
    internal val store = DshHostStore()
    private val runtime = DshHostConnectionRuntime(
        network = network,
        webSocket = webSocket,
        connection = connection,
        auth = auth,
        pagerId = pagerId,
        onState = { state ->
            if (state.phase == DshHostRuntimePhase.RECONNECTING || state.phase == DshHostRuntimePhase.AUTH_REQUIRED) {
                onGenerationLost()
            }
            if (state.phase == DshHostRuntimePhase.READY) onGenerationReady()
            onState(state)
        },
        onEventsFrame = ::handleEventsFrame,
        onControlFrame = ::handleControlFrame,
        onWorkspaceFrame = ::handleWorkspaceFrame,
        onSessionBaseline = { value -> store.replaceSessions(parseSessions(value)) },
    )

    private class Follow(
        val sessionId: String,
        var stream: DshMuxStream? = null,
        var snapshotReady: Boolean = false,
        val records: MutableList<JSONObject> = mutableListOf(),
        val waiters: MutableList<Pair<(List<DshWebTimelineItem>) -> Unit, (String) -> Unit>> = mutableListOf(),
        var header: JSONObject? = null,
        var hasMore: Boolean = false,
    )

    private class ActiveStream(
        val sessionId: String,
        val promptRpcId: String,
        val onDelta: (String, Boolean) -> Unit,
        val onComplete: (String) -> Unit,
        val onError: (String) -> Unit,
        var observed: Boolean = false,
        val accumulated: StringBuilder = StringBuilder(),
        var finalMessage: String = "",
        var failure: String = "",
        var attemptId: String = "",
    )

    private val follows = linkedMapOf<String, Follow>()
    private val activeStreams = linkedMapOf<String, ActiveStream>()
    private var hostImageLimits: DshImageLimits? = null

    fun currentConnectionState(): DshHostRuntimeState = runtime.currentState()
    fun isProductReady(): Boolean = runtime.currentState().phase == DshHostRuntimePhase.READY
    fun retryAuthentication() = runtime.retryAuthentication()
    fun stop() {
        follows.values.forEach { it.stream?.cancel() }
        follows.clear()
        runtime.stop()
    }

    fun imageLimits(): DshImageLimits? = hostImageLimits

    // ------------------------------------------------------------------ generation lifecycle

    private fun onGenerationLost() {
        // Streams die with the socket; keep the journals so the UI has something
        // to show, but mark them for re-follow on the next generation.
        follows.values.forEach { follow ->
            // Cancel drops a still-queued open so the READY flush and re-follow cannot both open one.
            follow.stream?.cancel()
            follow.stream = null
            follow.snapshotReady = false
        }
        activeStreams.values.forEach { it.observed = false }
    }

    private fun onGenerationReady() {
        follows.values.toList().forEach { follow ->
            if (follow.stream == null) openFollow(follow)
        }
    }

    // ------------------------------------------------------------------ $events

    private fun handleEventsFrame(frame: JSONObject) {
        when (frame.optString("type")) {
            "emit" -> {
                val event = frame.optString("event")
                val args = frame.optJSONArray("args") ?: JSONArray()
                val isError = event == DshHostProtocol.EVENT_SESSION_ERROR
                DshStreamLog.record(
                    level = if (isError) DshLogLevel.ERROR else DshLogLevel.INFO,
                    eventType = if (isError) event else DshLogEvent.HOST_EVENT,
                    message = "host.emit event=$event args='${DshStreamLog.preview(args.toString(), 200)}'",
                    sessionId = if (isError) args.optString(0).orEmpty() else "",
                )
                when (event) {
                    DshHostProtocol.EVENT_SESSION_ADDED -> {
                        val summary = args.optJSONObject(0) ?: return
                        parseSession(summary)?.let { store.applySessionAdded(it) }
                        onSessionsChanged()
                    }
                    DshHostProtocol.EVENT_SESSION_REMOVED -> {
                        val id = args.optString(0).orEmpty()
                        if (id.isEmpty()) return
                        store.removeSession(id)
                        follows.remove(id)?.stream?.cancel()
                        onSessionsChanged()
                    }
                    DshHostProtocol.EVENT_SESSION_STATUS -> {
                        val id = args.optString(0).orEmpty()
                        val running = args.optBoolean(1)
                        val current = store.sessions[id] ?: return
                        store.sessions[id] = current.copy(running = running, blank = if (running) false else current.blank)
                        onSessionStatus(id, running)
                    }
                    DshHostProtocol.EVENT_SESSION_ACTIVITY -> {
                        val id = args.optString(0).orEmpty()
                        val current = store.sessions[id] ?: return
                        store.sessions[id] = current.copy(
                            updatedLabel = args.optLong(1).takeIf { it > 0 }?.toString() ?: current.updatedLabel,
                            blank = false,
                        )
                        onSessionsChanged()
                    }
                    DshHostProtocol.EVENT_SESSION_ERROR -> onSessionError(args.optString(0).orEmpty(), args.optString(1).orEmpty())
                    else -> onRemoteEvent(event)
                }
            }
            "waterfall" -> {
                val eventId = frame.optString("eventId")
                val agentId = frame.optString("agentId")
                val event = frame.optString("event")
                if (eventId.isEmpty() || agentId.isEmpty()) return
                if (event != DshHostProtocol.EVENT_APPROVAL_REQUEST && event != DshHostProtocol.EVENT_QUESTION_REQUEST) {
                    // Unknown waterfall: defer to the Host default so it never hangs on us.
                    runtime.respondEvent(eventId, JSONObject().apply { put("kind", "next") }) { _, _ -> }
                    return
                }
                val pending = JSONObject().apply {
                    put("type", event)
                    put("sessionId", agentId)
                    put("eventId", eventId)
                    put("request", frame.optJSONObject("request") ?: JSONObject())
                }
                DshStreamLog.question("mux.waterfall event=$event eventId=$eventId session=$agentId")
                store.putPending(eventId, pending.toString())
                onPendingInteraction(agentId)
            }
            "cancel" -> {
                val eventId = frame.optString("eventId")
                val pending = store.pendingInteractions[eventId]
                store.removePending(eventId)
                val sessionId = pending?.let { runCatching { JSONObject(it) }.getOrNull()?.optString("sessionId") }.orEmpty()
                DshStreamLog.question("mux.waterfall-cancel eventId=$eventId session=$sessionId")
                if (sessionId.isNotEmpty()) onPendingInteraction(sessionId)
            }
        }
    }

    // ------------------------------------------------------------------ session/control

    private fun handleControlFrame(frame: JSONObject) {
        when (frame.optString("type")) {
            "baseline" -> {
                val value = frame.optJSONObject("value") ?: return
                val queues = value.optJSONObject("queues") ?: JSONObject()
                val jobs = value.optJSONObject("jobs") ?: JSONObject()
                val projections = value.optJSONObject("projections") ?: JSONObject()
                for (sessionId in queues.keys()) {
                    store.replaceQueue(sessionId, queues.optJSONArray(sessionId)?.toString() ?: "[]")
                    onQueueSnapshot(sessionId)
                }
                for (sessionId in jobs.keys()) {
                    store.replaceJobs(sessionId, jobs.optJSONArray(sessionId)?.toString() ?: "[]")
                    onJobsSnapshot(sessionId)
                }
                for (sessionId in projections.keys()) {
                    applyProjectionBaseline(sessionId, projections.optJSONObject(sessionId))
                }
            }
            "queue" -> {
                val sessionId = frame.optString("sessionId")
                store.replaceQueue(sessionId, frame.optJSONArray("items")?.toString() ?: "[]")
                onQueueSnapshot(sessionId)
            }
            "jobs" -> {
                val sessionId = frame.optString("sessionId")
                store.replaceJobs(sessionId, frame.optJSONArray("jobs")?.toString() ?: "[]")
                onJobsSnapshot(sessionId)
            }
            "projection" -> {
                val sessionId = frame.optString("sessionId")
                val key = frame.optString("key")
                val value = frame.opt("value")?.toString() ?: "null"
                applyProjection(sessionId, key, value, frame.optInt("seq", -1))
            }
        }
    }

    private fun applyProjectionBaseline(sessionId: String, baseline: JSONObject?) {
        if (baseline == null) return
        val seq = baseline.optInt("asOfSeq", -1)
        val values = baseline.optJSONObject("values") ?: return
        for (key in values.keys()) {
            applyProjection(sessionId, key, values.opt(key)?.toString() ?: "null", seq)
        }
    }

    private fun applyProjection(sessionId: String, key: String, value: String, seq: Int) {
        if (key == "imageLimits") {
            parseImageLimits(value)?.let { hostImageLimits = it }
        }
        store.applyProjection(sessionId, key, value, seq)
        // A title projection renames the store's list row; the page keeps its own copy.
        if (key == "title") onSessionsChanged()
        onProjection(sessionId, key, value, seq)
    }

    // ------------------------------------------------------------------ workspace/follow

    private fun handleWorkspaceFrame(frame: JSONObject) {
        when (frame.optString("type")) {
            "baseline" -> {
                val value = frame.optJSONObject("value") ?: return
                store.replaceWorkspaceBaseline(
                    value.optJSONArray("items")?.toString() ?: "[]",
                    stringSet(value.optJSONArray("archivedSessionIds")),
                )
            }
            "upsert" -> frame.optJSONObject("workspace")?.let { store.upsertWorkspace(it) }
            "remove" -> store.removeWorkspace(frame.optString("workspaceId"))
            "order" -> frame.optJSONArray("workspaceIds")?.let { store.reorderWorkspaces(it.toString()) }
            "archived" -> store.replaceArchivedSessionIds(stringSet(frame.optJSONArray("archivedSessionIds")))
        }
        onSessionsChanged()
    }

    // ------------------------------------------------------------------ sessions

    override fun loadSessions(onSuccess: (List<DshSession>) -> Unit, onError: (String) -> Unit) {
        call(DshHostProtocol.SESSION_LIST, DshHostProtocol.args { put("_request", JSONObject()) }) { value, error ->
            if (error != null || value == null) {
                onError(error?.message ?: "session/list 返回为空")
                return@call
            }
            val sessions = parseSessions(value)
            store.replaceSessions(sessions)
            onSuccess(store.sessions.values.toList())
        }
    }

    private fun parseSessions(value: JSONObject): List<DshSession> {
        val items = value.optJSONArray("items") ?: JSONArray()
        return buildList {
            for (index in 0 until items.length()) {
                parseSession(items.optJSONObject(index) ?: continue)?.let(::add)
            }
        }
    }

    private fun parseSession(item: JSONObject): DshSession? {
        val id = item.optString("sessionId")
        if (id.isEmpty()) return null
        val hints = item.optJSONObject("projections")?.optJSONObject("values")
        val title = hints?.optString("title")?.trim()?.removeSurrounding("\"")?.takeIf { it.isNotEmpty() }
            ?: store.projections[id]?.get("title")?.value?.trim()?.removeSurrounding("\"")?.takeIf { it.isNotEmpty() }
            ?: "尚无标题"
        return DshSession(
            id = id,
            title = title,
            workspace = "Host",
            updatedLabel = item.optLong("updatedAt").takeIf { it > 0 }?.toString().orEmpty(),
            running = item.optBoolean("running"),
            blank = item.optBoolean("blank"),
            cwd = item.optString("cwd"),
            parentSessionId = item.optString("parentSessionId").takeIf { it.isNotEmpty() },
            origin = item.optString("origin").takeIf { it.isNotEmpty() },
            agentPreset = item.optString("agentPreset").takeIf { it.isNotEmpty() },
        )
    }

    override fun createSession(workspaceId: String?, onSuccess: (String) -> Unit, onError: (String) -> Unit) {
        val payload = DshHostProtocol.request {
            workspaceId?.takeIf { it.isNotEmpty() }?.let { put("workspaceId", it) }
        }
        call(DshHostProtocol.SESSION_CREATE, payload) { value, error ->
            if (error != null || value == null) {
                onError(error?.message ?: "session/create 返回为空")
                return@call
            }
            val id = value.optString("sessionId")
            if (id.isEmpty()) onError("session/create 未返回 sessionId") else onSuccess(id)
        }
    }

    fun renameSession(sessionId: String, title: String, callback: (JSONObject?, DshRpcError?) -> Unit) {
        call(DshHostProtocol.SESSION_RENAME, DshHostProtocol.request {
            put("sessionId", sessionId)
            put("title", title)
        }) { value, error ->
            if (error == null && value != null) {
                val accepted = value.optString("title").ifEmpty { title }
                store.applyProjection(sessionId, "title", accepted, value.optInt("seq", -1))
            }
            callback(value, error)
        }
    }

    fun forkSession(sessionId: String, atSeq: Int?, callback: (JSONObject?, DshRpcError?) -> Unit) {
        call(DshHostProtocol.SESSION_FORK, DshHostProtocol.request {
            put("sessionId", sessionId)
            atSeq?.let { put("atSeq", it) }
        }) { value, error -> callback(value, error) }
    }

    fun archiveSession(sessionId: String, callback: (JSONObject?, DshRpcError?) -> Unit) {
        call(DshHostProtocol.WORKSPACE_ARCHIVE_SESSION, DshHostProtocol.request { put("sessionId", sessionId) }) { value, error ->
            if (error == null && value != null) {
                store.replaceArchivedSessionIds(stringSet(value.optJSONArray("archivedSessionIds")))
            }
            callback(value, error)
        }
    }

    fun sessionExportUrl(sessionId: String, includeDescendants: Boolean = true): String {
        val encodedSessionId = dshEncodeQueryComponent(sessionId)
        return "${connection.trimmedBase}${DshHostProtocol.SESSION_EXPORT_PATH}" +
            "?sessionId=$encodedSessionId" +
            "&includeDescendants=${if (includeDescendants) "true" else "false"}"
    }

    // ------------------------------------------------------------------ session/follow (history + live)

    /**
     * Deliver the folded timeline of one session. Opens (or reuses) that
     * session's `session/follow` stream: the first call returns the opening
     * snapshot, later calls fold the retained journal synchronously.
     */
    fun loadWebTimeline(
        sessionId: String,
        onSuccess: (List<DshWebTimelineItem>) -> Unit,
        onError: (String) -> Unit = {},
    ) {
        val follow = follows.getOrPut(sessionId) { Follow(sessionId) }
        if (follow.snapshotReady && follow.stream != null) {
            onSuccess(timelineOf(follow))
            return
        }
        follow.waiters += onSuccess to onError
        if (follow.stream == null) openFollow(follow)
    }

    /** Start following [sessionId] without asking for its timeline. */
    private fun ensureFollow(sessionId: String) {
        val follow = follows.getOrPut(sessionId) { Follow(sessionId) }
        if (follow.stream == null) openFollow(follow)
    }

    /** Stop following sessions that are no longer mounted, keeping [keep]. */
    fun trimFollows(keep: Set<String>) {
        follows.keys.filterNot { it in keep }.toList().forEach { id ->
            follows.remove(id)?.stream?.cancel()
        }
    }

    private fun timelineOf(follow: Follow): List<DshWebTimelineItem> {
        val array = JSONArray()
        follow.records.forEach(array::put)
        return DshWebTimelineParser.parseWebTimeline(array)
    }

    private fun openFollow(follow: Follow) {
        val payload = DshHostProtocol.request {
            put("address", JSONObject().apply {
                put("kind", "session")
                put("sessionId", follow.sessionId)
            })
            put("maxMessages", HISTORY_PAGE_MESSAGES)
            put("assistantStream", true)
        }
        follow.stream = runtime.openStream(DshHostProtocol.SESSION_FOLLOW, payload, object : DshMuxStreamListener {
            override fun onItem(value: JSONObject) = handleFollowFrame(follow, value)

            override fun onEnd() {
                DshStreamLog.record(
                    DshLogLevel.INFO,
                    DshLogEvent.FOLLOW,
                    "follow.end session=${follow.sessionId}",
                    sessionId = follow.sessionId,
                )
                follow.stream = null
                follow.snapshotReady = false
            }

            override fun onError(error: DshRpcError) {
                DshStreamLog.record(
                    DshLogLevel.ERROR,
                    DshLogEvent.FOLLOW,
                    "follow.error session=${follow.sessionId} code=${error.code} message='${error.message}'",
                    sessionId = follow.sessionId,
                )
                follow.stream = null
                follow.snapshotReady = false
                val waiters = follow.waiters.toList()
                follow.waiters.clear()
                if (!dshIsTransportInterrupt(error.code, error.message)) {
                    waiters.forEach { (_, onError) -> onError(error.message) }
                    if (error.code == "session/not-found") follows.remove(follow.sessionId)
                }
            }
        })
    }

    private fun handleFollowFrame(follow: Follow, frame: JSONObject) {
        val sessionId = follow.sessionId
        when (frame.optString("type")) {
            "snapshot" -> {
                follow.records.clear()
                follow.header = frame.optJSONObject("header")
                follow.hasMore = frame.optBoolean("hasMore")
                val records = frame.optJSONArray("records") ?: JSONArray()
                store.sessionEvents[sessionId] = mutableListOf()
                for (index in 0 until records.length()) {
                    val record = records.optJSONObject(index) ?: continue
                    follow.records += record
                    val event = record.optJSONObject("event") ?: continue
                    store.applySessionEvent(sessionId, event.optInt("seq", -1), event.optString("type"), record.toString())
                }
                store.applySubscribed(sessionId, frame.optInt("cursor", -1))
                applyProjectionBaseline(sessionId, frame.optJSONObject("projections"))
                follow.snapshotReady = true
                DshStreamLog.record(
                    DshLogLevel.INFO,
                    DshLogEvent.FOLLOW,
                    "follow.snapshot session=$sessionId records=${records.length()} cursor=${frame.optInt("cursor", -1)} hasMore=${follow.hasMore}",
                    sessionId = sessionId,
                    ref = frame.optInt("cursor", -1).toString(),
                )
                val waiters = follow.waiters.toList()
                follow.waiters.clear()
                if (waiters.isNotEmpty()) {
                    val timeline = timelineOf(follow)
                    waiters.forEach { (onSuccess, _) -> onSuccess(timeline) }
                }
                frame.optJSONObject("assistantStream")?.optJSONObject("activeAttempt")?.let { attempt ->
                    replayActiveAttempt(sessionId, attempt)
                }
            }
            "event" -> {
                val event = frame.optJSONObject("event") ?: return
                val seq = event.optInt("seq", -1)
                val type = event.optString("type")
                val record = JSONObject().apply { put("event", event) }
                follow.records += record
                if (follow.records.size > MAX_RETAINED_RECORDS) follow.records.removeAt(0)
                store.applySessionEvent(sessionId, seq, type, record.toString())
                // Durable events keep their Host type as the log's event type, so the page
                // filters by `tool/call`, `tool/result`, `turn/end` and friends directly.
                DshStreamLog.record(
                    DshLogLevel.INFO,
                    type.ifEmpty { DshLogEvent.FOLLOW },
                    "follow.event session=$sessionId type=$type seq=$seq chars=${record.toString().length}",
                    sessionId = sessionId,
                    ref = seq.toString(),
                    sizeBytes = record.toString().length,
                )
                if (seq > -1) onSessionEvent(sessionId, DshRawSessionEvent(seq, type, record.toString()))
                routeDurableEvent(sessionId, event)
            }
            "assistant-stream" -> {
                val inner = frame.optJSONObject("frame") ?: return
                routeAssistantStream(sessionId, inner)
            }
        }
    }

    /** Feed an in-flight attempt's compact stream to a stream adopted after reconnect. */
    private fun replayActiveAttempt(sessionId: String, attempt: JSONObject) {
        val active = activeStreams.values.lastOrNull { it.sessionId == sessionId } ?: return
        val stream = attempt.optJSONArray("stream") ?: return
        active.attemptId = attempt.optString("attemptId")
        var replayed = 0
        for (index in 0 until stream.length()) {
            val record = stream.optJSONObject(index) ?: continue
            val chunk = record.optJSONObject("chunk") ?: continue
            when (chunk.optString("type")) {
                "text-delta" -> {
                    val text = chunk.optString("text")
                    if (text.isNotEmpty()) {
                        active.accumulated.append(text)
                        active.onDelta(text, false)
                        replayed++
                    }
                }
                "reasoning-delta" -> chunk.optString("text").takeIf { it.isNotEmpty() }?.let { active.onDelta(it, true) }
            }
        }
        DshStreamLog.i("follow.replay-attempt session=$sessionId attempt=${active.attemptId} textChunks=$replayed")
    }

    private fun routeDurableEvent(sessionId: String, event: JSONObject) {
        val type = event.optString("type")
        val data = event.optJSONObject("data") ?: JSONObject()
        val source = data.optJSONObject("source") ?: data.optJSONObject("message")?.optJSONObject("source")
        val rpcId = source?.optString("rpcId").orEmpty()
        val active = resolveActiveStream(sessionId, rpcId)
        if (active == null) {
            if (type == "turn/end" || type == "user/message") {
                DshStreamLog.i("follow.drop-no-active-stream session=$sessionId event=$type")
            }
            return
        }
        when (type) {
            "user/message" -> {
                val kind = source?.optString("kind").orEmpty()
                if (kind.isEmpty() || kind == "user") active.observed = true
            }
            "assistant/message" -> {
                val message = data.optJSONObject("message") ?: data
                active.finalMessage = textFromBlocks(message.optJSONArray("content"))
            }
            "turn/end" -> {
                activeStreams.remove(active.promptRpcId)
                val reason = data.optJSONObject("reason")
                val error = reason?.optJSONObject("error")?.optString("message")?.takeIf { it.isNotEmpty() }
                    ?: active.failure.takeIf { it.isNotEmpty() }
                val completed = active.accumulated.toString().ifEmpty { active.finalMessage }
                DshStreamLog.record(
                    if (error != null) DshLogLevel.ERROR else DshLogLevel.INFO,
                    "turn/end",
                    "follow.turn-end session=$sessionId rpcId=${active.promptRpcId} kind=${reason?.optString("kind")} acc=${active.accumulated.length} error=${error ?: "-"}",
                    sessionId = sessionId,
                    ref = active.promptRpcId,
                )
                when {
                    error != null -> active.onError(error)
                    reason?.optString("kind") == "aborted" -> active.onComplete(completed)
                    else -> active.onComplete(completed)
                }
            }
        }
    }

    private fun routeAssistantStream(sessionId: String, frame: JSONObject) {
        val active = activeStreams.values.lastOrNull { it.sessionId == sessionId } ?: return
        when (frame.optString("type")) {
            "start" -> {
                active.attemptId = frame.optString("attemptId")
                active.observed = true
            }
            "chunk" -> {
                val chunk = frame.optJSONObject("chunk") ?: return
                val chunkType = chunk.optString("type")
                val text = chunk.optString("text")
                // Metadata only: the delta body never reaches the log centre.
                DshStreamLog.record(
                    DshLogLevel.DEBUG,
                    DshLogEvent.CHUNK,
                    "mux.chunk session=$sessionId rpcId=${active.promptRpcId} type=$chunkType deltaChars=${text.length} acc=${active.accumulated.length}",
                    sessionId = sessionId,
                    ref = active.promptRpcId,
                    sizeBytes = text.length,
                )
                when (chunkType) {
                    "text-delta" -> if (text.isNotEmpty()) {
                        active.observed = true
                        active.accumulated.append(text)
                        active.onDelta(text, false)
                    }
                    "reasoning-delta" -> if (text.isNotEmpty()) {
                        active.observed = true
                        active.onDelta(text, true)
                    }
                    "finish" -> {
                        val reason = chunk.optJSONObject("reason")
                        if (reason?.optString("kind") == "error") {
                            active.failure = reason.optJSONObject("failure")?.optString("message").orEmpty()
                        }
                    }
                }
            }
            "end" -> {
                val outcome = frame.optJSONObject("outcome")
                if (outcome?.optString("kind") == "abandoned" && active.failure.isEmpty()) {
                    // The durable turn/end carries the authoritative error; keep a hint meanwhile.
                    active.failure = ""
                }
            }
        }
    }

    private fun resolveActiveStream(sessionId: String, rpcId: String): ActiveStream? {
        if (rpcId.isNotEmpty()) {
            activeStreams[rpcId]?.takeIf { it.sessionId == sessionId }?.let { return it }
        }
        return activeStreams.values.lastOrNull { it.sessionId == sessionId }
    }

    // ------------------------------------------------------------------ prompt

    override fun streamReply(
        pagerId: String,
        sessionId: String,
        prompt: String,
        onDelta: (String, Boolean) -> Unit,
        onComplete: (String) -> Unit,
        onError: (String) -> Unit,
    ): DshStreamHandle = streamReply(pagerId, sessionId, prompt, emptyList(), onDelta, onComplete, onError)

    /**
     * Send one user turn; [images] become official `{type:"image"}` prompt parts.
     *
     * [onAccepted] fires on the `session/prompt` receipt, which is the point where the
     * Host has validated and stored the images — the UI shows "sending" until then.
     */
    fun streamReply(
        pagerId: String,
        sessionId: String,
        prompt: String,
        images: List<DshOutgoingImage>,
        onDelta: (String, Boolean) -> Unit,
        onComplete: (String) -> Unit,
        onError: (String) -> Unit,
        onAccepted: () -> Unit = {},
    ): DshStreamHandle {
        val requestId = dshRandomId("mobile")
        val content = JSONArray().apply {
            if (prompt.isNotEmpty()) put(JSONObject().apply { put("type", "text"); put("text", prompt) })
            images.forEach { image ->
                put(JSONObject().apply {
                    put("type", "image")
                    put("mediaType", image.mediaType)
                    put("data", image.base64)
                    image.name?.takeIf { it.isNotEmpty() }?.let { put("name", it) }
                })
            }
        }
        val payload = DshHostProtocol.request {
            put("requestId", requestId)
            put("sessionId", sessionId)
            put("mode", "queue")
            put("content", content)
            put("clientTimeZone", "UTC")
        }
        // The reply only reaches us on the session's follow stream; a session that
        // was just created has none open yet.
        ensureFollow(sessionId)
        // Register before dispatch: the follow stream may echo user/message before the HTTP receipt lands.
        activeStreams[requestId] = ActiveStream(sessionId, requestId, onDelta, onComplete, onError)
        val timeout = if (images.isEmpty()) DshHostConnectionRuntime.REQUEST_TIMEOUT_SECONDS else DshHostConnectionRuntime.PROMPT_TIMEOUT_SECONDS
        val call = runtime.call(DshHostProtocol.SESSION_PROMPT, payload, timeout) { _, error, _ ->
            if (error != null) {
                if (dshIsTransportInterrupt(error.code, error.message)) {
                    DshStreamLog.i("prompt.hold-for-resync session=$sessionId requestId=$requestId code=${error.code}")
                    return@call
                }
                activeStreams.remove(requestId)
                onError(dshPromptErrorMessage(error))
                return@call
            }
            onAccepted()
        }
        DshStreamLog.record(
            DshLogLevel.INFO,
            DshLogEvent.PROMPT,
            "prompt.start session=$sessionId requestId=$requestId promptChars=${prompt.length} images=${images.size}",
            sessionId = sessionId,
            ref = requestId,
            sizeBytes = prompt.length,
        )
        return object : DshStreamHandle {
            private var cancelled = false
            override fun cancel() {
                if (cancelled) return
                cancelled = true
                activeStreams.remove(requestId)
                call.cancel()
                cancelSession(sessionId)
            }
        }
    }

    fun cancelSession(sessionId: String) {
        runtime.call(DshHostProtocol.SESSION_CANCEL, DshHostProtocol.request { put("sessionId", sessionId) }) { _, _, _ -> }
    }

    fun adoptLiveStream(
        sessionId: String,
        onDelta: (String, Boolean) -> Unit,
        onComplete: (String) -> Unit,
        onError: (String) -> Unit,
    ): DshStreamHandle {
        detachLiveStreams(sessionId)
        val rpcId = "adopted-$sessionId"
        activeStreams[rpcId] = ActiveStream(sessionId, rpcId, onDelta, onComplete, onError, observed = true)
        DshStreamLog.i("prompt.adopt-live session=$sessionId rpcId=$rpcId")
        return object : DshStreamHandle {
            private var cancelled = false
            override fun cancel() {
                if (cancelled) return
                cancelled = true
                activeStreams.remove(rpcId)
                cancelSession(sessionId)
            }
        }
    }

    fun detachLiveStreams(sessionId: String) {
        val removed = activeStreams.entries.filter { it.value.sessionId == sessionId }.map { it.key }
        removed.forEach(activeStreams::remove)
        if (removed.isNotEmpty()) DshStreamLog.i("prompt.detach-live session=$sessionId count=${removed.size}")
    }

    // ------------------------------------------------------------------ approvals / questions

    fun pendingInteractions(sessionId: String): Pair<DshPendingApproval?, DshPendingQuestion?> {
        var approval: DshPendingApproval? = null
        var question: DshPendingQuestion? = null
        store.pendingInteractions.forEach { (eventId, raw) ->
            val payload = runCatching { JSONObject(raw) }.getOrNull() ?: return@forEach
            if (payload.optString("sessionId") != sessionId) return@forEach
            val request = payload.optJSONObject("request") ?: JSONObject()
            when (payload.optString("type")) {
                DshHostProtocol.EVENT_APPROVAL_REQUEST -> approval = DshPendingApproval(
                    rpcId = eventId,
                    sessionId = sessionId,
                    approvalId = eventId,
                    toolName = request.optString("toolName"),
                    callId = request.optString("callId").takeIf { it.isNotEmpty() },
                    reason = request.optString("reason").takeIf { it.isNotEmpty() },
                    command = approvalCommand(sessionId, request.optString("callId")),
                )
                DshHostProtocol.EVENT_QUESTION_REQUEST -> {
                    val questions = request.optJSONArray("questions") ?: JSONArray()
                    question = DshPendingQuestion(
                        rpcId = eventId,
                        sessionId = sessionId,
                        questions = (0 until questions.length()).mapNotNull { index ->
                            val item = questions.optJSONObject(index) ?: return@mapNotNull null
                            val options = item.optJSONArray("options") ?: JSONArray()
                            DshPendingQuestionItem(
                                id = item.optString("id"),
                                question = item.optString("question"),
                                header = item.optString("header"),
                                detail = item.optString("detail"),
                                options = (0 until options.length()).mapNotNull { optionIndex ->
                                    val option = options.optJSONObject(optionIndex) ?: return@mapNotNull null
                                    DshPendingQuestionOption(option.optString("label"), option.optString("description"))
                                },
                                multiSelect = item.optBoolean("multiSelect"),
                            )
                        },
                    )
                }
            }
        }
        return approval to question
    }

    fun respondApproval(
        rpcId: String,
        sessionId: String,
        approvalId: String,
        outcome: String,
        callback: (Boolean, String) -> Unit,
    ) {
        if (outcome != "allowed-once" && outcome != "rejected") {
            callback(false, "非法审批结果")
            return
        }
        DshStreamLog.question("repo.respondApproval eventId=$rpcId session=$sessionId outcome=$outcome approvalId=$approvalId")
        runtime.respondEvent(rpcId, JSONObject().apply { put("kind", "result"); put("value", outcome) }) { accepted, reason ->
            if (accepted) store.removePending(rpcId)
            callback(accepted, reason)
        }
    }

    fun respondQuestion(rpcId: String, sessionId: String, answer: JSONObject, callback: (Boolean, String) -> Unit) {
        DshStreamLog.question("repo.respondQuestion eventId=$rpcId session=$sessionId answer='${DshStreamLog.preview(answer.toString(), 400)}'")
        runtime.respondEvent(rpcId, JSONObject().apply { put("kind", "result"); put("value", answer) }) { accepted, reason ->
            if (accepted) store.removePending(rpcId)
            callback(accepted, reason)
        }
    }

    fun clearPending(rpcId: String) {
        DshStreamLog.question("repo.clearPending eventId=$rpcId")
        store.removePending(rpcId)
    }

    private fun approvalCommand(sessionId: String, callId: String): String? {
        if (callId.isEmpty()) return null
        val events = store.sessionEvents[sessionId] ?: return null
        events.forEach { event ->
            if (event.type != "tool/call") return@forEach
            val payload = runCatching { JSONObject(event.raw) }.getOrNull() ?: return@forEach
            val data = dshWireEvent(payload).optJSONObject("data") ?: return@forEach
            if (data.optString("callId") != callId) return@forEach
            val arguments = data.opt("arguments") ?: return@forEach
            return when (arguments) {
                is String -> runCatching { JSONObject(arguments) }.getOrNull()?.optString("command")?.takeIf { it.isNotEmpty() } ?: arguments
                is JSONObject -> arguments.optString("command").takeIf { it.isNotEmpty() } ?: arguments.toString()
                else -> arguments.toString()
            }
        }
        return null
    }

    // ------------------------------------------------------------------ credentials / models / skills

    override fun loadCredentialSetup(onSuccess: (DshCredentialSetup) -> Unit, onError: (String) -> Unit) {
        call(DshHostProtocol.LLM_LIST_PROVIDERS, DshHostProtocol.args()) { providersValue, providersError ->
            if (providersError != null) {
                onError(providersError.message)
                return@call
            }
            // llm/listProviders returns an array; the runtime wraps non-objects as {} so fall back to configured=true.
            val providers = providersValue?.optJSONArray("providers")
            val active = providers == null || (0 until providers.length()).any { index ->
                val provider = providers.optJSONObject(index) ?: return@any false
                provider.optString("provider") == DEEPSEEK_PROVIDER && provider.optBoolean("active", true)
            }
            if (!active) {
                onSuccess(DshCredentialSetup(false, false, false))
                return@call
            }
            call(DshHostProtocol.SETTINGS_DESCRIBE, DshHostProtocol.args()) { settingsValue, settingsError ->
                if (settingsError != null || settingsValue == null) {
                    onError(settingsError?.message ?: "settings/describe 返回为空")
                    return@call
                }
                var credentialRef = DEEPSEEK_CREDENTIAL_REF
                var namespaceFound = false
                val namespaces = settingsValue.optJSONArray("namespaces") ?: JSONArray()
                for (index in 0 until namespaces.length()) {
                    val namespace = namespaces.optJSONObject(index) ?: continue
                    if (namespace.optString("ns") != DEEPSEEK_SETTINGS_NS) continue
                    namespaceFound = true
                    credentialRef = namespace.optJSONObject("value")?.optString("apiKeyEnv")?.takeIf { it.isNotEmpty() } ?: credentialRef
                    break
                }
                call(DshHostProtocol.CREDENTIALS_DESCRIBE, DshHostProtocol.args {
                    put("refs", JSONArray().apply { put(credentialRef) })
                }) { credentialsValue, credentialsError ->
                    if (credentialsError != null || credentialsValue == null) {
                        onError(credentialsError?.message ?: "credentials/describe 返回为空")
                        return@call
                    }
                    val credential = credentialsValue.optJSONObject(credentialRef)
                    onSuccess(DshCredentialSetup(
                        true,
                        credential?.optBoolean("configured") == true,
                        settingsValue.optBoolean("writable") && namespaceFound && credential?.optBoolean("writable") == true,
                        credentialRef,
                    ))
                }
            }
        }
    }

    override fun saveDeepSeekApiKey(apiKey: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
        call(DshHostProtocol.CREDENTIALS_SET, DshHostProtocol.args {
            put("ref", DEEPSEEK_CREDENTIAL_REF)
            put("value", apiKey)
        }) { _, error -> if (error == null) onSuccess() else onError(error.message) }
    }

    override fun loadModels(sessionId: String, onSuccess: (DshSessionModels) -> Unit, onError: (String) -> Unit) {
        call(DshHostProtocol.SESSION_MODEL_CATALOG, DshHostProtocol.args()) { value, error ->
            if (error != null || value == null) {
                onError(error?.message ?: "session/modelCatalog 返回为空")
                return@call
            }
            val selection = currentSelection(sessionId) ?: value.optJSONObject("default") ?: JSONObject()
            val currentProvider = selection.optString("provider")
            val currentModel = selection.optString("model")
            val currentEffort = selection.optString("reasoningEffort").takeIf { it.isNotEmpty() }
            val routable = stringSet(value.optJSONArray("routableProviders"))
            val options = mutableListOf<DshModelOption>()
            val groups = value.optJSONArray("groups") ?: JSONArray()
            for (groupIndex in 0 until groups.length()) {
                val group = groups.optJSONObject(groupIndex) ?: continue
                val provider = group.optString("id")
                val providerName = group.optString("name").ifEmpty { provider }
                val models = group.optJSONArray("models") ?: JSONArray()
                for (modelIndex in 0 until models.length()) {
                    val model = models.optJSONObject(modelIndex) ?: continue
                    val id = model.optString("id")
                    if (provider.isEmpty() || id.isEmpty()) continue
                    val effort = model.optJSONObject("reasoning")?.optString("defaultEffort")?.takeIf { it.isNotEmpty() }
                    val selected = provider == currentProvider && id == currentModel
                    options += DshModelOption(
                        provider, providerName, id, model.optString("name").ifEmpty { id }, model.optString("description"),
                        if (selected) currentEffort ?: effort else effort,
                        selected,
                    )
                }
            }
            val current = options.firstOrNull { it.selected } ?: DshModelOption(
                currentProvider, currentProvider, currentModel, currentModel.ifEmpty { "选择模型" },
                reasoningEffort = currentEffort, selected = true,
            )
            onSuccess(DshSessionModels(current, options, routable.isEmpty() || currentProvider in routable))
        }
    }

    private fun currentSelection(sessionId: String): JSONObject? {
        val raw = store.projections[sessionId]?.get("modelSelection")?.value ?: return null
        val projection = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        return projection.optJSONObject("next") ?: projection.optJSONObject("lastUsed")
    }

    override fun selectModel(sessionId: String, option: DshModelOption, onSuccess: (DshModelOption) -> Unit, onError: (String) -> Unit) {
        call(DshHostProtocol.SESSION_SELECT_MODEL, DshHostProtocol.request {
            put("sessionId", sessionId)
            put("provider", option.provider)
            put("model", option.model)
            option.reasoningEffort?.let { put("reasoningEffort", it) }
        }) { value, error ->
            if (error != null || value == null) {
                onError(error?.message ?: "session/selectModel 返回为空")
                return@call
            }
            val selected = value.optJSONObject("selected") ?: JSONObject()
            onSuccess(option.copy(
                provider = selected.optString("provider").ifEmpty { option.provider },
                model = selected.optString("model").ifEmpty { option.model },
                reasoningEffort = selected.optString("reasoningEffort").takeIf { it.isNotEmpty() },
                selected = true,
            ))
        }
    }

    fun loadSkills(sessionId: String, onSuccess: (List<DshSkill>) -> Unit, onError: (String) -> Unit = {}) {
        call(DshHostProtocol.SKILLS_LIST, DshHostProtocol.request { put("sessionId", sessionId) }) { value, error ->
            if (error != null || value == null) {
                onError(error?.message ?: "skills/list 返回为空")
                return@call
            }
            val skills = buildList {
                val items = value.optJSONArray("skills") ?: JSONArray()
                for (index in 0 until items.length()) {
                    val item = items.optJSONObject(index) ?: continue
                    val name = item.optString("name")
                    if (name.isEmpty()) continue
                    add(DshSkill(name, item.optString("description"), item.optString("whenToUse"), item.optBoolean("modelInvocable", true)))
                }
            }
            onSuccess(skills)
        }
    }

    // ------------------------------------------------------------------ plugin inventory

    fun loadPluginInventory(onSuccess: (DshPluginInventory) -> Unit, onError: (DshRpcError) -> Unit) {
        call(DshHostProtocol.PLUGIN_INVENTORY_LIST, DshHostProtocol.args()) { value, error ->
            if (error != null || value == null) {
                onError(error ?: DshRpcError("bad-response", "pluginInventory/list 返回为空"))
                return@call
            }
            onSuccess(parsePluginInventory(value))
        }
    }

    /**
     * Asks the optional companion plugin which entries it will let the phone control.
     *
     * Capabilities are *advertised*, never inferred from a failed write: a 404 simply
     * means the plugin is not installed and the inventory stays read-only. Returns the
     * set of controllable entry ids, empty when the plugin is absent.
     */
    fun probePluginAdmin(onResult: (Set<String>) -> Unit) {
        runtime.callPath(
            DshHostProtocol.MOBILE_ADMIN_PATH,
            DshHostProtocol.adminRequest(DshHostProtocol.MOBILE_ADMIN_CAPABILITIES),
        ) { value, error ->
            if (error != null || value?.optBoolean("writable") != true) {
                onResult(emptySet())
                return@callPath
            }
            runtime.callPath(
                DshHostProtocol.MOBILE_ADMIN_PATH,
                DshHostProtocol.adminRequest(DshHostProtocol.MOBILE_ADMIN_INVENTORY),
            ) { inventory, inventoryError ->
                if (inventoryError != null || inventory == null) {
                    onResult(emptySet())
                    return@callPath
                }
                val entries = inventory.optJSONArray("entries")
                val controllable = mutableSetOf<String>()
                for (i in 0 until (entries?.length() ?: 0)) {
                    val entry = entries?.optJSONObject(i) ?: continue
                    if (entry.optBoolean("controllable")) controllable += entry.optString("entryId").orEmpty()
                }
                onResult(controllable)
            }
        }
    }

    /**
     * Runs one lifecycle action through the companion plugin. The plugin refuses an
     * unconfirmed write and answers `confirm-required` with the module name, which is
     * what the confirmation dialog shows; the caller then repeats with `confirm = true`.
     */
    fun controlPlugin(
        entryId: String,
        action: String,
        confirm: Boolean,
        callback: (DshRpcError?) -> Unit,
    ) {
        val params = JSONObject().apply {
            put("entryId", entryId)
            put("action", action)
            if (confirm) put("confirm", true)
        }
        runtime.callPath(
            DshHostProtocol.MOBILE_ADMIN_PATH,
            DshHostProtocol.adminRequest(DshHostProtocol.MOBILE_ADMIN_CONTROL, params),
            timeoutSeconds = 60,
        ) { _, error -> callback(error) }
    }

    // ------------------------------------------------------------------ goals

    fun goalEdit(sessionId: String, goal: DshGoalSnapshot, objective: String, callback: (DshRpcError?) -> Unit) =
        goalMutation(DshHostProtocol.GOALS_EDIT, sessionId, goal, enrich = {
            put("request", JSONObject().apply { put("objective", objective) })
        }, callback = callback)

    fun goalPause(sessionId: String, goal: DshGoalSnapshot, callback: (DshRpcError?) -> Unit) =
        goalMutation(DshHostProtocol.GOALS_PAUSE, sessionId, goal, callback = callback)

    fun goalResume(sessionId: String, goal: DshGoalSnapshot, callback: (DshRpcError?) -> Unit) =
        goalMutation(DshHostProtocol.GOALS_RESUME, sessionId, goal, callback = callback)

    fun goalClear(sessionId: String, goal: DshGoalSnapshot, callback: (DshRpcError?) -> Unit) =
        goalMutation(DshHostProtocol.GOALS_CLEAR, sessionId, goal, callback = callback)

    private fun goalMutation(
        endpoint: String,
        sessionId: String,
        goal: DshGoalSnapshot,
        enrich: JSONObject.() -> Unit = {},
        callback: (DshRpcError?) -> Unit,
    ) {
        call(endpoint, DshHostProtocol.args {
            put("agentId", sessionId)
            put("ref", JSONObject().apply { put("id", goal.id); put("revision", goal.revision) })
            enrich()
        }) { _, error -> callback(error) }
    }

    // ------------------------------------------------------------------ attachments

    fun loadAttachment(sessionId: String, attachmentId: String, callback: (String?, String?) -> Unit) {
        call(DshHostProtocol.SESSION_ATTACHMENT, DshHostProtocol.request {
            put("sessionId", sessionId)
            put("attachmentId", attachmentId)
        }) { value, error ->
            val data = value?.optString("data").orEmpty()
            val mediaType = value?.optJSONObject("attachment")?.optString("mediaType")?.takeIf { it.isNotEmpty() } ?: "image/png"
            if (error != null || data.isEmpty()) callback(null, error?.message ?: "attachment 返回为空")
            else callback("data:$mediaType;base64,$data", null)
        }
    }

    // ------------------------------------------------------------------ queue / jobs / workspaces

    fun queue(sessionId: String): List<DshQueueItem> {
        val raw = store.queueSnapshots[sessionId] ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val message = item.optJSONObject("message") ?: JSONObject()
                val text = textFromBlocks(message.optJSONArray("content"))
                add(DshQueueItem(
                    id = item.optString("id"),
                    placement = item.optString("placement"),
                    preview = text.lineSequence().firstOrNull().orEmpty(),
                    text = text.takeIf { it.isNotEmpty() },
                ))
            }
        }.filter { it.id.isNotEmpty() && it.placement == "queued" }
    }

    fun jobs(sessionId: String): List<DshJobItem> {
        val raw = store.jobSnapshots[sessionId] ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("id")
            if (id.isEmpty()) return@mapNotNull null
            DshJobItem(
                id = id,
                kind = item.optString("kind"),
                label = item.optString("label"),
                status = item.optString("status"),
                detail = item.optString("detail"),
                startedAt = item.optLong("startedAt"),
                finishedAt = item.optString("finishedAt").takeIf { it.isNotEmpty() }?.toLongOrNull(),
            )
        }
    }

    fun updateQueue(sessionId: String, itemId: String, action: JSONObject, callback: (JSONObject?, DshRpcError?) -> Unit) {
        call(DshHostProtocol.SESSION_UPDATE_QUEUE, DshHostProtocol.request {
            put("sessionId", sessionId)
            put("itemId", itemId)
            put("action", action)
        }) { value, error -> callback(value, error) }
    }

    fun workspaceGroups(): List<DshWorkspaceGroup> = store.workspaceGroups(includeArchived = false)

    fun archivedSessions(): List<DshSession> = store.sessions.values.filter { it.id in store.archivedSessionIds }

    fun workspaceIdForSession(sessionId: String): String? = store.workspaceIdForSession(sessionId)

    fun blankSessionInWorkspace(workspaceId: String?): DshSession? {
        if (workspaceId == null) return store.sessions.values.firstOrNull { it.blank && it.cwd.isEmpty() }
        return store.workspaceSessionIds(workspaceId).mapNotNull { store.sessions[it] }.firstOrNull { it.blank }
    }

    fun listDirectory(path: String?, callback: (DshDirectoryListing?, DshRpcError?) -> Unit) {
        call(DshHostProtocol.DIRECTORY_LIST, DshHostProtocol.args {
            path?.takeIf { it.isNotEmpty() }?.let { put("path", it) }
        }) { value, error ->
            if (error != null || value == null) {
                callback(null, error ?: DshRpcError("internal", "directoryPicker/list failed"))
                return@call
            }
            callback(parseDirectoryListing(value), null)
        }
    }

    fun createDirectory(path: String, name: String, callback: (String?, DshRpcError?) -> Unit) {
        call(DshHostProtocol.DIRECTORY_CREATE, DshHostProtocol.args {
            put("path", path)
            put("name", name)
        }) { value, error ->
            if (error != null) {
                callback(null, error)
                return@call
            }
            // The result is a bare string; the runtime wraps non-objects, so re-derive the path.
            val created = value?.optString("path")?.takeIf { it.isNotEmpty() } ?: "${path.trimEnd('/')}/$name"
            callback(created, null)
        }
    }

    fun createWorkspace(path: String, callback: (JSONObject?, DshRpcError?) -> Unit) {
        call(DshHostProtocol.WORKSPACE_CREATE, DshHostProtocol.request { put("path", path) }) { value, error -> callback(value, error) }
    }

    fun renameWorkspace(workspaceId: String, title: String, callback: (JSONObject?, DshRpcError?) -> Unit) {
        call(DshHostProtocol.WORKSPACE_RENAME, DshHostProtocol.request {
            put("workspaceId", workspaceId)
            put("title", title)
        }) { value, error -> callback(value, error) }
    }

    fun deleteWorkspace(workspaceId: String, callback: (JSONObject?, DshRpcError?) -> Unit) {
        call(DshHostProtocol.WORKSPACE_DELETE, DshHostProtocol.request { put("workspaceId", workspaceId) }) { value, error -> callback(value, error) }
    }

    fun moveWorkspaceBefore(workspaceId: String, beforeWorkspaceId: String?, callback: (JSONObject?, DshRpcError?) -> Unit) {
        call(DshHostProtocol.WORKSPACE_INSERT_BEFORE, DshHostProtocol.request {
            put("workspaceId", workspaceId)
            beforeWorkspaceId?.takeIf { it.isNotEmpty() }?.let { put("beforeWorkspaceId", it) }
        }) { value, error -> callback(value, error) }
    }

    private fun parseDirectoryListing(value: JSONObject): DshDirectoryListing {
        fun entries(array: JSONArray?): List<DshDirectoryEntry> = buildList {
            if (array == null) return@buildList
            for (index in 0 until array.length()) {
                val entry = array.optJSONObject(index) ?: continue
                add(DshDirectoryEntry(entry.optString("name"), entry.optString("path"), entry.optBoolean("hidden")))
            }
        }
        return DshDirectoryListing(
            path = value.optString("path"),
            home = value.optString("home"),
            crumbs = entries(value.optJSONArray("crumbs")),
            entries = entries(value.optJSONArray("entries")),
            truncated = value.optBoolean("truncated"),
        )
    }

    // ------------------------------------------------------------------ helpers

    private fun call(endpoint: String, payload: JSONObject, callback: (JSONObject?, DshRpcError?) -> Unit) =
        runtime.call(endpoint, payload) { value, error, _ -> callback(value, error) }

    private companion object {
        const val DEEPSEEK_PROVIDER = "deepseek-official"
        const val DEEPSEEK_SETTINGS_NS = "llm-deepseek"
        const val DEEPSEEK_CREDENTIAL_REF = "DEEPSEEK_API_KEY"
        const val HISTORY_PAGE_MESSAGES = 80
        const val MAX_RETAINED_RECORDS = 2_000
    }
}

internal fun stringSet(array: JSONArray?): Set<String> = buildSet {
    if (array != null) for (index in 0 until array.length()) {
        array.optString(index)?.takeIf { it.isNotEmpty() }?.let(::add)
    }
}

internal fun parseImageLimits(raw: String): DshImageLimits? {
    val value = runCatching { JSONObject(raw) }.getOrNull() ?: return null
    if (!value.has("maxImageBytes")) return null
    val types = value.optJSONArray("mediaTypes") ?: JSONArray()
    return DshImageLimits(
        maxImageBytes = value.optLong("maxImageBytes"),
        maxImagesPerMessage = value.optInt("maxImagesPerMessage"),
        maxMessageImageBytes = value.optLong("maxMessageImageBytes"),
        maxImagePixels = value.optLong("maxImagePixels"),
        maxImageDimension = value.optInt("maxImageDimension"),
        mediaTypes = (0 until types.length()).mapNotNull { types.optString(it)?.takeIf { s -> s.isNotEmpty() } },
    )
}

internal fun parsePluginInventory(value: JSONObject): DshPluginInventory {
    val entries = value.optJSONArray("entries") ?: JSONArray()
    val presets = value.optJSONArray("agentPresets")
    return DshPluginInventory(
        entries = (0 until entries.length()).mapNotNull { index ->
            val entry = entries.optJSONObject(index) ?: return@mapNotNull null
            DshPluginEntry(
                entryId = entry.optString("entryId"),
                moduleName = entry.optString("moduleName"),
                enabled = entry.optBoolean("enabled"),
                fiberPhase = entry.optString("fiberPhase").takeIf { it.isNotEmpty() },
            )
        },
        agentPresets = if (presets == null) null else (0 until presets.length()).mapNotNull { index ->
            val preset = presets.optJSONObject(index) ?: return@mapNotNull null
            val rows = preset.optJSONArray("rows") ?: JSONArray()
            DshAgentPresetPlugins(
                id = preset.optString("id"),
                trust = preset.optString("trust"),
                name = preset.optString("name").takeIf { it.isNotEmpty() },
                isDefault = preset.optBoolean("isDefault"),
                broken = preset.optString("broken").takeIf { it.isNotEmpty() },
                rows = (0 until rows.length()).mapNotNull { rowIndex ->
                    val row = rows.optJSONObject(rowIndex) ?: return@mapNotNull null
                    DshAgentPresetPluginRow(
                        entryId = row.optString("entryId").takeIf { it.isNotEmpty() },
                        moduleName = row.optString("moduleName"),
                        enabled = row.opt("enabled")?.toString() ?: "false",
                        condition = row.optString("condition").takeIf { it.isNotEmpty() },
                        fiberPhase = row.optString("fiberPhase").takeIf { it.isNotEmpty() },
                    )
                },
            )
        },
    )
}

/** Host-aligned, readable failure copy for prompt rejections (image admission and friends). */
/** Readable copy for the conversation-management failures the Host reports (Task 4). */
internal fun dshSessionErrorMessage(error: DshRpcError?): String = when (error?.code) {
    null -> "The Host did not answer."
    "session/title-invalid" -> "That title is not valid. Enter a name with at least one character."
    "session/not-found" -> "That conversation no longer exists on the Host."
    "workspace/not-found" -> "That conversation is not in a workspace the Host knows."
    else -> error.message.ifEmpty { error.code }
}

internal fun dshPromptErrorMessage(error: DshRpcError): String {
    val details = runCatching { JSONObject(error.details) }.getOrNull()
    val reason = details?.optString("reason").orEmpty()
    dshAttachmentReasonMessage(reason)?.let { return it }
    return if (error.code == "session/attachment-invalid" && reason.isNotEmpty()) {
        "${error.message} ($reason)"
    } else {
        error.message
    }
}
