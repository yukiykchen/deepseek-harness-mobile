package com.example.dsh.dsh

import com.tencent.kuikly.core.log.KLog
import kotlin.time.TimeMark

/**
 * Timing trace for startup, session switch and cache loads.
 *
 * BridgeModule.log is asynchronous on Android and can be printed seconds
 * after the event. KLog keeps the trace on Kuikly's logging path so Logcat
 * timestamps remain meaningful.
 */
internal fun dshPerfLog(stage: String, startedAt: TimeMark? = null) {
    val elapsed = startedAt?.elapsedNow()?.inWholeMilliseconds?.let { " +${it}ms" } ?: ""
    KLog.i("DshPerf", "[DshPerf] $stage$elapsed")
}
