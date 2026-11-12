package io.github.yukiykchen.dsh.feature.home

import io.github.yukiykchen.dsh.feature.home.DshHomeSessionCoordinator.SelectionResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DshHomeSessionCoordinatorTest {
    private val coordinator = DshHomeSessionCoordinator(panelLimit = 2, cacheWarmLimit = 2)

    @Test fun evictionKeepsTheActivePanelAndStopsBelowLimit() {
        assertEquals("old", coordinator.evictionTarget(listOf("active", "old"), "active"))
        assertNull(coordinator.evictionTarget(listOf("active"), "active"))
    }

    @Test fun warmListSkipsActiveAndMountedSessions() {
        val ids = listOf("active", "mounted", "one", "two", "three")
        assertEquals(listOf("one", "two"), coordinator.sessionsToWarm(ids, "active", setOf("active", "mounted")))
    }

    @Test fun selectionWaitsForDataThenPanelBeforeMountedSwitch() {
        assertEquals(SelectionResult.WAIT_FOR_DATA, coordinator.selectionResult("next", "active", emptySet(), setOf("next")))
        assertEquals(SelectionResult.WAIT_FOR_PANEL, coordinator.selectionResult("next", "active", setOf("next"), emptySet()))
        assertEquals(SelectionResult.READY, coordinator.selectionResult("next", "active", setOf("next"), setOf("next")))
    }
}
