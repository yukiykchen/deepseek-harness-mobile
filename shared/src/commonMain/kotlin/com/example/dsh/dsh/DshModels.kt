package com.example.dsh.dsh

internal enum class DshConnectionMode {
    LOCAL,
    RELAY,
    SSH,
    /** Developer mode: a plain `http://host:port` reachable from the phone (mock Host, LAN DSH). */
    DIRECT,
}

internal data class DshSessionScope(
    val mode: DshConnectionMode,
    val profileId: String? = null,
) {
    val storageKey: String
        get() = when (mode) {
            DshConnectionMode.LOCAL -> LOCAL_STORAGE_KEY
            DshConnectionMode.RELAY -> "relay:${profileId ?: "default"}"
            DshConnectionMode.SSH -> "ssh:${profileId ?: DEFAULT_REMOTE_PROFILE_ID}"
            DshConnectionMode.DIRECT -> "direct:${profileId ?: "default"}"
        }

    companion object {
        const val DEFAULT_REMOTE_PROFILE_ID = "default"
        const val LOCAL_STORAGE_KEY = "local"
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
    /** The `?token=` printed by `dsh web`; SSH has no plugin to discover it. */
    val authToken: String = "",
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
    /** No browser-session cookie and no launch token to mint one; the user must supply the token. */
    AUTH_REQUIRED,
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

/** `imageLimits` projection issued by the Host; enforced before `session/prompt`. */
internal data class DshImageLimits(
    val maxImageBytes: Long,
    val maxImagesPerMessage: Int,
    val maxMessageImageBytes: Long,
    val maxImagePixels: Long,
    val maxImageDimension: Int,
    val mediaTypes: List<String>,
)

/** Durable `ImageAttachmentRef`: what the conversation log keeps instead of bytes. */
internal data class DshImageAttachmentRef(
    val attachmentId: String,
    val mediaType: String,
    val bytes: Long,
    val width: Int,
    val height: Int,
    val name: String? = null,
    /**
     * Client-only key for an image the phone has sent but the Host has not yet
     * committed, so the optimistic user bubble can paint the local preview. Empty
     * on every reference that came from the Host.
     */
    val localId: String = "",
) {
    /** Key the preview cache is read with: the durable id once there is one. */
    val previewKey: String get() = attachmentId.ifEmpty { localId }
}

/** One image about to be sent as an official `{type:"image"}` prompt part. */
internal data class DshOutgoingImage(
    val mediaType: String,
    val base64: String,
    val name: String? = null,
    val bytes: Long = 0,
    val width: Int = 0,
    val height: Int = 0,
)

/** Lifecycle of one image staged in the composer. */
internal enum class DshAttachmentState { PENDING, UPLOADING, SENT, FAILED }

/**
 * One image staged in the composer strip. [localId] survives a retry so the same
 * thumbnail keeps its place, and it doubles as the key the sent user bubble reads
 * its preview from until the Host's durable `attachmentId` replaces it.
 */
internal data class DshStagedAttachment(
    val localId: String,
    val image: DshOutgoingImage,
    val state: DshAttachmentState = DshAttachmentState.PENDING,
    val error: String = "",
) {
    val name: String get() = image.name?.takeIf { it.isNotEmpty() } ?: image.mediaType
    val dataUrl: String get() = "data:${image.mediaType};base64,${image.base64}"

    /**
     * The staged image as the reference a user bubble renders before the Host has
     * committed one. An empty `attachmentId` marks it as not yet durable.
     */
    fun toLocalRef(): DshImageAttachmentRef = DshImageAttachmentRef(
        attachmentId = "",
        mediaType = image.mediaType,
        bytes = image.bytes,
        width = image.width,
        height = image.height,
        name = image.name,
        localId = localId,
    )
}

internal data class DshPluginEntry(
    val entryId: String,
    val moduleName: String,
    val enabled: Boolean,
    /** `pending`, `loading`, `active`, `failed`, `unloading`, or null without a live fiber. */
    val fiberPhase: String?,
)

internal data class DshAgentPresetPluginRow(
    val entryId: String?,
    val moduleName: String,
    /** `true`, `false`, or `conditional`. */
    val enabled: String,
    val condition: String?,
    val fiberPhase: String?,
)

internal data class DshAgentPresetPlugins(
    val id: String,
    val trust: String,
    val name: String?,
    val isDefault: Boolean,
    val broken: String?,
    val rows: List<DshAgentPresetPluginRow>,
)

internal data class DshPluginInventory(
    val entries: List<DshPluginEntry>,
    val agentPresets: List<DshAgentPresetPlugins>?,
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

    fun replaceArchivedSessionIds(archived: Set<String>) {
        archivedSessionIds = archived
    }

    /** `workspace/follow` upsert: replace the row in place or append it. */
    fun upsertWorkspace(workspace: com.tencent.kuikly.core.nvi.serialization.json.JSONObject) {
        val id = workspace.optString("workspaceId")
        if (id.isEmpty()) return
        val current = runCatching { com.tencent.kuikly.core.nvi.serialization.json.JSONArray(workspaceBaseline) }.getOrNull()
            ?: com.tencent.kuikly.core.nvi.serialization.json.JSONArray()
        val result = com.tencent.kuikly.core.nvi.serialization.json.JSONArray()
        var replaced = false
        for (index in 0 until current.length()) {
            val existing = current.optJSONObject(index) ?: continue
            if (existing.optString("workspaceId") == id) {
                result.put(workspace)
                replaced = true
            } else {
                result.put(existing)
            }
        }
        if (!replaced) result.put(workspace)
        workspaceBaseline = result.toString()
    }

    fun removeWorkspace(workspaceId: String) {
        if (workspaceId.isEmpty()) return
        val current = runCatching { com.tencent.kuikly.core.nvi.serialization.json.JSONArray(workspaceBaseline) }.getOrNull() ?: return
        val result = com.tencent.kuikly.core.nvi.serialization.json.JSONArray()
        for (index in 0 until current.length()) {
            val existing = current.optJSONObject(index) ?: continue
            if (existing.optString("workspaceId") != workspaceId) result.put(existing)
        }
        workspaceBaseline = result.toString()
    }

    fun removeSession(sessionId: String) {
        sessions.remove(sessionId)
        sessionEvents.remove(sessionId)
        sessionLastSeq.remove(sessionId)
        queueSnapshots.remove(sessionId)
        jobSnapshots.remove(sessionId)
        projections.remove(sessionId)
    }

    fun workspaceSessionIds(workspaceId: String): List<String> {
        val workspaces = runCatching { com.tencent.kuikly.core.nvi.serialization.json.JSONArray(workspaceBaseline) }.getOrNull()
            ?: return emptyList()
        for (index in 0 until workspaces.length()) {
            val workspace = workspaces.optJSONObject(index) ?: continue
            if (workspace.optString("workspaceId") != workspaceId) continue
            val ids = workspace.optJSONArray("sessionIds") ?: return emptyList()
            return (0 until ids.length()).mapNotNull { ids.optString(it)?.takeIf { id -> id.isNotEmpty() } }
        }
        return emptyList()
    }

    fun workspaceIdForSession(sessionId: String): String? {
        val workspaces = runCatching { com.tencent.kuikly.core.nvi.serialization.json.JSONArray(workspaceBaseline) }.getOrNull()
            ?: return null
        for (index in 0 until workspaces.length()) {
            val workspace = workspaces.optJSONObject(index) ?: continue
            val ids = workspace.optJSONArray("sessionIds") ?: continue
            for (sessionIndex in 0 until ids.length()) {
                if (ids.optString(sessionIndex) == sessionId) {
                    return workspace.optString("workspaceId").takeIf { it.isNotEmpty() }
                }
            }
        }
        return null
    }

    /** Join workspaces with known sessions; blank sessions never show, archived only when asked. */
    fun workspaceGroups(includeArchived: Boolean): List<DshWorkspaceGroup> {
        val workspaces = runCatching { com.tencent.kuikly.core.nvi.serialization.json.JSONArray(workspaceBaseline) }.getOrNull()
            ?: com.tencent.kuikly.core.nvi.serialization.json.JSONArray()
        val sessionById = sessions.values
            .filterNot { it.blank || (!includeArchived && archivedSessionIds.contains(it.id)) }
            .associateBy { it.id }
        val grouped = mutableSetOf<String>()
        val groups = (0 until workspaces.length()).mapNotNull { index ->
            val workspace = workspaces.optJSONObject(index) ?: return@mapNotNull null
            val workspaceId = workspace.optString("workspaceId")
            if (workspaceId.isEmpty()) return@mapNotNull null
            val sessionIds = workspace.optJSONArray("sessionIds") ?: com.tencent.kuikly.core.nvi.serialization.json.JSONArray()
            val members = (0 until sessionIds.length()).mapNotNull { sessionIndex ->
                val sessionId = sessionIds.optString(sessionIndex)
                sessionId?.takeIf { it.isNotEmpty() }?.let(grouped::add)
                sessionById[sessionId]
            }
            DshWorkspaceGroup(
                workspaceId = workspaceId,
                title = workspace.optString("title").ifEmpty { workspaceId },
                path = workspace.optString("path"),
                sessions = members,
            )
        }
        val ungrouped = sessionById.values.filterNot { grouped.contains(it.id) }
        return if (ungrouped.isEmpty()) groups else groups + DshWorkspaceGroup("", "未归类", "", ungrouped)
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
    /** Remote-only structured tool state; LOCAL keeps this null. */
    val remoteTool: DshRemoteToolCallModel? = null,
    /** Images the user sent with this message, as durable references. */
    val attachments: List<DshImageAttachmentRef> = emptyList(),
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
        remoteTool == other.remoteTool &&
        attachments == other.attachments

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
    val attachments: List<DshImageAttachmentRef> = emptyList(),
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

    fun streamReply(
        pagerId: String,
        sessionId: String,
        prompt: String,
        onDelta: (String, Boolean) -> Unit,
        onComplete: (String) -> Unit,
        onError: (String) -> Unit,
    ): DshStreamHandle
}
