package io.github.yukiykchen.dsh.framework

import kotlin.time.TimeMark

/** Log only the failed stage, never cache contents, SQL, paths, or credentials. */
internal fun dshCacheFailureLog(stage: String) {
    DshDiagnostics.info("DshCache", "operation failed: $stage")
}

/**
 * Timing trace for startup, session switch and cache loads.
 *
 * KLog keeps the trace on Kuikly's logging path so timestamps remain meaningful.
 */

fun dshPerfLog(stage: String, startedAt: TimeMark? = null) {
    val elapsed = startedAt?.elapsedNow()?.inWholeMilliseconds?.let { " +${it}ms" } ?: ""
    DshDiagnostics.info("DshPerf", "[DshPerf] $stage$elapsed")
}

fun dshSessionRenderLog(message: String) {
    DshDiagnostics.info("DshSessionRender", "[DshSessionRender] $message")
}
