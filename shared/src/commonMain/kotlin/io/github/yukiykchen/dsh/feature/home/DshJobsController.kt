package io.github.yukiykchen.dsh.feature.home

import com.tencent.kuikly.core.base.PagerScope
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.reactive.handler.observableList
import com.tencent.kuikly.core.timer.setTimeout
import io.github.yukiykchen.dsh.framework.bridgeModule
import io.github.yukiykchen.dsh.data.DshHostClient
import io.github.yukiykchen.dsh.model.DshJobItem

/** Background jobs panel for the active session, with a 1s clock while jobs run. */

class DshJobsController(
    private val ctx: DshHomeContext,
    private val currentTime: () -> Long = { ctx.bridgeModule.currentTimeStamp() },
    private val schedule: (Int, () -> Unit) -> Unit = { delay, callback -> setTimeout(ctx.pagerId, delay, callback) },
) : PagerScope by ctx {
    val items by observableList<DshJobItem>()
    var expanded by observable(false)
        private set
    var now by observable(0L)
        private set
    private var clockScheduled = false
    private var clockGeneration = 0
    private var disposed = false
    private var snapshotClient: DshHostClient? = null
    private var snapshotSessionId = ""

    fun refresh() {
        if (disposed) return
        val client = ctx.hostClient
        if (snapshotClient !== client || snapshotSessionId != ctx.activeSessionId) {
            expanded = false
            cancelClock()
        }
        snapshotClient = client
        snapshotSessionId = ctx.activeSessionId
        val next = client?.jobs(ctx.activeSessionId).orEmpty()
        if (items.toList() != next) {
            items.clear()
            items.addAll(next)
        }
        if (next.isEmpty()) expanded = false
        if (expanded && hasRunningJobs()) {
            now = currentTime()
            scheduleClock()
        } else cancelClock()
    }
    fun toggle() {
        if (disposed || items.isEmpty()) return
        expanded = !expanded
        if (expanded) {
            now = currentTime()
            scheduleClock()
        } else cancelClock()
    }

    private fun scheduleClock() {
        if (disposed || !expanded || clockScheduled || !hasRunningJobs()) return
        val generation = clockGeneration
        clockScheduled = true
        schedule(1_000) {
            if (disposed || generation != clockGeneration) return@schedule
            clockScheduled = false
            if (!expanded || !hasRunningJobs()) return@schedule
            now = currentTime()
            scheduleClock()
        }
    }

    private fun hasRunningJobs() = items.any { it.status == "running" || it.status == "stopping" }

    private fun cancelClock() {
        clockGeneration++
        clockScheduled = false
    }

    fun dispose() {
        disposed = true
        expanded = false
        items.clear()
        snapshotClient = null
        cancelClock()
    }
}
