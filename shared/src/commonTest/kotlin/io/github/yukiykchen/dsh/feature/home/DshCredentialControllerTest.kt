package io.github.yukiykchen.dsh.feature.home

import io.github.yukiykchen.dsh.data.createTestHostClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DshCredentialControllerTest {
    @Test
    fun repeatedSaveCannotSubmitTheKeyTwice() {
        val f = Fixture()
        f.controller.updateDraft("test-key")
        f.controller.save()
        f.controller.save()
        assertEquals(1, f.page.transport.requests.count { it.method == "credentials.set" })
    }

    @Test
    fun failureKeepsTheDraftAndCanBeRetried() {
        val f = Fixture()
        f.controller.updateDraft("test-key")
        f.controller.save()
        f.page.request("credentials.set").fail("denied")
        f.page.flush()
        assertEquals("test-key", f.controller.draft)
        assertTrue(f.controller.visible)
        assertFalse(f.controller.busy)
        assertTrue(f.controller.error().contains("denied"))
        f.controller.save()
        f.page.request("credentials.set").reply()
        f.page.flush()
        assertEquals(1, f.saved)
        assertEquals("", f.controller.draft)
        assertFalse(f.controller.visible)
    }

    @Test
    fun oldPostedSuccessCannotCloseAReopenedFormOrClearItsNewDraft() {
        val f = Fixture()
        f.controller.updateDraft("old-key")
        f.controller.save()
        f.page.request("credentials.set").reply()
        f.controller.close()
        assertEquals("", f.controller.draft)
        f.controller.open()
        f.page.completeCredentialSetup()
        f.controller.updateDraft("new-key")
        f.controller.save()
        f.page.flush()
        assertEquals("new-key", f.controller.draft)
        assertTrue(f.controller.visible)
        assertTrue(f.controller.busy)
        assertEquals(0, f.saved)
        f.page.request("credentials.set").reply()
        f.page.flush()
        assertEquals(1, f.saved)
    }

    @Test
    fun replacingTheClientRejectsSuccessButReleasesOnlyTheOldBusyState() {
        val f = Fixture()
        f.controller.updateDraft("test-key")
        f.controller.save()
        f.page.request("credentials.set").reply()
        f.page.client = createTestHostClient()
        f.page.flush()
        assertTrue(f.controller.visible)
        assertEquals("test-key", f.controller.draft)
        assertFalse(f.controller.busy)
        assertEquals(0, f.saved)
    }

    @Test
    fun newDraftTypedDuringTheRequestIsNotSilentlyDiscarded() {
        val f = Fixture()
        f.controller.updateDraft("old-key")
        f.controller.save()
        f.controller.updateDraft("new-key")
        f.page.request("credentials.set").reply()
        f.page.flush()
        assertEquals("new-key", f.controller.draft)
        assertTrue(f.controller.visible)
        assertFalse(f.controller.busy)
        assertTrue(f.controller.error().isNotEmpty())
        assertEquals(0, f.saved)
    }

    @Test
    fun disposalScrubsTheDraftAndRejectsLateCallbacksAndCommands() {
        val f = Fixture()
        f.controller.updateDraft("test-key")
        f.controller.save()
        f.page.request("credentials.set").reply()
        f.controller.dispose()
        f.page.flush()
        f.controller.open()
        f.controller.updateDraft("another-key")
        f.controller.save()
        assertEquals("", f.controller.draft)
        assertFalse(f.controller.visible)
        assertFalse(f.controller.busy)
        assertEquals(0, f.saved)
    }

    @Test
    fun validationAndDisconnectedSavesDoNotStartARequest() {
        val f = Fixture()
        f.controller.save()
        assertTrue(f.controller.error().isNotEmpty())
        f.controller.updateDraft("invalid key")
        f.controller.save()
        assertTrue(f.controller.error().isNotEmpty())
        f.page.client = null
        f.controller.updateDraft("test-key")
        f.controller.save()
        assertFalse(f.controller.busy)
        assertEquals(0, f.page.transport.requests.count { it.method == "credentials.set" })
    }

    @Test
    fun reconnectCancellationLeavesTheFormRetryable() {
        val f = Fixture()
        f.controller.updateDraft("test-key")
        f.controller.save()
        f.page.transport.sockets.first().disconnect()
        f.page.flush()
        assertFalse(f.controller.busy)
        assertTrue(f.controller.visible)
        assertEquals("test-key", f.controller.draft)
    }

    @Test
    fun setupLoadAfterDisconnectReleasesBusyAndKeepsTheFormClosable() {
        val f = Fixture(setupFails = true)
        f.page.transport.sockets.first().disconnect()
        f.page.scheduled.toList().forEach { it() }
        assertFalse(f.controller.busy)
        assertTrue(f.controller.visible)
        assertTrue(f.controller.error().isNotEmpty())
        f.controller.close()
        assertFalse(f.controller.visible)
    }

    @Test
    fun credentialWriteUsesTheHostConfiguredReference() {
        val f = Fixture()
        f.controller.updateDraft("secret-key")
        f.controller.save()
        val write = f.page.transport.requests.last { it.method == "credentials.set" }
        assertEquals("CUSTOM_DEEPSEEK_KEY", write.body.optJSONObject("payload")?.optString("ref"))
    }

    @Test
    fun providerOrCredentialLoadFailureDisablesSaving() {
        val unavailable = Fixture(providerAvailable = false)
        unavailable.controller.updateDraft("secret-key")
        unavailable.controller.save()
        unavailable.page.flush()
        assertEquals(0, unavailable.page.transport.requests.count { it.method == "credentials.set" })
        assertTrue(unavailable.controller.error().contains("Provider") || unavailable.controller.error().contains("不可写"))

        val failed = Fixture(setupFails = true)
        failed.controller.updateDraft("secret-key")
        failed.controller.save()
        failed.page.flush()
        assertEquals(0, failed.page.transport.requests.count { it.method == "credentials.set" })
        assertTrue(failed.controller.error().contains("无法读取"))
    }

    @Test
    fun readOnlyCredentialSetupDisablesSaving() {
        val f = Fixture(writable = false)
        f.controller.updateDraft("secret-key")
        f.controller.save()
        f.page.flush()
        assertEquals(0, f.page.transport.requests.count { it.method == "credentials.set" })
        assertTrue(f.controller.error().contains("不可写"))
    }

    private class Fixture(
        providerAvailable: Boolean = true,
        writable: Boolean = true,
        setupFails: Boolean = false,
    ) {
        val page = ControllerTestFixture()
        var saved = 0
        val controller = DshCredentialController(page.ctx, {}, { saved++ }, page::post) { _, action -> page.schedule(action) }
        init {
            controller.open()
            if (setupFails) page.request("llm.providers").fail("unavailable")
            else page.completeCredentialSetup(providerAvailable = providerAvailable, writable = writable)
            page.flush()
        }
    }
}
