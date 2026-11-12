package io.github.yukiykchen.dsh.feature.home

import com.tencent.kuikly.core.base.PagerScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DshSessionDrawerAnimatorTest {
    @Test
    fun timersUsePagerIdInjectedAfterConstruction() {
        val fixture = Fixture()
        fixture.id = "live-page"
        fixture.animator.open()
        assertEquals(listOf("live-page", "live-page"), fixture.ids)
    }

    @Test
    fun immediateCloseInvalidatesOpeningFrameAndCacheWarmup() {
        val fixture = Fixture()
        fixture.animator.open()
        fixture.animator.close()
        fixture.runAll()
        assertFalse(fixture.visible)
        assertFalse(fixture.animated)
        assertFalse(fixture.maskAnimated)
        assertEquals(0, fixture.warmed)
    }

    @Test
    fun staleCloseCannotHideDrawerReopenedAfterRepeatedClose() {
        val fixture = Fixture()
        fixture.animator.open()
        fixture.runAll()
        fixture.animator.close()
        fixture.animator.close()
        fixture.tasks.removeAt(0)() // stale close does not unmount
        assertTrue(fixture.visible)
        fixture.tasks.removeAt(0)() // current close unmounts
        fixture.animator.open()
        fixture.runAll()
        assertTrue(fixture.visible)
        assertTrue(fixture.animated)
    }

    @Test
    fun disposedPageIgnoresAlreadyScheduledWorkAndNewCommands() {
        val fixture = Fixture()
        fixture.animator.open()
        fixture.animator.dispose()
        fixture.runAll()
        assertFalse(fixture.animated)
        assertEquals(0, fixture.warmed)
        fixture.animator.close()
        assertTrue(fixture.tasks.isEmpty())
    }

    private class Fixture {
        var id = ""
        var visible = false
        var animated = false
        var maskAnimated = false
        var warmed = 0
        val ids = mutableListOf<String>()
        val tasks = mutableListOf<() -> Unit>()
        val animator = DshSessionDrawerAnimator(
            scope = object : PagerScope { override val pagerId get() = id },
            drawerVisible = { visible },
            setDrawerVisible = { visible = it },
            setDrawerAnimated = { animated = it },
            setMaskAnimated = { maskAnimated = it },
            setMaskAnimation = {},
            warmCache = { warmed++ },
            schedule = { pagerId, _, task -> ids.add(pagerId); tasks.add(task) },
        )

        fun runAll() {
            while (tasks.isNotEmpty()) tasks.removeAt(0)()
        }
    }
}
