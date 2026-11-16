package com.wudongsong.keyring.sync

import com.wudongsong.keyring.data.AppPreferences
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

object PairingEngine {
    private const val SALT_BYTES = 16
    private val random = SecureRandom()

    private const val CODE_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"

    private fun normalizeCode(code: String) =
        code.trim().replace("-", "").uppercase(java.util.Locale.ROOT)

    fun isValidPairCode(code: String): Boolean =
        normalizeCode(code).let { it.length == 16 && it.all { ch -> ch in CODE_ALPHABET } }

    fun generatePairCode(): String =
        (1..16)
            .map { CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)] }
            .joinToString("")
            .chunked(4)
            .joinToString("-")

    fun newSaltB64(): String =
        Base64.getEncoder().encodeToString(SecureTransport.randomBytes(SALT_BYTES))

    fun deriveSessionKey(
        pairCode: String,
        serverSaltB64: String,
        clientSaltB64: String,
    ): SecretKey {
        require(isValidPairCode(pairCode)) { "Invalid pairing code" }
        val serverSalt = Base64.getDecoder().decode(serverSaltB64)
        val clientSalt = Base64.getDecoder().decode(clientSaltB64)
        require(serverSalt.size == SALT_BYTES && clientSalt.size == SALT_BYTES)
        val combined = SecureTransport.sha256(serverSalt + clientSalt)
        return SecureTransport.deriveSessionKey(normalizeCode(pairCode), combined)
    }

    fun deriveTrustedDeviceKey(
        sessionKey: SecretKey,
        deviceIdA: String,
        deviceIdB: String,
    ): ByteArray {
        val ikm = sessionKey.encoded
        val salt = SecureTransport.sha256("KeyRing.Pairing.v1".toByteArray(Charsets.UTF_8))
        val info = ("trust:" + deviceIdA + ":" + deviceIdB).toByteArray(Charsets.UTF_8)
        return SecureTransport.hkdfSha256(ikm = ikm, salt = salt, info = info, outLen = 32)
    }

    /** 双方必须使用同一对 deviceId 顺序，避免 A/B 与 B/A 派生密钥不一致。 */
    fun deriveTrustedDeviceKeyCanonical(
        sessionKey: SecretKey,
        id1: String,
        id2: String,
    ): ByteArray {
        val (a, b) = if (id1 <= id2) id1 to id2 else id2 to id1
        return deriveTrustedDeviceKey(sessionKey, a, b)
    }

    fun makeConfirmPlaintext(): ByteArray {
        val now = System.currentTimeMillis()
        val nonce = UUID.randomUUID().toString()
        val bb = ByteBuffer.allocate(8 + 36)
        bb.putLong(now)
        bb.put(nonce.toByteArray(Charsets.UTF_8).copyOf(36))
        return bb.array()
    }

    fun storeTrustedDevice(
        prefs: AppPreferences,
        remoteDeviceId: String,
        remoteName: String,
        sharedKey: ByteArray,
    ) {
        prefs.upsertTrustedDevice(
            AppPreferences.TrustedDevice(
                deviceId = remoteDeviceId,
                name = remoteName,
                sharedKeyB64 = Base64.getEncoder().encodeToString(sharedKey),
                pairedAt = System.currentTimeMillis(),
            )
        )
    }

    fun asAesKey(bytes: ByteArray): SecretKey = SecretKeySpec(bytes, "AES")
}

private operator fun ByteArray.plus(other: ByteArray): ByteArray {
    val out = ByteArray(this.size + other.size)
    System.arraycopy(this, 0, out, 0, this.size)
    System.arraycopy(other, 0, out, this.size, other.size)
    return out
}
