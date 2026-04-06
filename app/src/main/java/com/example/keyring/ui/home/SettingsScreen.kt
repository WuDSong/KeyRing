package com.example.keyring.ui.home

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.keyring.R
import com.example.keyring.data.AppPreferences
import com.example.keyring.data.BiometricPasswordVault
import com.example.keyring.data.PasswordListSortOrder
import com.example.keyring.data.PasswordStore
import com.example.keyring.data.ThemeMode

@Composable
fun SettingsScreen(
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    appPreferences: AppPreferences,
    passwordStore: PasswordStore,
    biometricVault: BiometricPasswordVault,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val activity = context as FragmentActivity
    val listSort by appPreferences.observeListSortOrder().collectAsStateWithLifecycle(
        initialValue = appPreferences.getListSortOrder()
    )
    val autoLockEnabled by appPreferences.observeAutoLockEnabled().collectAsStateWithLifecycle(
        initialValue = appPreferences.getAutoLockEnabled()
    )
    val biometricEnabled by appPreferences.observeBiometricUnlockEnabled().collectAsStateWithLifecycle(
        initialValue = appPreferences.getBiometricUnlockEnabled()
    )
    val genRules by appPreferences.observePasswordGeneratorRules().collectAsStateWithLifecycle(
        initialValue = appPreferences.getPasswordGeneratorRules()
    )
    val networkFaviconEnabled by appPreferences.observeNetworkForFaviconEnabled()
        .collectAsStateWithLifecycle(
            initialValue = appPreferences.getNetworkForFaviconEnabled()
        )
    val dupPolicy by appPreferences.observeDuplicatePolicy().collectAsStateWithLifecycle(
        initialValue = appPreferences.getDuplicatePolicy()
    )

    val lengthOptions = remember {
        listOf(8, 12, 16, 20, 24, 32)
    }

    var showBiometricEnrollDialog by remember { mutableStateOf(false) }
    var enrollPassword by remember { mutableStateOf("") }
    var enrollPasswordError by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            SettingsSectionHeader(text = stringResource(R.string.settings_section_appearance))
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column {
                    SettingsDropdownRow(
                        label = stringResource(R.string.settings_dark_mode_title),
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Filled.DarkMode,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        },
                        options = ThemeMode.entries,
                        selected = themeMode,
                        optionLabel = { mode ->
                            when (mode) {
                                ThemeMode.LIGHT -> stringResource(R.string.settings_theme_off)
                                ThemeMode.DARK -> stringResource(R.string.settings_theme_on)
                                ThemeMode.SYSTEM -> stringResource(R.string.settings_theme_follow_system)
                            }
                        },
                        onSelect = onThemeModeChange
                    )
                }
            }
        }
        item {
            SettingsSectionHeader(text = stringResource(R.string.settings_section_security))
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column {
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_auto_lock_title),
                        summary = stringResource(R.string.settings_auto_lock_summary),
                        checked = autoLockEnabled,
                        onCheckedChange = { appPreferences.setAutoLockEnabled(it) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Filled.Lock,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 8.dp))
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_biometric_title),
                        summary = stringResource(R.string.settings_biometric_summary),
                        checked = biometricEnabled,
                        onCheckedChange = { want ->
                            if (!want) {
                                biometricVault.clear()
                                appPreferences.setBiometricUnlockEnabled(false)
                            } else {
                                if (!biometricVault.canEnrollStrongBiometric()) {
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.biometric_unavailable),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                } else {
                                    enrollPassword = ""
                                    enrollPasswordError = null
                                    showBiometricEnrollDialog = true
                                }
                            }
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Filled.Fingerprint,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    )
                }
            }
        }
        item {
            SettingsSectionHeader(text = stringResource(R.string.settings_section_list))
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column {
                    SettingsDropdownRow(
                        label = stringResource(R.string.settings_list_sort_title),
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Filled.Sort,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        },
                        options = PasswordListSortOrder.entries,
                        selected = listSort,
                        optionLabel = { order ->
                            when (order) {
                                PasswordListSortOrder.NAME_ASC ->
                                    stringResource(R.string.sort_name_asc)
                                PasswordListSortOrder.NAME_DESC ->
                                    stringResource(R.string.sort_name_desc)
                                PasswordListSortOrder.TIME_ASC ->
                                    stringResource(R.string.sort_time_asc)
                                PasswordListSortOrder.TIME_DESC ->
                                    stringResource(R.string.sort_time_desc)
                            }
                        },
                        onSelect = { appPreferences.setListSortOrder(it) }
                    )
                }
            }
        }
        item {
            SettingsSectionHeader(text = stringResource(R.string.settings_section_network))
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column {
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_network_favicon_title),
                        summary = stringResource(R.string.settings_network_favicon_summary),
                        checked = networkFaviconEnabled,
                        onCheckedChange = { appPreferences.setNetworkForFaviconEnabled(it) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Filled.Public,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    )
                }
            }
        }
        item {
            SettingsSectionHeader(text = stringResource(R.string.settings_section_sync))
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column {
                    Text(
                        text = stringResource(R.string.settings_sync_dup_summary),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                    SettingsDropdownRow(
                        label = stringResource(R.string.settings_sync_dup_policy_title),
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Filled.Sync,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        },
                        options = AppPreferences.DuplicatePolicy.entries.toList(),
                        selected = dupPolicy,
                        optionLabel = { p ->
                            when (p) {
                                AppPreferences.DuplicatePolicy.SKIP ->
                                    stringResource(R.string.settings_sync_dup_skip)
                                AppPreferences.DuplicatePolicy.OVERWRITE_NEWER ->
                                    stringResource(R.string.settings_sync_dup_overwrite)
                                AppPreferences.DuplicatePolicy.KEEP_BOTH ->
                                    stringResource(R.string.settings_sync_dup_keep_both)
                            }
                        },
                        onSelect = { appPreferences.setDuplicatePolicy(it) }
                    )
                }
            }
        }
        item {
            SettingsSectionHeader(text = stringResource(R.string.settings_section_generator))
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column {
                    Text(
                        text = stringResource(R.string.settings_generator_summary),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                    SettingsDropdownRow(
                        label = stringResource(R.string.settings_generator_length_label),
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Filled.Password,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        },
                        options = lengthOptions,
                        selected = genRules.length,
                        optionLabel = { n ->
                            stringResource(R.string.settings_gen_length_bits, n)
                        },
                        onSelect = { n ->
                            appPreferences.setPasswordGeneratorRules(genRules.copy(length = n))
                        }
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 8.dp))
                    SettingsCharsetSwitchRow(
                        title = stringResource(R.string.settings_gen_upper),
                        checked = genRules.includeUppercase,
                        onCheckedChange = { v ->
                            val next = genRules.copy(includeUppercase = v)
                            if (!next.hasAnyCharset()) {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.settings_gen_must_one_charset),
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                appPreferences.setPasswordGeneratorRules(next)
                            }
                        }
                    )
                    SettingsCharsetSwitchRow(
                        title = stringResource(R.string.settings_gen_lower),
                        checked = genRules.includeLowercase,
                        onCheckedChange = { v ->
                            val next = genRules.copy(includeLowercase = v)
                            if (!next.hasAnyCharset()) {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.settings_gen_must_one_charset),
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                appPreferences.setPasswordGeneratorRules(next)
                            }
                        }
                    )
                    SettingsCharsetSwitchRow(
                        title = stringResource(R.string.settings_gen_digits),
                        checked = genRules.includeDigits,
                        onCheckedChange = { v ->
                            val next = genRules.copy(includeDigits = v)
                            if (!next.hasAnyCharset()) {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.settings_gen_must_one_charset),
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                appPreferences.setPasswordGeneratorRules(next)
                            }
                        }
                    )
                    SettingsCharsetSwitchRow(
                        title = stringResource(R.string.settings_gen_symbols),
                        checked = genRules.includeSymbols,
                        onCheckedChange = { v ->
                            val next = genRules.copy(includeSymbols = v)
                            if (!next.hasAnyCharset()) {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.settings_gen_must_one_charset),
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                appPreferences.setPasswordGeneratorRules(next)
                            }
                        }
                    )
                }
            }
        }
    }

    if (showBiometricEnrollDialog) {
        AlertDialog(
            onDismissRequest = {
                showBiometricEnrollDialog = false
                enrollPassword = ""
                enrollPasswordError = null
            },
            title = { Text(stringResource(R.string.biometric_enroll_verify_title)) },
            text = {
                Column {
                    Text(
                        text = stringResource(R.string.biometric_enroll_verify_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = enrollPassword,
                        onValueChange = {
                            enrollPassword = it
                            enrollPasswordError = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.auth_password_label)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done
                        )
                    )
                    enrollPasswordError?.let { err ->
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = err,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (!passwordStore.verifyPassword(enrollPassword)) {
                            enrollPasswordError =
                                context.getString(R.string.auth_error_wrong_password)
                            return@TextButton
                        }
                        val pwd = enrollPassword
                        enrollPassword = ""
                        showBiometricEnrollDialog = false
                        enrollPasswordError = null
                        biometricVault.enrollEncryptPassword(activity, pwd) { result ->
                            result.fold(
                                onSuccess = {
                                    appPreferences.setBiometricUnlockEnabled(true)
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.biometric_enroll_success),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                },
                                onFailure = { e ->
                                    Toast.makeText(
                                        context,
                                        context.getString(
                                            R.string.biometric_enroll_failed,
                                            e.message ?: ""
                                        ),
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            )
                        }
                    }
                ) {
                    Text(stringResource(R.string.biometric_enroll_continue))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showBiometricEnrollDialog = false
                        enrollPassword = ""
                        enrollPasswordError = null
                    }
                ) {
                    Text(stringResource(R.string.dialog_cancel))
                }
            }
        )
    }
}

@Composable
private fun SettingsCharsetSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f, fill = false)
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    summary: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    leadingIcon: @Composable () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        leadingIcon()
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
private fun SettingsSectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
    )
}

@Composable
private fun <T> SettingsDropdownRow(
    label: String,
    leadingIcon: @Composable () -> Unit,
    options: List<T>,
    selected: T,
    optionLabel: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedText = optionLabel(selected)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            leadingIcon()
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f, fill = false)
            )
        }
        Box(contentAlignment = Alignment.CenterEnd) {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.widthIn(min = 120.dp, max = 220.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = selectedText,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        imageVector = Icons.Filled.ArrowDropDown,
                        contentDescription = null
                    )
                }
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.widthIn(min = 200.dp, max = 320.dp)
            ) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(optionLabel(option)) },
                        onClick = {
                            onSelect(option)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}
