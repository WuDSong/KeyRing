package com.example.keyring.data

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 使用 Android Keystore + 生物识别保护：加密保存应用密码密文，解锁时在 [BiometricPrompt] 成功后解密。
 */
class BiometricPasswordVault(private val context: Context) {
    private val appContext = context.applicationContext
    private val prefs =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun hasStoredCipherText(): Boolean = prefs.contains(KEY_ENC)

    fun clear() {
        prefs.edit().clear().apply()
        deleteKeyIfExists()
    }

    private fun deleteKeyIfExists() {
        runCatching {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (ks.containsAlias(KEY_ALIAS)) ks.deleteEntry(KEY_ALIAS)
        }
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (ks.containsAlias(KEY_ALIAS)) {
            return ks.getKey(KEY_ALIAS, null) as SecretKey
        }
        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE
        )
        val builder = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setUserAuthenticationParameters(
                0,
                KeyProperties.AUTH_BIOMETRIC_STRONG
            )
        } else {
            @Suppress("DEPRECATION")
            builder.setUserAuthenticationValidityDurationSeconds(0)
        }
        keyGenerator.init(builder.build())
        return keyGenerator.generateKey()
    }

    fun canEnrollStrongBiometric(): Boolean {
        val bm = BiometricManager.from(appContext)
        return bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
            BiometricManager.BIOMETRIC_SUCCESS
    }

    /**
     * 在已验证密码后调用：弹出系统生物识别，成功后写入密文。
     */
    fun enrollEncryptPassword(
        activity: FragmentActivity,
        plainPassword: String,
        onResult: (Result<Unit>) -> Unit
    ) {
        deleteKeyIfExists()
        prefs.edit().clear().apply()
        val key = try {
            getOrCreateSecretKey()
        } catch (e: Exception) {
            onResult(Result.failure(e))
            return
        }
        val cipher = try {
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, key)
            }
        } catch (e: Exception) {
            deleteKeyIfExists()
            onResult(Result.failure(e))
            return
        }
        val executor = ContextCompat.getMainExecutor(context)
        val prompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val c = result.cryptoObject?.cipher
                    if (c == null) {
                        onResult(Result.failure(IllegalStateException("cipher")))
                        return
                    }
                    try {
                        val encrypted = c.doFinal(plainPassword.toByteArray(Charsets.UTF_8))
                        val iv = c.iv
                        prefs.edit()
                            .putString(KEY_ENC, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                            .putString(KEY_IV, Base64.encodeToString(iv, Base64.NO_WRAP))
                            .apply()
                        onResult(Result.success(Unit))
                    } catch (e: Exception) {
                        onResult(Result.failure(e))
                    }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                        errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON
                    ) {
                        deleteKeyIfExists()
                    }
                    onResult(Result.failure(Exception("$errorCode: $errString")))
                }

                override fun onAuthenticationFailed() {
                    // 允许用户多次重试，不结束流程
                }
            }
        )
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(activity.getString(com.example.keyring.R.string.biometric_prompt_title))
            .setSubtitle(activity.getString(com.example.keyring.R.string.biometric_prompt_enroll_subtitle))
            .setNegativeButtonText(activity.getString(android.R.string.cancel))
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .build()
        try {
            prompt.authenticate(
                promptInfo,
                BiometricPrompt.CryptoObject(cipher)
            )
        } catch (e: Exception) {
            deleteKeyIfExists()
            onResult(Result.failure(e))
        }
    }

    /**
     * 弹出生物识别，成功后解密得到应用密码。
     */
    fun authenticateDecryptPassword(
        activity: FragmentActivity,
        onResult: (Result<String>) -> Unit
    ) {
        val encB64 = prefs.getString(KEY_ENC, null)
        val ivB64 = prefs.getString(KEY_IV, null)
        if (encB64 == null || ivB64 == null) {
            onResult(Result.failure(IllegalStateException("no_data")))
            return
        }
        val key = try {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            ks.getKey(KEY_ALIAS, null) as SecretKey
        } catch (e: Exception) {
            onResult(Result.failure(e))
            return
        }
        val iv = Base64.decode(ivB64, Base64.NO_WRAP)
        val enc = Base64.decode(encB64, Base64.NO_WRAP)
        val cipher = try {
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            }
        } catch (e: Exception) {
            onResult(Result.failure(e))
            return
        }
        val executor = ContextCompat.getMainExecutor(context)
        val prompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val c = result.cryptoObject?.cipher
                    if (c == null) {
                        onResult(Result.failure(IllegalStateException("cipher")))
                        return
                    }
                    try {
                        val plain = c.doFinal(enc).toString(Charsets.UTF_8)
                        onResult(Result.success(plain))
                    } catch (e: Exception) {
                        onResult(Result.failure(e))
                    }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    onResult(Result.failure(Exception("$errorCode: $errString")))
                }

                override fun onAuthenticationFailed() {
                    // 允许重试
                }
            }
        )
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(activity.getString(com.example.keyring.R.string.biometric_prompt_title))
            .setSubtitle(activity.getString(com.example.keyring.R.string.biometric_prompt_unlock_subtitle))
            .setNegativeButtonText(activity.getString(android.R.string.cancel))
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .build()
        try {
            prompt.authenticate(
                promptInfo,
                BiometricPrompt.CryptoObject(cipher)
            )
        } catch (e: Exception) {
            onResult(Result.failure(e))
        }
    }

    companion object {
        private const val PREFS_NAME = "biometric_vault"
        private const val KEY_ENC = "enc"
        private const val KEY_IV = "iv"
        private const val KEY_ALIAS = "mypasswords_biometric_aes"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    }
}
