package io.github.yukiykchen.dsh.feature.home

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DshQueueControllerTest {
    @Test
    fun failedEditKeepsTheDraftAndCanBeRetried() {
        val f = Fixture()
        f.controller.edit("q1")
        f.controller.updateEditingText("draft")
        f.controller.save("q1")
        f.page.request("session.updateQueue").fail("denied")
        f.page.flush()
        assertEquals("q1", f.controller.editingId)
        assertEquals("draft", f.controller.editingText)
        assertFalse(f.controller.actionBusy)
        assertTrue(f.controller.error.contains("denied"))
        f.controller.save("q1")
        f.page.request("session.updateQueue").reply()
        f.page.flush()
        assertEquals("", f.controller.editingId)
        assertEquals("", f.controller.error)
    }

    @Test
    fun newerTextTypedDuringSaveRemainsUnsavedInTheEditor() {
        val f = Fixture()
        f.controller.edit("q1")
        f.controller.updateEditingText("submitted")
        f.controller.save("q1")
        f.controller.updateEditingText("new draft")
        f.page.request("session.updateQueue").reply()
        f.page.flush()
        assertEquals("q1", f.controller.editingId)
        assertEquals("new draft", f.controller.editingText)
        assertFalse(f.controller.actionBusy)
    }

    @Test
    fun oldFailureCannotClearTheNextSessionsEditOrBusyState() {
        val f = Fixture()
        f.controller.edit("q1")
        f.controller.save("q1")
        val old = f.page.request("session.updateQueue")
        f.page.sessionId = "s2"
        f.page.queue("q2", "second")
        f.controller.refresh()
        assertFalse(f.controller.actionBusy)
        f.controller.edit("q2")
        f.controller.updateEditingText("second draft")
        f.controller.save("q2")
        old.fail("old failure")
        f.page.flush()
        assertTrue(f.controller.actionBusy)
        assertEquals("q2", f.controller.editingId)
        assertEquals("second draft", f.controller.editingText)
        assertEquals("", f.controller.error)
    }

    @Test
    fun repeatedSaveOrRemoveIsBlockedUntilTheFirstActionCompletes() {
        val f = Fixture()
        f.controller.edit("q1")
        f.controller.save("q1")
        f.controller.save("q1")
        f.controller.remove("q1")
        assertEquals(1, f.page.transport.requests.count { it.method == "session.updateQueue" })
    }

    @Test
    fun removedItemsCannotSubmitStaleEditsOrActions() {
        val f = Fixture()
        f.controller.edit("q1")
        f.page.client!!.store.replaceQueue("s1", "[]")
        f.controller.save("q1")
        f.controller.remove("q1")
        assertEquals("", f.controller.editingId)
        assertEquals(0, f.page.transport.requests.count { it.method == "session.updateQueue" })
    }

    @Test
    fun reconnectInvalidatesTheOldEditorAndPostedFailure() {
        val f = Fixture()
        f.controller.edit("q1")
        f.controller.save("q1")
        f.page.transport.sockets.first().disconnect()
        f.controller.refresh()
        f.page.flush()
        assertEquals("", f.controller.editingId)
        assertEquals("", f.controller.error)
        assertFalse(f.controller.actionBusy)
    }

    @Test
    fun disposalRejectsLateCallbacksAndNewActions() {
        val f = Fixture()
        f.controller.edit("q1")
        f.controller.save("q1")
        f.page.request("session.updateQueue").reply()
        f.controller.dispose()
        f.page.flush()
        f.controller.edit("q1")
        f.controller.remove("q1")
        assertTrue(f.controller.items.isEmpty())
        assertEquals("", f.controller.editingId)
        assertFalse(f.controller.actionBusy)
    }

    private class Fixture {
        val page = ControllerTestFixture()
        val controller = DshQueueController(page.ctx, page::post)
        init { page.queue(); controller.refresh() }
    }
}
