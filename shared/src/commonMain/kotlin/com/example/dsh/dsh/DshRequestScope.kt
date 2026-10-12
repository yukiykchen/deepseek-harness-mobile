package com.example.dsh.dsh

/** Invalidates older responses, including an A -> B -> A selection cycle. */
internal class DshRequestVersion {
    private var value = 0L
    fun next(): Long = ++value
    fun invalidate() { value++ }
    fun accepts(version: Long): Boolean = version == value
}

internal data class DshRequestIdentity(
    val sessionId: String,
    val selection: Long,
    val connectionGeneration: Long,
)

internal class DshSessionRequest private constructor(
    private val ctx: DshHomeContext,
    val client: DshHostClient,
    val sessionId: String,
    private val identity: DshRequestIdentity,
) {
    fun isCurrent(): Boolean = ctx.hostClient === client &&
        identity == DshRequestIdentity(ctx.activeSessionId, ctx.selectionGeneration, client.currentConnectionState().generation)

    companion object {
        fun capture(ctx: DshHomeContext, sessionId: String): DshSessionRequest? {
            val client = ctx.hostClient ?: return null
            return DshSessionRequest(ctx, client, sessionId, DshRequestIdentity(sessionId, ctx.selectionGeneration, client.currentConnectionState().generation))
        }
    }
}

internal fun DshHomeContext.captureRequest(sessionId: String = activeSessionId): DshSessionRequest? =
    DshSessionRequest.capture(this, sessionId)
