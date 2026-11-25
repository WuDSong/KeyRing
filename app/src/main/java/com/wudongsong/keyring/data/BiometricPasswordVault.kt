package com.wudongsong.keyring.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.UserNotAuthenticatedException
import android.util.Base64
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleObserver
import com.wudongsong.keyring.security.BiometricCredential
import com.wudongsong.keyring.util.UnlockTimings
import java.security.KeyStore
import java.security.MessageDigest
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
            // 0 seconds means per-use authorization, paired with a CryptoObject.
            builder.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        } else {
            // -1 means per-use authorization. 0 would mean "time-based, valid for 0 seconds",
            // which leaves the key permanently unauthenticated: Cipher.init() then throws
            // UserNotAuthenticatedException on Android 10 and the prompt never appears.
            @Suppress("DEPRECATION") builder.setUserAuthenticationValidityDurationSeconds(-1)
        }
        keyGenerator.init(builder.build())
        return keyGenerator.generateKey()
    }

    /**
     * A key that can never be authorized again (enrollment changed, or created with a broken
     * validity policy) is replaced; otherwise the existing key is reused so a cancelled
     * re-enrollment leaves the previous setup untouched.
     */
    private fun newEnrollmentCipher(): Cipher =
        try {
            encryptCipherForExistingKey()
        } catch (_: KeyPermanentlyInvalidatedException) {
            deleteKeyIfExists()
            encryptCipherForExistingKey()
        } catch (_: UserNotAuthenticatedException) {
            // Left behind by an earlier build whose key policy made the key unusable.
            UnlockTimings.event(appContext, "biometric key unusable; recreating")
            deleteKeyIfExists()
            encryptCipherForExistingKey()
        }

    private fun encryptCipherForExistingKey(): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        }

    suspend fun canEnrollStrongBiometric(): Boolean =
        withContext(Dispatchers.IO) {
            val bm = BiometricManager.from(appContext)
            val status = bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            UnlockTimings.event(appContext, "canAuthenticate(BIOMETRIC_STRONG)=$status")
            status == BiometricManager.BIOMETRIC_SUCCESS
        }

    /** All Keystore, cipher and disk work stays off the UI thread. Only the prompt runs on Main. */
    suspend fun enrollEncryptPassword(
        activity: FragmentActivity,
        plainPassword: String,
    ): Result<String> {
        val prepared =
            withContext(Dispatchers.IO) {
                runCatching { newEnrollmentCipher() }
            }
        val cipher =
            prepared.getOrElse {
                UnlockTimings.failure(appContext, "enroll: cipher init failed", it)
                return Result.failure(it)
            }
        UnlockTimings.event(appContext, "enroll: cipher ready")
        val authenticated =
            authenticate(
                activity,
                cipher,
                com.wudongsong.keyring.R.string.biometric_prompt_enroll_title,
                com.wudongsong.keyring.R.string.biometric_prompt_enroll_subtitle,
            )
        // Nothing was destroyed yet: a cancelled prompt keeps the previous enrollment usable.
        val unlockedCipher =
            authenticated.getOrElse {
                UnlockTimings.failure(appContext, "enroll: prompt failed", it)
                return Result.failure(it)
            }
        UnlockTimings.event(appContext, "enroll: prompt ok")
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
                UnlockTimings.event(appContext, "enroll: stored")
                ciphertextId(unlockedCipher.iv, encrypted)
            }
        }
    }

    suspend fun authenticateDecryptPassword(
        activity: FragmentActivity
    ): Result<BiometricCredential> {
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
                    } to (enc to ciphertextId(iv, enc))
                }
            }
        val (cipher, payload) =
            prepared.getOrElse {
                return Result.failure(it)
            }
        val authenticated =
            authenticate(
                activity,
                cipher,
                com.wudongsong.keyring.R.string.biometric_prompt_unlock_title,
                com.wudongsong.keyring.R.string.biometric_prompt_unlock_subtitle,
            )
        val unlockedCipher =
            authenticated.getOrElse {
                return Result.failure(it)
            }
        return withContext(Dispatchers.IO) {
            runCatching {
                UnlockTimings.measure(appContext, "biometric decrypt") {
                    BiometricCredential(
                        unlockedCipher.doFinal(payload.first).toString(Charsets.UTF_8),
                        payload.second,
                    )
                }
            }
        }
    }

    private fun ciphertextId(iv: ByteArray, encrypted: ByteArray): String =
        Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(iv + encrypted),
            Base64.NO_WRAP,
        )

    private suspend fun authenticate(
        activity: FragmentActivity,
        cipher: Cipher,
        title: Int,
        subtitle: Int,
    ): Result<Cipher> =
        withContext(Dispatchers.Main.immediate) {
            if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) ||
                activity.supportFragmentManager.isStateSaved
            ) {
                return@withContext Result.failure(
                    IllegalStateException("Activity is not ready for authentication")
                )
            }
            suspendCancellableCoroutine { continuation ->
                val lifecycle = activity.lifecycle
                val handler = Handler(Looper.getMainLooper())
                var prompt: BiometricPrompt? = null
                var finished = false
                var lifecycleObserver: LifecycleObserver? = null
                var screenReceiver: BroadcastReceiver? = null

                fun cleanup() {
                    handler.removeCallbacksAndMessages(null)
                    lifecycleObserver?.let { lifecycle.removeObserver(it) }
                    screenReceiver?.let { runCatching { activity.unregisterReceiver(it) } }
                }

                // Every exit resumes with a result rather than cancelling, so the caller can reset
                // its busy state and re-enable the biometric button. Cancelling here would unwind
                // through the caller's coroutine and leave the UI with no way back.
                fun finish(result: Result<Cipher>, abortPrompt: Boolean) {
                    if (finished) return
                    finished = true
                    if (abortPrompt) runCatching { prompt?.cancelAuthentication() }
                    cleanup()
                    if (continuation.isActive) continuation.resume(result)
                }

                // invokeOnCancellation may be registered exactly once per continuation, so the
                // prompt handle is assigned afterwards and all cleanups share this one handler.
                continuation.invokeOnCancellation {
                    handler.post {
                        finished = true
                        runCatching { prompt?.cancelAuthentication() }
                        cleanup()
                    }
                }

                // A destroyed activity is the only lifecycle event that aborts the prompt. Some OEM
                // implementations show the biometric UI in a separate window, which stops the host
                // activity while the user is still authenticating; aborting on ON_STOP dismissed
                // the prompt before the user could authenticate.
                val observer =
                    LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_DESTROY) continuation.cancel()
                    }
                lifecycleObserver = observer
                lifecycle.addObserver(observer)

                // Screen-off, and a prompt the platform never answers, are the two cases where no
                // authentication callback arrives at all; without these the caller would wait
                // forever. Screen-off is distinguishable from the OEM-window case because the
                // screen stays on while such a dialog is shown.
                val receiver =
                    object : BroadcastReceiver() {
                        override fun onReceive(context: Context?, intent: Intent?) {
                            if (intent?.action != Intent.ACTION_SCREEN_OFF) return
                            UnlockTimings.event(appContext, "biometric prompt: screen off")
                            finish(
                                Result.failure(IllegalStateException("biometric_screen_off")),
                                abortPrompt = true,
                            )
                        }
                    }
                screenReceiver = receiver
                // The screen-off guard is best effort: if registration fails the timeout below is
                // still enough to release the caller.
                runCatching {
                    ContextCompat.registerReceiver(
                        activity,
                        receiver,
                        IntentFilter(Intent.ACTION_SCREEN_OFF),
                        ContextCompat.RECEIVER_NOT_EXPORTED,
                    )
                }
                    .onFailure { screenReceiver = null }
                handler.postDelayed(
                    {
                        UnlockTimings.event(appContext, "biometric prompt: timed out")
                        finish(
                            Result.failure(IllegalStateException("biometric_timeout")),
                            abortPrompt = true,
                        )
                    },
                    AUTH_TIMEOUT_MS,
                )

                val callback =
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(
                            result: BiometricPrompt.AuthenticationResult
                        ) {
                            UnlockTimings.event(appContext, "biometric authenticated")
                            finish(
                                result.cryptoObject?.cipher?.let { Result.success(it) }
                                    ?: Result.failure(
                                        IllegalStateException("Missing biometric cipher")
                                    ),
                                abortPrompt = false,
                            )
                        }

                        override fun onAuthenticationError(code: Int, message: CharSequence) {
                            finish(Result.failure(Exception("$code: $message")), abortPrompt = false)
                        }

                        override fun onAuthenticationFailed() {
                            /* The system prompt allows retry. */
                        }
                    }
                try {
                    val created =
                        BiometricPrompt(
                            activity,
                            ContextCompat.getMainExecutor(activity),
                            callback,
                        )
                    prompt = created
                    val info =
                        BiometricPrompt.PromptInfo.Builder()
                            .setTitle(activity.getString(title))
                            .setSubtitle(activity.getString(subtitle))
                            .setNegativeButtonText(activity.getString(android.R.string.cancel))
                            .setAllowedAuthenticators(
                                BiometricManager.Authenticators.BIOMETRIC_STRONG
                            )
                            .build()
                    created.authenticate(info, BiometricPrompt.CryptoObject(cipher))
                } catch (e: Exception) {
                    finish(Result.failure(e), abortPrompt = false)
                }
            }
        }

    companion object {
        private const val PREFS_NAME = "biometric_vault"
        private const val KEY_ENC = "enc"
        private const val KEY_IV = "iv"
        private const val KEY_ALIAS = "mypasswords_biometric_aes"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"

        /** Backstop for platforms that never answer the prompt; the system dialog is faster. */
        private const val AUTH_TIMEOUT_MS = 45_000L
    }
}
