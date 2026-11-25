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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
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
import com.wudongsong.keyring.util.UnlockTimings
import com.wudongsong.keyring.util.wrapContextWithAppLanguage
import kotlinx.coroutines.CancellationException
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
        val systemBars = WindowCompat.getInsetsController(window, window.decorView)
        val app = application as MyPasswordsApplication
        val passwordStore = app.passwordStore
        val authSession = ViewModelProvider(this)[AuthSessionViewModel::class.java]
        // A positive hint can show the unlock screen immediately. Setup requires the store check.
        if (authSession.phase.value == PHASE_LOADING && passwordStore.hasPasswordSetHint()) {
            authSession.phase.value = PHASE_UNLOCK
        }
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
            val darkTheme = themeMode.isDark(isSystemInDarkTheme())
            SideEffect {
                // The bars are transparent, so their icons must match KeyRing's theme.
                systemBars.isAppearanceLightStatusBars = !darkTheme
                systemBars.isAppearanceLightNavigationBars = !darkTheme
            }
            MyPasswordsTheme(darkTheme = darkTheme) {
                // The database is warmed up in the background so the unlock screen appears on the
                // first frame instead of behind a full-screen spinner.
                var repository by remember { mutableStateOf<PasswordEntryRepository?>(null) }
                var databaseFailed by remember { mutableStateOf(false) }
                var attempt by remember { mutableStateOf(0) }
                LaunchedEffect(attempt) {
                    databaseFailed = false
                    try {
                        repository =
                            withContext(Dispatchers.IO) {
                                // Run below the unlock work: a cold SQLCipher open must never make
                                // password verification feel slower than it is.
                                val previous =
                                    android.os.Process.getThreadPriority(android.os.Process.myTid())
                                android.os.Process.setThreadPriority(
                                    android.os.Process.THREAD_PRIORITY_BACKGROUND
                                )
                                try {
                                    app.entryRepository.also {
                                        UnlockTimings.measure(
                                            applicationContext,
                                            "database open",
                                        ) {
                                            app.database.openHelper.writableDatabase
                                        }
                                    }
                                } finally {
                                    android.os.Process.setThreadPriority(previous)
                                }
                            }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        UnlockTimings.failure(applicationContext, "database open failed", e)
                        databaseFailed = true
                    }
                }
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    MyPasswordsApp(
                        authSession = authSession,
                        passwordStore = passwordStore,
                        entryRepository = repository,
                        databaseFailed = databaseFailed,
                        onRetryDatabase = { attempt++ },
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
    entryRepository: PasswordEntryRepository?,
    databaseFailed: Boolean,
    onRetryDatabase: () -> Unit,
    appPreferences: AppPreferences,
    biometricVault: BiometricPasswordVault,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
) {
    // Authorization must never be restored from an Activity saved-state bundle.
    var phase by authSession.phase
    val context = LocalContext.current
    var passwordCheckFailed by remember { mutableStateOf(false) }
    var passwordCheckAttempt by remember { mutableStateOf(0) }

    LaunchedEffect(passwordCheckAttempt) {
        if (phase == PHASE_LOADING) {
            passwordCheckFailed = false
            try {
                phase = if (passwordStore.isPasswordSet()) PHASE_UNLOCK else PHASE_SETUP
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                UnlockTimings.failure(context, "password state check failed", e)
                passwordCheckFailed = true
            }
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
                if (passwordCheckFailed) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            stringResource(R.string.auth_password_state_failed),
                            textAlign = TextAlign.Center,
                        )
                        Button(onClick = { passwordCheckAttempt++ }) {
                            Text(stringResource(R.string.database_retry))
                        }
                    }
                } else {
                    CircularProgressIndicator()
                }
            }
        }
        PHASE_SETUP ->
            SetPasswordScreen(
                passwordStore = passwordStore,
                onPasswordAlreadySet = { phase = PHASE_UNLOCK },
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
                val readyRepository = entryRepository
                if (readyRepository == null) {
                    VaultOpening(
                        failed = databaseFailed,
                        onRetry = onRetryDatabase,
                    )
                } else {
                    MainHomeScreen(
                        passwordStore = passwordStore,
                        entryRepository = readyRepository,
                        appPreferences = appPreferences,
                        biometricVault = biometricVault,
                        themeMode = themeMode,
                        onThemeModeChange = onThemeModeChange,
                        onRequireRelogin = { phase = PHASE_UNLOCK },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        else -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
    }
}

/** Shown only after a successful unlock while the encrypted database is still being opened. */
@Composable
private fun VaultOpening(failed: Boolean, onRetry: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (failed) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(24.dp),
            ) {
                Text(
                    text = stringResource(R.string.database_open_failed),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Button(onClick = onRetry) { Text(stringResource(R.string.database_retry)) }
            }
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.database_opening),
                    style = MaterialTheme.typography.bodySmall,
                )
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
