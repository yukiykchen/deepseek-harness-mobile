package io.github.yukiykchen.dsh.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import io.github.yukiykchen.dsh.model.DshConnectionMode
import io.github.yukiykchen.dsh.model.DshLegacyRemoteProfile
import io.github.yukiykchen.dsh.model.DshMessage
import io.github.yukiykchen.dsh.model.DshRelayProfile
import io.github.yukiykchen.dsh.model.DshRemoteProfile
import io.github.yukiykchen.dsh.model.DshSession

class DshLocalStorePolicyTest {
    @Test
    fun emptySessionBaselinePreservesCachedMessages() {
        assertFalse(shouldPruneMessageCache(emptyList()))
    }

    @Test
    fun nonEmptySessionBaselinePrunesRemovedSessions() {
        assertTrue(shouldPruneMessageCache(listOf(DshSession("s1", "Session", "Host", ""))))
    }

    @Test
    fun defaultSubmitPreservesSynchronousContract() {
        val store = object : DshLocalStore {
            var mode: DshConnectionMode? = null
            override fun loadLastConnectionMode() = mode ?: DshConnectionMode.RELAY
            override fun saveLastConnectionMode(mode: DshConnectionMode) { this.mode = mode }
            override fun loadRemoteProfile(): DshRemoteProfile? = null
            override fun saveRemoteProfile(profile: DshRemoteProfile) = Unit
            override fun loadRelayProfile(): DshRelayProfile? = null
            override fun saveRelayProfile(profile: DshRelayProfile) = Unit
            override fun clearRelayProfile() = Unit
            override fun migrateLegacyRemoteProfile(profile: DshLegacyRemoteProfile) = false
            override fun loadSessions(scopeId: String) = emptyList<DshSession>()
            override fun replaceSessions(scopeId: String, sessions: List<DshSession>) = Unit
            override fun loadMessages(scopeId: String, sessionId: String) = emptyList<DshMessage>()
            override fun replaceMessages(scopeId: String, sessionId: String, messages: List<DshMessage>) = Unit
            override fun replaceSessionsAsync(scopeId: String, sessions: List<DshSession>) = replaceSessions(scopeId, sessions)
            override fun submitAsync(
                command: DshStoreCommand,
                data: DshStoreCommandData,
                post: (() -> Unit) -> Unit,
                onResult: (Throwable?) -> Unit,
            ) = post { onResult(null) }
            override fun clearScope(scopeId: String) = Unit
        }
        assertEquals(DshConnectionMode.RELAY, store.loadLastConnectionMode())
    }
}
