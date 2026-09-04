package com.example.dsh.dsh

import com.tencent.kuikly.core.module.NetworkModule
import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

/**
 * Remote-only Host repository. Local mode intentionally keeps the legacy
 * repository and its transport behavior unchanged.
 */
internal class DshRemoteRepository(
    network: NetworkModule,
    webSocket: DshWebSocketModule,
    connection: DshHostConnection,
    pagerId: String,
    onState: (DshHostRuntimeState) -> Unit = {},
    onQueueSnapshot: (String) -> Unit = {},
    onJobsSnapshot: (String) -> Unit = {},
    onSessionStatus: (String, Boolean) -> Unit = { _, _ -> },
    onProjection: (String, String, String, Int) -> Unit = { _, _, _, _ -> },
    onSessionEvent: (String, DshRawSessionEvent) -> Unit = { _, _ -> },
    onRemoteEvent: (String) -> Unit = {},
    onArchivedSessionsChanged: () -> Unit = {},
    onPendingInteraction: (String) -> Unit = {},
) : DshRepository {
    private val delegate = DshRemoteHostRepository(
        network,
        webSocket,
        connection,
        pagerId,
        onState,
        onQueueSnapshot = onQueueSnapshot,
        onJobsSnapshot = onJobsSnapshot,
        onSessionStatus = onSessionStatus,
        onProjection = onProjection,
        onSessionEvent = onSessionEvent,
        onRemoteEvent = onRemoteEvent,
        onArchivedSessionsChanged = onArchivedSessionsChanged,
        onPendingInteraction = onPendingInteraction,
    )
    internal val store get() = delegate.store

    fun isProductReady(): Boolean = delegate.isProductReady()
    fun stop() = delegate.stop()
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

    fun adoptLiveStream(
        sessionId: String,
        onDelta: (String, Boolean) -> Unit,
        onComplete: (String) -> Unit,
        onError: (String) -> Unit,
    ): DshStreamHandle = delegate.adoptLiveStream(sessionId, onDelta, onComplete, onError)

    fun detachLiveStreams(sessionId: String) = delegate.detachLiveStreams(sessionId)

    fun loadSkills(sessionId: String, onSuccess: (List<DshSkill>) -> Unit, onError: (String) -> Unit = {}) =
        delegate.loadSkills(sessionId, onSuccess, onError)

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
        action: com.tencent.kuikly.core.nvi.serialization.json.JSONObject,
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

    override fun loadHistory(sessionId: String, onSuccess: (List<DshMessage>) -> Unit, onError: (String) -> Unit) =
        delegate.loadHistory(sessionId, onSuccess, onError)

    fun streamReply(
        pagerId: String,
        sessionId: String,
        prompt: String,
        onDelta: (String) -> Unit,
        onComplete: (String) -> Unit,
        onError: (String) -> Unit,
    ): DshStreamHandle = delegate.streamReply(pagerId, sessionId, prompt, onDelta, onComplete, onError)

    override fun streamReply(
        pagerId: String,
        sessionId: String,
        prompt: String,
        onDelta: (String, Boolean) -> Unit,
        onComplete: (String) -> Unit,
        onError: (String) -> Unit,
    ): DshStreamHandle = delegate.streamReply(pagerId, sessionId, prompt, onDelta, onComplete, onError)
}
