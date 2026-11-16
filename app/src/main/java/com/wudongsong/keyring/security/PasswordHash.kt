package com.wudongsong.keyring.security

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

object PasswordHash {
    const val ITERATIONS = 600_000
    private const val PREFIX = "pbkdf2-sha256"

    fun create(password: String): String {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val chars = password.toCharArray()
        val hash =
            try {
                derive(chars, salt, ITERATIONS)
            } finally {
                chars.fill('\u0000')
            }
        return listOf(PREFIX, ITERATIONS.toString(), encode(salt), encode(hash)).joinToString("$")
    }

    fun isLegacy(record: String): Boolean = !record.contains('$')

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
                require(iterations in ITERATIONS..2_000_000)
                val salt = Base64.getDecoder().decode(parts[2])
                val expected = Base64.getDecoder().decode(parts[3])
                require(salt.size == 16 && expected.size == 32)
                val chars = password.toCharArray()
                try {
                    MessageDigest.isEqual(expected, derive(chars, salt, iterations))
                } finally {
                    chars.fill('\u0000')
                }
            }
        } catch (_: IllegalArgumentException) {
            false
        }

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
