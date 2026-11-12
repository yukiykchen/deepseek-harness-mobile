package io.github.yukiykchen.dsh.feature.home

import io.github.yukiykchen.dsh.framework.DshStreamLog
import io.github.yukiykchen.dsh.model.DshHostRuntimeState
import io.github.yukiykchen.dsh.model.DshRawSessionEvent
import io.github.yukiykchen.dsh.data.isRemoteCatalogInvalidationEvent

/** Callbacks invoked by the Host client and forwarded to the home page. */
data class DshHomeHostCallbacks(
    val onState: (DshHostRuntimeState) -> Unit,
    val onQueueSnapshot: (String) -> Unit,
    val onJobsSnapshot: (String) -> Unit,
    val onSessionStatus: (String, Boolean) -> Unit,
    val onProjection: (String, String, String, Int) -> Unit,
    val onSessionEvent: (String, DshRawSessionEvent) -> Unit,
    val onRemoteEvent: (String) -> Unit,
    val onPendingInteraction: (String) -> Unit,
)

fun createHomeHostCallbacks(
    activeSessionId: () -> String,
    handleRuntimeState: (DshHostRuntimeState) -> Unit,
    refreshQueue: () -> Unit,
    refreshInteractions: () -> Unit,
    refreshJobs: () -> Unit,
    sessionRunning: () -> Boolean,
    setSessionRunning: (Boolean) -> Unit,
    resyncStreaming: (String, String) -> Unit,
    syncTurnStatus: () -> Unit,
    setConnectionLabel: (String) -> Unit,
    applyGoalProjection: (String) -> Unit,
    showRunningTool: (DshRawSessionEvent) -> Unit,
    settleRunningTool: (DshRawSessionEvent) -> Unit,
    showContextInjection: (DshRawSessionEvent) -> Unit,
    showAssistantBlocks: (DshRawSessionEvent) -> Unit,
    loadSkills: (String) -> Unit,
    loadModels: (String) -> Unit,
    loadWebTimeline: (String) -> Unit,
    acceptsClient: () -> Boolean = { true },
): DshHomeHostCallbacks = DshHomeHostCallbacks(
    onState = { state -> if (acceptsClient()) handleRuntimeState(state) },
    onQueueSnapshot = { sessionId ->
        if (acceptsClient() && sessionId == activeSessionId()) {
            refreshQueue()
            refreshInteractions()
        }
    },
    onJobsSnapshot = { sessionId ->
        if (acceptsClient() && sessionId == activeSessionId()) refreshJobs()
    },
    onSessionStatus = { sessionId, running ->
        if (acceptsClient() && sessionId == activeSessionId()) {
            val wasRunning = sessionRunning()
            setSessionRunning(running)
            if (wasRunning != running) {
                resyncStreaming(
                    sessionId,
                    if (running) "host-session-running" else "host-session-idle",
                )
            }
            syncTurnStatus()
        }
    },
    onProjection = { sessionId, key, value, seq ->
        if (acceptsClient() && sessionId == activeSessionId()) {
            when (key) {
                "title" -> {
                    val title = value.trim().removeSurrounding("\"")
                    if (title.isNotEmpty()) setConnectionLabel(title)
                }
                "goal" -> applyGoalProjection(value)
            }
        }
    },
    onSessionEvent = { sessionId, event ->
        if (acceptsClient() && sessionId == activeSessionId()) {
            when (event.type) {
                "tool/call" -> showRunningTool(event)
                "tool/result" -> settleRunningTool(event)
                "user/message" -> showContextInjection(event)
                "assistant/message" -> showAssistantBlocks(event)
            }
        }
    },
    onRemoteEvent = { event ->
        if (acceptsClient() && activeSessionId().isNotEmpty() && isRemoteCatalogInvalidationEvent(event)) {
            loadSkills(activeSessionId())
            loadModels(activeSessionId())
        }
    },
    onPendingInteraction = { sessionId ->
        DshStreamLog.question("ui.pending-frame session=$sessionId active=${activeSessionId()}")
        if (acceptsClient() && sessionId == activeSessionId()) {
            refreshInteractions()
            loadWebTimeline(sessionId)
        }
    },
)
