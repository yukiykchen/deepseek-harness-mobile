package io.github.yukiykchen.dsh.feature.home

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DshGoalControllerTest {
    @Test
    fun lateClearCannotEraseTheNextSessionsGoalEvenBeforeUiDispatch() {
        val f = Fixture()
        f.controller.clear()
        val old = f.page.request("goal.clear")
        f.page.sessionId = "s2"
        f.controller.clearSnapshot()
        f.controller.applyProjection(projection("next"))
        old.reply()
        assertEquals("next", f.controller.snapshot?.id)
        f.page.flush()
        assertEquals("next", f.controller.snapshot?.id)
        assertFalse(f.controller.busy)
    }

    @Test
    fun aHigherRevisionProjectionSurvivesTheOldClearAcknowledgement() {
        val f = Fixture()
        f.controller.clear()
        f.controller.applyProjection(projection("g1", 2))
        f.page.request("goal.clear").reply()
        f.page.flush()
        assertEquals(2, f.controller.snapshot?.revision)
        assertFalse(f.controller.busy)
    }

    @Test
    fun oldEditFailureCannotReleaseTheNextGoalsMutationOrInvokeItsCallback() {
        val f = Fixture()
        var done = 0
        f.controller.edit("first") { done++ }
        val old = f.page.request("goal.edit")
        f.controller.applyProjection(projection("next"))
        f.controller.pause()
        old.fail("old failure")
        f.page.flush()
        assertTrue(f.controller.busy)
        assertEquals("", f.controller.error)
        assertEquals(0, done)
        f.page.request("goal.pause").reply()
        f.page.flush()
        assertFalse(f.controller.busy)
    }

    @Test
    fun failureKeepsTheGoalAndAValidClearRemovesIt() {
        val f = Fixture()
        f.controller.clear()
        f.page.request("goal.clear").fail("denied")
        f.page.flush()
        assertEquals("g1", f.controller.snapshot?.id)
        assertTrue(f.controller.error.contains("denied"))
        f.controller.clear()
        f.page.request("goal.clear").reply()
        f.page.flush()
        assertNull(f.controller.snapshot)
    }

    @Test
    fun disposalRejectsLateCallbacksAndFutureProjectionsOrMutations() {
        val f = Fixture()
        f.controller.clear()
        f.controller.dispose()
        f.page.request("goal.clear").reply()
        f.page.flush()
        f.controller.applyProjection(projection("late"))
        f.controller.pause()
        assertNull(f.controller.snapshot)
        assertFalse(f.controller.busy)
    }

    private class Fixture {
        val page = ControllerTestFixture()
        val controller = DshGoalController(page.ctx, page::post)
        init { controller.applyProjection(projection("g1")) }
    }

    companion object {
        private fun projection(id: String, revision: Int = 1) =
            """{"goal":{"id":"$id","revision":$revision,"objective":"Objective","phase":"running"}}"""
    }
}
