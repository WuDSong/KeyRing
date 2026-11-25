package com.wudongsong.keyring.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.withResumed
import coil.compose.AsyncImage
import com.wudongsong.keyring.R
import com.wudongsong.keyring.data.AppPreferences
import com.wudongsong.keyring.data.BiometricPasswordVault
import com.wudongsong.keyring.data.PasswordStore
import com.wudongsong.keyring.ui.rememberPasswordAction
import com.wudongsong.keyring.util.UnlockTimings

@Composable
fun SetPasswordScreen(
    passwordStore: PasswordStore,
    onSuccess: () -> Unit,
    onPasswordAlreadySet: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val focusManager = LocalFocusManager.current
    val context = LocalContext.current
    val authAction = rememberPasswordAction { error ->
        UnlockTimings.failure(context, "set password failed", error)
        errorMessage = context.getString(R.string.auth_set_failed)
    }

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.auth_set_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.auth_set_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(24.dp))
        OutlinedTextField(
            value = password,
            onValueChange = {
                password = it
                errorMessage = null
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.auth_password_label)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions =
                KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
        )
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(
            value = confirm,
            onValueChange = {
                confirm = it
                errorMessage = null
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.auth_confirm_label)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions =
                KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
        )
        if (errorMessage != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = errorMessage!!,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(modifier = Modifier.height(24.dp))
        Button(
            enabled = !authAction.busy,
            onClick = {
                when {
                    password.length < PasswordStore.MIN_PASSWORD_LENGTH ->
                        errorMessage = context.getString(R.string.auth_error_too_short)
                    password != confirm ->
                        errorMessage = context.getString(R.string.auth_error_mismatch)
                    else -> {
                        authAction.run {
                            passwordStore
                                .setPassword(password)
                                .fold(
                                    onSuccess = { onSuccess() },
                                    onFailure = { error ->
                                        if (error is PasswordStore.PasswordAlreadySetException) {
                                            onPasswordAlreadySet()
                                        } else {
                                            errorMessage = context.getString(R.string.auth_set_failed)
                                        }
                                    },
                                )
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.auth_set_button))
        }
    }
}

@Composable
fun UnlockScreen(
    passwordStore: PasswordStore,
    appPreferences: AppPreferences,
    biometricVault: BiometricPasswordVault,
    onSuccess: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var password by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val focusManager = LocalFocusManager.current
    val context = LocalContext.current
    val authAction = rememberPasswordAction { error ->
        UnlockTimings.failure(context, "unlock failed", error)
        errorMessage = context.getString(R.string.auth_unlock_failed)
    }
    val keyboard = LocalSoftwareKeyboardController.current
    val submitPassword: () -> Unit = {
        val enteredPassword = password
        authAction.run {
            errorMessage = null
            UnlockTimings.event(context, "password submitted")
            keyboard?.hide()
            if (passwordStore.verifyPassword(enteredPassword)) {
                focusManager.clearFocus()
                onSuccess()
            } else {
                errorMessage = context.getString(R.string.auth_error_wrong_password)
            }
        }
    }
    // Neutral, non-error guidance. A failed automatic attempt must never look like a save error.
    var biometricHint by remember { mutableStateOf<String?>(null) }
    // Kept separate from the password action so a stuck or dismissed biometric prompt can never
    // disable the password field and the unlock button.
    val biometricAction = rememberPasswordAction { error ->
        UnlockTimings.failure(context, "biometric unlock failed", error)
        biometricHint = context.getString(R.string.biometric_unlock_failed)
    }
    val activity = context as FragmentActivity

    var biometricReady by remember { mutableStateOf(false) }
    val unlockWithBiometric: () -> Unit = {
        biometricHint = null
        biometricAction.run {
            val credential =
                biometricVault.authenticateDecryptPassword(activity).getOrNull()
            if (credential == null) {
                UnlockTimings.event(context, "biometric decrypt cancelled")
                biometricHint = context.getString(R.string.biometric_unlock_failed)
                return@run
            }
            if (passwordStore.verifyBiometricCredential(credential)) {
                UnlockTimings.event(context, "entering home")
                onSuccess()
            } else {
                errorMessage = context.getString(R.string.biometric_reverify_required)
            }
        }
    }
    LaunchedEffect(Unit) {
        val enabled = appPreferences.getBiometricUnlockEnabled()
        val available = biometricVault.canEnrollStrongBiometric()
        val stored = enabled && biometricVault.hasStoredCipherText()
        biometricReady = stored && available
        when {
            !enabled -> Unit
            !available -> biometricHint = context.getString(R.string.biometric_unavailable)
            !stored -> biometricHint = context.getString(R.string.biometric_unlock_failed)
        }
        if (biometricReady) activity.lifecycle.withResumed { unlockWithBiometric() }
    }

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AsyncImage(
            model = R.drawable.app_logo,
            contentDescription = null,
            modifier = Modifier.size(88.dp),
        )
        Spacer(modifier = Modifier.height(20.dp))
        Text(
            text = stringResource(R.string.auth_unlock_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.auth_unlock_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(24.dp))
        OutlinedTextField(
            value = password,
            onValueChange = {
                password = it
                errorMessage = null
            },
            modifier = Modifier.fillMaxWidth(0.82f),
            label = { Text(stringResource(R.string.auth_password_label)) },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(textAlign = TextAlign.Center),
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions =
                KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions =
                KeyboardActions(onDone = { submitPassword() }),
        )
        if (errorMessage != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = errorMessage!!,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(modifier = Modifier.height(24.dp))
        Button(
            enabled = !authAction.busy,
            onClick = submitPassword,
            modifier = Modifier.fillMaxWidth(0.82f),
        ) {
            if (authAction.busy)
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            else Text(stringResource(R.string.auth_unlock_button))
        }
        if (authAction.busy) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.auth_verifying),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (biometricReady) {
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(
                enabled = !biometricAction.busy,
                onClick = unlockWithBiometric,
                modifier = Modifier.fillMaxWidth(0.82f),
            ) {
                Text(stringResource(R.string.auth_biometric_unlock))
            }
        }
        biometricHint?.let { hint ->
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = hint,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
            if (biometricReady) {
                TextButton(enabled = !biometricAction.busy, onClick = unlockWithBiometric) {
                    Text(stringResource(R.string.biometric_retry))
                }
            }
        }
    }
}
