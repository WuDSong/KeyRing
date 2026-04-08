package com.example.keyring.sync

import android.util.Base64
import com.example.keyring.data.AppPreferences
import java.nio.ByteBuffer
import java.util.UUID
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

object PairingEngine {
    private const val SALT_BYTES = 16

    fun generatePairCode(): String {
        val n = (0..999_999).random()
        return n.toString().padStart(6, '0')
    }

    fun newSaltB64(): String = Base64.encodeToString(SecureTransport.randomBytes(SALT_BYTES), Base64.NO_WRAP)

    fun deriveSessionKey(pairCode: String, serverSaltB64: String, clientSaltB64: String): SecretKey {
        val serverSalt = Base64.decode(serverSaltB64, Base64.NO_WRAP)
        val clientSalt = Base64.decode(clientSaltB64, Base64.NO_WRAP)
        val combined = SecureTransport.sha256(serverSalt + clientSalt)
        return SecureTransport.deriveSessionKey(pairCode, combined)
    }

    fun deriveTrustedDeviceKey(
        sessionKey: SecretKey,
        deviceIdA: String,
        deviceIdB: String
    ): ByteArray {
        val ikm = sessionKey.encoded
        val salt = SecureTransport.sha256("KeyRing.Pairing.v1".toByteArray(Charsets.UTF_8))
        val info = ("trust:" + deviceIdA + ":" + deviceIdB).toByteArray(Charsets.UTF_8)
        return SecureTransport.hkdfSha256(ikm = ikm, salt = salt, info = info, outLen = 32)
    }

    /** 双方必须使用同一对 deviceId 顺序，避免 A/B 与 B/A 派生密钥不一致。 */
    fun deriveTrustedDeviceKeyCanonical(sessionKey: SecretKey, id1: String, id2: String): ByteArray {
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
        sharedKey: ByteArray
    ) {
        prefs.upsertTrustedDevice(
            AppPreferences.TrustedDevice(
                deviceId = remoteDeviceId,
                name = remoteName,
                sharedKeyB64 = Base64.encodeToString(sharedKey, Base64.NO_WRAP),
                pairedAt = System.currentTimeMillis()
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

