package com.wudongsong.keyring

import android.content.Context
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wudongsong.keyring.data.AppPreferences
import com.wudongsong.keyring.data.BiometricPasswordVault
import com.wudongsong.keyring.data.PasswordEntryRepository
import com.wudongsong.keyring.data.PasswordStore
import com.wudongsong.keyring.data.ThemeMode
import com.wudongsong.keyring.data.isDark
import com.wudongsong.keyring.ui.AutoLockEffect
import com.wudongsong.keyring.ui.ExternalActivityState
import com.wudongsong.keyring.ui.LocalExternalActivityState
import com.wudongsong.keyring.ui.auth.SetPasswordScreen
import com.wudongsong.keyring.ui.auth.UnlockScreen
import com.wudongsong.keyring.ui.home.MainHomeScreen
import com.wudongsong.keyring.ui.theme.MyPasswordsTheme
import com.wudongsong.keyring.util.wrapContextWithAppLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val PHASE_LOADING = 0
private const val PHASE_SETUP = 1
private const val PHASE_UNLOCK = 2
private const val PHASE_MAIN = 3

// Survives rotation, but is never serialized for restoration after process death.
class AuthSessionViewModel : ViewModel() {
    val phase = mutableStateOf(PHASE_LOADING)
}

class MainActivity : FragmentActivity() {
    override fun onResume() {
        super.onResume()
        (application as MyPasswordsApplication).sensitiveClipboard.clearIfExpired()
    }

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
        val authSession = ViewModelProvider(this)[AuthSessionViewModel::class.java]
        setContent {
            val appPreferences = remember { app.appPreferences }
            val protectScreen by
                appPreferences
                    .observeScreenProtectionEnabled()
                    .collectAsStateWithLifecycle(
                        initialValue = appPreferences.getScreenProtectionEnabled()
                    )
            DisposableEffect(protectScreen) {
                if (protectScreen) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                onDispose {}
            }
            var themeMode by remember { mutableStateOf(appPreferences.getMode()) }
            MyPasswordsTheme(darkTheme = themeMode.isDark(isSystemInDarkTheme())) {
                var repository by remember { mutableStateOf<PasswordEntryRepository?>(null) }
                var failed by remember { mutableStateOf(false) }
                var attempt by remember { mutableStateOf(0) }
                LaunchedEffect(attempt) {
                    failed = false
                    try {
                        repository =
                            withContext(Dispatchers.IO) {
                                app.entryRepository.also {
                                    app.database.openHelper.writableDatabase
                                }
                            }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        failed = true
                    }
                }
                val readyRepository = repository
                if (readyRepository == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        if (failed)
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(stringResource(R.string.database_open_failed))
                                Button(onClick = { attempt++ }) {
                                    Text(stringResource(R.string.database_retry))
                                }
                            }
                        else CircularProgressIndicator()
                    }
                } else {
                    MyPasswordsApp(
                        authSession = authSession,
                        passwordStore = passwordStore,
                        entryRepository = readyRepository,
                        appPreferences = appPreferences,
                        biometricVault = app.biometricVault,
                        themeMode = themeMode,
                        onThemeModeChange = { newMode ->
                            appPreferences.setMode(newMode)
                            themeMode = newMode
                        },
                    )
                }
            }
        }
    }
}

@Composable
fun MyPasswordsApp(
    authSession: AuthSessionViewModel,
    passwordStore: PasswordStore,
    entryRepository: PasswordEntryRepository,
    appPreferences: AppPreferences,
    biometricVault: BiometricPasswordVault,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
) {
    // Authorization must never be restored from an Activity saved-state bundle.
    var phase by authSession.phase

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
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        PHASE_SETUP ->
            SetPasswordScreen(
                passwordStore = passwordStore,
                onSuccess = {
                    appPreferences.recordSuccessfulUnlockTimestamp()
                    phase = PHASE_MAIN
                },
            )
        PHASE_UNLOCK ->
            UnlockScreen(
                passwordStore = passwordStore,
                appPreferences = appPreferences,
                biometricVault = biometricVault,
                onSuccess = {
                    appPreferences.recordSuccessfulUnlockTimestamp()
                    phase = PHASE_MAIN
                },
            )
        PHASE_MAIN -> {
            val externalActivity = remember { ExternalActivityState() }
            CompositionLocalProvider(LocalExternalActivityState provides externalActivity) {
                AutoLockEffect(appPreferences = appPreferences, onLock = { phase = PHASE_UNLOCK })
                MainHomeScreen(
                    passwordStore = passwordStore,
                    entryRepository = entryRepository,
                    appPreferences = appPreferences,
                    biometricVault = biometricVault,
                    themeMode = themeMode,
                    onThemeModeChange = onThemeModeChange,
                    onRequireRelogin = { phase = PHASE_UNLOCK },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        else -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
    }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(text = "Hello $name!", modifier = modifier)
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    MyPasswordsTheme { Greeting("Android") }
}
