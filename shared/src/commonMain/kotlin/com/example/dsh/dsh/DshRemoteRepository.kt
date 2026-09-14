package com.example.dsh.dsh

import com.tencent.kuikly.core.module.NetworkModule
import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

/**
 * Page-facing facade over [DshRemoteHostRepository]. Scan / SSH / direct
 * connections all speak the same 0.1.5 Host protocol; only how the phone
 * reaches `baseUrl` and how it obtains the launch token differ.
 */
internal class DshRemoteRepository(
    network: NetworkModule,
    webSocket: DshWebSocketModule,
    connection: DshHostConnection,
    auth: DshHostAuthenticator,
    pagerId: String,
    onState: (DshHostRuntimeState) -> Unit = {},
    onQueueSnapshot: (String) -> Unit = {},
    onJobsSnapshot: (String) -> Unit = {},
    onSessionStatus: (String, Boolean) -> Unit = { _, _ -> },
    onProjection: (String, String, String, Int) -> Unit = { _, _, _, _ -> },
    onSessionEvent: (String, DshRawSessionEvent) -> Unit = { _, _ -> },
    onRemoteEvent: (String) -> Unit = {},
    onPendingInteraction: (String) -> Unit = {},
    onSessionsChanged: () -> Unit = {},
    onSessionError: (String, String) -> Unit = { _, _ -> },
) : DshRepository {
    private val delegate = DshRemoteHostRepository(
        network,
        webSocket,
        connection,
        auth,
        pagerId,
        onState,
        onQueueSnapshot = onQueueSnapshot,
        onJobsSnapshot = onJobsSnapshot,
        onSessionStatus = onSessionStatus,
        onProjection = onProjection,
        onSessionEvent = onSessionEvent,
        onRemoteEvent = onRemoteEvent,
        onPendingInteraction = onPendingInteraction,
        onSessionsChanged = onSessionsChanged,
        onSessionError = onSessionError,
    )
    internal val store get() = delegate.store

    fun currentConnectionState(): DshHostRuntimeState = delegate.currentConnectionState()
    fun isProductReady(): Boolean = delegate.isProductReady()
    fun retryAuthentication() = delegate.retryAuthentication()
    fun stop() = delegate.stop()
    fun imageLimits(): DshImageLimits? = delegate.imageLimits()

    fun respondApproval(
        rpcId: String,
        sessionId: String,
        approvalId: String,
        outcome: String,
        callback: (Boolean, String) -> Unit,
    ) = delegate.respondApproval(rpcId, sessionId, approvalId, outcome, callback)

    fun respondQuestion(
        rpcId: String,
        sessionId: String,
        answer: JSONObject,
        callback: (Boolean, String) -> Unit,
    ) = delegate.respondQuestion(rpcId, sessionId, answer, callback)

    fun clearPending(rpcId: String) = delegate.clearPending(rpcId)

    fun loadWebTimeline(
        sessionId: String,
        onSuccess: (List<DshWebTimelineItem>) -> Unit,
        onError: (String) -> Unit = {},
    ) = delegate.loadWebTimeline(sessionId, onSuccess, onError)

    fun trimFollows(keep: Set<String>) = delegate.trimFollows(keep)

    fun adoptLiveStream(
        sessionId: String,
        onDelta: (String, Boolean) -> Unit,
        onComplete: (String) -> Unit,
        onError: (String) -> Unit,
    ): DshStreamHandle = delegate.adoptLiveStream(sessionId, onDelta, onComplete, onError)

    fun detachLiveStreams(sessionId: String) = delegate.detachLiveStreams(sessionId)

    fun cancelSession(sessionId: String) = delegate.cancelSession(sessionId)

    fun loadSkills(sessionId: String, onSuccess: (List<DshSkill>) -> Unit, onError: (String) -> Unit = {}) =
        delegate.loadSkills(sessionId, onSuccess, onError)

    fun loadPluginInventory(onSuccess: (DshPluginInventory) -> Unit, onError: (DshRpcError) -> Unit) =
        delegate.loadPluginInventory(onSuccess, onError)

    fun probePluginAdmin(onResult: (Set<String>) -> Unit) = delegate.probePluginAdmin(onResult)

    fun controlPlugin(entryId: String, action: String, confirm: Boolean, callback: (DshRpcError?) -> Unit) =
        delegate.controlPlugin(entryId, action, confirm, callback)

    fun goalEdit(sessionId: String, goal: DshGoalSnapshot, objective: String, callback: (DshRpcError?) -> Unit) =
        delegate.goalEdit(sessionId, goal, objective, callback)

    fun goalPause(sessionId: String, goal: DshGoalSnapshot, callback: (DshRpcError?) -> Unit) =
        delegate.goalPause(sessionId, goal, callback)

    fun goalResume(sessionId: String, goal: DshGoalSnapshot, callback: (DshRpcError?) -> Unit) =
        delegate.goalResume(sessionId, goal, callback)

    fun goalClear(sessionId: String, goal: DshGoalSnapshot, callback: (DshRpcError?) -> Unit) =
        delegate.goalClear(sessionId, goal, callback)

    fun loadAttachment(
        sessionId: String,
        attachmentId: String,
        callback: (String?, String?) -> Unit,
    ) = delegate.loadAttachment(sessionId, attachmentId, callback)

    companion object {
        fun parseWebTimelineForTest(events: JSONArray): List<DshWebTimelineItem> =
            DshWebTimelineParser.parseWebTimeline(events)
    }

    fun queue(sessionId: String): List<DshQueueItem> = delegate.queue(sessionId)

    fun jobs(sessionId: String): List<DshJobItem> = delegate.jobs(sessionId)

    fun workspaceGroups(): List<DshWorkspaceGroup> = delegate.workspaceGroups()

    fun archivedSessions(): List<DshSession> = delegate.archivedSessions()

    fun workspaceIdForSession(sessionId: String): String? = delegate.workspaceIdForSession(sessionId)

    fun blankSessionInWorkspace(workspaceId: String?): DshSession? = delegate.blankSessionInWorkspace(workspaceId)

    fun pendingInteractions(sessionId: String): Pair<DshPendingApproval?, DshPendingQuestion?> =
        delegate.pendingInteractions(sessionId)

    fun updateQueue(
        sessionId: String,
        itemId: String,
        action: JSONObject,
        callback: (JSONObject?, DshRpcError?) -> Unit,
    ) = delegate.updateQueue(sessionId, itemId, action, callback)

    fun renameSession(
        sessionId: String,
        title: String,
        callback: (JSONObject?, DshRpcError?) -> Unit,
    ) = delegate.renameSession(sessionId, title, callback)

    fun archiveSession(
        sessionId: String,
        callback: (JSONObject?, DshRpcError?) -> Unit,
    ) = delegate.archiveSession(sessionId, callback)

    fun forkSession(
        sessionId: String,
        atSeq: Int?,
        callback: (JSONObject?, DshRpcError?) -> Unit,
    ) = delegate.forkSession(sessionId, atSeq, callback)

    fun sessionExportUrl(sessionId: String): String = delegate.sessionExportUrl(sessionId)

    fun listDirectory(
        path: String?,
        callback: (DshDirectoryListing?, DshRpcError?) -> Unit,
    ) = delegate.listDirectory(path, callback)

    fun createDirectory(
        path: String,
        name: String,
        callback: (String?, DshRpcError?) -> Unit,
    ) = delegate.createDirectory(path, name, callback)

    fun createWorkspace(
        path: String,
        callback: (JSONObject?, DshRpcError?) -> Unit,
    ) = delegate.createWorkspace(path, callback)

    fun renameWorkspace(
        workspaceId: String,
        title: String,
        callback: (JSONObject?, DshRpcError?) -> Unit,
    ) = delegate.renameWorkspace(workspaceId, title, callback)

    fun deleteWorkspace(
        workspaceId: String,
        callback: (JSONObject?, DshRpcError?) -> Unit,
    ) = delegate.deleteWorkspace(workspaceId, callback)

    fun moveWorkspaceBefore(
        workspaceId: String,
        beforeWorkspaceId: String?,
        callback: (JSONObject?, DshRpcError?) -> Unit,
    ) = delegate.moveWorkspaceBefore(workspaceId, beforeWorkspaceId, callback)

    override fun loadCredentialSetup(onSuccess: (DshCredentialSetup) -> Unit, onError: (String) -> Unit) =
        delegate.loadCredentialSetup(onSuccess, onError)

    override fun saveDeepSeekApiKey(apiKey: String, onSuccess: () -> Unit, onError: (String) -> Unit) =
        delegate.saveDeepSeekApiKey(apiKey, onSuccess, onError)

    override fun loadModels(sessionId: String, onSuccess: (DshSessionModels) -> Unit, onError: (String) -> Unit) =
        delegate.loadModels(sessionId, onSuccess, onError)

    override fun selectModel(sessionId: String, option: DshModelOption, onSuccess: (DshModelOption) -> Unit, onError: (String) -> Unit) =
        delegate.selectModel(sessionId, option, onSuccess, onError)

    override fun loadSessions(onSuccess: (List<DshSession>) -> Unit, onError: (String) -> Unit) =
        delegate.loadSessions(onSuccess, onError)

    override fun createSession(workspaceId: String?, onSuccess: (String) -> Unit, onError: (String) -> Unit) =
        delegate.createSession(workspaceId, onSuccess, onError)

    override fun streamReply(
        pagerId: String,
        sessionId: String,
        prompt: String,
        onDelta: (String, Boolean) -> Unit,
        onComplete: (String) -> Unit,
        onError: (String) -> Unit,
    ): DshStreamHandle = delegate.streamReply(pagerId, sessionId, prompt, onDelta, onComplete, onError)

    fun streamReply(
        pagerId: String,
        sessionId: String,
        prompt: String,
        images: List<DshOutgoingImage>,
        onDelta: (String, Boolean) -> Unit,
        onComplete: (String) -> Unit,
        onError: (String) -> Unit,
        onAccepted: () -> Unit = {},
    ): DshStreamHandle =
        delegate.streamReply(pagerId, sessionId, prompt, images, onDelta, onComplete, onError, onAccepted)
}

/**
 * Authenticator backed by the page's local store: the cookie is persisted per
 * connection scope, the launch token comes from the SSH profile or, for scan
 * connections, from the relay plugin's `/dsh-scan-remote/api/auth` route.
 */
internal class DshStoredHostAuthenticator(
    private val scopeKey: String,
    private val store: DshLocalStore?,
    initialToken: String,
    override val relayTokenAvailable: Boolean,
    private val mintCookie: (baseUrl: String, launchToken: String, bearer: String, callback: (String?, String?) -> Unit) -> Unit,
    private val onTokenDiscovered: (String) -> Unit = {},
) : DshHostAuthenticator {
    private var token = initialToken
    private var cookie = runCatching { store?.loadSetting(cookieKey(scopeKey)) }.getOrNull().orEmpty()

    override fun cookie(): String = cookie

    override fun launchToken(): String = token

    fun updateLaunchToken(value: String) {
        token = value
    }

    override fun onLaunchToken(token: String) {
        this.token = token
        onTokenDiscovered(token)
    }

    override fun onCookie(cookie: String) {
        this.cookie = cookie
        runCatching { store?.saveSetting(cookieKey(scopeKey), cookie) }
    }

    override fun mint(baseUrl: String, launchToken: String, bearer: String, callback: (String?, String?) -> Unit) {
        mintCookie(baseUrl, launchToken, bearer, callback)
    }

    companion object {
        fun cookieKey(scopeKey: String): String = "auth_cookie:$scopeKey"
    }
}
