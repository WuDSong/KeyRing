package com.example.keyring

import android.app.Application
import com.example.keyring.data.AppPreferences
import com.example.keyring.data.BiometricPasswordVault
import com.example.keyring.data.PasswordEntryRepository
import com.example.keyring.data.local.AppDatabase

class MyPasswordsApplication : Application() {
    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }
    val appPreferences: AppPreferences by lazy { AppPreferences(this) }
    val biometricVault: BiometricPasswordVault by lazy { BiometricPasswordVault(this) }
    val entryRepository: PasswordEntryRepository by lazy {
        PasswordEntryRepository(database.passwordEntryDao(), appPreferences)
    }
}
