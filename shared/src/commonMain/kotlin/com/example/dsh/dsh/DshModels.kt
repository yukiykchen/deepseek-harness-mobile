package com.example.dsh.dsh

internal enum class DshConnectionMode {
    RELAY,
    SSH,
}

internal data class DshSessionScope(
    val mode: DshConnectionMode,
    val profileId: String? = null,
) {
    val storageKey: String
        get() = when (mode) {
            DshConnectionMode.RELAY -> "relay:${profileId ?: "default"}"
            DshConnectionMode.SSH -> "ssh:${profileId ?: DEFAULT_REMOTE_PROFILE_ID}"
        }

    companion object {
        const val DEFAULT_REMOTE_PROFILE_ID = "default"
    }
}

internal data class DshRelayProfile(
    val hostId: String,
    val hostName: String,
    val relayOrigin: String,
    val pairedAt: Long,
)

internal data class DshRemoteProfile(
    val profileId: String = DshSessionScope.DEFAULT_REMOTE_PROFILE_ID,
    val host: String,
    val sshPort: Int,
    val username: String,
    val remoteDshPort: Int,
    val keyId: String,
    val hostFingerprint: String = "",
)

internal enum class DshSessionCacheState {
    SYNCED,
    STALE,
    SYNC_FAILED,
}

internal enum class DshHostRuntimePhase {
    DISCONNECTED,
    CONNECTING,
    HOST_HANDSHAKE,
    SYNCING,
    READY,
    RECONNECTING,
    ERROR,
    STOPPED,
}

internal data class DshHostRuntimeState(
    val phase: DshHostRuntimePhase,
    val generation: Long,
    val muxOpen: Boolean = false,
    val hostOpen: Boolean = false,
    val message: String = "",
)

internal enum class DshEventStream {
    MUX,
    HOST,
}

/** A raw downlink frame. Reducers must route mux frames by sessionId. */
internal data class DshDownlinkFrame(
    val generation: Long,
    val stream: DshEventStream,
    val raw: String,
)

internal data class DshRpcError(
    val code: String,
    val message: String,
    val details: String = "{}",
)

internal fun dshIsTransportInterrupt(code: String, message: String = ""): Boolean {
    if (code == "generation-cancelled" || code == "cancelled") return true
    if (code.startsWith("transport-")) return true
    return message.contains("世代已失效") || message.contains("连接已停止")
}

internal fun dshTurnStatusLabel(reconnecting: Boolean): String =
    if (reconnecting) "Reconnecting..." else "Deep diving..."

/** Matches DSH conversation `duration.seconds` / `duration.minutes`. */
internal fun dshFormatTurnDuration(elapsedMs: Long): String {
    val total = maxOf(0L, elapsedMs / 1000L)
    val minutes = total / 60L
    val seconds = total % 60L
    return if (minutes > 0) "${minutes}分${seconds.toString().padStart(2, '0')}秒" else "${total}秒"
}

internal data class DshImageLimits(
    val maxImageBytes: Long,
    val maxImagesPerMessage: Int,
    val maxMessageImageBytes: Long,
    val maxImagePixels: Long,
    val mediaTypes: List<String>,
)

internal data class DshRawSessionEvent(
    val seq: Int,
    val type: String,
    val raw: String,
)

internal data class DshProjectionCell(
    val value: String,
    val seq: Int,
)

/** Host-authoritative control-plane state, partitioned by session id. */
internal class DshHostStore {
    val sessions = linkedMapOf<String, DshSession>()
    var workspaceBaseline: String = "{}"
        private set
    var archivedSessionIds: Set<String> = emptySet()
        private set
    val sessionEvents = linkedMapOf<String, MutableList<DshRawSessionEvent>>()
    val sessionLastSeq = linkedMapOf<String, Int>()
    val queueSnapshots = linkedMapOf<String, String>()
    val jobSnapshots = linkedMapOf<String, String>()
    val projections = linkedMapOf<String, MutableMap<String, DshProjectionCell>>()
    val pendingInteractions = linkedMapOf<String, String>()

    fun replaceWorkspaceBaseline(raw: String, archived: Set<String>) {
        workspaceBaseline = raw
        archivedSessionIds = archived
    }

    fun reorderWorkspaces(orderJson: String) {
        val order = runCatching { com.tencent.kuikly.core.nvi.serialization.json.JSONArray(orderJson) }
            .getOrNull() ?: return
        val orderedIds = buildList {
            for (index in 0 until order.length()) add(order.optString(index))
        }
        val current = runCatching {
            com.tencent.kuikly.core.nvi.serialization.json.JSONArray(workspaceBaseline)
        }.getOrNull() ?: return
        val byId = buildMap {
            for (index in 0 until current.length()) {
                val workspace = current.optJSONObject(index) ?: continue
                put(workspace.optString("workspaceId"), workspace)
            }
        }
        val reordered = orderedIds.mapNotNull { byId[it] }
        val remaining = (0 until current.length())
            .mapNotNull { index -> current.optJSONObject(index) }
            .filterNot { orderedIds.contains(it.optString("workspaceId")) }
        val result = com.tencent.kuikly.core.nvi.serialization.json.JSONArray()
        (reordered + remaining).forEach(result::put)
        workspaceBaseline = result.toString()
    }

    /** List baseline is authoritative for blank, while retaining local seq-newer projections. */
    fun replaceSessions(baseline: List<DshSession>) {
        val old = sessions.toMap()
        sessions.clear()
        baseline.forEach { next ->
            val previous = old[next.id]
            val titleProjection = projections[next.id]?.get("title")?.value?.trim()?.removeSurrounding("\"")
            sessions[next.id] = if (previous == null) next.copy(title = titleProjection ?: next.title) else next.copy(
                title = titleProjection ?: previous.title.takeUnless { it == "尚无标题" } ?: next.title,
                blank = next.blank,
                subscribedLastSeq = maxOf(previous.subscribedLastSeq, next.subscribedLastSeq),
            )
        }
    }

    /** Creation frames must never turn an existing list row back into blank. */
    fun applySessionAdded(session: DshSession): DshSession {
        val previous = sessions[session.id]
        val merged = if (previous == null) session else previous.copy(
            running = session.running || previous.running,
            cwd = session.cwd.ifEmpty { previous.cwd },
            parentSessionId = session.parentSessionId ?: previous.parentSessionId,
            origin = session.origin ?: previous.origin,
            agentPreset = session.agentPreset ?: previous.agentPreset,
            blank = previous.blank,
        )
        sessions[session.id] = merged
        return merged
    }

    fun applySubscribed(sessionId: String, lastSeq: Int) {
        sessionLastSeq[sessionId] = maxOf(sessionLastSeq[sessionId] ?: -1, lastSeq)
        sessions[sessionId]?.let { sessions[sessionId] = it.copy(subscribedLastSeq = maxOf(it.subscribedLastSeq, lastSeq)) }
    }

    fun applySessionEvent(sessionId: String, seq: Int, type: String, raw: String) {
        val events = sessionEvents.getOrPut(sessionId) { mutableListOf() }
        if (events.none { it.seq == seq }) {
            events += DshRawSessionEvent(seq, type, raw)
            events.sortBy { it.seq }
        }
        sessionLastSeq[sessionId] = maxOf(sessionLastSeq[sessionId] ?: -1, seq)
    }

    /** Queue/jobs are whole snapshots; later frames replace the whole value. */
    fun replaceQueue(sessionId: String, rawItems: String) { queueSnapshots[sessionId] = rawItems }
    fun replaceJobs(sessionId: String, rawJobs: String) { jobSnapshots[sessionId] = rawJobs }

    /** Projection updates use higher-seq-wins, including across reconnect baselines. */
    fun applyProjection(sessionId: String, key: String, value: String, seq: Int) {
        val cells = projections.getOrPut(sessionId) { mutableMapOf() }
        val previous = cells[key]
        if (previous == null || seq >= previous.seq) {
            cells[key] = DshProjectionCell(value, seq)
            if (key == "title") {
                val title = value.trim().removeSurrounding("\"")
                sessions[sessionId]?.let { sessions[sessionId] = it.copy(title = title) }
            }
        }
    }

    fun putPending(rpcId: String, raw: String) { pendingInteractions[rpcId] = raw }
    fun removePending(rpcId: String) { pendingInteractions.remove(rpcId) }
}

internal enum class DshRemoteFailure {
    KEY_MISSING,
    AUTH_FAILED,
    HOST_FINGERPRINT_REQUIRED,
    SSH_UNREACHABLE,
    SSH_PORT_IN_USE,
    DSH_UNAVAILABLE,
}

internal data class DshLegacyRemoteProfile(
    val mode: DshConnectionMode,
    val host: String,
    val sshPort: Int,
    val username: String,
    val remoteDshPort: Int,
    val keyId: String,
    val hostFingerprint: String = "",
)

/** The small client-side model used by the first DSH surface. */
internal data class DshSession(
    val id: String,
    val title: String,
    val workspace: String,
    val updatedLabel: String,
    val running: Boolean = false,
    val blank: Boolean = false,
    val cwd: String = "",
    val parentSessionId: String? = null,
    val origin: String? = null,
    val agentPreset: String? = null,
    val subscribedLastSeq: Int = -1,
)

internal enum class DshMessageRole {
    USER,
    ASSISTANT,
    TOOL,
    ERROR,
}

internal data class DshMessage(
    val id: String,
    val role: DshMessageRole,
    val content: String,
    val streaming: Boolean = false,
    val toolName: String? = null,
    val hidden: Boolean = false,
    val toolCardType: DshToolCardType = DshToolCardType.GENERIC,
    val toolRunning: Boolean = false,
    val toolError: Boolean = false,
    val isContextInjection: Boolean = false,
    val contextForm: String = "",
    val contextBody: String = "",
    val contextCatalog: List<DshContextCatalogEntry> = emptyList(),
    val contextSections: List<DshContextSection> = emptyList(),
    val contextRecalls: List<DshContextRecall> = emptyList(),
    val contextInstructions: List<DshContextInstruction> = emptyList(),
    val contextRelaySender: String = "",
    val isReasoning: Boolean = false,
    val attachmentId: String? = null,
    val toolCallId: String = "",
    /** Structured tool state from the Host tool view; null for non-tool rows. */
    val remoteTool: DshRemoteToolCallModel? = null,
)

internal fun dshIsLiveAssistantText(message: DshMessage): Boolean =
    message.role == DshMessageRole.ASSISTANT &&
        !message.isReasoning &&
        message.attachmentId == null

/**
 * What the assistant bubble should paint.
 *
 * The list row is only a placeholder until settle writes the finished string.
 * While this row is the live target, [live] is the source of truth — never a
 * shorter first-flush snapshot in [stored].
 */
internal fun dshDisplayedAssistantContent(
    stored: String,
    live: String,
    isLiveRow: Boolean,
): String {
    if (isLiveRow && live.isNotEmpty()) {
        return if (live.length >= stored.length) live else stored
    }
    if (stored.length >= live.length) return stored.ifEmpty { live }
    return live
}

/**
 * Assistant text that belongs to the in-progress turn sits after the latest
 * user message. A completed previous reply is before that user and must not
 * be reused as the live streaming target.
 */
internal fun dshAssistantTailForCurrentTurn(messages: List<DshMessage>): DshMessage? {
    val lastUserIndex = messages.indexOfLast { it.role == DshMessageRole.USER }
    return messages.withIndex().lastOrNull { (index, message) ->
        index > lastUserIndex && dshIsLiveAssistantText(message)
    }?.value
}

/**
 * History resync after sending a new prompt can still contain the previous
 * assistant. [anchorAssistantId] is that previous reply; never resume into it.
 */
internal fun dshHistoryTailToResume(
    messages: List<DshMessage>,
    anchorAssistantId: String,
): DshMessage? {
    val live = dshAssistantTailForCurrentTurn(messages) ?: return null
    if (anchorAssistantId.isNotEmpty() && live.id == anchorAssistantId) return null
    return live
}

/** Ignores id/streaming so a host key remap does not look like new content. */
internal fun DshMessage.visuallyEquals(other: DshMessage): Boolean =
    role == other.role &&
        content == other.content &&
        toolName == other.toolName &&
        hidden == other.hidden &&
        toolCardType == other.toolCardType &&
        toolRunning == other.toolRunning &&
        toolError == other.toolError &&
        isContextInjection == other.isContextInjection &&
        contextForm == other.contextForm &&
        contextBody == other.contextBody &&
        isReasoning == other.isReasoning &&
        attachmentId == other.attachmentId &&
        toolCallId == other.toolCallId &&
        remoteTool == other.remoteTool

internal fun dshMessagesVisuallyEqual(left: List<DshMessage>, right: List<DshMessage>): Boolean {
    if (left.size != right.size) return false
    return left.indices.all { left[it].visuallyEquals(right[it]) }
}

internal data class DshContextCatalogEntry(
    val name: String,
    val description: String,
)

internal data class DshContextSection(
    val title: String,
    val body: String,
)

internal data class DshContextRecall(
    val label: String,
    val retainedMessages: Int,
    val omittedMessages: Int,
    val truncated: Boolean,
)

internal data class DshContextInstruction(
    val path: String,
    val action: String,
)

internal data class DshWebTimelineItem(
    val key: String,
    val kind: Kind,
    val text: String = "",
    val sourceLabel: String = "",
    val toolName: String? = null,
    val input: String? = null,
    val output: String? = null,
    val error: String? = null,
    val running: Boolean = false,
    val callId: String = "",
    val callSeq: Int = -1,
    val cardType: DshToolCardType = DshToolCardType.GENERIC,
    val cardTitle: String = "",
    val cardBody: String = "",
    val attachmentId: String? = null,
    val source: com.tencent.kuikly.core.nvi.serialization.json.JSONObject? = null,
    val remoteTool: DshRemoteToolCallModel? = null,
) {
    enum class Kind {
        USER,
        ASSISTANT,
        REASONING,
        IMAGE,
        UNKNOWN_BLOCK,
        CONTEXT,
        TOOL,
        ERROR,
    }
}

internal enum class DshToolCardType {
    GENERIC,
    TERMINAL,
    READ,
    DIFF,
    SEARCH,
    WEB,
    JSON,
}

internal data class DshJsonNode(
    val key: String,
    val label: String,
    val preview: String,
    val children: List<DshJsonNode> = emptyList(),
    val depth: Int = 0,
)

internal data class DshQueueItem(
    val id: String,
    val placement: String,
    val preview: String,
    val text: String?,
)

internal data class DshJobItem(
    val id: String,
    val kind: String,
    val label: String,
    val status: String,
    val detail: String,
    val startedAt: Long,
    val finishedAt: Long?,
)

internal data class DshWorkspaceGroup(
    val workspaceId: String,
    val title: String,
    val path: String,
    val sessions: List<DshSession>,
)

internal data class DshDirectoryEntry(
    val name: String,
    val path: String,
    val hidden: Boolean,
)

internal data class DshDirectoryListing(
    val path: String,
    val home: String,
    val crumbs: List<DshDirectoryEntry>,
    val entries: List<DshDirectoryEntry>,
    val truncated: Boolean,
)

internal data class DshPendingApproval(
    val rpcId: String,
    val sessionId: String,
    val approvalId: String,
    val toolName: String,
    val callId: String?,
    val reason: String?,
    val command: String? = null,
)

internal data class DshPendingQuestionOption(
    val label: String,
    val description: String,
)

internal data class DshPendingQuestionItem(
    val id: String,
    val question: String,
    val header: String,
    val detail: String,
    val options: List<DshPendingQuestionOption>,
    val multiSelect: Boolean,
)

internal data class DshPendingQuestion(
    val rpcId: String,
    val sessionId: String,
    val questions: List<DshPendingQuestionItem>,
)

internal data class DshQuestionDraft(
    val selected: List<String> = emptyList(),
    val custom: String = "",
    val skipped: Boolean = false,
)

internal fun DshMessage.isRuntimeContextSnapshot(): Boolean {
    return role == DshMessageRole.USER &&
        content.startsWith("Current runtime context. This snapshot supersedes earlier runtime-context snapshots.")
}

internal data class DshCredentialSetup(
    val providerAvailable: Boolean,
    val configured: Boolean,
    val writable: Boolean,
    val credentialRef: String = "DEEPSEEK_API_KEY",
)

internal data class DshModelOption(
    val provider: String,
    val providerName: String,
    val model: String,
    val name: String,
    val description: String = "",
    val reasoningEffort: String? = null,
    val selected: Boolean = false,
)

internal data class DshSkill(
    val name: String,
    val description: String,
    val whenToUse: String = "",
    val modelInvocable: Boolean = true,
)

internal data class DshGoalSnapshot(
    val id: String,
    val revision: Int,
    val objective: String,
    val phase: String,
    val blockedReason: String = "",
)

internal data class DshSessionModels(
    val current: DshModelOption,
    val options: List<DshModelOption>,
    val routable: Boolean,
)

internal interface DshStreamHandle {
    fun cancel()
}

internal interface DshRepository {
    fun loadCredentialSetup(
        onSuccess: (DshCredentialSetup) -> Unit,
        onError: (String) -> Unit,
    )

    fun saveDeepSeekApiKey(
        apiKey: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit,
    )

    fun loadModels(
        sessionId: String,
        onSuccess: (DshSessionModels) -> Unit,
        onError: (String) -> Unit,
    )

    fun selectModel(
        sessionId: String,
        option: DshModelOption,
        onSuccess: (DshModelOption) -> Unit,
        onError: (String) -> Unit,
    )

    fun loadSessions(
        onSuccess: (List<DshSession>) -> Unit,
        onError: (String) -> Unit,
    )

    fun createSession(
        workspaceId: String?,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit,
    )

    fun loadHistory(
        sessionId: String,
        onSuccess: (List<DshMessage>) -> Unit,
        onError: (String) -> Unit,
    )
    fun streamReply(
        pagerId: String,
        sessionId: String,
        prompt: String,
        onDelta: (String, Boolean) -> Unit,
        onComplete: (String) -> Unit,
        onError: (String) -> Unit,
    ): DshStreamHandle
}
