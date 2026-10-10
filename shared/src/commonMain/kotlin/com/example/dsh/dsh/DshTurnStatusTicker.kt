package com.example.dsh.dsh

import com.tencent.kuikly.core.base.PagerScope
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.timer.setTimeout
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Elapsed time of the running turn for the "思考中 · 12s" status row.
 *
 * Silent for the first [TURN_STATUS_CLOCK_AFTER_MS], then ticks once a second.
 * [elapsedMs] only changes when the visible second changes, so the row does
 * not re-render on every timer callback. Call [sync] whenever [isActive] may
 * have flipped; stale timer chains are dropped by a generation token.
 */
internal class DshTurnStatusTicker(
    scope: PagerScope,
    private val isActive: () -> Boolean,
) : PagerScope by scope {
    var elapsedMs by observable(0L)
        private set
    private var startedAt: TimeMark? = null
    private var generation = 0
    private var clockBucket = -1L

    fun sync() {
        if (!isActive()) {
            generation += 1
            reset()
            return
        }
        if (startedAt == null) startedAt = TimeSource.Monotonic.markNow()
        val token = ++generation
        fun tick() {
            if (token != generation) return
            if (!isActive()) {
                reset()
                return
            }
            val elapsed = startedAt?.elapsedNow()?.inWholeMilliseconds ?: 0L
            val showClock = elapsed >= TURN_STATUS_CLOCK_AFTER_MS
            val bucket = if (showClock) elapsed / 1_000L else 0L
            if (bucket != clockBucket) {
                clockBucket = bucket
                elapsedMs = elapsed
            }
            val wait = if (showClock) 1_000L else (TURN_STATUS_CLOCK_AFTER_MS - elapsed).coerceAtLeast(200L)
            setTimeout(pagerId, wait.toInt()) { tick() }
        }
        tick()
    }

    private fun reset() {
        startedAt = null
        elapsedMs = 0
        clockBucket = -1L
    }
}
