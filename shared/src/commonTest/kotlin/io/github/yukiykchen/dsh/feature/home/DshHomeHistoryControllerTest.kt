package io.github.yukiykchen.dsh.feature.home

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DshHomeHistoryControllerTest {
    @Test
    fun newerHistoryRequestRejectsOlderSuccessAndFailure() {
        val f = Fixture()
        f.controller.loadTimeline("s1", afterApply = { f.effects.add("old-after") })
        val old = f.page.request("session.history")
        f.controller.loadTimeline("s1", false, true) { f.effects.add("after") }
        old.reply()
        old.fail()
        assertTrue(f.effects.isEmpty())
        f.page.request("session.history").reply()
        assertEquals(listOf("timeline:s1:false:true", "after"), f.effects)
    }

    @Test
    fun skillsAndHistoryDoNotInvalidateEachOthersRequests() {
        val f = Fixture()
        f.controller.loadTimeline("s1")
        f.controller.loadSkills("s1")
        f.page.request("skill.list").reply("""{"skills":[{"name":"review"}]}""")
        f.page.request("session.history").reply()
        assertEquals("review", f.controller.skills.single().name)
        assertEquals(listOf("timeline:s1:true:false"), f.effects)
    }

    @Test
    fun switchedSessionAndInvalidatedHistoryRejectLateEffects() {
        val f = Fixture()
        f.controller.loadTimeline("s1") { f.effects.add("after") }
        f.controller.loadSkills("s1")
        f.page.sessionId = "s2"
        f.page.request("session.history").reply()
        f.page.request("skill.list").reply("""{"skills":[{"name":"stale"}]}""")
        assertTrue(f.effects.isEmpty())
        assertTrue(f.controller.skills.isEmpty())
        f.controller.loadTimeline("s2")
        f.controller.invalidateHistory()
        f.page.request("session.history").reply()
        assertTrue(f.effects.isEmpty())
    }

    @Test
    fun acceptedFailureRunsRecoveryBeforeContinuation() {
        val f = Fixture()
        f.controller.loadTimeline("s1", forceReplace = true) { f.effects.add("after") }
        f.page.request("session.history").fail()
        assertEquals(listOf("failure:s1:true", "after"), f.effects)
    }

    @Test
    fun reentrantNavigationDuringApplyCannotResumeTheOldTurn() {
        val f = Fixture()
        f.onApply = { f.page.sessionId = "s2" }
        f.controller.loadTimeline("s1") { f.effects.add("old-turn") }
        f.page.request("session.history").reply()
        assertEquals(listOf("timeline:s1:true:false"), f.effects)
    }

    @Test
    fun reentrantHistoryReloadDuringFailureCannotContinueTheSupersededRequest() {
        val f = Fixture()
        f.onFailure = { f.controller.loadTimeline("s1") }
        f.controller.loadTimeline("s1") { f.effects.add("old-turn") }
        f.page.request("session.history").fail()
        assertEquals(listOf("failure:s1:false"), f.effects)
    }

    @Test
    fun disconnectionAndDisposalRejectLateCallbacksAndNewWork() {
        val f = Fixture()
        f.controller.loadTimeline("s1")
        f.controller.loadSkills("s1")
        f.page.transport.sockets.first().disconnect()
        f.page.request("session.history").reply()
        assertTrue(f.effects.isEmpty())
        f.controller.dispose()
        f.page.request("skill.list").reply("""{"skills":[{"name":"stale"}]}""")
        val count = f.page.transport.requests.size
        f.controller.loadSkills("s1")
        f.controller.loadTimeline("s1")
        assertEquals(count, f.page.transport.requests.size)
        assertTrue(f.controller.skills.isEmpty())
    }

    private class Fixture {
        val page = ControllerTestFixture()
        val effects = mutableListOf<String>()
        var onApply: () -> Unit = {}
        var onFailure: () -> Unit = {}
        val controller = DshHomeHistoryController(
            ctx = page.ctx,
            onTimeline = { sessionId, _, scroll, force ->
                effects.add("timeline:$sessionId:$scroll:$force")
                onApply()
            },
            onFailure = { sessionId, force ->
                effects.add("failure:$sessionId:$force")
                onFailure()
            },
        )
    }
}
