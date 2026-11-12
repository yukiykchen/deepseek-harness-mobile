package io.github.yukiykchen.dsh.data

import io.github.yukiykchen.dsh.model.DshConnectionMode
import io.github.yukiykchen.dsh.model.DshRelayProfile
import io.github.yukiykchen.dsh.model.DshRemoteProfile
import io.github.yukiykchen.dsh.model.DshLegacyRemoteProfile
import io.github.yukiykchen.dsh.model.DshSession
import io.github.yukiykchen.dsh.model.DshMessage

/** A transient empty Host baseline must not erase cached conversation rows. */

fun shouldPruneMessageCache(sessions: List<DshSession>): Boolean = sessions.isNotEmpty()

/** Small durable cache used to make the native client feel continuous across launches. */

interface DshLocalStore {
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
    fun replaceMessagesAsync(
        scopeId: String,
        sessionId: String,
        messages: List<DshMessage>,
    ) = replaceMessages(scopeId, sessionId, messages)
    fun clearScope(scopeId: String)

    /** Queue a transaction and receive its real completion result on [post]. */
    fun submitAsync(
        command: DshStoreCommand,
        data: DshStoreCommandData,
        post: (() -> Unit) -> Unit,
        onResult: (Throwable?) -> Unit,
    )

    /** Queue a best-effort cache snapshot; failures are reported through the database diagnostics. */
    fun replaceSessionsAsync(scopeId: String, sessions: List<DshSession>)

    /** Drain pending writes and release this store's database resources. */
    fun close() = Unit
}

enum class DshStoreCommand {
    SAVE_RELAY_MODE,
    SAVE_SSH_MODE,
    SAVE_RELAY_PROFILE,
    CLEAR_RELAY_PROFILE,
    SAVE_SSH_PROFILE,
}

data class DshStoreCommandData(
    val relayProfile: DshRelayProfile? = null,
    val remoteProfile: DshRemoteProfile? = null,
    val sessionsScopeId: String = "",
    val sessions: List<DshSession> = emptyList(),
)


expect fun createDshLocalStore(
    path: String,
    legacyProfile: DshLegacyRemoteProfile? = null,
): DshLocalStore

internal fun DshLocalStore?.closeSafely() {
    runCatching { this?.close() }.onFailure {
        io.github.yukiykchen.dsh.framework.dshCacheFailureLog("close")
    }
}
