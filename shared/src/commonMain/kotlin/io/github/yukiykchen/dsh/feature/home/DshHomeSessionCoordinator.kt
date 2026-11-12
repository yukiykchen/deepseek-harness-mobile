package io.github.yukiykchen.dsh.feature.home

/** Session-list and mounted-panel policy, independent of Kuikly view state. */
internal class DshHomeSessionCoordinator(
    private val panelLimit: Int,
    private val cacheWarmLimit: Int,
) {
    fun evictionTarget(panelIds: List<String>, activeSessionId: String): String? {
        if (panelIds.size < panelLimit) return null
        return panelIds.firstOrNull { it != activeSessionId }
    }

    fun sessionsToWarm(
        allSessionIds: List<String>,
        activeSessionId: String,
        panelIds: Set<String>,
    ): List<String> = allSessionIds.asSequence()
        .filter { it != activeSessionId }
        .filterNot(panelIds::contains)
        .take(cacheWarmLimit)
        .toList()

    enum class SelectionResult { READY, WAIT_FOR_DATA, WAIT_FOR_PANEL }

    fun selectionResult(
        requestedId: String,
        activeSessionId: String,
        readySessionIds: Set<String>,
        panelIds: Set<String>,
    ): SelectionResult = when {
        requestedId == activeSessionId -> SelectionResult.READY
        requestedId !in readySessionIds -> SelectionResult.WAIT_FOR_DATA
        requestedId !in panelIds -> SelectionResult.WAIT_FOR_PANEL
        else -> SelectionResult.READY
    }
}
