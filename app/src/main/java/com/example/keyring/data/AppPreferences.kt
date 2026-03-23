package com.example.keyring.data

import android.content.Context
import android.content.SharedPreferences
import kotlin.math.abs
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

class AppPreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getMode(): ThemeMode {
        val ordinal = prefs.getInt(KEY_MODE, ThemeMode.SYSTEM.ordinal)
        return ThemeMode.entries.getOrElse(ordinal) { ThemeMode.SYSTEM }
    }

    fun setMode(mode: ThemeMode) {
        prefs.edit().putInt(KEY_MODE, mode.ordinal).apply()
    }

    fun getAppLanguage(): String = prefs.getString(KEY_APP_LANGUAGE, LANG_SYSTEM) ?: LANG_SYSTEM

    fun setAppLanguage(language: String) {
        val safe = when (language) {
            LANG_SYSTEM, LANG_ZH_CN, LANG_EN -> language
            else -> LANG_SYSTEM
        }
        prefs.edit().putString(KEY_APP_LANGUAGE, safe).apply()
    }

    fun observeAppLanguage(): Flow<String> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_APP_LANGUAGE) trySend(getAppLanguage())
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(getAppLanguage())
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()

    fun getListSortOrder(): PasswordListSortOrder {
        val ordinal = prefs.getInt(KEY_LIST_SORT, PasswordListSortOrder.TIME_DESC.ordinal)
        return PasswordListSortOrder.entries.getOrElse(ordinal) { PasswordListSortOrder.TIME_DESC }
    }

    fun setListSortOrder(order: PasswordListSortOrder) {
        prefs.edit().putInt(KEY_LIST_SORT, order.ordinal).apply()
    }

    fun observeListSortOrder(): Flow<PasswordListSortOrder> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_LIST_SORT) trySend(getListSortOrder())
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(getListSortOrder())
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()

    fun getAutoLockEnabled(): Boolean = prefs.getBoolean(KEY_AUTO_LOCK, true)

    fun setAutoLockEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_LOCK, enabled).apply()
    }

    fun observeAutoLockEnabled(): Flow<Boolean> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_AUTO_LOCK) trySend(getAutoLockEnabled())
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(getAutoLockEnabled())
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()

    fun getBiometricUnlockEnabled(): Boolean = prefs.getBoolean(KEY_BIOMETRIC_UNLOCK, false)

    fun setBiometricUnlockEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_BIOMETRIC_UNLOCK, enabled).apply()
    }

    fun observeBiometricUnlockEnabled(): Flow<Boolean> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_BIOMETRIC_UNLOCK) trySend(getBiometricUnlockEnabled())
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(getBiometricUnlockEnabled())
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()

    fun getPasswordGeneratorRules(): PasswordGeneratorRules {
        val length = snapGeneratorLength(prefs.getInt(KEY_GEN_LENGTH, 16))
        return PasswordGeneratorRules(
            length = length,
            includeUppercase = prefs.getBoolean(KEY_GEN_UPPER, true),
            includeLowercase = prefs.getBoolean(KEY_GEN_LOWER, true),
            includeDigits = prefs.getBoolean(KEY_GEN_DIGITS, true),
            includeSymbols = prefs.getBoolean(KEY_GEN_SYMBOLS, true)
        )
    }

    fun setPasswordGeneratorRules(rules: PasswordGeneratorRules) {
        val length = snapGeneratorLength(rules.length)
        prefs.edit()
            .putInt(KEY_GEN_LENGTH, length)
            .putBoolean(KEY_GEN_UPPER, rules.includeUppercase)
            .putBoolean(KEY_GEN_LOWER, rules.includeLowercase)
            .putBoolean(KEY_GEN_DIGITS, rules.includeDigits)
            .putBoolean(KEY_GEN_SYMBOLS, rules.includeSymbols)
            .apply()
    }

    fun getNetworkForFaviconEnabled(): Boolean =
        prefs.getBoolean(KEY_NETWORK_FAVICON, false)

    fun setNetworkForFaviconEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_NETWORK_FAVICON, enabled).apply()
    }

    fun recordSuccessfulUnlockTimestamp() {
        prefs.edit().putLong(KEY_LAST_UNLOCK_AT, System.currentTimeMillis()).apply()
    }

    fun getLastSuccessfulUnlockTimestamp(): Long =
        prefs.getLong(KEY_LAST_UNLOCK_AT, 0L)

    fun observeLastSuccessfulUnlockTimestamp(): Flow<Long> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_LAST_UNLOCK_AT) trySend(getLastSuccessfulUnlockTimestamp())
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(getLastSuccessfulUnlockTimestamp())
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()

    fun observeNetworkForFaviconEnabled(): Flow<Boolean> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_NETWORK_FAVICON) trySend(getNetworkForFaviconEnabled())
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(getNetworkForFaviconEnabled())
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()

    fun observePasswordGeneratorRules(): Flow<PasswordGeneratorRules> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key != null && key.startsWith(PREFIX_GEN)) trySend(getPasswordGeneratorRules())
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(getPasswordGeneratorRules())
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()

    private fun snapGeneratorLength(value: Int): Int {
        val opts = listOf(8, 12, 16, 20, 24, 32)
        val v = value.coerceIn(
            PasswordGeneratorRules.MIN_LENGTH,
            PasswordGeneratorRules.MAX_LENGTH
        )
        return opts.minBy { abs(it - v) }
    }

    companion object {
        private const val PREFS_NAME = "app_ui_prefs"
        private const val KEY_MODE = "theme_mode"
        private const val KEY_APP_LANGUAGE = "app_language"
        private const val KEY_LIST_SORT = "list_sort_order"
        private const val KEY_AUTO_LOCK = "auto_lock_enabled"
        private const val KEY_BIOMETRIC_UNLOCK = "biometric_unlock_enabled"
        private const val KEY_NETWORK_FAVICON = "network_favicon_enabled"
        private const val KEY_LAST_UNLOCK_AT = "last_successful_unlock_at"
        private const val PREFIX_GEN = "gen_"
        private const val KEY_GEN_LENGTH = "${PREFIX_GEN}length"
        private const val KEY_GEN_UPPER = "${PREFIX_GEN}upper"
        private const val KEY_GEN_LOWER = "${PREFIX_GEN}lower"
        private const val KEY_GEN_DIGITS = "${PREFIX_GEN}digits"
        private const val KEY_GEN_SYMBOLS = "${PREFIX_GEN}symbols"

        const val LANG_SYSTEM = "system"
        const val LANG_ZH_CN = "zh-CN"
        const val LANG_EN = "en"
    }
}
