package com.example.dsh.dsh

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DshLifecycleTest {
    @Test
    fun changingViewsDoesNotCancelTheHostTurn() {
        var detached = 0
        var cancelled = 0
        val observer = DshTurnSubscription({ detached++ }, { cancelled++ })
        observer.detach()
        observer.detach()
        assertEquals(1, detached)
        assertEquals(0, cancelled)
        observer.cancel()
        observer.cancel()
        assertEquals(1, detached)
        assertEquals(1, cancelled)
    }

    @Test
    fun readyAfterHandshakeRecoversExactlyOncePerGeneration() {
        val recovery = DshConnectionRecovery()
        fun state(phase: DshHostRuntimePhase, generation: Long) = DshHostRuntimeState(phase, generation)
        assertFalse(recovery.accept(state(DshHostRuntimePhase.READY, 1)))
        assertFalse(recovery.accept(state(DshHostRuntimePhase.RECONNECTING, 2)))
        assertTrue(recovery.reconnecting)
        assertFalse(recovery.accept(state(DshHostRuntimePhase.CONNECTING, 3)))
        assertFalse(recovery.accept(state(DshHostRuntimePhase.SYNCING, 3)))
        assertTrue(recovery.reconnecting)
        assertTrue(recovery.accept(state(DshHostRuntimePhase.READY, 3)))
        assertFalse(recovery.reconnecting)
        assertFalse(recovery.accept(state(DshHostRuntimePhase.READY, 3)))
    }
}
