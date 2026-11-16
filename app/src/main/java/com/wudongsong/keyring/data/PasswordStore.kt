package com.wudongsong.keyring.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.wudongsong.keyring.security.BiometricBinding
import com.wudongsong.keyring.security.BiometricCredential
import com.wudongsong.keyring.security.PasswordHash
import com.wudongsong.keyring.util.UnlockTimings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class PasswordStore(context: Context) {
    private val mutex = Mutex()
    private val appContext = context.applicationContext

    private val prefs by lazy {
        val masterKey =
            MasterKey.Builder(appContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            appContext,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    suspend fun isPasswordSet(): Boolean =
        withContext(Dispatchers.IO) { prefs.getString(KEY_PASSWORD_HASH, null) != null }

    suspend fun setPassword(plainPassword: String): Result<Unit> =
        withContext(Dispatchers.IO) { mutex.withLock { savePassword(plainPassword) } }

    suspend fun verifyPassword(plainPassword: String): Boolean =
        withContext(Dispatchers.IO) { mutex.withLock { verifyAndUpgrade(plainPassword) } }

    suspend fun verifyForBiometricEnrollment(password: String): String? =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                if (verifyAndUpgrade(password)) prefs.getString(KEY_PASSWORD_HASH, null) else null
            }
        }

    suspend fun bindBiometricCredential(ciphertextId: String, verifiedRecord: String): Boolean =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                if (
                    prefs.getString(KEY_PASSWORD_HASH, null) != verifiedRecord ||
                        ciphertextId.isBlank()
                )
                    false
                else saveBiometricBinding(ciphertextId, verifiedRecord)
            }
        }

    suspend fun verifyBiometricCredential(credential: BiometricCredential): Boolean =
        withContext(Dispatchers.IO) {
            UnlockTimings.measure(appContext, "biometric credential check") {
                mutex.withLock {
                    val current = prefs.getString(KEY_PASSWORD_HASH, null) ?: return@withLock false
                    if (
                        BiometricBinding.matches(
                            current,
                            credential.ciphertextId,
                            prefs.getString(KEY_BIOMETRIC_VERIFIER, null),
                            prefs.getString(KEY_BIOMETRIC_ID, null),
                        )
                    ) {
                        UnlockTimings.event(appContext, "biometric binding hit")
                        return@withLock true
                    }
                    UnlockTimings.event(
                        appContext,
                        "biometric binding missing; performing one full verification",
                    )
                    // Existing installations upgrade the binding once, after full legacy
                    // verification.
                    if (!verifyAndUpgrade(credential.password)) return@withLock false
                    val verified = prefs.getString(KEY_PASSWORD_HASH, null) ?: return@withLock false
                    saveBiometricBinding(credential.ciphertextId, verified)
                    true
                }
            }
        }

    private fun saveBiometricBinding(id: String, verifier: String): Boolean =
        prefs
            .edit()
            .putString(KEY_BIOMETRIC_ID, id)
            .putString(KEY_BIOMETRIC_VERIFIER, verifier)
            .commit()

    private fun verifyAndUpgrade(password: String): Boolean {
        val stored = prefs.getString(KEY_PASSWORD_HASH, null) ?: return false
        if (!PasswordHash.verify(password, stored)) return false
        if (PasswordHash.isLegacy(stored)) {
            check(
                prefs
                    .edit()
                    .putString(KEY_PASSWORD_HASH, PasswordHash.create(password))
                    .remove(KEY_BIOMETRIC_ID)
                    .remove(KEY_BIOMETRIC_VERIFIER)
                    .commit()
            ) {
                "Cannot upgrade password verifier"
            }
        }
        return true
    }

    suspend fun changePassword(oldPassword: String, newPassword: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                runCatching {
                    require(verifyAndUpgrade(oldPassword)) { "wrong_old" }
                    savePassword(newPassword).getOrThrow()
                }
            }
        }

    private fun savePassword(password: String): Result<Unit> = runCatching {
        require(password.length >= MIN_PASSWORD_LENGTH) { "too_short" }
        check(
            prefs
                .edit()
                .putString(KEY_PASSWORD_HASH, PasswordHash.create(password))
                .remove(KEY_BIOMETRIC_ID)
                .remove(KEY_BIOMETRIC_VERIFIER)
                .commit()
        ) {
            "Cannot save password verifier"
        }
    }

    companion object {
        const val MIN_PASSWORD_LENGTH = 4
        private const val PREFS_NAME = "auth_secure_prefs"
        private const val KEY_BIOMETRIC_ID = "biometric_ciphertext_id"
        private const val KEY_BIOMETRIC_VERIFIER = "biometric_verified_record"
        private const val KEY_PASSWORD_HASH = "password_hash"
    }
}
