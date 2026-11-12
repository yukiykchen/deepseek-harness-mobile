package io.github.yukiykchen.dsh.relay

import android.content.Context
import io.github.yukiykchen.dsh.security.DshEncryptedFiles
import io.github.yukiykchen.dsh.security.DshKeystoreKey
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject

/** Wraps Relay pairing secrets with Android Keystore AES-GCM. SQLite never stores tokens. */
internal class DshRelaySecrets(private val files: DshEncryptedFiles) : DshRelayCredentialStore {
    constructor(context: Context) : this(DshEncryptedFiles(File(context.filesDir, "relay-secrets"),
        DshKeystoreKey("dsh.relay.secret-store")::get))

    override fun load(): DshRelayCredentials? = files.locked {
        if (files.exists(RECORD)) {
            val json = JSONObject(requireNotNull(readString(RECORD)))
            val credentials = DshRelayCredentials(json.getString("masterKey"), json.getString("clientToken"),
                json.getString("hostId"), json.getString("hostName"), json.getString("relayOrigin"))
            validate(credentials)
            removeLegacy()
            return@locked credentials
        }
        val master = readString("master.bin") ?: return@locked null
        val token = readString("client.bin") ?: error("Incomplete legacy pairing")
        val origin = readString("origin.bin") ?: error("Incomplete legacy pairing")
        val credentials = DshRelayCredentials(master, token, readString("host.bin").orEmpty(),
            readString("name.bin").orEmpty(), origin)
        save(credentials)
        credentials
    }

    override fun save(credentials: DshRelayCredentials) = files.locked {
        validate(credentials)
        val plaintext = JSONObject().put("masterKey", credentials.masterKey).put("clientToken", credentials.clientToken)
            .put("hostId", credentials.hostId).put("hostName", credentials.hostName)
            .put("relayOrigin", credentials.relayOrigin).toString().toByteArray(Charsets.UTF_8)
        try { files.write(RECORD, plaintext) } finally { plaintext.fill(0) }
        removeLegacy()
    }

    override fun clear() = files.locked {
        var failed = false
        for (name in LEGACY + RECORD) {
            try { files.delete(name) } catch (_: Exception) { failed = true }
        }
        check(!failed) { "Cannot remove pairing credentials" }
    }

    private fun readString(name: String): String? {
        val bytes = files.read(name) ?: return null
        return try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } finally { bytes.fill(0) }
    }

    private fun removeLegacy() { LEGACY.forEach(files::delete) }

    private fun validate(credentials: DshRelayCredentials) {
        val decoded = SealedTunnelCrypto.decodeBase64Url(credentials.masterKey, 32)
        decoded.fill(0)
        require(credentials.clientToken.isNotEmpty() && credentials.clientToken.all { it.code in 0x21..0x7e }) {
            "Invalid pairing token"
        }
        val url = credentials.relayOrigin.toHttpUrlOrNull() ?: error("Invalid Relay origin")
        require(url.username.isEmpty() && url.password.isEmpty()) { "Invalid Relay origin" }
    }

    companion object {
        private const val RECORD = "pairing.bin"
        private val LEGACY = listOf("master.bin", "client.bin", "host.bin", "name.bin", "origin.bin")
    }
}
