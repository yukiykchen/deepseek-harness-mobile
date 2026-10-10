package com.example.dsh.dsh

import com.tencent.kuikly.core.module.NetworkModule
import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.timer.setTimeout

/**
 * Typed client for one DSH Host (Relay loopback or SSH forward): RPC calls,
 * the mux/host event streams via [DshHostConnectionRuntime], and the
 * host-authoritative [DshHostStore] projection.
 */
internal class DshHostClient(
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
) {
    internal val store = DshHostStore()
    private val onQueueSnapshotHandler = onQueueSnapshot
    private val onJobsSnapshotHandler = onJobsSnapshot
    private val onSessionStatusHandler = onSessionStatus
    private val onProjectionHandler = onProjection
    private val onSessionEventHandler = onSessionEvent
    private val onRemoteEventHandler = onRemoteEvent
    private val onPendingInteractionHandler = onPendingInteraction
    private val runtime = DshHostConnectionRuntime(
        network = network,
        webSocket = webSocket,
        connection = connection,
        pagerId = pagerId,
        onFrame = ::handleFrame,
        onState = onState,
        onWorkspaceBaseline = { value ->
            val archived = value.optJSONArray("archivedSessionIds")
            val archivedIds = buildSet {
                if (archived != null) for (index in 0 until archived.length()) {
                    archived.optString(index)?.takeIf { it.isNotEmpty() }?.let(::add)
                }
            }
            store.replaceWorkspaceBaseline(value.optJSONArray("items")?.toString() ?: "[]", archivedIds)
        },
        onSessionBaseline = { value -> store.replaceSessions(parseSessions(value)) },
        onQueueSnapshot = onQueueSnapshot,
        onJobsSnapshot = onJobsSnapshot,
        onSessionStatus = onSessionStatus,
        onProjection = onProjectionHandler,
        onSessionEvent = onSessionEventHandler,
        onRemoteEvent = onRemoteEvent,
    )
    private val activeStreams = mutableMapOf<String, ActiveStream>()

    private data class ActiveStream(
        val sessionId: String,
        val promptRpcId: String,
        val onDelta: (String, Boolean) -> Unit,
        val onComplete: (String) -> Unit,
        val onError: (String) -> Unit,
        var observed: Boolean = true,
        val accumulated: StringBuilder = StringBuilder(),
        var finalMessage: String = "",
        var failure: String = "",
    )

    fun currentConnectionState(): DshHostRuntimeState = runtime.currentState()
    fun isProductReady(): Boolean = runtime.currentState().phase == DshHostRuntimePhase.READY
    fun stop() = runtime.stop()

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
        DshStreamLog.question(
            "repo.respondQuestion rpcId=$rpcId session=$sessionId answer='${DshStreamLog.preview(answer.toString(), 400)}'",
        )
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

    fun saveDeepSeekApiKey(apiKey: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
        call(DshHostProtocol.CREDENTIALS_SET, JSONObject().apply {
            put("ref", DEEPSEEK_CREDENTIAL_REF)
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
            val sessions = parseSessions(value)
            store.replaceSessions(sessions)
            onSuccess(sessions)
        }
    }

    private fun parseSessions(value: JSONObject): List<DshSession> {
        val items = value.optJSONArray("items") ?: JSONArray()
        return buildList {
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                val id = item.optString("sessionId")
                if (id.isEmpty()) continue
                val projections = item.optJSONObject("projections")?.optJSONObject("values")
                add(DshSession(
                    id = id,
                    title = projections?.optString("title")?.takeIf { it.isNotEmpty() } ?: "尚无标题",
                    workspace = "Host",
                    updatedLabel = item.optLong("updatedAt").takeIf { it > 0 }?.toString().orEmpty(),
                    running = item.optBoolean("running"), blank = item.optBoolean("blank"), cwd = item.optString("cwd"),
                    parentSessionId = item.optString("parentSessionId").takeIf { it.isNotEmpty() },
                    origin = item.optString("origin").takeIf { it.isNotEmpty() },
                    agentPreset = item.optString("agentPreset").takeIf { it.isNotEmpty() },
                ))
            }
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
                DshStreamLog.i("history.fail session=$sessionId error='${error?.message ?: "empty"}'")
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

    fun pendingInteractions(sessionId: String): Pair<DshPendingApproval?, DshPendingQuestion?> {
        var approval: DshPendingApproval? = null
        var question: DshPendingQuestion? = null
        store.pendingInteractions.forEach { (rpcId, raw) ->
            val payload = runCatching { JSONObject(raw) }.getOrNull() ?: return@forEach
            if (payload.optString("sessionId") != sessionId) return@forEach
            when (payload.optString("type")) {
                "approval/requested" -> approval = DshPendingApproval(
                    rpcId = rpcId,
                    sessionId = sessionId,
                    approvalId = payload.optString("approvalId"),
                    toolName = payload.optString("toolName"),
                    callId = payload.optString("callId").takeIf { it.isNotEmpty() },
                    reason = payload.optString("reason").takeIf { it.isNotEmpty() },
                    command = approvalCommand(sessionId, payload.optString("callId")),
                )
                "question/requested" -> {
                    val questions = payload.optJSONArray("questions") ?: JSONArray()
                    question = DshPendingQuestion(
                        rpcId = rpcId,
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
                                    DshPendingQuestionOption(
                                        label = option.optString("label"),
                                        description = option.optString("description"),
                                    )
                                },
                                multiSelect = item.optBoolean("multiSelect") || item.optBoolean("multi_select"),
                            )
                        },
                    )
                }
            }
        }
        return approval to question
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

    fun workspaceGroups(): List<DshWorkspaceGroup> {
        val raw = store.workspaceBaseline
        val workspaces = runCatching { JSONArray(raw) }.getOrNull() ?: JSONArray()
        val archived = store.archivedSessionIds
        val sessionById = store.sessions.values
            .filterNot { it.blank || archived.contains(it.id) }
            .associateBy { it.id }
        val grouped = mutableSetOf<String>()
        val groups = (0 until workspaces.length()).mapNotNull { index ->
            val workspace = workspaces.optJSONObject(index) ?: return@mapNotNull null
            val workspaceId = workspace.optString("workspaceId")
            if (workspaceId.isEmpty()) return@mapNotNull null
            val sessionIds = workspace.optJSONArray("sessionIds") ?: JSONArray()
            val sessions = (0 until sessionIds.length()).mapNotNull { sessionIndex ->
                val sessionId = sessionIds.optString(sessionIndex)
                sessionId?.takeIf { it.isNotEmpty() }?.let(grouped::add)
                sessionById[sessionId]
            }
            DshWorkspaceGroup(
                workspaceId = workspaceId,
                title = workspace.optString("title").ifEmpty { workspaceId },
                path = workspace.optString("path"),
                sessions = sessions,
            )
        }
        val ungrouped = sessionById.values.filterNot { grouped.contains(it.id) }
        return if (ungrouped.isEmpty()) groups else groups + DshWorkspaceGroup(
            workspaceId = "",
            title = "未归类",
            path = "",
            sessions = ungrouped,
        )
    }

    fun workspaceIdForSession(sessionId: String): String? {
        val workspaces = runCatching { JSONArray(store.workspaceBaseline) }.getOrNull() ?: JSONArray()
        for (index in 0 until workspaces.length()) {
            val workspace = workspaces.optJSONObject(index) ?: continue
            val sessionIds = workspace.optJSONArray("sessionIds") ?: continue
            for (sessionIndex in 0 until sessionIds.length()) {
                if (sessionIds.optString(sessionIndex) == sessionId) {
                    return workspace.optString("workspaceId").takeIf { it.isNotEmpty() }
                }
            }
        }
        return null
    }

    fun blankSessionInWorkspace(workspaceId: String?): DshSession? {
        if (workspaceId == null) return store.sessions.values.firstOrNull { it.blank && it.cwd.isEmpty() }
        val workspaces = runCatching { JSONArray(store.workspaceBaseline) }.getOrNull() ?: JSONArray()
        for (index in 0 until workspaces.length()) {
            val workspace = workspaces.optJSONObject(index) ?: continue
            if (workspace.optString("workspaceId") != workspaceId) continue
            val sessionIds = workspace.optJSONArray("sessionIds") ?: continue
            for (sessionIndex in 0 until sessionIds.length()) {
                val sessionId = sessionIds.optString(sessionIndex)
                val session = store.sessions[sessionId] ?: continue
                if (session.blank) return session
            }
        }
        return null
    }

    private fun approvalCommand(sessionId: String, callId: String): String? {
        if (callId.isEmpty()) return null
        val events = store.sessionEvents[sessionId] ?: return null
        events.forEach { event ->
            if (event.type != "tool/call") return@forEach
            val payload = runCatching { JSONObject(event.raw) }.getOrNull() ?: return@forEach
            val data = payload.optJSONObject("data") ?: return@forEach
            if (data.optString("callId") != callId) return@forEach
            val arguments = data.opt("arguments") ?: return@forEach
            return when (arguments) {
                is String -> arguments
                else -> {
                    val obj = arguments as? JSONObject
                    obj?.optString("command")?.takeIf { it.isNotEmpty() } ?: arguments.toString()
                }
            }
        }
        return null
    }

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
            callback(parseDirectoryListing(value), null)
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

    private fun parseDirectoryListing(value: JSONObject): DshDirectoryListing {
        fun entries(array: JSONArray?): List<DshDirectoryEntry> = buildList {
            if (array == null) return@buildList
            for (index in 0 until array.length()) {
                val entry = array.optJSONObject(index) ?: continue
                add(DshDirectoryEntry(
                    name = entry.optString("name"),
                    path = entry.optString("path"),
                    hidden = entry.optBoolean("hidden"),
                ))
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

    fun streamReply(pagerId: String, sessionId: String, prompt: String, onDelta: (String) -> Unit, onComplete: (String) -> Unit, onError: (String) -> Unit): DshStreamHandle {
        val call = runtime.call(DshHostProtocol.SESSION_PROMPT, JSONObject().apply {
            put("sessionId", sessionId); put("mode", "queue")
            put("content", JSONArray().apply { put(JSONObject().apply { put("type", "text"); put("text", prompt) }) })
            put("clientTimeZone", "UTC")
        }) { value, error, rpcId ->
            if (error != null) {
                if (dshIsTransportInterrupt(error.code, error.message)) {
                    DshStreamLog.i("prompt.hold-for-resync session=$sessionId rpcId=$rpcId code=${error.code}")
                    return@call
                }
                activeStreams.remove(rpcId); onError(error.message); return@call
            }
            val command = value?.optJSONObject("command")
            if (command != null) {
                activeStreams.remove(rpcId); onComplete(command.optString("text"))
            }
        }
        activeStreams[call.rpcId] = ActiveStream(sessionId, call.rpcId, { text, _ -> onDelta(text) }, onComplete, onError)
        return object : DshStreamHandle {
            private var cancelled = false
            override fun cancel() {
                if (cancelled) return
                cancelled = true
                activeStreams.remove(call.rpcId)
                call.cancel()
                runtime.call(DshHostProtocol.SESSION_CANCEL, JSONObject().apply { put("sessionId", sessionId) }) { _, _, _ -> }
            }
        }
    }

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
        }) { value, error, rpcId ->
            if (error != null) {
                if (dshIsTransportInterrupt(error.code, error.message)) {
                    DshStreamLog.i("prompt.hold-for-resync session=$sessionId rpcId=$rpcId code=${error.code}")
                    return@call
                }
                activeStreams.remove(rpcId); onError(error.message); return@call
            }
            val command = value?.optJSONObject("command")
            if (command != null) {
                activeStreams.remove(rpcId); onComplete(command.optString("text"))
            }
        }
        activeStreams[call.rpcId] = ActiveStream(sessionId, call.rpcId, onDelta, onComplete, onError)
        DshStreamLog.i("prompt.start session=$sessionId rpcId=${call.rpcId} promptChars=${prompt.length} prompt='${DshStreamLog.preview(prompt)}'")
        return object : DshStreamHandle {
            private var cancelled = false
            override fun cancel() {
                if (cancelled) return
                cancelled = true
                activeStreams.remove(call.rpcId)
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
        activeStreams[rpcId] = ActiveStream(sessionId, rpcId, onDelta, onComplete, onError)
        DshStreamLog.i("prompt.adopt-live session=$sessionId rpcId=$rpcId")
        return object : DshStreamHandle {
            private var cancelled = false
            override fun cancel() {
                if (cancelled) return
                cancelled = true
                activeStreams.remove(rpcId)
                runtime.call(DshHostProtocol.SESSION_CANCEL, JSONObject().apply { put("sessionId", sessionId) }) { _, _, _ -> }
            }
        }
    }

    fun detachLiveStreams(sessionId: String) {
        val removed = activeStreams.entries
            .filter { it.value.sessionId == sessionId }
            .map { it.key }
        removed.forEach(activeStreams::remove)
        if (removed.isNotEmpty()) {
            DshStreamLog.i("prompt.detach-live session=$sessionId count=${removed.size}")
        }
    }

    private fun call(method: String, payload: JSONObject, callback: (JSONObject?, DshRpcError?) -> Unit) =
        runtime.call(method, payload) { value, error, _ -> callback(value, error) }

    private fun handleFrame(frame: DshDownlinkFrame) {
        val envelope = runCatching { JSONObject(frame.raw) }.getOrNull()
        if (envelope == null) {
            DshStreamLog.i(
                "host.frame stream=${frame.stream.name.lowercase()} parse-error chars=${frame.raw.length} raw='${DshStreamLog.preview(frame.raw, 240)}'",
            )
            return
        }
        val payload = envelope.optJSONObject("payload")
        if (payload == null) {
            DshStreamLog.i(
                "host.frame stream=${frame.stream.name.lowercase()} no-payload envelopeType=${envelope.optString("type")} raw='${DshStreamLog.preview(frame.raw, 240)}'",
            )
            return
        }
        val frameType = payload.optString("type")
        val inboundEvent = payload.optJSONObject("event")
        DshStreamLog.i(
            "host.frame stream=${frame.stream.name.lowercase()} type=$frameType session=${payload.optString("sessionId")} event=${inboundEvent?.optString("type").orEmpty()} seq=${inboundEvent?.optInt("seq", -1) ?: -1} chars=${frame.raw.length} payload='${DshStreamLog.preview(payload.toString(), 400)}'",
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
                        "mux.requested-drop empty-rpcId type=$frameType raw='${DshStreamLog.preview(frame.raw, 240)}'",
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
        store.applySessionEvent(sessionId, seq, type, eventEnvelope.toString())
        if (seq > -1) onSessionEventHandler(sessionId, DshRawSessionEvent(seq, type, eventEnvelope.toString()))
        val data = event.optJSONObject("data") ?: JSONObject()
        val source = sessionEventSource(data)
        val rpcId = source?.optString("rpcId").orEmpty()
        val active = resolveActiveStream(sessionId, type, rpcId)
        if (active == null) {
            DshStreamLog.i(
                "host.frame drop-no-active-stream session=$sessionId event=$type seq=$seq rpcId=$rpcId",
            )
            return
        }
        when (type) {
            "user/message" -> {
                val kind = source?.optString("kind").orEmpty()
                if (kind.isEmpty() || kind == "user") active.observed = true
            }
            "assistant/chunk" -> {
                val chunk = data.optJSONObject("chunk") ?: return
                val chunkType = chunk.optString("type")
                val text = chunk.optString("text").ifEmpty { chunk.optString("delta") }
                DshStreamLog.i(
                    "mux.chunk session=$sessionId rpcId=${active.promptRpcId} type=$chunkType deltaChars=${text.length} delta='${DshStreamLog.preview(text)}' acc=${active.accumulated.length}",
                )
                when (chunkType) {
                    "text-delta", "text_delta", "text" -> text.takeIf { it.isNotEmpty() }?.let {
                        active.observed = true
                        active.accumulated.append(it)
                        active.onDelta(it, false)
                    }
                    "reasoning-delta", "reasoning_delta" -> text.takeIf { it.isNotEmpty() }?.let {
                        active.observed = true
                        active.onDelta(it, true)
                    }
                }
                if (chunkType == "finish") {
                    val reason = chunk.optJSONObject("reason")
                    if (reason?.optString("kind") == "error") {
                        active.failure = reason.optJSONObject("failure")?.optString("message").orEmpty()
                    }
                }
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
                DshStreamLog.i(
                    "mux.turn-end session=$sessionId rpcId=${active.promptRpcId} acc=${active.accumulated.length} final=${active.finalMessage.length} error=${error ?: "-"} preview='${DshStreamLog.preview(completed)}'",
                )
                if (error != null) active.onError(error) else {
                    active.onComplete(completed)
                }
            }
        }
    }

    private fun sessionEventSource(data: JSONObject): JSONObject? =
        data.optJSONObject("source") ?: data.optJSONObject("message")?.optJSONObject("source")

    private fun resolveActiveStream(sessionId: String, type: String, rpcId: String): ActiveStream? {
        if (rpcId.isNotEmpty()) {
            activeStreams[rpcId]?.takeIf { it.sessionId == sessionId }?.let { return it }
        }
        return activeStreams.values.lastOrNull { it.sessionId == sessionId }
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
                store.sessions.remove(id)
                store.sessionEvents.remove(id)
                store.queueSnapshots.remove(id)
                store.jobSnapshots.remove(id)
                store.projections.remove(id)
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

internal fun pendingInteractionRpcId(envelope: JSONObject, payload: JSONObject): String {
    val nested = payload.optJSONObject("payload")
    return listOf(
        envelope.optString("rpcId"),
        payload.optString("rpcId"),
        nested?.optString("rpcId").orEmpty(),
    ).firstOrNull { it.isNotEmpty() }.orEmpty()
}

internal fun dshEncodeQueryComponent(value: String): String {
    val allowed = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_.~"
    return buildString {
        value.encodeToByteArray().forEach { byte ->
            val unsigned = byte.toInt() and 0xFF
            val char = unsigned.toChar()
            if (char in allowed) append(char)
            else append('%').append(unsigned.toString(16).uppercase().padStart(2, '0'))
        }
    }
}
