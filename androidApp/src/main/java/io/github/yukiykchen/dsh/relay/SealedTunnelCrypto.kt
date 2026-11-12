package io.github.yukiykchen.dsh.relay

import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString

internal class E2eeException(message: String) : IllegalStateException(message)

internal data class SealedPayload(val seq: String, val ciphertextB64: String)

internal object SealedTunnelCrypto {
    const val CLAIM_INFO = "dsh-claim-v1"
    private const val KEY_BYTES = 32
    private const val RANDOM_BYTES = 32
    private const val NONCE_PREFIX_BYTES = 4
    private const val TAG_BYTES = 16

    fun encodeBase64Url(value: ByteArray): String =
        value.toByteString().base64Url().trimEnd('=')

    fun decodeBase64Url(value: String, bytes: Int? = null): ByteArray {
        if (!value.matches(Regex("^[A-Za-z0-9_-]*$"))) throw E2eeException("invalid base64url")
        val decoded = value.decodeBase64()?.toByteArray() ?: throw E2eeException("invalid base64url")
        if (encodeBase64Url(decoded) != value) throw E2eeException("non-canonical base64url")
        if (bytes != null && decoded.size != bytes) throw E2eeException("invalid binary length")
        return decoded
    }

    fun deriveClaimToken(masterKeyB64: String): String {
        val key = decodeBase64Url(masterKeyB64, KEY_BYTES)
        return encodeBase64Url(hkdfSha256(key, ByteArray(0), CLAIM_INFO.toByteArray(), KEY_BYTES))
    }

    fun hashClaimToken(claimTokenB64: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(decodeBase64Url(claimTokenB64, KEY_BYTES)).joinToString("") { "%02x".format(it) }
    }

    fun clientProof(masterKeyB64: String, accessSessionId: String, clientRandomB64: String): String {
        val key = decodeBase64Url(masterKeyB64, KEY_BYTES)
        decodeBase64Url(clientRandomB64, RANDOM_BYTES)
        return encodeBase64Url(hmacSha256(key, canonical("dsh-e2ee-client", 1, accessSessionId, clientRandomB64)))
    }

    fun serverProof(
        masterKeyB64: String,
        accessSessionId: String,
        clientRandomB64: String,
        serverRandomB64: String,
    ): String {
        val key = decodeBase64Url(masterKeyB64, KEY_BYTES)
        decodeBase64Url(clientRandomB64, RANDOM_BYTES)
        decodeBase64Url(serverRandomB64, RANDOM_BYTES)
        return encodeBase64Url(
            hmacSha256(key, canonical("dsh-e2ee-server", 1, accessSessionId, clientRandomB64, serverRandomB64)),
        )
    }

    fun randomBytes(count: Int = RANDOM_BYTES): ByteArray = ByteArray(count).also { SecureRandom().nextBytes(it) }

    fun createClientCipher(
        masterKeyB64: String,
        accessSessionId: String,
        clientRandomB64: String,
        serverRandomB64: String,
        serverProofB64: String,
    ): SecureCipher {
        val expected = serverProof(masterKeyB64, accessSessionId, clientRandomB64, serverRandomB64)
        if (!constantTimeEquals(expected, serverProofB64)) throw E2eeException("server proof failed")
        val material = deriveMaterial(masterKeyB64, accessSessionId, clientRandomB64, serverRandomB64)
        return SecureCipher(
            accessSessionId = accessSessionId,
            sendDirection = "c2d",
            sendKey = material.c2dKey,
            sendNonceBase = material.c2dNonceBase,
            receiveDirection = "d2c",
            receiveKey = material.d2cKey,
            receiveNonceBase = material.d2cNonceBase,
        )
    }

    fun createHostCipher(
        masterKeyB64: String,
        accessSessionId: String,
        clientRandomB64: String,
        serverRandomB64: String,
    ): SecureCipher {
        val material = deriveMaterial(masterKeyB64, accessSessionId, clientRandomB64, serverRandomB64)
        return SecureCipher(
            accessSessionId = accessSessionId,
            sendDirection = "d2c",
            sendKey = material.d2cKey,
            sendNonceBase = material.d2cNonceBase,
            receiveDirection = "c2d",
            receiveKey = material.c2dKey,
            receiveNonceBase = material.c2dNonceBase,
        )
    }

    internal fun deriveMaterial(
        masterKeyB64: String,
        accessSessionId: String,
        clientRandomB64: String,
        serverRandomB64: String,
    ): Material {
        val key = decodeBase64Url(masterKeyB64, KEY_BYTES)
        decodeBase64Url(clientRandomB64, RANDOM_BYTES)
        decodeBase64Url(serverRandomB64, RANDOM_BYTES)
        val salt = MessageDigest.getInstance("SHA-256").digest(
            canonical("dsh-e2ee-salt", 1, accessSessionId, clientRandomB64, serverRandomB64),
        )
        fun expand(info: String, length: Int) = hkdfSha256(key, salt, info.toByteArray(), length)
        return Material(
            c2dKey = expand("dsh-e2ee-v1:c2d:key", KEY_BYTES),
            d2cKey = expand("dsh-e2ee-v1:d2c:key", KEY_BYTES),
            c2dNonceBase = expand("dsh-e2ee-v1:c2d:nonce", NONCE_PREFIX_BYTES),
            d2cNonceBase = expand("dsh-e2ee-v1:d2c:nonce", NONCE_PREFIX_BYTES),
        )
    }

    private fun constantTimeEquals(left: String, right: String): Boolean {
        val a = runCatching { decodeBase64Url(left) }.getOrNull() ?: return false
        val b = runCatching { decodeBase64Url(right) }.getOrNull() ?: return false
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }

    internal fun canonical(vararg parts: Any): ByteArray {
        val array = JSONArray()
        parts.forEach { part ->
            when (part) {
                is Int -> array.put(part)
                is Long -> array.put(part)
                else -> array.put(part.toString())
            }
        }
        return array.toString().toByteArray(Charsets.UTF_8)
    }

    internal fun hmacSha256(key: ByteArray, message: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(message)
    }

    internal fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val extractSalt = if (salt.isEmpty()) ByteArray(32) else salt
        val prk = hmacSha256(extractSalt, ikm)
        val result = ByteArray(length)
        var previous = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < length) {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            mac.update(previous)
            mac.update(info)
            mac.update(counter.toByte())
            previous = mac.doFinal()
            val copy = minOf(previous.size, length - offset)
            System.arraycopy(previous, 0, result, offset, copy)
            offset += copy
            counter += 1
        }
        return result
    }

    internal data class Material(
        val c2dKey: ByteArray,
        val d2cKey: ByteArray,
        val c2dNonceBase: ByteArray,
        val d2cNonceBase: ByteArray,
    )
}

internal class SecureCipher(
    private val accessSessionId: String,
    private val sendDirection: String,
    private val sendKey: ByteArray,
    private val sendNonceBase: ByteArray,
    private val receiveDirection: String,
    private val receiveKey: ByteArray,
    private val receiveNonceBase: ByteArray,
) {
    private val lock = Any()
    private var sendSequence = 0L
    private var receiveSequence = 0L

    fun seal(value: JSONObject): SealedPayload = synchronized(lock) {
        val sequence = sendSequence
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(sendKey, "AES"), GCMParameterSpec(128, nonce(sendNonceBase, sequence)))
        cipher.updateAAD(aad(sendDirection, sequence))
        val ciphertext = cipher.doFinal(value.toString().toByteArray(Charsets.UTF_8))
        sendSequence += 1
        SealedPayload(sequence.toString(), SealedTunnelCrypto.encodeBase64Url(ciphertext))
    }

    fun open(payload: SealedPayload): JSONObject = synchronized(lock) {
        if (!payload.seq.matches(Regex("^(0|[1-9][0-9]*)$"))) throw E2eeException("invalid sequence")
        val sequence = payload.seq.toLong()
        if (sequence != receiveSequence) throw E2eeException("unexpected sequence")
        val sealed = SealedTunnelCrypto.decodeBase64Url(payload.ciphertextB64)
        if (sealed.size < 16) throw E2eeException("truncated ciphertext")
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(receiveKey, "AES"), GCMParameterSpec(128, nonce(receiveNonceBase, sequence)))
            cipher.updateAAD(aad(receiveDirection, sequence))
            val plaintext = cipher.doFinal(sealed)
            receiveSequence += 1
            JSONObject(String(plaintext, Charsets.UTF_8))
        } catch (_: Exception) {
            throw E2eeException("ciphertext authentication failed")
        }
    }

    private fun nonce(prefix: ByteArray, sequence: Long): ByteArray {
        val buffer = ByteBuffer.allocate(12)
        buffer.put(prefix.copyOf(4))
        buffer.putLong(sequence)
        return buffer.array()
    }

    private fun aad(direction: String, sequence: Long): ByteArray =
        SealedTunnelCrypto.canonical("dsh-e2ee", 1, accessSessionId, direction, sequence.toString())
}
