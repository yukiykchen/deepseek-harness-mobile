package com.example.dsh.dsh

internal actual fun createDshLocalStore(path: String, legacyProfile: DshLegacyRemoteProfile?): DshLocalStore = EmptyDshLocalStore

private object EmptyDshLocalStore : DshLocalStore {
    override fun loadApiKey(): String = ""
    override fun saveApiKey(apiKey: String) = Unit
    override fun loadLastConnectionMode(): DshConnectionMode = DshConnectionMode.RELAY
    override fun saveLastConnectionMode(mode: DshConnectionMode) = Unit
    override fun loadRemoteProfile(): DshRemoteProfile? = null
    override fun saveRemoteProfile(profile: DshRemoteProfile) = Unit
    override fun loadRelayProfile(): DshRelayProfile? = null
    override fun saveRelayProfile(profile: DshRelayProfile) = Unit
    override fun clearRelayProfile() = Unit
    override fun migrateLegacyRemoteProfile(profile: DshLegacyRemoteProfile): Boolean = false
    override fun loadSetting(key: String): String = ""
    override fun saveSetting(key: String, value: String) = Unit
    override fun loadSessions(connectionId: String): List<DshSession> = emptyList()
    override fun replaceSessions(connectionId: String, sessions: List<DshSession>) = Unit
    override fun loadMessages(connectionId: String, sessionId: String): List<DshMessage> = emptyList()
    override fun replaceMessages(connectionId: String, sessionId: String, messages: List<DshMessage>) = Unit
    override fun clearScope(scopeId: String) = Unit
}
