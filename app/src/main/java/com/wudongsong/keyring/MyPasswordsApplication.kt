package com.wudongsong.keyring

import android.app.Application
import com.wudongsong.keyring.data.AppPreferences
import com.wudongsong.keyring.data.BiometricPasswordVault
import com.wudongsong.keyring.data.PasswordEntryRepository
import com.wudongsong.keyring.data.PasswordStore
import com.wudongsong.keyring.data.local.AppDatabase

class MyPasswordsApplication : Application() {
    val backgroundScope =
        kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
        )
    val sensitiveClipboard by lazy { com.wudongsong.keyring.util.SensitiveClipboard(this) }
    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }
    val appPreferences: AppPreferences by lazy { AppPreferences(this) }
    val passwordStore by lazy { PasswordStore(this) }
    val biometricVault: BiometricPasswordVault by lazy { BiometricPasswordVault(this) }
    val entryRepository: PasswordEntryRepository by lazy {
        PasswordEntryRepository(database.passwordEntryDao(), appPreferences)
    }
}
