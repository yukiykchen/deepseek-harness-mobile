package io.github.yukiykchen.dsh.feature.home

import com.tencent.kuikly.core.base.Animation
import com.tencent.kuikly.core.base.PagerScope
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import io.github.yukiykchen.dsh.framework.BridgeModule
import io.github.yukiykchen.dsh.feature.connection.DshConnectionController

/** Stateful drawer transition separated from the Kuikly page lifecycle. */
class DshSessionDrawerAnimator(
    private val scope: PagerScope,
    private val drawerVisible: () -> Boolean,
    private val setDrawerVisible: (Boolean) -> Unit,
    private val setDrawerAnimated: (Boolean) -> Unit,
    private val setMaskAnimated: (Boolean) -> Unit,
    private val setMaskAnimation: (Animation) -> Unit,
    private val warmCache: () -> Unit,
    private val schedule: (String, Int, () -> Unit) -> Unit = { id, delay, callback ->
        com.tencent.kuikly.core.timer.setTimeout(id, delay, callback)
    },
) {
    private var generation = 0
    private var closed = false

    fun open() {
        if (closed || drawerVisible()) return
        val current = ++generation
        setMaskAnimation(Animation.easeInOut(DSH_HOME_ANIMATION_DURATION_S))
        setMaskAnimated(false)
        setDrawerAnimated(false)
        setDrawerVisible(true)
        schedule(scope.pagerId, 16) {
            if (closed || generation != current) return@schedule
            setDrawerAnimated(true)
            setMaskAnimated(true)
        }
        schedule(scope.pagerId, DSH_HOME_ANIMATION_DURATION_MS) {
            if (!closed && generation == current) warmCache()
        }
    }

    fun close() {
        if (closed || !drawerVisible()) return
        val current = ++generation
        setMaskAnimation(Animation.easeInOut(DSH_HOME_ANIMATION_DURATION_S))
        setMaskAnimated(false)
        setDrawerAnimated(false)
        schedule(scope.pagerId, DSH_HOME_ANIMATION_DURATION_MS) {
            if (!closed && generation == current) setDrawerVisible(false)
        }
    }

    fun dispose() {
        closed = true
        generation++
    }
}

/** Small page commands that only coordinate existing controllers and modules. */
class DshHomePageActions(
    private val pageData: () -> com.tencent.kuikly.core.pager.PageData,
    private val bridge: () -> BridgeModule,
    private val openPage: (String, JSONObject) -> Unit,
    private val dismissKeyboard: () -> Unit,
    private val setAttachmentMenuVisible: (Boolean) -> Unit,
    private val setSettingsPageVisible: (Boolean) -> Unit,
    private val credentials: DshCredentialController,
    private val connection: DshConnectionController,
    private val goal: DshGoalController,
    private val turn: DshStreamingTurnController,
) {
    fun openCredentialSettings() {
        dismissKeyboard()
        setAttachmentMenuVisible(false)
        credentials.open()
    }

    fun closeCredentialSettings() {
        dismissKeyboard()
        credentials.close()
    }

    fun openSettingsPage() {
        dismissKeyboard()
        setAttachmentMenuVisible(false)
        setSettingsPageVisible(true)
        dimSystemBars(true)
    }

    fun closeSettingsPage() {
        setSettingsPageVisible(false)
        dimSystemBars(false)
    }

    fun stopCurrentEngine(disposing: Boolean = false) {
        if (disposing) connection.dispose() else connection.stop()
        goal.reset()
        turn.detach()
    }

    fun openConnectionSetup(passphrase: String = "") {
        openPage(
            "connection_setup",
            JSONObject().apply { put("pageName", "connection_setup"); put("keyPassphrase", passphrase) },
        )
    }

    fun dimSystemBars(dimmed: Boolean) {
        val current = pageData()
        if (current.isAndroid || current.isIOS) {
            bridge().setSystemBarsDimmed(dimmed)
        }
    }
}

internal fun refreshHomeSessionControllers(
    interactions: DshInteractionController,
    goal: DshGoalController,
    queue: DshQueueController,
    jobs: DshJobsController,
) {
    interactions.reset()
    goal.clearSnapshot()
    queue.refresh()
    jobs.refresh()
    interactions.refresh()
}
