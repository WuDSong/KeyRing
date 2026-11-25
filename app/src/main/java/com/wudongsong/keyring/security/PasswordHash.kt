package com.wudongsong.keyring.security

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object PasswordHash {
    /**
     * Cost parameter for new verifier records. Every record stores the count it was derived with,
     * so raising or lowering this only affects records written from now on; [needsUpgrade] drives
     * the rewrite of older records after a successful unlock.
     */
    const val ITERATIONS = 120_000
    private const val PREFIX = "pbkdf2-sha256"
    private const val MAX_ITERATIONS = 2_000_000
    private const val HMAC = "HmacSHA256"
    private val FIRST_BLOCK = byteArrayOf(0, 0, 0, 1)

    fun create(password: String): String {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val hash = deriveVerifier(password, salt, ITERATIONS)
        return try {
            listOf(PREFIX, ITERATIONS.toString(), encode(salt), encode(hash)).joinToString("$")
        } finally {
            hash.fill(0)
        }
    }

    fun isLegacy(record: String): Boolean = !record.contains('$')

    /** Cost recorded in a PBKDF2 record; null for legacy or malformed records. */
    fun iterationsOf(record: String): Int? =
        if (isLegacy(record)) null else record.split('$').getOrNull(1)?.toIntOrNull()

    /** True when the record was written under a different policy and should be re-derived. */
    fun needsUpgrade(record: String): Boolean = iterationsOf(record) != ITERATIONS

    fun verify(password: String, record: String): Boolean =
        try {
            if (isLegacy(record)) {
                val expected = Base64.getDecoder().decode(record)
                expected.size == 32 &&
                    MessageDigest.isEqual(
                        expected,
                        MessageDigest.getInstance("SHA-256")
                            .digest(password.toByteArray(Charsets.UTF_8)),
                    )
            } else {
                val parts = record.split('$')
                require(parts.size == 4 && parts[0] == PREFIX)
                val iterations = parts[1].toInt()
                require(iterations in ITERATIONS..MAX_ITERATIONS)
                val salt = Base64.getDecoder().decode(parts[2])
                val expected = Base64.getDecoder().decode(parts[3])
                require(salt.size == 16 && expected.size == 32)
                val actual = deriveVerifier(password, salt, iterations)
                try {
                    MessageDigest.isEqual(expected, actual)
                } finally {
                    actual.fill(0)
                }
            }
        } catch (_: IllegalArgumentException) {
            false
        }

    /** A 256-bit PBKDF2 verifier needs one HMAC-SHA256 block. Use the platform's accelerated MAC. */
    private fun deriveVerifier(password: String, salt: ByteArray, iterations: Int): ByteArray {
        val mac = Mac.getInstance(HMAC)
        // HMAC pads both an empty key and a single zero byte to the same key block.
        val key = if (password.isEmpty()) ByteArray(1) else password.toByteArray(Charsets.UTF_8)
        try {
            mac.init(SecretKeySpec(key, HMAC))
        } finally {
            key.fill(0)
        }
        mac.update(salt)
        val block = mac.doFinal(FIRST_BLOCK)
        val result = block.copyOf()
        try {
            repeat(iterations - 1) {
                mac.update(block)
                mac.doFinal(block, 0)
                for (index in result.indices) {
                    result[index] = (result[index].toInt() xor block[index].toInt()).toByte()
                }
            }
        } finally {
            block.fill(0)
        }
        return result
    }

    /** Backup archives keep using the original provider and encoding. */
    internal fun derive(password: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(password, salt, iterations, 256)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun encode(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)
}
