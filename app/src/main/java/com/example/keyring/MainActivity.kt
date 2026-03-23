package com.example.keyring

import android.content.Context
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.example.keyring.data.AppPreferences
import com.example.keyring.data.BiometricPasswordVault
import com.example.keyring.data.PasswordEntryRepository
import com.example.keyring.data.PasswordStore
import com.example.keyring.data.ThemeMode
import com.example.keyring.data.isDark
import com.example.keyring.ui.AutoLockEffect
import com.example.keyring.ui.auth.SetPasswordScreen
import com.example.keyring.ui.auth.UnlockScreen
import com.example.keyring.ui.home.MainHomeScreen
import com.example.keyring.ui.theme.MyPasswordsTheme
import com.example.keyring.util.wrapContextWithAppLanguage

private const val PHASE_LOADING = 0
private const val PHASE_SETUP = 1
private const val PHASE_UNLOCK = 2
private const val PHASE_MAIN = 3

class MainActivity : FragmentActivity() {
    override fun attachBaseContext(newBase: Context?) {
        if (newBase == null) {
            super.attachBaseContext(null)
            return
        }
        val appPrefs = AppPreferences(newBase)
        val wrapped = wrapContextWithAppLanguage(newBase, appPrefs.getAppLanguage())
        super.attachBaseContext(wrapped)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val passwordStore = PasswordStore(applicationContext)
        val app = application as MyPasswordsApplication
        setContent {
            val appPreferences = remember { app.appPreferences }
            var themeMode by remember { mutableStateOf(appPreferences.getMode()) }
            MyPasswordsTheme(
                darkTheme = themeMode.isDark(isSystemInDarkTheme())
            ) {
                MyPasswordsApp(
                    passwordStore = passwordStore,
                    entryRepository = app.entryRepository,
                    appPreferences = appPreferences,
                    biometricVault = app.biometricVault,
                    themeMode = themeMode,
                    onThemeModeChange = { newMode ->
                        appPreferences.setMode(newMode)
                        themeMode = newMode
                    }
                )
            }
        }
    }
}

@Composable
fun MyPasswordsApp(
    passwordStore: PasswordStore,
    entryRepository: PasswordEntryRepository,
    appPreferences: AppPreferences,
    biometricVault: BiometricPasswordVault,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit
) {
    var phase by rememberSaveable { mutableStateOf(PHASE_LOADING) }

    LaunchedEffect(Unit) {
        if (phase == PHASE_LOADING) {
            phase = if (passwordStore.isPasswordSet()) PHASE_UNLOCK else PHASE_SETUP
        }
    }

    LaunchedEffect(Unit) {
        if (appPreferences.getBiometricUnlockEnabled() && !biometricVault.hasStoredCipherText()) {
            appPreferences.setBiometricUnlockEnabled(false)
        }
    }

    when (phase) {
        PHASE_LOADING -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        }
        PHASE_SETUP -> SetPasswordScreen(
            passwordStore = passwordStore,
            onSuccess = {
                appPreferences.recordSuccessfulUnlockTimestamp()
                phase = PHASE_MAIN
            }
        )
        PHASE_UNLOCK -> UnlockScreen(
            passwordStore = passwordStore,
            appPreferences = appPreferences,
            biometricVault = biometricVault,
            onSuccess = {
                appPreferences.recordSuccessfulUnlockTimestamp()
                phase = PHASE_MAIN
            }
        )
        PHASE_MAIN -> {
            AutoLockEffect(
                appPreferences = appPreferences,
                onLock = { phase = PHASE_UNLOCK }
            )
            MainHomeScreen(
                passwordStore = passwordStore,
                entryRepository = entryRepository,
                appPreferences = appPreferences,
                biometricVault = biometricVault,
                themeMode = themeMode,
                onThemeModeChange = onThemeModeChange,
                onRequireRelogin = { phase = PHASE_UNLOCK },
                modifier = Modifier.fillMaxSize()
            )
        }
        else -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        }
    }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
        text = "Hello $name!",
        modifier = modifier
    )
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    MyPasswordsTheme {
        Greeting("Android")
    }
}
