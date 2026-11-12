package io.github.yukiykchen.dsh.data

import io.github.yukiykchen.dsh.framework.dshCacheFailureLog
import net.shantu.kuiklysqlite.ColumnType
import net.shantu.kuiklysqlite.DatabaseManager
import net.shantu.kuiklysqlite.SqlDriver
import net.shantu.kuiklysqlite.SqlSchema
import net.shantu.kuiklysqlite.SqlStatement
import io.github.yukiykchen.dsh.model.DshConnectionMode
import io.github.yukiykchen.dsh.model.DshSessionScope
import io.github.yukiykchen.dsh.model.DshRelayProfile
import io.github.yukiykchen.dsh.model.DshRemoteProfile
import io.github.yukiykchen.dsh.model.DshLegacyRemoteProfile
import io.github.yukiykchen.dsh.model.DshSession
import io.github.yukiykchen.dsh.model.DshMessage

/** Returns wall-clock millis from the SQLite platform; SQL and schema are shared. */

internal expect fun sqliteNowMs(): Long

/** SQL and driver access are confined to a single FIFO database worker. */

class DshSqliteLocalStore(
    path: String,
    legacyProfile: DshLegacyRemoteProfile?,
) : DshLocalStore {
    private val driverDelegate = lazy { DatabaseManager(path, DshSchema(legacyProfile)).driver }
    private val driver: SqlDriver by driverDelegate
    private val databaseQueue = DshDatabaseQueue(
        onAsyncFailure = { dshCacheFailureLog("message-write") },
        onClosed = { if (driverDelegate.isInitialized()) driver.close() },
    )

    private fun <T> onDatabase(block: () -> T): T = databaseQueue.call(block)

    override fun close() = databaseQueue.close()

    override fun submitAsync(
        command: DshStoreCommand,
        data: DshStoreCommandData,
        post: (() -> Unit) -> Unit,
        onResult: (Throwable?) -> Unit,
    ) {
        databaseQueue.submitReporting({
            runCommand(command, data)
        }, { error ->
            post { onResult(error) }
        })
        databaseQueue.submitReporting({ post { onResult(null) } }, { error ->
            post { onResult(error) }
        })
    }

    private fun runCommand(command: DshStoreCommand, data: DshStoreCommandData) {
        when (command) {
            DshStoreCommand.SAVE_RELAY_MODE -> saveLastConnectionModeOnDatabase(DshConnectionMode.RELAY)
            DshStoreCommand.SAVE_SSH_MODE -> saveLastConnectionModeOnDatabase(DshConnectionMode.SSH)
            DshStoreCommand.SAVE_RELAY_PROFILE -> saveRelayProfileOnDatabase(requireNotNull(data.relayProfile))
            DshStoreCommand.CLEAR_RELAY_PROFILE -> clearRelayProfileOnDatabase()
            DshStoreCommand.SAVE_SSH_PROFILE -> {
                val profile = requireNotNull(data.remoteProfile)
                driver.transaction {
                    insertProfile(profile, sqliteNowMs())
                    execute(
                        "INSERT OR REPLACE INTO dsh_settings (key, value) VALUES (?, ?)",
                        listOf("last_connection_mode", "ssh"),
                    )
                }
            }
        }
    }

    override fun replaceMessagesAsync(
        scopeId: String,
        sessionId: String,
        messages: List<DshMessage>,
    ) {
        val snapshot = messages.toList()
        databaseQueue.submit {
            replaceMessagesOnDatabase(scopeId, sessionId, snapshot)
        }
    }

    override fun loadLastConnectionMode(): DshConnectionMode = onDatabase {
        loadLastConnectionModeOnDatabase()
    }

    private fun loadLastConnectionModeOnDatabase(): DshConnectionMode {
        return queryOne("SELECT value FROM dsh_settings WHERE key = ?", listOf("last_connection_mode")) {
            it.getColumnString(0)
        }.orEmpty().let {
            when (it) {
                "relay" -> DshConnectionMode.RELAY
                "ssh", "remote" -> DshConnectionMode.SSH
                else -> DshConnectionMode.RELAY
            }
        }
    }

    private fun saveLastConnectionModeOnDatabase(mode: DshConnectionMode) {
        execute(
            "INSERT OR REPLACE INTO dsh_settings (key, value) VALUES (?, ?)",
            listOf("last_connection_mode", if (mode == DshConnectionMode.RELAY) "relay" else "ssh"),
        )
    }

    override fun saveLastConnectionMode(mode: DshConnectionMode) = onDatabase {
        saveLastConnectionModeOnDatabase(mode)
    }

    override fun loadRemoteProfile(): DshRemoteProfile? = onDatabase {
        queryOne(
            "SELECT profile_id, host, ssh_port, username, remote_dsh_port, key_id, host_fingerprint " +
                "FROM dsh_connection_profiles WHERE profile_id = ?",
            listOf(DshSessionScope.DEFAULT_REMOTE_PROFILE_ID),
        ) { s -> s.remoteProfile() }
    }

    override fun saveRemoteProfile(profile: DshRemoteProfile) = onDatabase {
        insertProfile(profile, sqliteNowMs())
    }

    override fun loadRelayProfile(): DshRelayProfile? = onDatabase {
        queryOne(
            "SELECT host_id, host_name, relay_origin, paired_at FROM dsh_relay_profiles LIMIT 1",
            emptyList(),
        ) { s -> DshRelayProfile(s.getColumnString(0), s.getColumnString(1), s.getColumnString(2), s.getColumnLong(3)) }
    }

    private fun saveRelayProfileOnDatabase(profile: DshRelayProfile) {
        driver.transaction {
            execute("DELETE FROM dsh_relay_profiles", emptyList())
            execute(
                "INSERT INTO dsh_relay_profiles (host_id, host_name, relay_origin, paired_at) VALUES (?, ?, ?, ?)",
                listOf(profile.hostId, profile.hostName, profile.relayOrigin, profile.pairedAt.toString()),
            )
        }
    }

    override fun saveRelayProfile(profile: DshRelayProfile) = onDatabase {
        saveRelayProfileOnDatabase(profile)
    }

    private fun clearRelayProfileOnDatabase() {
        execute("DELETE FROM dsh_relay_profiles", emptyList())
    }

    override fun clearRelayProfile() = onDatabase { clearRelayProfileOnDatabase() }

    override fun migrateLegacyRemoteProfile(profile: DshLegacyRemoteProfile): Boolean = onDatabase {
        if (queryOne(
                "SELECT value FROM dsh_settings WHERE key = ?",
                listOf("remote_profile_migration_done"),
            ) { it.getColumnString(0) } == "true"
        ) return@onDatabase false
        driver.transaction {
            insertProfile(DshRemoteProfile(host = profile.host, sshPort = profile.sshPort, username = profile.username, remoteDshPort = profile.remoteDshPort, keyId = profile.keyId, hostFingerprint = profile.hostFingerprint), sqliteNowMs())
            execute("INSERT OR REPLACE INTO dsh_settings (key, value) VALUES (?, ?)", listOf("remote_profile_migration_done", "true"))
        }
        true
    }

    override fun loadSessions(scopeId: String): List<DshSession> = onDatabase {
        query(
            "SELECT id, title, workspace, updated_label, running, blank, cwd, parent_session_id, origin, agent_preset FROM dsh_sessions WHERE scope_id = ? ORDER BY updated_at DESC",
            listOf(scopeId),
        ) { s -> DshSession(
            s.getColumnString(0), s.getColumnString(1), s.getColumnString(2), s.getColumnString(3),
            s.getColumnLong(4) != 0L, s.getColumnLong(5) != 0L, s.getColumnString(6),
            s.getColumnString(7).takeIf { it.isNotEmpty() }, s.getColumnString(8).takeIf { it.isNotEmpty() },
            s.getColumnString(9).takeIf { it.isNotEmpty() },
        ) }
    }

    private fun replaceSessionsOnDatabase(scopeId: String, sessions: List<DshSession>) {
        val now = sqliteNowMs()
        driver.transaction {
            if (shouldPruneMessageCache(sessions)) {
                val placeholders = sessions.joinToString(",") { "?" }
                execute(
                    "DELETE FROM dsh_messages WHERE scope_id = ? AND session_id NOT IN ($placeholders)",
                    listOf(scopeId) + sessions.map { it.id },
                )
            }
            // A transient empty Host baseline only replaces the session index.
            // Message caches remain until their session is explicitly removed.
            execute("DELETE FROM dsh_sessions WHERE scope_id = ?", listOf(scopeId))
            sessions.forEachIndexed { index, session ->
                execute(
                    "INSERT OR REPLACE INTO dsh_sessions (scope_id, id, title, workspace, updated_label, running, blank, cwd, parent_session_id, origin, agent_preset, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    listOf(scopeId, session.id, session.title, session.workspace, session.updatedLabel,
                        if (session.running) "1" else "0", if (session.blank) "1" else "0", session.cwd,
                        session.parentSessionId, session.origin, session.agentPreset, (now - index).toString()),
                )
            }
        }
    }

    override fun replaceSessions(scopeId: String, sessions: List<DshSession>) = onDatabase {
        replaceSessionsOnDatabase(scopeId, sessions)
    }

    override fun replaceSessionsAsync(scopeId: String, sessions: List<DshSession>) {
        val snapshot = sessions.toList()
        databaseQueue.submit { replaceSessionsOnDatabase(scopeId, snapshot) }
    }

    override fun loadMessages(scopeId: String, sessionId: String): List<DshMessage> = onDatabase {
        query(
            "SELECT payload FROM dsh_messages WHERE scope_id = ? AND session_id = ? ORDER BY seq ASC",
            listOf(scopeId, sessionId),
        ) { s -> dshMessageFromCache(s.getColumnString(0)) }
    }

    override fun replaceMessages(scopeId: String, sessionId: String, messages: List<DshMessage>) = onDatabase {
        replaceMessagesOnDatabase(scopeId, sessionId, messages)
    }

    private fun replaceMessagesOnDatabase(scopeId: String, sessionId: String, messages: List<DshMessage>) {
        driver.transaction {
            execute("DELETE FROM dsh_messages WHERE scope_id = ? AND session_id = ?", listOf(scopeId, sessionId))
            messages.forEachIndexed { index, message ->
                execute(
                    "INSERT OR REPLACE INTO dsh_messages (scope_id, id, session_id, payload, seq) VALUES (?, ?, ?, ?, ?)",
                    listOf(scopeId, message.id, sessionId, message.toCacheJson(), index.toString()),
                )
            }
        }
    }

    override fun clearScope(scopeId: String) = onDatabase {
        driver.transaction {
            execute("DELETE FROM dsh_messages WHERE scope_id = ?", listOf(scopeId))
            execute("DELETE FROM dsh_sessions WHERE scope_id = ?", listOf(scopeId))
        }
    }

    private fun insertProfile(profile: DshRemoteProfile, updatedAt: Long) = execute(
        "INSERT OR REPLACE INTO dsh_connection_profiles " +
            "(profile_id, mode, protocol, host, ssh_port, username, remote_dsh_port, key_id, host_fingerprint, updated_at) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        listOf(profile.profileId, "SSH", "SSH_TUNNEL", profile.host, profile.sshPort.toString(), profile.username, profile.remoteDshPort.toString(), profile.keyId, profile.hostFingerprint, updatedAt.toString()),
    )

    private fun SqlStatement.remoteProfile(): DshRemoteProfile = DshRemoteProfile(
        getColumnString(0), getColumnString(1), getColumnLong(2).toInt(), getColumnString(3),
        getColumnLong(4).toInt(), getColumnString(5), getColumnString(6),
    )

    private fun execute(sql: String, args: List<String?>) {
        val statement = driver.prepare(sql)
        try {
            args.forEachIndexed { index, value -> statement.bindString(index + 1, value) }
            statement.step()
        } finally {
            statement.close()
        }
    }

    private fun <T> query(sql: String, args: List<String?>, mapper: (SqlStatement) -> T): List<T> {
        val statement = driver.prepare(sql)
        return try {
            args.forEachIndexed { index, value -> statement.bindString(index + 1, value) }
            buildList { while (statement.step()) add(mapper(statement)) }
        } finally {
            statement.close()
        }
    }

    private fun <T> queryOne(sql: String, args: List<String?>, mapper: (SqlStatement) -> T): T? =
        query(sql, args, mapper).firstOrNull()
}

/** Shared table and migration definitions for every native SQLite driver. */

class DshSchema(private val legacyProfile: DshLegacyRemoteProfile?) : SqlSchema {
    override val version: Int = 6

    override fun create(driver: SqlDriver) {
        driver.execute("CREATE TABLE IF NOT EXISTS dsh_settings (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        createScopedTables(driver)
        createProfileTable(driver)
        createRelayTable(driver)
        writeLegacyProfile(driver)
    }

    override fun migrate(driver: SqlDriver, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 4) {
            // Legacy caches cannot be attributed reliably, so v4 deliberately drops them.
            // DatabaseManager already wraps schema migration in one transaction.
            driver.execute("DROP TABLE IF EXISTS dsh_messages")
            driver.execute("DROP TABLE IF EXISTS dsh_sessions")
            createScopedTables(driver)
            createProfileTable(driver)
            writeLegacyProfile(driver)
        }
        if (oldVersion < 5) migrateToV5(driver)
        if (oldVersion < 6) {
            // v5 columns cannot preserve the full message/session model.
            driver.execute("DROP TABLE IF EXISTS dsh_messages")
            driver.execute("DROP TABLE IF EXISTS dsh_sessions")
            createScopedTables(driver)
        }
    }

    private fun migrateToV5(driver: SqlDriver) {
        createRelayTable(driver)
        execute(driver, "UPDATE dsh_settings SET value = ? WHERE key = ? AND value = ?", listOf("ssh", "last_connection_mode", "remote"))
        execute(driver, "UPDATE dsh_sessions SET scope_id = 'ssh:' || substr(scope_id, 8) WHERE scope_id LIKE 'remote:%'", emptyList())
        execute(driver, "UPDATE dsh_messages SET scope_id = 'ssh:' || substr(scope_id, 8) WHERE scope_id LIKE 'remote:%'", emptyList())
        execute(driver, "UPDATE dsh_connection_profiles SET mode = ? WHERE mode = ?", listOf("SSH", "REMOTE"))
    }

    private fun writeLegacyProfile(driver: SqlDriver) {
        val profile = legacyProfile ?: return
        execute(
            driver,
            "INSERT OR REPLACE INTO dsh_connection_profiles " +
                "(profile_id, mode, protocol, host, ssh_port, username, remote_dsh_port, key_id, host_fingerprint, updated_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            listOf(
                DshSessionScope.DEFAULT_REMOTE_PROFILE_ID, "SSH", "SSH_TUNNEL", profile.host,
                profile.sshPort.toString(), profile.username, profile.remoteDshPort.toString(),
                profile.keyId, profile.hostFingerprint, sqliteNowMs().toString(),
            ),
        )
        execute(driver, "INSERT OR REPLACE INTO dsh_settings (key, value) VALUES (?, ?)", listOf("remote_profile_migration_done", "true"))
    }

    private fun execute(driver: SqlDriver, sql: String, args: List<String?>) {
        val statement = driver.prepare(sql)
        try {
            args.forEachIndexed { index, value -> statement.bindString(index + 1, value) }
            statement.step()
        } finally {
            statement.close()
        }
    }

    private fun createScopedTables(driver: SqlDriver) {
        driver.execute("CREATE TABLE IF NOT EXISTS dsh_sessions (scope_id TEXT NOT NULL, id TEXT NOT NULL, title TEXT NOT NULL, workspace TEXT NOT NULL, updated_label TEXT NOT NULL, running INTEGER NOT NULL, blank INTEGER NOT NULL, cwd TEXT NOT NULL, parent_session_id TEXT, origin TEXT, agent_preset TEXT, updated_at INTEGER NOT NULL, PRIMARY KEY (scope_id, id))")
        driver.execute("CREATE TABLE IF NOT EXISTS dsh_messages (scope_id TEXT NOT NULL, id TEXT NOT NULL, session_id TEXT NOT NULL, payload TEXT NOT NULL, seq INTEGER NOT NULL, PRIMARY KEY (scope_id, session_id, id))")
        driver.execute("CREATE INDEX IF NOT EXISTS idx_dsh_messages_session ON dsh_messages(scope_id, session_id, seq)")
    }

    private fun createProfileTable(driver: SqlDriver) {
        driver.execute("CREATE TABLE IF NOT EXISTS dsh_connection_profiles (profile_id TEXT PRIMARY KEY, mode TEXT NOT NULL, protocol TEXT NOT NULL, host TEXT NOT NULL, ssh_port INTEGER NOT NULL, username TEXT NOT NULL, remote_dsh_port INTEGER NOT NULL, key_id TEXT NOT NULL, host_fingerprint TEXT NOT NULL, updated_at INTEGER NOT NULL)")
    }

    private fun createRelayTable(driver: SqlDriver) {
        driver.execute("CREATE TABLE IF NOT EXISTS dsh_relay_profiles (host_id TEXT PRIMARY KEY, host_name TEXT NOT NULL, relay_origin TEXT NOT NULL, paired_at INTEGER NOT NULL)")
    }
}
