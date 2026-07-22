package com.wudongsong.keyring

import android.app.Application
import com.wudongsong.keyring.data.AppPreferences
import com.wudongsong.keyring.data.BiometricPasswordVault
import com.wudongsong.keyring.data.PasswordEntryRepository
import com.wudongsong.keyring.data.local.AppDatabase

class MyPasswordsApplication : Application() {
    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }
    val appPreferences: AppPreferences by lazy { AppPreferences(this) }
    val biometricVault: BiometricPasswordVault by lazy { BiometricPasswordVault(this) }
    val entryRepository: PasswordEntryRepository by lazy {
        PasswordEntryRepository(database.passwordEntryDao(), appPreferences)
    }
}
