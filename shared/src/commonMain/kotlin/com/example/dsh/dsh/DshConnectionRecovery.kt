package com.example.dsh.dsh

/** Tracks recovery by protocol state, never by translated UI text. */
internal class DshConnectionRecovery {
    private var readyGeneration: Long? = null
    var reconnecting = false
        private set

    fun accept(state: DshHostRuntimeState): Boolean {
        if (state.phase == DshHostRuntimePhase.RECONNECTING) reconnecting = true
        if (state.phase != DshHostRuntimePhase.READY) return false
        if (readyGeneration == state.generation) return false
        val recovered = reconnecting || readyGeneration != null
        readyGeneration = state.generation
        reconnecting = false
        return recovered
    }
}
