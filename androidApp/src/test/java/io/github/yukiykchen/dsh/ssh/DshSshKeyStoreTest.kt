package io.github.yukiykchen.dsh.ssh

import io.github.yukiykchen.dsh.security.DshEncryptedFiles
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyPairGenerator
import javax.crypto.KeyGenerator

class DshSshKeyStoreTest {
    @get:Rule val temp = TemporaryFolder()
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test fun upgradeRemovesOnlyTheOldPlaintextTemporaryFiles() {
        val root = temp.newFolder()
        val removed = listOf("ssh-import-12345.key", "ssh-key-6789.pem")
        val retained = listOf("key-123.bin", "notes.txt", "ssh-key-custom.pem")
        for (name in removed + retained) File(root, name).writeText("test")
        removeLegacySshPlaintext(root)
        assertEquals(retained.toSet(), root.listFiles()!!.map { it.name }.toSet())
    }

    @Test fun importReadAndValidationNeverLeaveAnUnencryptedPrivateKeyFile() {
        val root = temp.newFolder()
        val store = DshSshKeyStore(DshEncryptedFiles(root, { key }))
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val bytes = ByteArrayOutputStream().also { OpenSSHKeyPairResourceWriter.INSTANCE.writePrivateKey(pair, "test", null, it) }.toByteArray()
        val id = store.importBytes(bytes)
        assertTrue(store.exists(id))
        assertArrayEquals(bytes, store.readKeyBytes(id))
        assertTrue(store.validateKey(id))
        assertEquals(listOf("$id.bin"), root.listFiles()!!.map { it.name })
        assertFalse(File(root, "$id.bin").readText(Charsets.ISO_8859_1).contains("BEGIN OPENSSH PRIVATE KEY"))
        store.delete(id)
        assertFalse(store.exists(id))
    }

    @Test fun idsCannotEscapeStorageAndInvalidKeyValidationIsFalse() {
        val store = DshSshKeyStore(DshEncryptedFiles(temp.newFolder(), { key }))
        val id = store.importBytes("not-a-key".toByteArray())
        assertFalse(store.validateKey(id))
        for (invalid in listOf("../../other", "/absolute", "", "sub/key")) {
            assertFalse(store.exists(invalid))
            assertThrows(IllegalArgumentException::class.java) { store.readKeyBytes(invalid) }
            assertThrows(IllegalArgumentException::class.java) { store.delete(invalid) }
        }
    }
}
