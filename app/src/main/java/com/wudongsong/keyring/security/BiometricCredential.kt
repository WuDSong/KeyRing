package com.wudongsong.keyring.security

import java.security.MessageDigest

/** Created only after a CryptoObject has authenticated and decrypted the vault successfully. */
class BiometricCredential
internal constructor(internal val password: String, internal val ciphertextId: String)

internal object BiometricBinding {
    fun matches(
        currentVerifier: String,
        ciphertextId: String,
        boundVerifier: String?,
        boundId: String?,
    ): Boolean =
        currentVerifier.isNotEmpty() &&
            ciphertextId.isNotEmpty() &&
            boundVerifier != null &&
            boundId != null &&
            MessageDigest.isEqual(
                currentVerifier.toByteArray(Charsets.UTF_8),
                boundVerifier.toByteArray(Charsets.UTF_8),
            ) &&
            MessageDigest.isEqual(
                ciphertextId.toByteArray(Charsets.UTF_8),
                boundId.toByteArray(Charsets.UTF_8),
            )
}
