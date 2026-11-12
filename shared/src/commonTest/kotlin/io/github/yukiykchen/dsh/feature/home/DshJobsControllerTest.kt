package io.github.yukiykchen.dsh.feature.home

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DshJobsControllerTest {
    @Test
    fun repeatedRefreshKeepsOnlyOneActiveClock() {
        val f = Fixture()
        f.controller.toggle()
        f.controller.refresh()
        f.controller.refresh()
        assertEquals(1, f.timers.size)
    }

    @Test
    fun oldClockCannotAdvanceOrRescheduleAReopenedPanel() {
        val f = Fixture()
        f.controller.toggle()
        f.controller.toggle()
        f.controller.toggle()
        val calls = f.timeReads
        f.runTimer()
        assertEquals(calls, f.timeReads)
        assertEquals(1, f.timers.size)
        f.runTimer()
        assertEquals(calls + 1, f.timeReads)
        assertEquals(1, f.timers.size)
    }

    @Test
    fun sessionSwitchInvalidatesItsOldClockAndCollapsesTheNextPanel() {
        val f = Fixture()
        f.controller.toggle()
        f.page.sessionId = "s2"
        f.jobs()
        f.controller.refresh()
        assertFalse(f.controller.expanded)
        f.controller.toggle()
        val calls = f.timeReads
        f.runTimer()
        assertEquals(calls, f.timeReads)
        f.runTimer()
        assertEquals(calls + 1, f.timeReads)
    }

    @Test
    fun finishedJobsStopTheClockWithoutAnotherBridgeRead() {
        val f = Fixture()
        f.controller.toggle()
        val calls = f.timeReads
        f.jobs("done")
        f.controller.refresh()
        f.runTimer()
        assertEquals(calls, f.timeReads)
        assertTrue(f.timers.isEmpty())
    }

    @Test
    fun disconnectedClientClearsThePanelAndStopsItsClock() {
        val f = Fixture()
        f.controller.toggle()
        val calls = f.timeReads
        f.page.client = null
        f.controller.refresh()
        f.runTimer()
        assertEquals(calls, f.timeReads)
        assertFalse(f.controller.expanded)
        assertTrue(f.controller.items.isEmpty())
    }

    @Test
    fun disposalStopsTimersAndRejectsFutureCommands() {
        val f = Fixture()
        f.controller.toggle()
        val calls = f.timeReads
        f.controller.dispose()
        f.runTimer()
        f.controller.toggle()
        f.controller.refresh()
        assertEquals(calls, f.timeReads)
        assertTrue(f.timers.isEmpty())
    }

    private class Fixture {
        val page = ControllerTestFixture()
        val timers = mutableListOf<() -> Unit>()
        var timeReads = 0
        val controller = DshJobsController(
            ctx = page.ctx,
            currentTime = { timeReads++; 1234L },
            schedule = { _, callback -> timers.add(callback) },
        )
        init { jobs(); controller.refresh() }
        fun jobs(status: String = "running") {
            page.client!!.store.replaceJobs(page.sessionId, """[{"id":"j1","status":"$status","startedAt":0}]""")
        }
        fun runTimer() { timers.removeAt(0)() }
    }
}
