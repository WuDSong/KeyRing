package com.wudongsong.keyring.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class PasswordAction(private val scope: CoroutineScope, private val onError: () -> Unit) {
    var busy by mutableStateOf(false)
        private set

    fun run(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                onError()
            } finally {
                busy = false
            }
        }
    }
}

@Composable
fun rememberPasswordAction(onError: () -> Unit): PasswordAction {
    val scope = rememberCoroutineScope()
    val currentError by rememberUpdatedState(onError)
    return remember(scope) { PasswordAction(scope) { currentError() } }
}
