package io.github.yukiykchen.dsh.ssh

import io.github.yukiykchen.dsh.security.DshEncryptedFiles
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyEncryptionContext
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.security.KeyPairGenerator
import javax.crypto.KeyGenerator

class DshSshEncryptedKeyTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun preconnectionValidationAcceptsTheCorrectPasswordAndRejectsMissingOrIncorrectPasswords() {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val store = DshSshKeyStore(DshEncryptedFiles(folder.newFolder(), { key }))
        val identity = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val encryption = OpenSSHKeyEncryptionContext().apply { password = "test-passphrase"; cipherType = "256" }
        val bytes = ByteArrayOutputStream().also {
            OpenSSHKeyPairResourceWriter.INSTANCE.writePrivateKey(identity, "encrypted-test", encryption, it)
        }.toByteArray()
        val id = try { store.importBytes(bytes) } finally { bytes.fill(0) }
        assertFalse(store.validateKey(id))
        assertFalse(store.validateKey(id, "wrong-password"))
        assertTrue(store.validateKey(id, "test-passphrase"))
    }
}
