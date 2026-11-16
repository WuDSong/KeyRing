package com.wudongsong.keyring.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf

class ExternalActivityState {
    var pending: Boolean = false
}

val LocalExternalActivityState = staticCompositionLocalOf<ExternalActivityState> { error("Missing external activity state") }

class ExternalActivityLauncher<I>(private val action: (I) -> Unit) {
    fun launch(input: I) = action(input)
}

/** Only an explicitly launched picker/document operation may defer background locking. */
@Composable
fun <I, O> rememberExternalActivityLauncher(
    contract: ActivityResultContract<I, O>,
    onResult: (O) -> Unit
): ExternalActivityLauncher<I> {
    val state = LocalExternalActivityState.current
    val launcher = rememberLauncherForActivityResult(contract) { result ->
        state.pending = false
        onResult(result)
    }
    return remember(launcher, state) {
        ExternalActivityLauncher { input ->
            state.pending = true
            try {
                launcher.launch(input)
            } catch (e: Exception) {
                state.pending = false
                throw e
            }
        }
    }
}
