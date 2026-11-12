package io.github.yukiykchen.dsh.feature.home

import com.tencent.kuikly.core.base.PagerScope
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.timer.setTimeout
import io.github.yukiykchen.dsh.model.DshRpcError
import io.github.yukiykchen.dsh.model.DshGoalSnapshot
import io.github.yukiykchen.dsh.data.DshHostClient
import io.github.yukiykchen.dsh.data.parseGoalProjection

/** Session goal bar: the projected goal snapshot and pause / resume / edit / clear. */

class DshGoalController(
    private val ctx: DshHomeContext,
    private val postToPage: (() -> Unit) -> Unit = { setTimeout(ctx.pagerId, 0, it) },
) : PagerScope by ctx {
    var snapshot by observable<DshGoalSnapshot?>(null)
        private set
    var busy by observable(false)
        private set
    var error by observable("")
        private set
    private val requests = DshControllerRequestScope(ctx)

    /** Apply a `goal` projection pushed by the Host for the active session. */
    fun applyProjection(raw: String) {
        if (requests.closed) return
        val next = parseGoalProjection(raw)
        if (snapshot?.id != next?.id) reset()
        snapshot = next
    }

    /** Forget the goal of the previous session (switch) or connection (stop). */
    fun reset() {
        requests.invalidate()
        snapshot = null
        busy = false
        error = ""
    }
    fun clearSnapshot() = reset()
    fun pause() = mutate { client, goal, callback -> client.goalPause(ctx.activeSessionId, goal, callback) }
    fun resume() = mutate { client, goal, callback -> client.goalResume(ctx.activeSessionId, goal, callback) }
    fun edit(objective: String, onDone: (Boolean) -> Unit) = mutate(onDone) { client, goal, callback ->
        client.goalEdit(ctx.activeSessionId, goal, objective, callback)
    }
    fun clear() = mutate(clearOnSuccess = true) { client, goal, callback ->
        client.goalClear(ctx.activeSessionId, goal, callback)
    }

    private fun mutate(
        onDone: (Boolean) -> Unit = {},
        clearOnSuccess: Boolean = false,
        action: (DshHostClient, DshGoalSnapshot, (DshRpcError?) -> Unit) -> Unit,
    ) {
        val goal = snapshot ?: return
        if (busy || requests.closed) return
        val request = requests.begin(ctx.activeSessionId) ?: return
        busy = true
        error = ""
        action(request.client, goal) { rpcError ->
            postToPage {
                if (!requests.owns(request)) return@postToPage
                busy = false
                if (!requests.accepts(request)) return@postToPage
                if (clearOnSuccess && rpcError == null && snapshot == goal) snapshot = null
                error = if (rpcError != null) "${rpcError.message} (${rpcError.code})" else ""
                onDone(rpcError == null)
            }
        }
    }

    fun dispose() {
        reset()
        requests.close()
    }
}
