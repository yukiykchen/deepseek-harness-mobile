package com.example.dsh.dsh

import net.shantu.kuiklysqlite.ColumnType
import net.shantu.kuiklysqlite.DatabaseManager
import net.shantu.kuiklysqlite.SqlDriver
import net.shantu.kuiklysqlite.SqlSchema
import net.shantu.kuiklysqlite.SqlStatement

internal actual fun createDshLocalStore(path: String, legacyProfile: DshLegacyRemoteProfile?): DshLocalStore =
    DshSqliteStore(path, legacyProfile)

private class DshSqliteStore(path: String, legacyProfile: DshLegacyRemoteProfile?) : DshLocalStore {
    private val driver: SqlDriver by lazy { DatabaseManager(path, DshSchema(legacyProfile)).driver }

    override fun loadApiKey(): String = queryOne("SELECT value FROM dsh_settings WHERE key = ?", listOf("deepseek_api_key")) {
        it.getColumnString(0)
    }.orEmpty()

    override fun saveApiKey(apiKey: String) = execute(
        "INSERT OR REPLACE INTO dsh_settings (key, value) VALUES (?, ?)",
        listOf("deepseek_api_key", apiKey),
    )

    override fun loadSetting(key: String): String = queryOne("SELECT value FROM dsh_settings WHERE key = ?", listOf(key)) {
        it.getColumnString(0)
    }.orEmpty()

    override fun saveSetting(key: String, value: String) = execute(
        "INSERT OR REPLACE INTO dsh_settings (key, value) VALUES (?, ?)",
        listOf(key, value),
    )

    override fun loadLastConnectionMode(): DshConnectionMode = queryOne(
        "SELECT value FROM dsh_settings WHERE key = ?", listOf("last_connection_mode"),
    ) { it.getColumnString(0) }.orEmpty().let {
        when (it) {
            "relay" -> DshConnectionMode.RELAY
            "ssh", "remote" -> DshConnectionMode.SSH
            "direct" -> DshConnectionMode.DIRECT
            else -> DshConnectionMode.RELAY
        }
    }

    override fun saveLastConnectionMode(mode: DshConnectionMode) = execute(
        "INSERT OR REPLACE INTO dsh_settings (key, value) VALUES (?, ?)",
        listOf(
            "last_connection_mode",
            when (mode) {
                DshConnectionMode.RELAY -> "relay"
                DshConnectionMode.SSH -> "ssh"
                DshConnectionMode.LOCAL -> "local"
                DshConnectionMode.DIRECT -> "direct"
            },
        ),
    )

    override fun loadRemoteProfile(): DshRemoteProfile? = queryOne(
        "SELECT profile_id, host, ssh_port, username, remote_dsh_port, key_id, host_fingerprint, auth_token " +
            "FROM dsh_connection_profiles WHERE profile_id = ?",
        listOf(DshSessionScope.DEFAULT_REMOTE_PROFILE_ID),
    ) { s ->
        DshRemoteProfile(s.getColumnString(0), s.getColumnString(1), s.getColumnLong(2).toInt(), s.getColumnString(3), s.getColumnLong(4).toInt(), s.getColumnString(5), s.getColumnString(6), s.getColumnString(7))
    }

    override fun saveRemoteProfile(profile: DshRemoteProfile) = execute(
        "INSERT OR REPLACE INTO dsh_connection_profiles " +
            "(profile_id, mode, protocol, host, ssh_port, username, remote_dsh_port, key_id, host_fingerprint, auth_token, updated_at) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        listOf(profile.profileId, "SSH", "SSH_TUNNEL", profile.host, profile.sshPort.toString(), profile.username, profile.remoteDshPort.toString(), profile.keyId, profile.hostFingerprint, profile.authToken, System.currentTimeMillis().toString()),
    )

    override fun loadRelayProfile(): DshRelayProfile? = queryOne(
        "SELECT host_id, host_name, relay_origin, paired_at FROM dsh_relay_profiles LIMIT 1",
        emptyList(),
    ) { s ->
        DshRelayProfile(s.getColumnString(0), s.getColumnString(1), s.getColumnString(2), s.getColumnLong(3))
    }

    override fun saveRelayProfile(profile: DshRelayProfile) {
        driver.transaction {
            execute("DELETE FROM dsh_relay_profiles", emptyList())
            execute(
                "INSERT INTO dsh_relay_profiles (host_id, host_name, relay_origin, paired_at) VALUES (?, ?, ?, ?)",
                listOf(profile.hostId, profile.hostName, profile.relayOrigin, profile.pairedAt.toString()),
            )
        }
    }

    override fun clearRelayProfile() = execute("DELETE FROM dsh_relay_profiles", emptyList())

    override fun migrateLegacyRemoteProfile(profile: DshLegacyRemoteProfile): Boolean {
        if (queryOne("SELECT value FROM dsh_settings WHERE key = ?", listOf("remote_profile_migration_done")) { it.getColumnString(0) } == "true") return false
        driver.transaction {
            execute("INSERT OR REPLACE INTO dsh_connection_profiles (profile_id, mode, protocol, host, ssh_port, username, remote_dsh_port, key_id, host_fingerprint, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", listOf(DshSessionScope.DEFAULT_REMOTE_PROFILE_ID, "SSH", "SSH_TUNNEL", profile.host, profile.sshPort.toString(), profile.username, profile.remoteDshPort.toString(), profile.keyId, profile.hostFingerprint, System.currentTimeMillis().toString()))
            execute("INSERT OR REPLACE INTO dsh_settings (key, value) VALUES (?, ?)", listOf("remote_profile_migration_done", "true"))
        }
        return true
    }

    override fun loadSessions(scopeId: String): List<DshSession> = query(
        "SELECT id, title, workspace, updated_label, running FROM dsh_sessions WHERE scope_id = ? ORDER BY updated_at DESC", listOf(scopeId),
    ) { s -> DshSession(s.getColumnString(0), s.getColumnString(1), s.getColumnString(2), s.getColumnString(3), s.getColumnLong(4) != 0L) }

    override fun replaceSessions(scopeId: String, sessions: List<DshSession>) {
        val now = System.currentTimeMillis()
        driver.transaction {
            if (sessions.isEmpty()) {
                execute("DELETE FROM dsh_messages WHERE scope_id = ?", listOf(scopeId))
            } else {
                val placeholders = sessions.joinToString(",") { "?" }
                execute(
                    "DELETE FROM dsh_messages WHERE scope_id = ? AND session_id NOT IN ($placeholders)",
                    listOf(scopeId) + sessions.map { it.id },
                )
            }
            execute("DELETE FROM dsh_sessions WHERE scope_id = ?", listOf(scopeId))
            sessions.forEachIndexed { index, s ->
                execute("INSERT OR REPLACE INTO dsh_sessions (scope_id, id, title, workspace, updated_label, running, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)", listOf(scopeId, s.id, s.title, s.workspace, s.updatedLabel, if (s.running) "1" else "0", (now - index).toString()))
            }
        }
    }

    override fun loadMessages(scopeId: String, sessionId: String): List<DshMessage> = query(
        "SELECT id, role, content, streaming, tool_name, hidden FROM dsh_messages WHERE scope_id = ? AND session_id = ? ORDER BY seq ASC", listOf(scopeId, sessionId),
    ) { s -> DshMessage(s.getColumnString(0), DshMessageRole.valueOf(s.getColumnString(1)), s.getColumnString(2), s.getColumnLong(3) != 0L, nullableString(s, 4), s.getColumnLong(5) != 0L) }

    override fun replaceMessages(scopeId: String, sessionId: String, messages: List<DshMessage>) {
        driver.transaction {
            execute("DELETE FROM dsh_messages WHERE scope_id = ? AND session_id = ?", listOf(scopeId, sessionId))
            messages.forEachIndexed { index, m ->
                execute("INSERT OR REPLACE INTO dsh_messages (scope_id, id, session_id, role, content, streaming, tool_name, hidden, seq) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", listOf(scopeId, m.id, sessionId, m.role.name, m.content, if (m.streaming) "1" else "0", m.toolName, if (m.hidden) "1" else "0", index.toString()))
            }
        }
    }

    override fun clearScope(scopeId: String) {
        driver.transaction {
            execute("DELETE FROM dsh_messages WHERE scope_id = ?", listOf(scopeId))
            execute("DELETE FROM dsh_sessions WHERE scope_id = ?", listOf(scopeId))
        }
    }

    private fun execute(sql: String, args: List<String?>) {
        val s = driver.prepare(sql)
        try { bind(s, args); s.step() } finally { s.close() }
    }

    private fun <T> query(sql: String, args: List<String?>, mapper: (SqlStatement) -> T): List<T> {
        val s = driver.prepare(sql)
        return try { bind(s, args); buildList { while (s.step()) add(mapper(s)) } } finally { s.close() }
    }

    private fun <T> queryOne(sql: String, args: List<String?>, mapper: (SqlStatement) -> T): T? = query(sql, args, mapper).firstOrNull()
    private fun bind(s: SqlStatement, args: List<String?>) = args.forEachIndexed { i, value -> s.bindString(i + 1, value) }
    private fun nullableString(s: SqlStatement, index: Int): String? = if (s.getColumnType(index) == ColumnType.NULL) null else s.getColumnString(index)
}

private class DshSchema(private val legacyProfile: DshLegacyRemoteProfile?) : SqlSchema {
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
        if (oldVersion < 6) migrateToV6(driver)
    }

    private fun migrateToV6(driver: SqlDriver) {
        driver.execute("ALTER TABLE dsh_connection_profiles ADD COLUMN auth_token TEXT NOT NULL DEFAULT ''")
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
        execute(driver,
            "INSERT OR REPLACE INTO dsh_connection_profiles " +
                "(profile_id, mode, protocol, host, ssh_port, username, remote_dsh_port, key_id, host_fingerprint, updated_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            listOf(
                DshSessionScope.DEFAULT_REMOTE_PROFILE_ID, "SSH", "SSH_TUNNEL", profile.host,
                profile.sshPort.toString(), profile.username, profile.remoteDshPort.toString(),
                profile.keyId, profile.hostFingerprint, System.currentTimeMillis().toString(),
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
        driver.execute("CREATE TABLE IF NOT EXISTS dsh_sessions (scope_id TEXT NOT NULL, id TEXT NOT NULL, title TEXT NOT NULL, workspace TEXT NOT NULL, updated_label TEXT NOT NULL, running INTEGER NOT NULL, updated_at INTEGER NOT NULL, PRIMARY KEY (scope_id, id))")
        driver.execute("CREATE TABLE IF NOT EXISTS dsh_messages (scope_id TEXT NOT NULL, id TEXT NOT NULL, session_id TEXT NOT NULL, role TEXT NOT NULL, content TEXT NOT NULL, streaming INTEGER NOT NULL, tool_name TEXT, hidden INTEGER NOT NULL, seq INTEGER NOT NULL, PRIMARY KEY (scope_id, session_id, id))")
        driver.execute("CREATE INDEX IF NOT EXISTS idx_dsh_messages_session ON dsh_messages(scope_id, session_id, seq)")
    }

    private fun createProfileTable(driver: SqlDriver) {
        driver.execute("CREATE TABLE IF NOT EXISTS dsh_connection_profiles (profile_id TEXT PRIMARY KEY, mode TEXT NOT NULL, protocol TEXT NOT NULL, host TEXT NOT NULL, ssh_port INTEGER NOT NULL, username TEXT NOT NULL, remote_dsh_port INTEGER NOT NULL, key_id TEXT NOT NULL, host_fingerprint TEXT NOT NULL, updated_at INTEGER NOT NULL, auth_token TEXT NOT NULL DEFAULT '')")
    }

    private fun createRelayTable(driver: SqlDriver) {
        driver.execute("CREATE TABLE IF NOT EXISTS dsh_relay_profiles (host_id TEXT PRIMARY KEY, host_name TEXT NOT NULL, relay_origin TEXT NOT NULL, paired_at INTEGER NOT NULL)")
    }
}
