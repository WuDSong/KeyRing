package com.example.keyring.data

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.MessageDigest

class PasswordStore(context: Context) {
    private val appContext = context.applicationContext

    private val prefs by lazy {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appContext,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun isPasswordSet(): Boolean =
        prefs.getString(KEY_PASSWORD_HASH, null) != null

    fun setPassword(plainPassword: String): Result<Unit> {
        if (plainPassword.length < MIN_PASSWORD_LENGTH) {
            return Result.failure(IllegalArgumentException("too_short"))
        }
        prefs.edit().putString(KEY_PASSWORD_HASH, hash(plainPassword)).apply()
        return Result.success(Unit)
    }

    fun verifyPassword(plainPassword: String): Boolean {
        val stored = prefs.getString(KEY_PASSWORD_HASH, null) ?: return false
        return hash(plainPassword) == stored
    }

    fun changePassword(oldPassword: String, newPassword: String): Result<Unit> {
        if (!verifyPassword(oldPassword)) {
            return Result.failure(IllegalArgumentException("wrong_old"))
        }
        if (newPassword.length < MIN_PASSWORD_LENGTH) {
            return Result.failure(IllegalArgumentException("too_short"))
        }
        prefs.edit().putString(KEY_PASSWORD_HASH, hash(newPassword)).apply()
        return Result.success(Unit)
    }

    private fun hash(password: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(password.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(digest, Base64.NO_WRAP)
    }

    companion object {
        const val MIN_PASSWORD_LENGTH = 4
        private const val PREFS_NAME = "auth_secure_prefs"
        private const val KEY_PASSWORD_HASH = "password_hash"
    }
}
