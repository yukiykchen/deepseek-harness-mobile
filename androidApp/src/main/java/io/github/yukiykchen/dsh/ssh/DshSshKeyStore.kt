package io.github.yukiykchen.dsh.ssh

import android.content.Context
import java.io.File
import java.util.UUID
import io.github.yukiykchen.dsh.security.DshEncryptedFiles
import io.github.yukiykchen.dsh.security.DshKeystoreKey
import org.apache.sshd.common.NamedResource
import org.apache.sshd.common.config.keys.FilePasswordProvider
import org.apache.sshd.common.util.security.SecurityUtils

/** Stores imported private keys encrypted with an Android Keystore AES key. */
internal class DshSshKeyStore(private val files: DshEncryptedFiles) {
    constructor(context: Context) : this(createEncryptedFiles(context))

    fun importBytes(bytes: ByteArray): String {
        require(bytes.isNotEmpty()) { "SSH private key is empty" }
        val keyId = "key-${UUID.randomUUID()}"
        files.write("$keyId.bin", bytes)
        return keyId
    }

    fun delete(keyId: String) { files.delete(keyFile(keyId)) }

    fun exists(keyId: String): Boolean = validKeyId(keyId) && files.exists("$keyId.bin")

    fun readKeyBytes(keyId: String): ByteArray = files.read(keyFile(keyId)) ?: error("SSH private key is missing")

    fun validateKey(keyId: String, passphrase: String = ""): Boolean {
        if (!exists(keyId)) return false
        return runCatching {
            val bytes = readKeyBytes(keyId)
            try {
                bytes.inputStream().use {
                    val keys = SecurityUtils.loadKeyPairIdentities(null, NamedResource.ofName("imported-ssh-key"),
                        it, if (passphrase.isEmpty()) FilePasswordProvider.EMPTY else FilePasswordProvider.of(passphrase))?.toList()
                    require(!keys.isNullOrEmpty()) { "SSH private key contains no identities" }
                }
            } finally { bytes.fill(0) }
        }.isSuccess
    }

    private fun keyFile(keyId: String): String {
        require(validKeyId(keyId)) { "Invalid SSH key id" }
        return "$keyId.bin"
    }

    private fun validKeyId(keyId: String) = keyId.length in 1..128 && keyId.matches(Regex("[A-Za-z0-9_-]+"))

    companion object {
        private fun createEncryptedFiles(context: Context): DshEncryptedFiles {
            val root = File(context.filesDir, "ssh-keys")
            val files = DshEncryptedFiles(root, DshKeystoreKey("dsh.ssh.key-store")::get)
            files.locked { removeLegacySshPlaintext(root) }
            return files
        }
    }
}

internal fun removeLegacySshPlaintext(root: File) {
    val legacyName = Regex("(?:ssh-import-[0-9]+\\.key|ssh-key-[0-9]+\\.pem)")
    for (file in checkNotNull(root.listFiles()) { "Cannot inspect SSH key storage" }) {
        if (file.isFile && file.name.matches(legacyName)) {
            check(file.delete()) { "Cannot remove legacy plaintext SSH key" }
        }
    }
}
