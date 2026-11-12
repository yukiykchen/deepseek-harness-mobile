package io.github.yukiykchen.dsh.relay

import io.github.yukiykchen.dsh.security.DshEncryptedFiles
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import javax.crypto.KeyGenerator

class DshRelaySecretsTest {
    @get:Rule val temp = TemporaryFolder()
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val credentials = DshRelayCredentials(SealedTunnelCrypto.encodeBase64Url(ByteArray(32) { 1 }),
        "client-token", "host", "Host", "https://relay.example")

    @Test fun pairingCommitsOneRecordAndClearRemovesAllCredentials() {
        val root = temp.newFolder()
        val store = DshRelaySecrets(DshEncryptedFiles(root, { key }))
        assertNull(store.load())
        store.save(credentials)
        assertEquals(credentials, store.load())
        assertEquals(listOf("pairing.bin"), root.listFiles()!!.map { it.name })
        store.clear()
        assertNull(store.load())
        assertTrue(root.listFiles()!!.isEmpty())
    }

    @Test fun fiveLegacyFieldsMigrateOnlyAfterTheNewRecordCommits() {
        val root = temp.newFolder()
        val files = DshEncryptedFiles(root, { key })
        seed(files)
        assertEquals(credentials, DshRelaySecrets(files).load())
        assertEquals(listOf("pairing.bin"), root.listFiles()!!.map { it.name })
    }

    @Test fun migrationWriteFailurePreservesAllLegacyFields() {
        val root = temp.newFolder()
        seed(DshEncryptedFiles(root, { key }))
        val failing = DshRelaySecrets(DshEncryptedFiles(root, { key }) { _, _ -> throw IOException("storage unavailable") })
        assertThrows(IOException::class.java) { failing.load() }
        assertEquals(5, root.listFiles()!!.size)
        assertEquals(credentials, DshRelaySecrets(DshEncryptedFiles(root, { key })).load())
    }

    @Test fun authenticationFailureInNewRecordCannotUseStaleLegacyPairing() {
        val root = temp.newFolder()
        val files = DshEncryptedFiles(root, { key })
        val store = DshRelaySecrets(files)
        store.save(credentials)
        seed(files)
        val record = File(root, "pairing.bin")
        record.writeBytes(record.readBytes().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() })
        assertThrows(Exception::class.java) { store.load() }
        assertTrue(File(root, "master.bin").isFile)
    }

    @Test fun failedReplacementKeepsTheCompletePreviousPairing() {
        val root = temp.newFolder()
        val store = DshRelaySecrets(DshEncryptedFiles(root, { key }))
        store.save(credentials)
        val failing = DshRelaySecrets(DshEncryptedFiles(root, { key }) { _, _ -> throw IOException("rename") })
        assertThrows(IOException::class.java) { failing.save(credentials.copy(clientToken = "other-token")) }
        assertEquals(credentials, store.load())
    }

    @Test fun invalidTokensOriginsAndMasterKeysCannotReplaceAnExistingPairing() {
        val store = DshRelaySecrets(DshEncryptedFiles(temp.newFolder(), { key }))
        store.save(credentials)
        for (invalid in listOf(credentials.copy(clientToken = "bad\nheader"), credentials.copy(masterKey = "bad"),
            credentials.copy(relayOrigin = "https://user:password@relay.example"))) {
            assertThrows(Exception::class.java) { store.save(invalid) }
            assertEquals(credentials, store.load())
        }
    }

    private fun seed(files: DshEncryptedFiles) {
        for ((name, text) in mapOf("master.bin" to credentials.masterKey, "client.bin" to credentials.clientToken,
            "host.bin" to credentials.hostId, "name.bin" to credentials.hostName, "origin.bin" to credentials.relayOrigin)) {
            files.write(name, text.toByteArray())
        }
    }
}
