package com.example.dsh.dsh

import com.example.dsh.base.Utils
import com.tencent.kuikly.core.base.PagerScope
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.reactive.handler.observableList
import com.tencent.kuikly.core.timer.setTimeout

/** Background jobs panel for the active session, with a 1s clock while jobs run. */
internal class DshJobsController(private val ctx: DshHomeContext) : PagerScope by ctx {
    val items by observableList<DshJobItem>()
    var expanded by observable(false)
        private set
    var now by observable(0L)
        private set
    private var clockScheduled = false

    fun refresh() {
        val client = ctx.hostClient ?: return
        val next = client.jobs(ctx.activeSessionId)
        items.clear()
        items.addAll(next)
        if (next.isEmpty()) expanded = false
        if (expanded) {
            now = currentTime()
            scheduleClock()
        }
    }

    fun toggle() {
        expanded = !expanded
        if (expanded) {
            now = currentTime()
            scheduleClock()
        }
    }

    private fun scheduleClock() {
        if (!expanded || clockScheduled || items.none { it.status == "running" || it.status == "stopping" }) return
        clockScheduled = true
        setTimeout(pagerId, 1_000) {
            clockScheduled = false
            if (!expanded) return@setTimeout
            now = currentTime()
            scheduleClock()
        }
    }

    private fun currentTime(): Long = Utils.bridgeModule(pagerId).currentTimeStamp()
}
