package io.github.yukiykchen.dsh.security

import java.io.File
import java.io.FileOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Bounded AES-GCM records. Plaintext is never materialized as a file. */
internal class DshEncryptedFiles(
    private val root: File,
    private val key: (create: Boolean) -> SecretKey,
    private val replace: (File, File) -> Unit = { temp, target ->
        // Both files share a directory. Android's renameTo uses POSIX rename (atomic replacement).
        check(temp.renameTo(target)) { "Cannot replace encrypted record" }
    },
) {
    init { require(root.isDirectory || root.mkdirs() || root.isDirectory) { "Cannot create encrypted storage" } }

    fun <T> locked(block: () -> T): T = synchronized(lock, block)

    fun exists(name: String): Boolean = locked { file(name).isFile }

    fun read(name: String): ByteArray? = locked {
        val source = file(name)
        if (!source.isFile) return@locked null
        val packed = source.inputStream().use { it.readBytesBounded(MAX_RECORD_BYTES) }
        require(packed.size >= 29 && packed[0].toInt() == 12) { "Invalid encrypted record" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, packed, 1, 12))
        cipher.doFinal(packed, 13, packed.size - 13)
    }

    fun write(name: String, plaintext: ByteArray) = locked {
        require(plaintext.size <= MAX_PLAINTEXT_BYTES) { "Invalid encrypted record size" }
        val target = file(name)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        // Never replace a missing wrapping key while old encrypted records still exist.
        val hasRecords = checkNotNull(root.listFiles()) { "Cannot inspect encrypted storage" }
            .any { it.isFile && it.name.endsWith(".bin") }
        cipher.init(Cipher.ENCRYPT_MODE, key(!hasRecords))
        require(cipher.iv.size == 12) { "Invalid encrypted record nonce" }
        val sealed = cipher.doFinal(plaintext)
        val temp = File.createTempFile("sealed-", ".tmp", root)
        try {
            FileOutputStream(temp).use {
                it.write(12)
                it.write(cipher.iv)
                it.write(sealed)
                it.fd.sync()
            }
            replace(temp, target)
        } finally {
            check(!temp.exists() || temp.delete()) { "Cannot remove encrypted temporary file" }
        }
    }

    fun delete(name: String) = locked {
        val target = file(name)
        check(!target.exists() || target.delete()) { "Cannot delete encrypted record" }
    }

    private fun file(name: String): File {
        require(name.length <= 160 && name.matches(Regex("[A-Za-z0-9_-][A-Za-z0-9._-]*\\.bin"))) {
            "Invalid encrypted record name"
        }
        return File(root, name)
    }

    companion object {
        const val MAX_PLAINTEXT_BYTES = 64 * 1024
        private const val MAX_RECORD_BYTES = MAX_PLAINTEXT_BYTES + 29
        private val lock = Any()
    }
}

internal fun java.io.InputStream.readBytesBounded(max: Int): ByteArray {
    require(max in 0..1024 * 1024) { "Invalid input limit" }
    val buffer = ByteArray(max + 1)
    var size = 0
    try {
        while (true) {
            val count = read(buffer, size, buffer.size - size)
            if (count < 0) return buffer.copyOf(size)
            require(count > 0) { "Incomplete input" }
            size += count
            require(size <= max) { "Input too large" }
        }
    } finally { buffer.fill(0) }
}
