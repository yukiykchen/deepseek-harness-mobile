package com.example.dsh.dsh

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DshRequestAndEventTest {
    @Test
    fun sameSessionAfterRoundTripHasDifferentRequestIdentity() {
        val before = DshRequestIdentity("A", 1, 3)
        assertNotEquals(before, DshRequestIdentity("A", 3, 3))
        assertNotEquals(before, DshRequestIdentity("A", 1, 5))
        val versions = DshRequestVersion()
        val old = versions.next()
        val latest = versions.next()
        assertFalse(versions.accepts(old))
        assertTrue(versions.accepts(latest))
        versions.invalidate()
        assertFalse(versions.accepts(latest))
    }

    @Test
    fun duplicateEventIsRejectedButLateDistinctEventIsAccepted() {
        val store = DshHostStore()
        assertTrue(store.applySessionEvent("A", 10, "assistant/chunk", "first"))
        assertFalse(store.applySessionEvent("A", 10, "assistant/chunk", "first"))
        assertTrue(store.applySessionEvent("A", 8, "tool/call", "late"))
        assertTrue(store.applySessionEvent("B", 10, "assistant/chunk", "other"))
        assertEquals(listOf(8, 10), store.sessionEvents.getValue("A").map { it.seq })
        assertEquals(10, store.sessionLastSeq["A"])
    }

    @Test
    fun projectionsAreRetainedForInactiveSessionsAndRejectOlderFrames() {
        val store = DshHostStore()
        store.replaceSessions(listOf(DshSession("B", "old", "Host", "")))
        assertTrue(store.applyProjection("B", "title", "new", 9))
        assertFalse(store.applyProjection("B", "title", "stale", 8))
        assertFalse(store.applyProjection("B", "title", "new", 9))
        assertEquals("new", store.sessions.getValue("B").title)
        assertTrue(store.applyProjection("B", "goal", "goal-state", 12))
        assertEquals("goal-state", store.projections.getValue("B").getValue("goal").value)
    }
}
