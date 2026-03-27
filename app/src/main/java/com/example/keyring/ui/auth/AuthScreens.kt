package com.example.keyring.ui.auth

import androidx.compose.foundation.Image
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.example.keyring.R
import com.example.keyring.data.AppPreferences
import com.example.keyring.data.BiometricPasswordVault
import com.example.keyring.data.PasswordStore

@Composable
fun SetPasswordScreen(
    passwordStore: PasswordStore,
    onSuccess: () -> Unit,
    modifier: Modifier = Modifier
) {
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val focusManager = LocalFocusManager.current
    val context = LocalContext.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(R.string.auth_set_title),
            style = MaterialTheme.typography.headlineSmall
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.auth_set_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
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
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Next
            )
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
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(
                onDone = { focusManager.clearFocus() }
            )
        )
        if (errorMessage != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = errorMessage!!,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }
        Spacer(modifier = Modifier.height(24.dp))
        Button(
            onClick = {
                when {
                    password.length < PasswordStore.MIN_PASSWORD_LENGTH ->
                        errorMessage = context.getString(R.string.auth_error_too_short)
                    password != confirm ->
                        errorMessage = context.getString(R.string.auth_error_mismatch)
                    else -> {
                        passwordStore.setPassword(password).fold(
                            onSuccess = { onSuccess() },
                            onFailure = {
                                errorMessage = context.getString(R.string.auth_error_generic)
                            }
                        )
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
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
    modifier: Modifier = Modifier
) {
    var password by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val focusManager = LocalFocusManager.current
    val context = LocalContext.current
    val activity = context as FragmentActivity

    val biometricReady =
        appPreferences.getBiometricUnlockEnabled() &&
            biometricVault.hasStoredCipherText() &&
            biometricVault.canEnrollStrongBiometric()

    LaunchedEffect(Unit) {
        if (appPreferences.getBiometricUnlockEnabled() &&
            biometricVault.hasStoredCipherText() &&
            biometricVault.canEnrollStrongBiometric()
        ) {
            biometricVault.authenticateDecryptPassword(activity) { result ->
                result.onSuccess { plain ->
                    if (passwordStore.verifyPassword(plain)) {
                        onSuccess()
                    }
                }
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(
            painter = painterResource(R.drawable.app_logo),
            contentDescription = null,
            modifier = Modifier.size(88.dp)
        )
        Spacer(modifier = Modifier.height(20.dp))
        Text(
            text = stringResource(R.string.auth_unlock_title),
            style = MaterialTheme.typography.headlineSmall
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.auth_unlock_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
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
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(
                onDone = {
                    focusManager.clearFocus()
                    if (passwordStore.verifyPassword(password)) {
                        onSuccess()
                    } else {
                        errorMessage = context.getString(R.string.auth_error_wrong_password)
                    }
                }
            )
        )
        if (errorMessage != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = errorMessage!!,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }
        Spacer(modifier = Modifier.height(24.dp))
        Button(
            onClick = {
                if (passwordStore.verifyPassword(password)) {
                    onSuccess()
                } else {
                    errorMessage = context.getString(R.string.auth_error_wrong_password)
                }
            },
            modifier = Modifier.fillMaxWidth(0.82f)
        ) {
            Text(stringResource(R.string.auth_unlock_button))
        }
        if (biometricReady) {
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(
                onClick = {
                    biometricVault.authenticateDecryptPassword(activity) { result ->
                        result.onSuccess { plain ->
                            if (passwordStore.verifyPassword(plain)) {
                                onSuccess()
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(0.82f)
            ) {
                Text(stringResource(R.string.auth_biometric_unlock))
            }
        }
    }
}
