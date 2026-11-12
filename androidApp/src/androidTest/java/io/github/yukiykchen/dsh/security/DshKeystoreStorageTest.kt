package io.github.yukiykchen.dsh.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yukiykchen.dsh.relay.DshRelayCredentials
import io.github.yukiykchen.dsh.relay.DshRelaySecrets
import io.github.yukiykchen.dsh.relay.SealedTunnelCrypto
import io.github.yukiykchen.dsh.ssh.DshSshKeyStore
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DshKeystoreStorageTest {
    @Test fun actualKeystoreKeyIsNonExportableAndPairingIsAtomic() = withFiles { root, alias, files ->
        val credentials = DshRelayCredentials(SealedTunnelCrypto.encodeBase64Url(ByteArray(32) { 1 }),
            "test-client-token", "test-host", "Test Host", "https://relay.example")
        val store = DshRelaySecrets(files)
        store.save(credentials)
        assertEquals(credentials, store.load())
        val key = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey(alias, null)
        assertNotNull(key)
        assertNull(key.encoded)
        assertEquals(listOf("pairing.bin"), root.listFiles()!!.map { it.name })
        assertFalse(File(root, "pairing.bin").readText(Charsets.ISO_8859_1).contains(credentials.clientToken))
        store.save(credentials.copy(clientToken = "second-token"))
        assertEquals("second-token", store.load()!!.clientToken)
        store.clear()
        assertNull(store.load())
    }

    @Test fun importedPrivateKeyCanBeValidatedWithoutAnyPlaintextFile() = withFiles { root, _, files ->
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val bytes = ByteArrayOutputStream().also {
            OpenSSHKeyPairResourceWriter.INSTANCE.writePrivateKey(pair, "test", null, it)
        }.toByteArray()
        val store = DshSshKeyStore(files)
        val id = store.importBytes(bytes)
        assertTrue(store.validateKey(id))
        val read = store.readKeyBytes(id)
        try { assertArrayEquals(bytes, read) } finally { read.fill(0); bytes.fill(0) }
        assertEquals(listOf("$id.bin"), root.listFiles()!!.map { it.name })
        store.delete(id)
        assertFalse(store.exists(id))
    }

    @Test fun missingKeystoreAliasCannotSilentlyReplaceAnExistingRecord() = withFiles { root, alias, files ->
        files.write("key.bin", byteArrayOf(1, 2, 3))
        val original = File(root, "key.bin").readBytes()
        val keystore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        keystore.deleteEntry(alias)
        assertThrows(IllegalStateException::class.java) { files.read("key.bin") }
        assertThrows(IllegalStateException::class.java) { files.write("key.bin", byteArrayOf(4)) }
        assertFalse(keystore.containsAlias(alias))
        assertArrayEquals(original, File(root, "key.bin").readBytes())
    }

    private fun withFiles(block: (File, String, DshEncryptedFiles) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = UUID.randomUUID().toString()
        val root = File(context.cacheDir, "keystore-test-$id")
        val alias = "dsh.test.$id"
        val files = DshEncryptedFiles(root, DshKeystoreKey(alias)::get)
        try { block(root, alias, files) } finally {
            root.deleteRecursively()
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
        }
    }
}
