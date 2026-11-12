package io.github.yukiykchen.dsh.feature.home

import io.github.yukiykchen.dsh.model.DshHostRuntimePhase
import io.github.yukiykchen.dsh.model.DshHostRuntimeState
import io.github.yukiykchen.dsh.model.DshRawSessionEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DshHomeHostCallbacksTest {
    @Test
    fun invalidatedClientCannotDispatchAnyPageEffects() {
        val f = Fixture()
        f.accepted = false
        f.callbacks.onState(DshHostRuntimeState(DshHostRuntimePhase.STOPPED, 1))
        f.callbacks.onQueueSnapshot("s1")
        f.callbacks.onJobsSnapshot("s1")
        f.callbacks.onSessionStatus("s1", true)
        f.callbacks.onProjection("s1", "goal", "{}", 1)
        f.callbacks.onSessionEvent("s1", DshRawSessionEvent(1, "tool/call", "{}"))
        f.callbacks.onRemoteEvent("skills/change")
        f.callbacks.onPendingInteraction("s1")
        assertTrue(f.effects.isEmpty())
    }

    @Test
    fun acceptedClientStillScopesSessionEffectsAndRefreshesCatalogs() {
        val f = Fixture()
        f.callbacks.onQueueSnapshot("other")
        f.callbacks.onSessionEvent("other", DshRawSessionEvent(1, "tool/call", "{}"))
        assertTrue(f.effects.isEmpty())
        f.callbacks.onQueueSnapshot("s1")
        f.callbacks.onRemoteEvent("skills/change")
        assertEquals(listOf("queue", "interactions", "skills", "models"), f.effects)
    }

    private class Fixture {
        var accepted = true
        val effects = mutableListOf<String>()
        val callbacks = createHomeHostCallbacks(
            activeSessionId = { "s1" },
            handleRuntimeState = { effects.add("state") },
            refreshQueue = { effects.add("queue") },
            refreshInteractions = { effects.add("interactions") },
            refreshJobs = { effects.add("jobs") },
            sessionRunning = { false },
            setSessionRunning = { effects.add("running") },
            resyncStreaming = { _, _ -> effects.add("resync") },
            syncTurnStatus = { effects.add("status") },
            setConnectionLabel = { effects.add("label") },
            applyGoalProjection = { effects.add("goal") },
            showRunningTool = { effects.add("tool") },
            settleRunningTool = { effects.add("result") },
            showContextInjection = { effects.add("context") },
            showAssistantBlocks = { effects.add("assistant") },
            loadSkills = { effects.add("skills") },
            loadModels = { effects.add("models") },
            loadWebTimeline = { effects.add("timeline") },
            acceptsClient = { accepted },
        )
    }
}
