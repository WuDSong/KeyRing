package com.example.keyring.sync

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object SecureTransport {
    private const val PBKDF2_ITERATIONS = 120_000
    private const val KEY_BYTES = 32
    private const val GCM_NONCE_BYTES = 12
    private const val GCM_TAG_BITS = 128

    private val rng = SecureRandom()

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { rng.nextBytes(it) }

    fun deriveSessionKey(pairCode: String, salt: ByteArray): SecretKey {
        val normalized = pairCode.trim()
        require(normalized.length in 4..32) { "bad pair code" }
        val spec = PBEKeySpec(normalized.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_BYTES * 8)
        val f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val keyBytes = f.generateSecret(spec).encoded
        return SecretKeySpec(keyBytes, "AES")
    }

    fun encryptJson(key: SecretKey, aad: String, plaintext: ByteArray): EncryptedPayload {
        val nonce = randomBytes(GCM_NONCE_BYTES)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, nonce))
        cipher.updateAAD(aad.toByteArray(Charsets.UTF_8))
        val ciphertext = cipher.doFinal(plaintext)
        return EncryptedPayload(nonceB64 = b64(nonce), ciphertextB64 = b64(ciphertext))
    }

    fun decryptJson(key: SecretKey, aad: String, payload: EncryptedPayload): ByteArray {
        val nonce = b64d(payload.nonceB64)
        val ciphertext = b64d(payload.ciphertextB64)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, nonce))
        cipher.updateAAD(aad.toByteArray(Charsets.UTF_8))
        return cipher.doFinal(ciphertext)
    }

    fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, outLen: Int): ByteArray {
        val prk = hmacSha256(salt, ikm)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        var t = ByteArray(0)
        val out = ByteArray(outLen)
        var offset = 0
        var c: Byte = 1
        while (offset < outLen) {
            mac.reset()
            mac.update(t)
            mac.update(info)
            mac.update(c)
            t = mac.doFinal()
            val n = minOf(t.size, outLen - offset)
            System.arraycopy(t, 0, out, offset, n)
            offset += n
            c = (c + 1).toByte()
        }
        return out
    }

    fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun b64d(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)
}

data class EncryptedPayload(
    val nonceB64: String,
    val ciphertextB64: String
)

