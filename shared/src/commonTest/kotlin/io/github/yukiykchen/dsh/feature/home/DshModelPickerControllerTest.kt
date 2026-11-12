package io.github.yukiykchen.dsh.feature.home

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DshModelPickerControllerTest {
    @Test
    fun openingWithoutAClientStopsTheSpinnerAndReportsAnError() {
        val f = ControllerTestFixture()
        f.client = null
        val controller = DshModelPickerController(f.ctx)
        controller.open()
        assertFalse(controller.busy)
        assertTrue(controller.visible)
        assertTrue(controller.error.isNotEmpty())
    }

    @Test
    fun latestModelLoadWinsEvenWhenResponsesArriveOutOfOrder() {
        val f = ControllerTestFixture()
        val controller = DshModelPickerController(f.ctx)
        controller.load("s1")
        val old = f.request("session.models")
        controller.load("s1")
        f.request("session.models").reply(modelPayload("New"))
        old.reply(modelPayload("Old"))
        assertEquals("New", controller.selectedLabel)
        assertFalse(controller.busy)
    }

    @Test
    fun oldSelectionCannotCloseOrRepaintTheNextSessionsPicker() {
        val f = ControllerTestFixture()
        val controller = DshModelPickerController(f.ctx)
        controller.open()
        f.request("session.models").reply(modelPayload())
        controller.select(controller.options.single())
        val old = f.request("session.selectModel")
        f.sessionId = "s2"
        controller.load("s2")
        f.request("session.models").reply(modelPayload("Session Two", "m2"))
        old.reply("""{"selected":{"provider":"p1","model":"m1"}}""")
        assertEquals("Session Two", controller.selectedLabel)
        assertTrue(controller.visible)
        assertFalse(controller.busy)
    }

    @Test
    fun closingAndReopeningRejectsTheOldSelectionCallback() {
        val f = ControllerTestFixture()
        val controller = DshModelPickerController(f.ctx)
        controller.open()
        f.request("session.models").reply(modelPayload())
        controller.select(controller.options.single())
        val old = f.request("session.selectModel")
        controller.close()
        controller.open()
        old.reply("""{"selected":{"provider":"p1","model":"m1"}}""")
        assertTrue(controller.visible)
        assertTrue(controller.busy)
        f.request("session.models").reply(modelPayload("Reopened"))
        assertEquals("Reopened", controller.selectedLabel)
    }

    @Test
    fun repeatedSelectionIsBlockedAndCatalogRefreshRunsAfterItsAcknowledgement() {
        val f = ControllerTestFixture()
        val controller = DshModelPickerController(f.ctx)
        controller.open()
        f.request("session.models").reply(modelPayload())
        val option = controller.options.single()
        controller.select(option)
        controller.select(option)
        controller.load("s1")
        assertEquals(1, f.transport.requests.count { it.method == "session.selectModel" })
        assertEquals(1, f.transport.requests.count { it.method == "session.models" })
        f.request("session.selectModel").reply("""{"selected":{"provider":"p1","model":"m1"}}""")
        assertEquals(2, f.transport.requests.count { it.method == "session.models" })
        f.request("session.models").reply(modelPayload("Refreshed"))
        assertEquals("Refreshed", controller.selectedLabel)
        assertFalse(controller.visible)
    }

    @Test
    fun catalogRefreshAfterFailedSelectionDoesNotHideTheMutationError() {
        val f = ControllerTestFixture()
        val controller = DshModelPickerController(f.ctx)
        controller.open()
        f.request("session.models").reply(modelPayload())
        controller.select(controller.options.single())
        controller.load("s1")
        f.request("session.selectModel").fail("selection denied")
        assertTrue(controller.error.contains("selection denied"))
        f.request("session.models").reply(modelPayload())
        assertTrue(controller.error.contains("selection denied"))
        assertTrue(controller.visible)
        assertFalse(controller.busy)
    }

    @Test
    fun aStaleOptionClickAfterSessionSwitchLoadsCurrentModelsInsteadOfMutatingTheNewSession() {
        val f = ControllerTestFixture()
        val controller = DshModelPickerController(f.ctx)
        controller.open()
        f.request("session.models").reply(modelPayload())
        val oldOption = controller.options.single()
        f.sessionId = "s2"
        controller.select(oldOption)
        assertEquals(0, f.transport.requests.count { it.method == "session.selectModel" })
        assertEquals("s2", f.request("session.models").body.optJSONObject("payload")!!.optString("sessionId"))
    }

    @Test
    fun disposalRejectsPendingCallbacksAndNewCommands() {
        val f = ControllerTestFixture()
        val controller = DshModelPickerController(f.ctx)
        controller.open()
        val request = f.request("session.models")
        controller.dispose()
        request.reply(modelPayload())
        controller.open()
        assertFalse(controller.visible)
        assertFalse(controller.busy)
        assertTrue(controller.options.isEmpty())
    }
}
