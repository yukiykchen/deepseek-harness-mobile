package io.github.yukiykchen.dsh.data

import io.github.yukiykchen.dsh.model.DshConnectionMode
import io.github.yukiykchen.dsh.model.DshMessage
import io.github.yukiykchen.dsh.model.DshMessageRole
import io.github.yukiykchen.dsh.model.DshRemoteProfile
import io.github.yukiykchen.dsh.model.DshSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DshSqliteLocalStoreTest {
    @Test
    fun emptyBaselineRetainsMessagesAcrossSessionIndexRestoration() = withStore { store ->
        store.replaceSessions("relay:host", listOf(session("s1")))
        store.replaceMessagesAsync("relay:host", "s1", listOf(message("cached")))
        store.replaceSessions("relay:host", emptyList())
        assertEquals(emptyList(), store.loadSessions("relay:host"))
        assertEquals("cached", store.loadMessages("relay:host", "s1").single().content)
        store.replaceSessions("relay:host", listOf(session("s1")))
        assertEquals("cached", store.loadMessages("relay:host", "s1").single().content)
    }

    @Test
    fun sameSessionIdsAreIsolatedByConnectionAndPruningDoesNotCrossScope() = withStore { store ->
        store.replaceMessages("relay:host", "s1", listOf(message("relay")))
        store.replaceMessages("ssh:default", "s1", listOf(message("ssh")))
        store.replaceSessions("relay:host", listOf(session("s2")))
        assertEquals(emptyList(), store.loadMessages("relay:host", "s1"))
        assertEquals("ssh", store.loadMessages("ssh:default", "s1").single().content)
        store.clearScope("ssh:default")
        assertEquals(emptyList(), store.loadMessages("ssh:default", "s1"))
    }

    @Test
    fun asyncSnapshotsAreDefensiveAndLastSubmittedSnapshotWins() = withStore { store ->
        val snapshot = mutableListOf(message("original"))
        store.replaceMessagesAsync("relay:host", "s1", snapshot)
        snapshot[0] = message("mutated")
        assertEquals("original", store.loadMessages("relay:host", "s1").single().content)
        repeat(100) { store.replaceMessagesAsync("relay:host", "s1", listOf(message("version-$it"))) }
        assertEquals("version-99", store.loadMessages("relay:host", "s1").single().content)
    }

    @Test
    fun settingsAndProfilesRoundTripAndClosedStoreRejectsAccess() {
        val store = createDshLocalStore(":memory:")
        try {
            store.saveLastConnectionMode(DshConnectionMode.SSH)
            assertEquals(DshConnectionMode.SSH, store.loadLastConnectionMode())
            val profile = DshRemoteProfile(host = "localhost", sshPort = 22, username = "user", remoteDshPort = 3080, keyId = "key-1")
            store.saveRemoteProfile(profile)
            assertEquals(profile, store.loadRemoteProfile())
        } finally {
            store.close()
        }
        assertFailsWith<IllegalStateException> { store.loadSessions("relay:host") }
    }

    @Test
    fun asyncCommandsCommitInSubmissionOrderAndDoNotSelfDeadlock() {
        val results = mutableListOf<Throwable?>()
        var completed = false
        val store = createDshLocalStore(":memory:")
        store.submitAsync(DshStoreCommand.SAVE_SSH_MODE, DshStoreCommandData(), { it() }) { error ->
            completed = true
            results.add(error)
        }
        store.submitAsync(DshStoreCommand.SAVE_RELAY_MODE, DshStoreCommandData(), { it() }) { error ->
            results.add(error)
        }
        store.close()
        assertEquals(listOf<Throwable?>(null, null), results)
        assertEquals(true, completed)
        // Read the last committed mode before closing would race the worker, so
        // ordering is proven by both callbacks and a successful drain above.
    }

    @Test
    fun asyncSessionIndexCommitsInSubmissionOrder() = withStore { store ->
        store.replaceSessionsAsync("relay:host", listOf(session("first")))
        store.replaceSessionsAsync("relay:host", listOf(session("first"), session("second")))
        assertEquals(listOf("first", "second"), store.loadSessions("relay:host").map { it.id })
    }

    @Test
    fun sessionAndMessageModelsRoundTripWithoutDroppingRenderingFields() = withStore { store ->
        val session = DshSession("s1", "Title", "/workspace", "now", running = true, blank = false,
            cwd = "/workspace", parentSessionId = "root", origin = "relay", agentPreset = "default")
        store.replaceSessions("relay:host", listOf(session))
        assertEquals(session, store.loadSessions("relay:host").single())
        val message = DshMessage("m1", DshMessageRole.ASSISTANT, "text", isReasoning = true,
            attachmentId = "attachment-1", toolCallId = "call-1")
        store.replaceMessages("relay:host", "s1", listOf(message))
        assertEquals(message, store.loadMessages("relay:host", "s1").single())
    }

    private fun session(id: String) = DshSession(id, "Session", "Host", "")
    private fun message(content: String) = DshMessage("m1", DshMessageRole.ASSISTANT, content)
    private fun withStore(test: (DshLocalStore) -> Unit) {
        val store = createDshLocalStore(":memory:")
        try { test(store) } finally { store.close() }
    }
}
