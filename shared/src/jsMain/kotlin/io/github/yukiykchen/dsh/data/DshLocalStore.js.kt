package io.github.yukiykchen.dsh.data

import io.github.yukiykchen.dsh.model.DshConnectionMode
import io.github.yukiykchen.dsh.model.DshRelayProfile
import io.github.yukiykchen.dsh.model.DshRemoteProfile
import io.github.yukiykchen.dsh.model.DshLegacyRemoteProfile
import io.github.yukiykchen.dsh.model.DshSession
import io.github.yukiykchen.dsh.model.DshMessage

actual fun createDshLocalStore(path: String, legacyProfile: DshLegacyRemoteProfile?): DshLocalStore = EmptyDshLocalStore
private object EmptyDshLocalStore : DshLocalStore {
    override fun loadLastConnectionMode(): DshConnectionMode = DshConnectionMode.RELAY
    override fun saveLastConnectionMode(mode: DshConnectionMode) = Unit
    override fun loadRemoteProfile(): DshRemoteProfile? = null
    override fun saveRemoteProfile(profile: DshRemoteProfile) = Unit
    override fun loadRelayProfile(): DshRelayProfile? = null
    override fun saveRelayProfile(profile: DshRelayProfile) = Unit
    override fun clearRelayProfile() = Unit
    override fun migrateLegacyRemoteProfile(profile: DshLegacyRemoteProfile): Boolean = false
    override fun loadSessions(scopeId: String): List<DshSession> = emptyList()
    override fun replaceSessions(scopeId: String, sessions: List<DshSession>) = Unit
    override fun loadMessages(scopeId: String, sessionId: String): List<DshMessage> = emptyList()
    override fun replaceMessages(scopeId: String, sessionId: String, messages: List<DshMessage>) = Unit
    override fun replaceSessionsAsync(scopeId: String, sessions: List<DshSession>) = Unit
    override fun submitAsync(
        command: DshStoreCommand,
        data: DshStoreCommandData,
        post: (() -> Unit) -> Unit,
        onResult: (Throwable?) -> Unit,
    ) = post { onResult(null) }
    override fun clearScope(scopeId: String) = Unit
}
