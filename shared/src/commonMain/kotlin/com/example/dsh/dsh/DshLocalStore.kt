package com.example.dsh.dsh

/** Small durable cache used to make the native client feel continuous across launches. */
internal interface DshLocalStore {
    fun loadLastConnectionMode(): DshConnectionMode
    fun saveLastConnectionMode(mode: DshConnectionMode)
    fun loadRemoteProfile(): DshRemoteProfile?
    fun saveRemoteProfile(profile: DshRemoteProfile)
    fun loadRelayProfile(): DshRelayProfile?
    fun saveRelayProfile(profile: DshRelayProfile)
    fun clearRelayProfile()
    fun migrateLegacyRemoteProfile(profile: DshLegacyRemoteProfile): Boolean
    fun clearLegacyRemotePreferenceKeys() = Unit

    fun loadSessions(scopeId: String): List<DshSession>
    fun replaceSessions(scopeId: String, sessions: List<DshSession>)
    fun loadMessages(scopeId: String, sessionId: String): List<DshMessage>
    fun replaceMessages(scopeId: String, sessionId: String, messages: List<DshMessage>)

    fun clearScope(scopeId: String)
}

internal expect fun createDshLocalStore(
    path: String,
    legacyProfile: DshLegacyRemoteProfile? = null,
): DshLocalStore
