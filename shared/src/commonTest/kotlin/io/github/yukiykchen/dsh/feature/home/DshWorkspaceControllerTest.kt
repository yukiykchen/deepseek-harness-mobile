package io.github.yukiykchen.dsh.feature.home

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DshWorkspaceControllerTest {
    @Test
    fun movingDownInsertsAfterTheTargetRatherThanBeforeIt() {
        val f = Fixture()
        f.controller.move("w1", 1)
        assertEquals("w3", f.page.request("workspace.insertBefore").body.optJSONObject("payload")!!.optString("beforeWorkspaceId"))
    }

    @Test
    fun movingToTheLastPositionOmitsTheBeforeWorkspaceId() {
        val f = Fixture()
        f.controller.move("w2", 1)
        assertFalse(f.page.request("workspace.insertBefore").body.optJSONObject("payload")!!.has("beforeWorkspaceId"))
    }

    @Test
    fun movingUpUsesTheTargetsOriginalPositionAndBoundsDoNotSubmit() {
        val f = Fixture()
        f.controller.move("w1", -1)
        f.controller.move("w3", 1)
        assertEquals(0, f.page.transport.requests.count { it.method == "workspace.insertBefore" })
        f.controller.move("w3", -1)
        assertEquals("w2", f.page.request("workspace.insertBefore").body.optJSONObject("payload")!!.optString("beforeWorkspaceId"))
    }

    @Test
    fun newestDirectoryReadWinsWhenResponsesArriveOutOfOrder() {
        val f = Fixture()
        f.controller.openBrowser()
        val old = f.page.request("host.listDirectory")
        f.controller.loadDirectory("/new")
        f.page.request("host.listDirectory").reply(directory("/new"))
        old.reply(directory("/old"))
        f.page.flush()
        assertEquals("/new", f.controller.browserPath)
        assertFalse(f.controller.browserBusy)
    }

    @Test
    fun closingAndReopeningTheBrowserRejectsTheOldRead() {
        val f = Fixture()
        f.controller.openBrowser()
        val old = f.page.request("host.listDirectory")
        f.controller.closeBrowser()
        f.controller.openBrowser()
        old.reply(directory("/old"))
        f.page.flush()
        assertTrue(f.controller.browserBusy)
        assertEquals("", f.controller.browserPath)
        f.page.request("host.listDirectory").reply(directory("/new"))
        f.page.flush()
        assertEquals("/new", f.controller.browserPath)
    }

    @Test
    fun anOldRenameCannotCloseOrReleaseTheNextRename() {
        val f = Fixture()
        f.controller.openRename("w1", "First")
        f.controller.saveRename()
        val old = f.page.request("workspace.rename")
        f.controller.closeRename()
        f.controller.openRename("w2", "Second")
        f.controller.saveRename()
        old.reply()
        f.page.flush()
        assertEquals("w2", f.controller.renameTargetId)
        assertEquals("Second", f.controller.renameDraft)
        assertTrue(f.controller.actionBusy)
        f.page.request("workspace.rename").reply()
        f.page.flush()
        assertEquals("", f.controller.renameTargetId)
    }

    @Test
    fun failedDeleteRetainsItsTargetAndCanBeRetried() {
        val f = Fixture()
        f.controller.openDelete("w1")
        f.controller.confirmDelete()
        f.page.request("workspace.delete").fail("denied")
        f.page.flush()
        assertEquals("w1", f.controller.deleteTargetId)
        assertTrue(f.controller.actionError.contains("denied"))
        assertFalse(f.controller.actionBusy)
        f.controller.confirmDelete()
        f.page.request("workspace.delete").reply()
        f.page.flush()
        assertEquals("", f.controller.deleteTargetId)
    }

    @Test
    fun directoryAdoptionAfterClosingDoesNotNavigateOrNotifyThePage() {
        val f = Fixture()
        f.controller.openBrowser()
        f.page.request("host.listDirectory").reply(directory("/work"))
        f.page.flush()
        f.controller.adoptCurrentDirectory()
        val old = f.page.request("workspace.create")
        f.controller.closeBrowser()
        old.reply()
        f.page.flush()
        assertEquals(0, f.adopted)
        assertFalse(f.controller.browserVisible)
    }

    @Test
    fun openingWithoutAClientDoesNotLeaveAnInfiniteSpinner() {
        val f = Fixture()
        f.page.client = null
        f.controller.openBrowser()
        assertFalse(f.controller.browserBusy)
        assertTrue(f.controller.browserError.isNotEmpty())
    }

    @Test
    fun disposalRejectsQueuedResultsAndFutureActions() {
        val f = Fixture()
        f.controller.openBrowser()
        val old = f.page.request("host.listDirectory")
        f.controller.dispose()
        old.reply(directory("/late"))
        f.page.flush()
        f.controller.openBrowser()
        f.controller.openDelete("w1")
        f.controller.confirmDelete()
        assertFalse(f.controller.browserVisible)
        assertEquals("", f.controller.deleteTargetId)
        assertTrue(f.controller.directoryEntries.isEmpty())
    }

    private class Fixture {
        val page = ControllerTestFixture()
        var adopted = 0
        val controller = DshWorkspaceController(page.ctx, {}, { adopted++ }, page::post)
        init {
            page.client!!.store.replaceWorkspaceBaseline("""[{"workspaceId":"w1"},{"workspaceId":"w2"},{"workspaceId":"w3"}]""", emptySet())
            controller.refreshGroups()
        }
    }

    companion object {
        private fun directory(path: String) = """{"path":"$path","home":"/home","entries":[]}"""
    }
}
