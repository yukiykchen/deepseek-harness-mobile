package io.github.yukiykchen.dsh.feature.connection

import com.tencent.kuikly.core.base.PagerScope
import io.github.yukiykchen.dsh.model.DshRemoteProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DshSshProfileFormTest {
    @Test fun importResultIsReclaimedEvenIfThePagerDropsItsScheduledUiCallback() {
        val f = Fixture()
        f.selectFile()
        f.imports.single().second("queued-key")
        f.form.dispose()
        f.posts.clear()
        assertEquals(listOf("queued-key"), f.deleted)
        assertEquals("saved-key", f.form.keyId)
    }
    @Test fun repeatedSelectionIsBlockedWhileThePickerOrImportIsPending() {
        val f = Fixture()
        f.form.pickKey()
        f.form.pickKey()
        assertEquals(1, f.pickers.size)
        f.pickers.single()("file")
        f.pickers.single()("duplicate")
        f.flush()
        f.form.pickKey()
        assertEquals(1, f.imports.size)
        assertEquals("file", f.imports.single().first)
    }

    @Test fun profileReloadRejectsAnOldPickerWithoutStartingAnImport() {
        val f = Fixture()
        f.form.pickKey()
        f.form.load(profile("new-key"))
        f.pickers.single()("old-file")
        f.flush()
        assertTrue(f.imports.isEmpty())
        assertEquals("new-key", f.form.keyId)
        assertFalse(f.form.busy)
    }

    @Test fun cancelledImportReclaimsItsResultAndCannotReleaseTheNewPickersBusyState() {
        val f = Fixture()
        f.selectFile()
        val old = f.imports.single().second
        f.form.cancelSelection()
        f.form.pickKey()
        old("orphan-key")
        old("orphan-key")
        f.flush()
        assertEquals(listOf("orphan-key"), f.deleted)
        assertTrue(f.form.busy)
        assertEquals("saved-key", f.form.keyId)
    }

    @Test fun duplicateImportResultsDoNotReplaceOrDeleteTheAcceptedKey() {
        val f = Fixture()
        f.selectFile()
        val callback = f.imports.single().second
        callback("selected-key")
        callback("selected-key")
        callback("extra-key")
        f.flush()
        assertEquals("selected-key", f.form.keyId)
        assertEquals(listOf("extra-key"), f.deleted)
        assertFalse(f.form.busy)
    }

    @Test fun disposalReclaimsLateImportsAndRejectsNewWork() {
        val f = Fixture()
        f.selectFile()
        f.form.passphrase = "secret"
        f.form.dispose()
        f.imports.single().second("late-key")
        f.flush()
        f.form.pickKey()
        f.form.load(profile("another"))
        f.form.host = "ignored"
        assertEquals(listOf("late-key"), f.deleted)
        assertEquals(1, f.pickers.size)
        assertEquals("host", f.form.host)
        assertEquals("", f.form.passphrase)
        assertNull(f.form.validate())
    }

    @Test fun reloadClearsSecretsErrorsAndUnsavedKeyOwnership() {
        val f = Fixture()
        f.selectFile()
        f.imports.single().second("unsaved-key")
        f.flush()
        f.form.passphrase = "secret"
        f.form.error = "old failure"
        f.form.load(profile("next-key"))
        assertEquals(listOf("unsaved-key"), f.deleted)
        assertEquals("next-key", f.form.keyId)
        assertEquals("", f.form.passphrase)
        assertEquals("", f.form.error)
    }

    @Test fun persistedKeysAreNotDeletedOnDisposeButReplacedUnsavedKeysAre() {
        val f = Fixture()
        f.selectFile()
        f.imports.last().second("first-key")
        f.flush()
        f.selectFile()
        f.imports.last().second("second-key")
        f.flush()
        f.form.markKeySaved()
        f.form.dispose()
        assertEquals(listOf("first-key"), f.deleted)
    }

    @Test fun pickerCancellationAndSynchronousBridgeFailureAreRetryable() {
        val f = Fixture()
        f.form.pickKey()
        f.pickers.single()("")
        f.flush()
        assertFalse(f.form.busy)
        val broken = DshSshProfileForm(scope(), pickFile = { throw IllegalStateException("native unavailable") }, post = { it() })
        broken.pickKey()
        assertFalse(broken.busy)
        assertTrue(broken.error.isNotEmpty())
    }

    @Test fun endpointEditsInvalidateTrustAndValidConfigClearsPreviousError() {
        val f = Fixture()
        f.form.host = "another-host"
        assertEquals("", f.form.fingerprint)
        f.form.fingerprint = "SHA256:new"
        f.form.sshPort = "2222"
        assertEquals("", f.form.fingerprint)
        f.form.error = "old failure"
        assertEquals(2222, f.form.validate()!!.sshPort)
        assertEquals("", f.form.error)
        f.form.sshPort = "0"
        assertNull(f.form.validate())
    }

    @Test fun passphraseIsTransientAndRedactedFromConfigDiagnostics() {
        val f = Fixture()
        f.form.passphrase = "do-not-log-me"
        val config = f.form.toSshConfig()
        assertEquals("do-not-log-me", config.keyPassphrase)
        assertFalse(config.toString().contains("do-not-log-me"))
        assertFalse(f.form.toProfile().toString().contains("do-not-log-me"))
    }

    private class Fixture {
        val pickers = mutableListOf<(String) -> Unit>()
        val imports = mutableListOf<Pair<String, (String) -> Unit>>()
        val deleted = mutableListOf<String>()
        val posts = mutableListOf<() -> Unit>()
        val form = DshSshProfileForm(scope(), pickFile = { pickers.add(it) },
            importFile = { uri, done -> imports.add(uri to done) }, deleteKey = { deleted.add(it) }, post = { posts.add(it) })
        init { form.load(profile("saved-key")) }
        fun flush() { while (posts.isNotEmpty()) posts.removeAt(0)() }
        fun selectFile() { form.pickKey(); pickers.last()("file"); flush() }
    }

    companion object {
        private fun scope() = object : PagerScope { override val pagerId = "test-page" }
        private fun profile(key: String) = DshRemoteProfile(host = "host", sshPort = 22, username = "user", remoteDshPort = 3080, keyId = key, hostFingerprint = "SHA256:trusted")
    }
}
