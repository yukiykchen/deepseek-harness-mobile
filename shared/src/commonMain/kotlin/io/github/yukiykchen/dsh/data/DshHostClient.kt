package io.github.yukiykchen.dsh.data

import com.tencent.kuikly.core.module.NetworkModule
import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.timer.setTimeout
import io.github.yukiykchen.dsh.framework.DshStreamLog
import io.github.yukiykchen.dsh.framework.DshDiagnostics
import io.github.yukiykchen.dsh.framework.DshAppError
import io.github.yukiykchen.dsh.framework.DshAppErrorKind
import io.github.yukiykchen.dsh.platform.DshWebSocketModule
import io.github.yukiykchen.dsh.model.DshHostProtocol
import io.github.yukiykchen.dsh.model.DshHostConnection
import io.github.yukiykchen.dsh.model.DshWebTimelineParser
import io.github.yukiykchen.dsh.model.DshHostRuntimePhase
import io.github.yukiykchen.dsh.model.DshHostRuntimeState
import io.github.yukiykchen.dsh.model.DshEventStream
import io.github.yukiykchen.dsh.model.DshDownlinkFrame
import io.github.yukiykchen.dsh.model.DshRpcError
import io.github.yukiykchen.dsh.model.dshIsTransportInterrupt
import io.github.yukiykchen.dsh.model.DshRawSessionEvent
import io.github.yukiykchen.dsh.model.DshHostStore
import io.github.yukiykchen.dsh.model.DshSession
import io.github.yukiykchen.dsh.model.DshWebTimelineItem
import io.github.yukiykchen.dsh.model.DshQueueItem
import io.github.yukiykchen.dsh.model.DshJobItem
import io.github.yukiykchen.dsh.model.DshWorkspaceGroup
import io.github.yukiykchen.dsh.model.DshDirectoryListing
import io.github.yukiykchen.dsh.model.DshPendingApproval
import io.github.yukiykchen.dsh.model.DshPendingQuestion
import io.github.yukiykchen.dsh.model.DshCredentialSetup
import io.github.yukiykchen.dsh.model.DshModelOption
import io.github.yukiykchen.dsh.model.DshSkill
import io.github.yukiykchen.dsh.model.DshGoalSnapshot
import io.github.yukiykchen.dsh.model.DshSessionModels
import io.github.yukiykchen.dsh.model.DshStreamHandle

/**
 * Typed client for one DSH Host (Relay loopback or SSH forward): RPC calls,
 * the mux/host event streams via [DshHostConnectionRuntime], and the
 * host-authoritative [DshHostStore] projection.
 */

class DshHostClient(
    network: NetworkModule,
    webSocket: DshWebSocketModule,
    private val connection: DshHostConnection,
    pagerId: String,
    onState: (DshHostRuntimeState) -> Unit = {},
    onQueueSnapshot: (String) -> Unit = {},
    onJobsSnapshot: (String) -> Unit = {},
    onSessionStatus: (String, Boolean) -> Unit = { _, _ -> },
    onProjection: (String, String, String, Int) -> Unit = { _, _, _, _ -> },
    onSessionEvent: (String, DshRawSessionEvent) -> Unit = { _, _ -> },
    onRemoteEvent: (String) -> Unit = {},
    onPendingInteraction: (String) -> Unit = {},
    transport: DshHostTransport = DshNativeHostTransport(network, webSocket),
    schedule: (Int, () -> Unit) -> Unit = { delay, action -> setTimeout(pagerId, delay, action) },
) {
    internal val store = DshHostStore()
    private val queries = DshHostStoreQueries(store)
    private val onQueueSnapshotHandler = onQueueSnapshot
    private val onJobsSnapshotHandler = onJobsSnapshot
    private val onSessionStatusHandler = onSessionStatus
    private val onProjectionHandler = onProjection
    private val onSessionEventHandler = onSessionEvent
    private val onRemoteEventHandler = onRemoteEvent
    private val onPendingInteractionHandler = onPendingInteraction
    private val runtime = DshHostConnectionRuntime(
        transport = transport,
        connection = connection,
        pagerId = pagerId,
        schedule = schedule,
        onFrame = ::handleFrame,
        onState = onState,
        onWorkspaceBaseline = { value -> parseWorkspaceBaseline(value, store) },
        onSessionBaseline = { value -> parseSessionBaseline(value, store) },
        onQueueSnapshot = onQueueSnapshot,
        onJobsSnapshot = onJobsSnapshot,
        onSessionStatus = onSessionStatus,
        onProjection = onProjectionHandler,
        onSessionEvent = onSessionEventHandler,
        onRemoteEvent = onRemoteEvent,
    )
    private val streams = DshPromptStreamRegistry()

    fun start() = runtime.start()

    fun currentConnectionState(): DshHostRuntimeState = runtime.currentState()
    fun isProductReady(): Boolean = runtime.currentState().phase == DshHostRuntimePhase.READY
    fun stop() {
        streams.clear()
        runtime.stop()
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
        runtime.respond(rpcId, JSONObject().apply {
            put("sessionId", sessionId)
            put("approvalId", approvalId)
            put("outcome", outcome)
        }, callback)
    }

    fun respondQuestion(
        rpcId: String,
        sessionId: String,
        answer: JSONObject,
        callback: (Boolean, String) -> Unit,
    ) {
        DshStreamLog.question("repo.respondQuestion rpcId=$rpcId session=$sessionId answer=${DshDiagnostics.preview(answer.toString())}")
        runtime.respond(rpcId, JSONObject().apply {
            put("sessionId", sessionId)
            put("answer", answer)
        }, callback)
    }

    fun clearPending(rpcId: String) {
        DshStreamLog.question("repo.clearPending rpcId=$rpcId")
        store.removePending(rpcId)
    }

    fun loadCredentialSetup(onSuccess: (DshCredentialSetup) -> Unit, onError: (String) -> Unit) {
        call(DshHostProtocol.LLM_PROVIDERS, JSONObject()) { providersValue, providersError ->
            if (providersError != null || providersValue == null) {
                onError(providersError?.message ?: "llm.providers 返回为空")
                return@call
            }
            val providers = providersValue.optJSONArray("providers") ?: JSONArray()
            val active = (0 until providers.length()).any { index ->
                val provider = providers.optJSONObject(index) ?: return@any false
                provider.optString("provider") == DEEPSEEK_PROVIDER &&
                    provider.optString("settingsNs") == DEEPSEEK_SETTINGS_NS && provider.optBoolean("active")
            }
            if (!active) {
                onSuccess(DshCredentialSetup(false, false, false))
                return@call
            }
            call(DshHostProtocol.SETTINGS_DESCRIBE, JSONObject()) { settingsValue, settingsError ->
                if (settingsError != null || settingsValue == null) {
                    onError(settingsError?.message ?: "settings.describe 返回为空")
                    return@call
                }
                var credentialRef = DEEPSEEK_CREDENTIAL_REF
                var namespaceFound = false
                val namespaces = settingsValue.optJSONArray("namespaces") ?: JSONArray()
                for (index in 0 until namespaces.length()) {
                    val namespace = namespaces.optJSONObject(index) ?: continue
                    if (namespace.optString("ns") != DEEPSEEK_SETTINGS_NS) continue
                    namespaceFound = true
                    credentialRef = namespace.optJSONObject("value")?.optString("apiKeyEnv")
                        ?.takeIf { it.isNotEmpty() } ?: credentialRef
                    break
                }
                call(DshHostProtocol.CREDENTIALS_DESCRIBE, JSONObject().apply {
                    put("refs", JSONArray().apply { put(credentialRef) })
                }) { credentialsValue, credentialsError ->
                    if (credentialsError != null || credentialsValue == null) {
                        onError(credentialsError?.message ?: "credentials.describe 返回为空")
                        return@call
                    }
                    val credential = credentialsValue.optJSONObject("credentials")?.optJSONObject(credentialRef)
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

    fun saveApiKey(credentialRef: String, apiKey: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
        call(DshHostProtocol.CREDENTIALS_SET, JSONObject().apply {
            put("ref", credentialRef)
            put("value", apiKey)
        }) { _, error -> if (error == null) onSuccess() else onError(error.message) }
    }

    fun loadModels(sessionId: String, onSuccess: (DshSessionModels) -> Unit, onError: (String) -> Unit) {
        call(DshHostProtocol.SESSION_MODELS, JSONObject().apply { put("sessionId", sessionId) }) { value, error ->
            if (error != null || value == null) {
                onError(error?.message ?: "session.models 返回为空")
                return@call
            }
            val current = value.optJSONObject("current") ?: JSONObject()
            val currentProvider = current.optString("provider")
            val currentModel = current.optString("model")
            val currentEffort = current.optString("reasoningEffort").takeIf { it.isNotEmpty() }
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
                    options += DshModelOption(
                        provider, providerName, id, model.optString("name").ifEmpty { id }, model.optString("description"),
                        if (provider == currentProvider && id == currentModel) currentEffort ?: effort else effort,
                        provider == currentProvider && id == currentModel,
                    )
                }
            }
            val selected = options.firstOrNull { it.selected } ?: DshModelOption(
                currentProvider, currentProvider, currentModel, currentModel.ifEmpty { "选择模型" }, reasoningEffort = currentEffort, selected = true,
            )
            onSuccess(DshSessionModels(selected, options, value.optBoolean("routable")))
        }
    }

    fun selectModel(sessionId: String, option: DshModelOption, onSuccess: (DshModelOption) -> Unit, onError: (String) -> Unit) {
        call(DshHostProtocol.SESSION_SELECT_MODEL, JSONObject().apply {
            put("sessionId", sessionId); put("provider", option.provider); put("model", option.model)
            option.reasoningEffort?.let { put("reasoningEffort", it) }
        }) { value, error ->
            if (error != null || value == null) {
                onError(error?.message ?: "session.selectModel 返回为空")
                return@call
            }
            val selected = value.optJSONObject("selected") ?: JSONObject()
            onSuccess(option.copy(
                provider = selected.optString("provider").ifEmpty { option.provider },
                model = selected.optString("model").ifEmpty { option.model },
                reasoningEffort = selected.optString("reasoningEffort").takeIf { it.isNotEmpty() }, selected = true,
            ))
        }
    }

    fun loadSessions(onSuccess: (List<DshSession>) -> Unit, onError: (String) -> Unit) {
        call(DshHostProtocol.SESSION_LIST, JSONObject()) { value, error ->
            if (error != null || value == null) {
                onError(error?.message ?: "session.list 返回为空")
                return@call
            }
            val sessions = parseSessionsPayload(value)
            store.replaceSessions(sessions)
            onSuccess(sessions)
        }
    }

    fun createSession(workspaceId: String?, onSuccess: (String) -> Unit, onError: (String) -> Unit) {
        val payload = JSONObject()
        workspaceId?.takeIf { it.isNotEmpty() }?.let { payload.put("workspaceId", it) }
        call(DshHostProtocol.SESSION_CREATE, payload) { value, error ->
            if (error != null || value == null) {
                onError(error?.message ?: "session.create 返回为空")
                return@call
            }
            val id = value.optString("sessionId")
            if (id.isEmpty()) onError("session.create 未返回 sessionId") else onSuccess(id)
        }
    }

    fun loadWebTimeline(
        sessionId: String,
        onSuccess: (List<DshWebTimelineItem>) -> Unit,
        onError: (String) -> Unit = {},
    ) {
        call(DshHostProtocol.SESSION_HISTORY, JSONObject().apply {
            put("sessionId", sessionId)
            put("maxMessages", HISTORY_PAGE_MESSAGES)
        }) { value, error ->
            if (error != null || value == null) {
                DshStreamLog.i("history.fail session=$sessionId error=${error?.message?.length ?: 0}")
                onError(error?.message ?: "session.history 返回为空")
                return@call
            }
            onSuccess(DshWebTimelineParser.parseWebTimeline(value.optJSONArray("events") ?: JSONArray()))
        }
    }

    fun loadSkills(sessionId: String, onSuccess: (List<DshSkill>) -> Unit, onError: (String) -> Unit = {}) {
        call(DshHostProtocol.SKILL_LIST, JSONObject().apply { put("sessionId", sessionId) }) { value, error ->
            if (error != null || value == null) {
                onError(error?.message ?: "skill.list 返回为空")
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

    fun goalEdit(sessionId: String, goal: DshGoalSnapshot, objective: String, callback: (DshRpcError?) -> Unit) =
        goalMutation(
            DshHostProtocol.GOAL_EDIT,
            sessionId,
            goal,
            enrich = { it.put("objective", objective) },
            callback = callback,
        )

    fun goalPause(sessionId: String, goal: DshGoalSnapshot, callback: (DshRpcError?) -> Unit) =
        goalMutation(DshHostProtocol.GOAL_PAUSE, sessionId, goal, callback = callback)

    fun goalResume(sessionId: String, goal: DshGoalSnapshot, callback: (DshRpcError?) -> Unit) =
        goalMutation(DshHostProtocol.GOAL_RESUME, sessionId, goal, callback = callback)

    fun goalClear(sessionId: String, goal: DshGoalSnapshot, callback: (DshRpcError?) -> Unit) =
        goalMutation(DshHostProtocol.GOAL_CLEAR, sessionId, goal, callback = callback)

    private fun goalMutation(
        method: String,
        sessionId: String,
        goal: DshGoalSnapshot,
        enrich: (JSONObject) -> Unit = {},
        callback: (DshRpcError?) -> Unit,
    ) {
        call(method, JSONObject().apply {
            put("sessionId", sessionId)
            put("ref", JSONObject().apply { put("id", goal.id); put("revision", goal.revision) })
            enrich(this)
        }) { _, error -> callback(error) }
    }

    fun loadAttachment(
        sessionId: String,
        attachmentId: String,
        callback: (String?, String?) -> Unit,
    ) {
        call(DshHostProtocol.SESSION_ATTACHMENT, JSONObject().apply {
            put("sessionId", sessionId)
            put("attachmentId", attachmentId)
        }) { value, error ->
            val data = value?.optString("data").orEmpty()
            val mediaType = value?.optJSONObject("attachment")?.optString("mediaType")?.takeIf { it.isNotEmpty() }
                ?: "image/png"
            if (error != null || data.isEmpty()) callback(null, error?.message ?: "attachment 返回为空")
            else callback("data:$mediaType;base64,$data", null)
        }
    }

    fun queue(sessionId: String): List<DshQueueItem> = queries.queue(sessionId)

    fun pendingInteractions(sessionId: String): Pair<DshPendingApproval?, DshPendingQuestion?> =
        queries.pendingInteractions(sessionId)

    fun jobs(sessionId: String): List<DshJobItem> = queries.jobs(sessionId)

    fun workspaceGroups(): List<DshWorkspaceGroup> = queries.workspaceGroups()

    fun workspaceIdForSession(sessionId: String): String? = queries.workspaceIdForSession(sessionId)

    fun blankSessionInWorkspace(workspaceId: String?): DshSession? = queries.blankSessionInWorkspace(workspaceId)

    fun updateQueue(
        sessionId: String,
        itemId: String,
        action: JSONObject,
        callback: (JSONObject?, DshRpcError?) -> Unit,
    ) {
        call(DshHostProtocol.SESSION_UPDATE_QUEUE, JSONObject().apply {
            put("sessionId", sessionId)
            put("itemId", itemId)
            put("action", action)
        }) { value, error -> callback(value, error) }
    }

    fun renameSession(
        sessionId: String,
        title: String,
        callback: (JSONObject?, DshRpcError?) -> Unit,
    ) {
        call(DshHostProtocol.SESSION_RENAME, JSONObject().apply {
            put("sessionId", sessionId)
            put("title", title)
        }) { value, error -> callback(value, error) }
    }

    fun forkSession(
        sessionId: String,
        atSeq: Int?,
        callback: (JSONObject?, DshRpcError?) -> Unit,
    ) {
        val payload = JSONObject().apply { put("sessionId", sessionId) }
        atSeq?.let { payload.put("atSeq", it) }
        call(DshHostProtocol.SESSION_FORK, payload) { value, error -> callback(value, error) }
    }

    fun archiveSession(
        sessionId: String,
        callback: (JSONObject?, DshRpcError?) -> Unit,
    ) {
        call(DshHostProtocol.WORKSPACE_ARCHIVE_SESSION, JSONObject().apply {
            put("sessionId", sessionId)
        }) { value, error -> callback(value, error) }
    }

    fun sessionExportUrl(sessionId: String, includeDescendants: Boolean = true): String {
        val encodedSessionId = dshEncodeQueryComponent(sessionId)
        return "${connection.baseUrl.trimEnd('/')}${DshHostProtocol.SESSION_EXPORT_PATH}" +
            "?sessionId=$encodedSessionId" +
            "&includeDescendants=${if (includeDescendants) "true" else "false"}"
    }

    fun listDirectory(
        path: String?,
        callback: (DshDirectoryListing?, DshRpcError?) -> Unit,
    ) {
        val payload = JSONObject()
        path?.takeIf { it.isNotEmpty() }?.let { payload.put("path", it) }
        call(DshHostProtocol.HOST_LIST_DIRECTORY, payload) { value, error ->
            if (error != null || value == null) {
                callback(null, error ?: DshRpcError("internal", "host.listDirectory failed"))
                return@call
            }
            callback(parseDirectoryListingPayload(value), null)
        }
    }

    fun createDirectory(
        path: String,
        name: String,
        callback: (String?, DshRpcError?) -> Unit,
    ) {
        call(DshHostProtocol.HOST_CREATE_DIRECTORY, JSONObject().apply {
            put("path", path)
            put("name", name)
        }) { value, error ->
            if (error != null || value == null) {
                callback(null, error ?: DshRpcError("internal", "host.createDirectory failed"))
                return@call
            }
            callback(value.optString("path").takeIf { it.isNotEmpty() }, null)
        }
    }

    fun createWorkspace(
        path: String,
        callback: (JSONObject?, DshRpcError?) -> Unit,
    ) {
        call(DshHostProtocol.WORKSPACE_CREATE, JSONObject().apply {
            put("path", path)
        }) { value, error -> callback(value, error) }
    }

    fun renameWorkspace(
        workspaceId: String,
        title: String,
        callback: (JSONObject?, DshRpcError?) -> Unit,
    ) {
        call(DshHostProtocol.WORKSPACE_RENAME, JSONObject().apply {
            put("workspaceId", workspaceId)
            put("title", title)
        }) { value, error -> callback(value, error) }
    }

    fun deleteWorkspace(
        workspaceId: String,
        callback: (JSONObject?, DshRpcError?) -> Unit,
    ) {
        call(DshHostProtocol.WORKSPACE_DELETE, JSONObject().apply {
            put("workspaceId", workspaceId)
        }) { value, error -> callback(value, error) }
    }

    fun moveWorkspaceBefore(
        workspaceId: String,
        beforeWorkspaceId: String?,
        callback: (JSONObject?, DshRpcError?) -> Unit,
    ) {
        val payload = JSONObject().apply {
            put("workspaceId", workspaceId)
            beforeWorkspaceId?.takeIf { it.isNotEmpty() }?.let { put("beforeWorkspaceId", it) }
        }
        call(DshHostProtocol.WORKSPACE_INSERT_BEFORE, payload) { value, error ->
            callback(value, error)
        }
    }

    fun streamReply(pagerId: String, sessionId: String, prompt: String, onDelta: (String) -> Unit, onComplete: (String) -> Unit, onError: (String) -> Unit): DshStreamHandle =
        streamReply(pagerId, sessionId, prompt, { text, _ -> onDelta(text) }, onComplete, onError)

    fun streamReply(
        pagerId: String,
        sessionId: String,
        prompt: String,
        onDelta: (String, Boolean) -> Unit,
        onComplete: (String) -> Unit,
        onError: (String) -> Unit,
    ): DshStreamHandle {
        val call = runtime.call(DshHostProtocol.SESSION_PROMPT, JSONObject().apply {
            put("sessionId", sessionId); put("mode", "queue")
            put("content", JSONArray().apply { put(JSONObject().apply { put("type", "text"); put("text", prompt) }) })
            put("clientTimeZone", "UTC")
        }, beforeDispatch = { rpcId ->
            streams.put(DshPromptStreamRegistry.ActiveStream(sessionId, rpcId, onDelta, onComplete, onError))
        }) { value, error, rpcId ->
            if (error != null) {
                if (DshAppError.fromRpc(error.code, error.message).kind == DshAppErrorKind.RETRYABLE ||
                    DshAppError.fromRpc(error.code, error.message).kind == DshAppErrorKind.IGNORABLE) {
                    DshStreamLog.i("prompt.hold-for-resync session=$sessionId rpcId=$rpcId code=${error.code}")
                    return@call
                }
                if (streams.remove(rpcId) != null) onError(error.message)
                return@call
            }
            val command = value?.optJSONObject("command")
            if (command != null) {
                if (streams.remove(rpcId) != null) onComplete(command.optString("text"))
            }
        }
        DshStreamLog.i("prompt.start session=$sessionId rpcId=${call.rpcId} prompt=${DshDiagnostics.preview(prompt)}")
        return object : DshStreamHandle {
            private var cancelled = false
            override fun cancel() {
                if (cancelled) return
                cancelled = true
                if (streams.remove(call.rpcId) == null) return
                call.cancel()
                runtime.call(DshHostProtocol.SESSION_CANCEL, JSONObject().apply { put("sessionId", sessionId) }) { _, _, _ -> }
            }
        }
    }

    fun adoptLiveStream(
        sessionId: String,
        onDelta: (String, Boolean) -> Unit,
        onComplete: (String) -> Unit,
        onError: (String) -> Unit,
    ): DshStreamHandle {
        detachLiveStreams(sessionId)
        val rpcId = "adopted-$sessionId"
        val active = DshPromptStreamRegistry.ActiveStream(sessionId, rpcId, onDelta, onComplete, onError)
        streams.put(active)
        DshStreamLog.i("prompt.adopt-live session=$sessionId rpcId=$rpcId")
        return object : DshStreamHandle {
            private var cancelled = false
            override fun cancel() {
                if (cancelled) return
                cancelled = true
                if (!streams.removeIfCurrent(rpcId, active)) return
                runtime.call(DshHostProtocol.SESSION_CANCEL, JSONObject().apply { put("sessionId", sessionId) }) { _, _, _ -> }
            }
        }
    }

    fun detachLiveStreams(sessionId: String) {
        val removed = streams.detachSession(sessionId)
        if (removed > 0) {
            DshStreamLog.i("prompt.detach-live session=$sessionId count=$removed")
        }
    }

    private fun call(method: String, payload: JSONObject, callback: (JSONObject?, DshRpcError?) -> Unit) =
        runtime.call(method, payload) { value, error, _ -> callback(value, error) }

    private fun handleFrame(frame: DshDownlinkFrame) {
        val envelope = runCatching { JSONObject(frame.raw) }.getOrNull()
        if (envelope == null) {
            DshStreamLog.i(
                "host.frame stream=${frame.stream.name.lowercase()} parse-error raw=${DshDiagnostics.preview(frame.raw)}",
            )
            return
        }
        val payload = envelope.optJSONObject("payload")
        if (payload == null) {
            DshStreamLog.i(
                "host.frame stream=${frame.stream.name.lowercase()} no-payload envelopeType=${envelope.optString("type")} raw=${DshDiagnostics.preview(frame.raw)}",
            )
            return
        }
        val frameType = payload.optString("type")
        val inboundEvent = payload.optJSONObject("event")
        DshStreamLog.i(
            "host.frame stream=${frame.stream.name.lowercase()} type=$frameType session=${payload.optString("sessionId")} event=${inboundEvent?.optString("type").orEmpty()} seq=${inboundEvent?.optInt("seq", -1) ?: -1} payload=${DshDiagnostics.preview(payload.toString())}",
        )
        if (frame.stream == DshEventStream.HOST) {
            handleHostFrame(payload)
            return
        }
        if (frame.stream != DshEventStream.MUX) return
        when (frameType) {
            "session/subscribed" -> {
                store.applySubscribed(payload.optString("sessionId"), payload.optInt("lastSeq", -1))
                return
            }
            "session/queue" -> {
                store.replaceQueue(payload.optString("sessionId"), payload.optJSONArray("items")?.toString() ?: "[]")
                onQueueSnapshotHandler(payload.optString("sessionId"))
                return
            }
            "session/jobs" -> {
                store.replaceJobs(payload.optString("sessionId"), payload.optJSONArray("jobs")?.toString() ?: "[]")
                onJobsSnapshotHandler(payload.optString("sessionId"))
                return
            }
            "session/projection" -> {
                val value = payload.optJSONObject("value")?.toString() ?: payload.optString("value")
                store.applyProjection(
                    payload.optString("sessionId"),
                    payload.optString("key"),
                    value,
                    payload.optInt("seq", -1),
                )
                onProjectionHandler(payload.optString("sessionId"), payload.optString("key"), value, payload.optInt("seq", -1))
                return
            }
            "approval/requested", "question/requested" -> {
                val rpcId = pendingInteractionRpcId(envelope, payload)
                DshStreamLog.question(
                    "mux.requested type=$frameType rpcId=$rpcId session=${payload.optString("sessionId")} envelopeRpc=${envelope.optString("rpcId")} payloadRpc=${payload.optString("rpcId")}",
                )
                if (rpcId.isEmpty()) {
                    DshStreamLog.question(
                        "mux.requested-drop empty-rpcId type=$frameType raw=${DshDiagnostics.preview(frame.raw)}",
                    )
                    return
                }
                store.putPending(rpcId, payload.toString())
                onPendingInteractionHandler(payload.optString("sessionId"))
                return
            }
            "approval/resolved", "question/resolved" -> {
                val rpcId = pendingInteractionRpcId(envelope, payload)
                    .ifEmpty { payload.optString("questionRpcId") }
                    .ifEmpty { payload.optString("approvalId") }
                DshStreamLog.question(
                    "mux.resolved type=$frameType rpcId=$rpcId session=${payload.optString("sessionId")} outcome=${payload.optString("outcome")}",
                )
                store.removePending(rpcId)
                onPendingInteractionHandler(payload.optString("sessionId"))
                return
            }
        }
        if (frameType != "session/event") return
        val sessionId = payload.optString("sessionId")
        val event = payload.optJSONObject("event") ?: return
        val type = event.optString("type")
        val seq = event.optInt("seq", -1)
        // Preserve the optional host-computed tool view. History entries and
        // live mux frames must feed the same remote tool model.
        val eventEnvelope = JSONObject().apply {
            put("event", event)
            payload.optJSONObject("view")?.let { put("view", it) }
        }
        val accepted = store.applySessionEvent(sessionId, seq, type, eventEnvelope.toString())
        if (!accepted) {
            DshStreamLog.i("host.frame duplicate-session-event session=$sessionId event=$type seq=$seq")
            return
        }
        if (seq > -1) onSessionEventHandler(sessionId, DshRawSessionEvent(seq, type, eventEnvelope.toString()))
        if (!streams.applyEvent(eventEnvelope, sessionId)) {
            DshStreamLog.i(
                "host.frame no-active-stream session=$sessionId event=$type seq=$seq",
            )
        }
    }

    private fun handleHostFrame(payload: JSONObject) {
        when (payload.optString("type")) {
            "host/remote-event" -> {
                onRemoteEventHandler(payload.optString("event"))
                return
            }
            "host/session-added" -> {
                val id = payload.optString("sessionId")
                if (id.isEmpty()) return
                store.applySessionAdded(DshSession(
                    id = id,
                    title = "尚无标题",
                    workspace = "Host",
                    updatedLabel = "",
                    blank = true,
                    cwd = payload.optString("cwd"),
                    parentSessionId = payload.optString("parentSessionId").takeIf { it.isNotEmpty() },
                    origin = payload.optString("origin").takeIf { it.isNotEmpty() },
                    agentPreset = payload.optString("agentPreset").takeIf { it.isNotEmpty() },
                ))
            }
            "host/session-status" -> {
                val id = payload.optString("sessionId")
                val current = store.sessions[id] ?: return
                val running = payload.optBoolean("running")
                store.sessions[id] = current.copy(running = running, blank = if (running) false else current.blank)
                onSessionStatusHandler(id, running)
            }
            "host/session-removed" -> {
                val id = payload.optString("sessionId")
                store.removeSession(id)
            }
            "host/workspace-order-changed" -> {
                val order = payload.optJSONArray("workspaceIds")?.toString() ?: return
                store.reorderWorkspaces(order)
            }
        }
    }

    private companion object {
        const val DEEPSEEK_PROVIDER = "deepseek-official"
        const val DEEPSEEK_SETTINGS_NS = "llm-deepseek"
        const val DEEPSEEK_CREDENTIAL_REF = "DEEPSEEK_API_KEY"
        const val HISTORY_PAGE_MESSAGES = 80
    }
}
