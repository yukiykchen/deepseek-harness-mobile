package io.github.yukiykchen.dsh.feature.home

import io.github.yukiykchen.dsh.data.DshHostClient

/** One controller's current request, including its Host and optional session scope. */
internal class DshControllerRequestScope(private val ctx: DshHomeContext) {
    class Request internal constructor(
        internal val owner: DshControllerRequestScope,
        val client: DshHostClient,
        internal val connectionGeneration: Long,
        internal val sessionId: String?,
        internal val sequence: Long,
    )

    private var sequence = 0L
    var closed = false
        private set

    fun begin(sessionId: String? = null): Request? {
        if (closed) return null
        invalidate()
        val client = ctx.hostClient ?: return null
        return Request(this, client, client.currentConnectionState().generation, sessionId, sequence)
    }

    /** Ownership is independent of context, so an invalid request can release only its own busy state. */
    fun owns(request: Request): Boolean = !closed && request.owner === this && request.sequence == sequence

    fun accepts(request: Request): Boolean = owns(request) &&
        ctx.hostClient === request.client &&
        request.client.currentConnectionState().generation == request.connectionGeneration &&
        (request.sessionId == null || request.sessionId == ctx.activeSessionId)

    fun invalidate() { sequence++ }

    fun close() {
        closed = true
        invalidate()
    }
}
