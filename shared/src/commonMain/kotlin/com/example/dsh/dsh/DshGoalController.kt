package com.example.dsh.dsh

import com.tencent.kuikly.core.base.PagerScope
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.timer.setTimeout

/** Session goal bar: the projected goal snapshot and pause / resume / edit / clear. */
internal class DshGoalController(private val ctx: DshHomeContext) : PagerScope by ctx {
    var snapshot by observable<DshGoalSnapshot?>(null)
        private set
    var busy by observable(false)
        private set
    var error by observable("")
        private set

    /** Apply a `goal` projection pushed by the Host for the active session. */
    fun applyProjection(raw: String) {
        snapshot = parseGoalProjection(raw)
    }

    /** Forget the goal of the previous session (switch) or connection (stop). */
    fun reset() {
        snapshot = null
        busy = false
        error = ""
    }

    fun clearSnapshot() {
        snapshot = null
    }

    fun pause() = mutate { client, goal, callback -> client.goalPause(ctx.activeSessionId, goal, callback) }

    fun resume() = mutate { client, goal, callback -> client.goalResume(ctx.activeSessionId, goal, callback) }

    fun edit(objective: String, onDone: (Boolean) -> Unit) = mutate(onDone) { client, goal, callback ->
        client.goalEdit(ctx.activeSessionId, goal, objective, callback)
    }

    fun clear() = mutate { client, goal, callback ->
        client.goalClear(ctx.activeSessionId, goal) { rpcError ->
            if (rpcError == null) snapshot = null
            callback(rpcError)
        }
    }

    private fun mutate(
        onDone: (Boolean) -> Unit = {},
        action: (DshHostClient, DshGoalSnapshot, (DshRpcError?) -> Unit) -> Unit,
    ) {
        val goal = snapshot ?: return
        val client = ctx.hostClient ?: return
        if (busy) return
        busy = true
        error = ""
        action(client, goal) { rpcError ->
            setTimeout(pagerId, 0) {
                busy = false
                error = if (rpcError != null) "${rpcError.message} (${rpcError.code})" else ""
                onDone(rpcError == null)
            }
        }
    }
}
