package com.wudongsong.keyring.data

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
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** 使用 Android Keystore + 生物识别保护：加密保存应用密码密文，解锁时在 [BiometricPrompt] 成功后解密。 */
class BiometricPasswordVault(private val context: Context) {
    private val appContext = context.applicationContext
    private val prefs by lazy { appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    suspend fun hasStoredCipherText(): Boolean =
        withContext(Dispatchers.IO) { prefs.contains(KEY_ENC) && prefs.contains(KEY_IV) }

    suspend fun clear() =
        withContext(Dispatchers.IO) {
            check(prefs.edit().clear().commit()) { "Cannot clear biometric vault" }
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
        val keyGenerator =
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val builder =
            KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setUserAuthenticationRequired(true)
                .setInvalidatedByBiometricEnrollment(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        } else {
            @Suppress("DEPRECATION") builder.setUserAuthenticationValidityDurationSeconds(0)
        }
        keyGenerator.init(builder.build())
        return keyGenerator.generateKey()
    }

    suspend fun canEnrollStrongBiometric(): Boolean =
        withContext(Dispatchers.IO) {
            val bm = BiometricManager.from(appContext)
            bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
                BiometricManager.BIOMETRIC_SUCCESS
        }

    /** All Keystore, cipher and disk work stays off the UI thread. Only the prompt runs on Main. */
    suspend fun enrollEncryptPassword(
        activity: FragmentActivity,
        plainPassword: String,
    ): Result<Unit> {
        val prepared =
            withContext(Dispatchers.IO) {
                runCatching {
                    deleteKeyIfExists()
                    check(prefs.edit().clear().commit()) { "Cannot reset biometric vault" }
                    Cipher.getInstance("AES/GCM/NoPadding").apply {
                        init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
                    }
                }
            }
        val cipher =
            prepared.getOrElse {
                return Result.failure(it)
            }
        val authenticated =
            authenticate(
                activity,
                cipher,
                com.wudongsong.keyring.R.string.biometric_prompt_enroll_subtitle,
            )
        val unlockedCipher =
            authenticated.getOrElse {
                withContext(Dispatchers.IO) { deleteKeyIfExists() }
                return Result.failure(it)
            }
        return withContext(Dispatchers.IO) {
            runCatching {
                val encrypted = unlockedCipher.doFinal(plainPassword.toByteArray(Charsets.UTF_8))
                check(
                    prefs
                        .edit()
                        .putString(KEY_ENC, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                        .putString(KEY_IV, Base64.encodeToString(unlockedCipher.iv, Base64.NO_WRAP))
                        .commit()
                ) {
                    "Cannot save biometric vault"
                }
            }
        }
    }

    suspend fun authenticateDecryptPassword(activity: FragmentActivity): Result<String> {
        val prepared =
            withContext(Dispatchers.IO) {
                runCatching {
                    val enc =
                        Base64.decode(
                            prefs.getString(KEY_ENC, null) ?: error("no_data"),
                            Base64.NO_WRAP,
                        )
                    val iv =
                        Base64.decode(
                            prefs.getString(KEY_IV, null) ?: error("no_data"),
                            Base64.NO_WRAP,
                        )
                    val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
                    // Never generate a replacement key when the existing ciphertext cannot be
                    // opened.
                    val key = ks.getKey(KEY_ALIAS, null) as? SecretKey ?: error("missing_key")
                    Cipher.getInstance("AES/GCM/NoPadding").apply {
                        init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
                    } to enc
                }
            }
        val (cipher, encrypted) =
            prepared.getOrElse {
                return Result.failure(it)
            }
        val authenticated =
            authenticate(
                activity,
                cipher,
                com.wudongsong.keyring.R.string.biometric_prompt_unlock_subtitle,
            )
        val unlockedCipher =
            authenticated.getOrElse {
                return Result.failure(it)
            }
        return withContext(Dispatchers.IO) {
            runCatching { unlockedCipher.doFinal(encrypted).toString(Charsets.UTF_8) }
        }
    }

    private suspend fun authenticate(
        activity: FragmentActivity,
        cipher: Cipher,
        subtitle: Int,
    ): Result<Cipher> =
        withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { continuation ->
                val prompt =
                    BiometricPrompt(
                        activity,
                        ContextCompat.getMainExecutor(activity),
                        object : BiometricPrompt.AuthenticationCallback() {
                            override fun onAuthenticationSucceeded(
                                result: BiometricPrompt.AuthenticationResult
                            ) {
                                if (continuation.isActive)
                                    continuation.resume(
                                        result.cryptoObject?.cipher?.let { Result.success(it) }
                                            ?: Result.failure(
                                                IllegalStateException("Missing biometric cipher")
                                            )
                                    )
                            }

                            override fun onAuthenticationError(code: Int, message: CharSequence) {
                                if (continuation.isActive)
                                    continuation.resume(
                                        Result.failure(Exception("$code: $message"))
                                    )
                            }

                            override fun onAuthenticationFailed() {
                                /* The system prompt allows retry. */
                            }
                        },
                    )
                continuation.invokeOnCancellation {
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        prompt.cancelAuthentication()
                    }
                }
                try {
                    val info =
                        BiometricPrompt.PromptInfo.Builder()
                            .setTitle(
                                activity.getString(
                                    com.wudongsong.keyring.R.string.biometric_prompt_title
                                )
                            )
                            .setSubtitle(activity.getString(subtitle))
                            .setNegativeButtonText(activity.getString(android.R.string.cancel))
                            .setAllowedAuthenticators(
                                BiometricManager.Authenticators.BIOMETRIC_STRONG
                            )
                            .build()
                    prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
                } catch (e: Exception) {
                    if (continuation.isActive) continuation.resume(Result.failure(e))
                }
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
