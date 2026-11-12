package io.github.yukiykchen.dsh.security

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.KeyGenerator

class DshEncryptedFilesTest {
    @get:Rule val temp = TemporaryFolder()
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test fun ciphertextRoundTripsWithoutAnyPlaintextFile() {
        val root = temp.newFolder()
        val files = DshEncryptedFiles(root, { key })
        val text = "private-key-bytes".toByteArray()
        files.write("key.bin", text)
        assertArrayEquals(text, files.read("key.bin"))
        assertEquals(listOf("key.bin"), root.listFiles()!!.map { it.name })
        assertFalse(File(root, "key.bin").readBytes().toString(Charsets.ISO_8859_1).contains("private-key-bytes"))
    }

    @Test fun failedAtomicReplacementKeepsTheOldRecordAndRemovesTemporaryOutput() {
        val root = temp.newFolder()
        val files = DshEncryptedFiles(root, { key })
        files.write("key.bin", byteArrayOf(1))
        val failing = DshEncryptedFiles(root, { key }) { _, _ -> throw IOException("rename failed") }
        assertThrows(IOException::class.java) { failing.write("key.bin", byteArrayOf(2)) }
        assertArrayEquals(byteArrayOf(1), files.read("key.bin"))
        assertEquals(listOf("key.bin"), root.listFiles()!!.map { it.name })
    }

    @Test fun readAndOverwriteNeverGenerateAMissingKeyForExistingRecords() {
        val root = temp.newFolder()
        val requests = mutableListOf<Boolean>()
        val files = DshEncryptedFiles(root, { create -> requests.add(create); key })
        files.write("key.bin", byteArrayOf(1))
        files.read("key.bin")
        files.write("other.bin", byteArrayOf(2))
        assertEquals(listOf(true, false, false), requests)
        val missing = DshEncryptedFiles(root, { create -> check(!create); error("key unavailable") })
        assertThrows(IllegalStateException::class.java) { missing.read("key.bin") }
        assertThrows(IllegalStateException::class.java) { missing.write("other.bin", byteArrayOf(3)) }
        assertArrayEquals(byteArrayOf(2), files.read("other.bin"))
    }

    @Test fun traversalAndAbsoluteNamesCannotReadOrDeleteOutsideStorage() {
        val root = temp.newFolder()
        val outside = temp.newFile("outside.bin").apply { writeBytes(byteArrayOf(1)) }
        val files = DshEncryptedFiles(root, { key })
        for (name in listOf("../outside.bin", outside.absolutePath, "sub/key.bin", ".bin")) {
            assertThrows(IllegalArgumentException::class.java) { files.read(name) }
            assertThrows(IllegalArgumentException::class.java) { files.write(name, byteArrayOf(1)) }
            assertThrows(IllegalArgumentException::class.java) { files.delete(name) }
        }
        assertTrue(outside.isFile)
    }

    @Test fun malformedOversizedAndUnauthenticatedRecordsAreRejected() {
        val root = temp.newFolder()
        val files = DshEncryptedFiles(root, { key })
        val record = File(root, "key.bin")
        for (bytes in listOf(ByteArray(1), ByteArray(28), ByteArray(65566))) {
            record.writeBytes(bytes)
            assertThrows(Exception::class.java) { files.read("key.bin") }
        }
        files.write("key.bin", byteArrayOf(1))
        val changed = record.readBytes().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        record.writeBytes(changed)
        assertThrows(Exception::class.java) { files.read("key.bin") }
        assertThrows(IllegalArgumentException::class.java) { files.write("key.bin", ByteArray(65537)) }
    }

    @Test fun boundedInputStopsBeforeRetainingAnOversizedOrNonProgressingStream() {
        assertArrayEquals(byteArrayOf(1, 2), byteArrayOf(1, 2).inputStream().readBytesBounded(2))
        assertThrows(IllegalArgumentException::class.java) { byteArrayOf(1, 2, 3).inputStream().readBytesBounded(2) }
        val stalled = object : java.io.InputStream() {
            override fun read() = 0
            override fun read(bytes: ByteArray, offset: Int, length: Int) = 0
        }
        assertThrows(IllegalArgumentException::class.java) { stalled.readBytesBounded(2) }
    }

    @Test fun separateStoreInstancesSerializeConcurrentWritesAndReads() {
        val root = temp.newFolder()
        val first = DshEncryptedFiles(root, { key })
        val second = DshEncryptedFiles(root, { key })
        val executor = Executors.newFixedThreadPool(4)
        try {
            val futures = (1..40).map { value -> executor.submit {
                val files = if (value % 2 == 0) first else second
                files.locked {
                    val bytes = ByteArray(256) { value.toByte() }
                    files.write("key.bin", bytes)
                    assertArrayEquals(bytes, files.read("key.bin"))
                }
            } }
            futures.forEach { it.get(10, TimeUnit.SECONDS) }
        } finally { executor.shutdownNow() }
        assertEquals(listOf("key.bin"), root.listFiles()!!.map { it.name })
    }
}
