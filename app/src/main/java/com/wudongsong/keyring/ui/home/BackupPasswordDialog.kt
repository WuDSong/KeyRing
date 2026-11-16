package com.wudongsong.keyring.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.wudongsong.keyring.R
import com.wudongsong.keyring.security.BackupCipher

@Composable
fun BackupPasswordDialog(
    export: Boolean,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onConfirm: (CharArray) -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    val valid =
        !export || (password.length >= BackupCipher.MIN_PASSWORD_LENGTH && password == confirmation)
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = {
            Text(
                stringResource(
                    if (export) R.string.backup_encrypt_title else R.string.backup_decrypt_title
                )
            )
        },
        text = {
            Column {
                Text(
                    stringResource(
                        if (export) R.string.backup_encrypt_description
                        else R.string.backup_decrypt_description
                    )
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    enabled = !busy,
                    label = { Text(stringResource(R.string.backup_password_label)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                if (export) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = confirmation,
                        onValueChange = { confirmation = it },
                        enabled = !busy,
                        label = { Text(stringResource(R.string.auth_confirm_label)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    )
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (busy) {
                    Spacer(Modifier.height(8.dp))
                    CircularProgressIndicator()
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid && !busy,
                onClick = {
                    val secret = password.toCharArray()
                    password = ""
                    confirmation = ""
                    onConfirm(secret)
                },
            ) {
                Text(
                    stringResource(
                        if (export) R.string.drawer_export_data else R.string.drawer_import_data
                    )
                )
            }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) {
                Text(stringResource(R.string.dialog_cancel))
            }
        },
    )
}
