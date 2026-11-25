package com.wudongsong.keyring.ui.home

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wudongsong.keyring.R
import com.wudongsong.keyring.data.AppPreferences
import com.wudongsong.keyring.data.BiometricPasswordVault
import com.wudongsong.keyring.data.PasswordGeneratorRules
import com.wudongsong.keyring.data.PasswordListSortOrder
import com.wudongsong.keyring.data.PasswordStore
import com.wudongsong.keyring.data.ThemeMode
import com.wudongsong.keyring.ui.rememberPasswordAction
import com.wudongsong.keyring.util.UnlockTimings

@Composable
fun SettingsScreen(
    themeMode: ThemeMode,
    darkTheme: Boolean,
    onThemeModeChange: (ThemeMode) -> Unit,
    appPreferences: AppPreferences,
    passwordStore: PasswordStore,
    biometricVault: BiometricPasswordVault,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val activity = context as FragmentActivity
    val listSort by
        appPreferences
            .observeListSortOrder()
            .collectAsStateWithLifecycle(initialValue = appPreferences.getListSortOrder())
    val autoLockEnabled by
        appPreferences
            .observeAutoLockEnabled()
            .collectAsStateWithLifecycle(initialValue = appPreferences.getAutoLockEnabled())
    val biometricEnabled by
        appPreferences
            .observeBiometricUnlockEnabled()
            .collectAsStateWithLifecycle(initialValue = appPreferences.getBiometricUnlockEnabled())
    val genRules by
        appPreferences
            .observePasswordGeneratorRules()
            .collectAsStateWithLifecycle(initialValue = appPreferences.getPasswordGeneratorRules())
    val networkFaviconEnabled by
        appPreferences
            .observeNetworkForFaviconEnabled()
            .collectAsStateWithLifecycle(
                initialValue = appPreferences.getNetworkForFaviconEnabled()
            )
    val dupPolicy by
        appPreferences
            .observeDuplicatePolicy()
            .collectAsStateWithLifecycle(initialValue = appPreferences.getDuplicatePolicy())

    val screenProtection by
        appPreferences
            .observeScreenProtectionEnabled()
            .collectAsStateWithLifecycle(initialValue = appPreferences.getScreenProtectionEnabled())
    val clipboardClear by
        appPreferences
            .observeClipboardAutoClearEnabled()
            .collectAsStateWithLifecycle(
                initialValue = appPreferences.getClipboardAutoClearEnabled()
            )

    val lengthOptions = remember { listOf(8, 12, 16, 20, 24, 32) }

    var showBiometricEnrollDialog by remember { mutableStateOf(false) }
    var enrollPassword by remember { mutableStateOf("") }
    var enrollPasswordError by remember { mutableStateOf<String?>(null) }
    // Toggle-level failures must surface outside the enroll dialog, which may already be closed.
    val authAction = rememberPasswordAction { error ->
        UnlockTimings.failure(context, "biometric toggle failed", error)
        Toast.makeText(context, context.getString(R.string.auth_error_generic), Toast.LENGTH_LONG)
            .show()
    }

    fun updateGeneratorRules(next: PasswordGeneratorRules) {
        if (next.hasAnyCharset()) {
            appPreferences.setPasswordGeneratorRules(next)
        } else {
            Toast.makeText(context, R.string.settings_gen_must_one_charset, Toast.LENGTH_SHORT).show()
        }
    }

    val sectionColor =
        if (darkTheme) MaterialTheme.colorScheme.surfaceContainer
        else MaterialTheme.colorScheme.surfaceContainerLowest
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier.widthIn(max = 600.dp).fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item(key = "appearance") {
                SettingsSection(stringResource(R.string.settings_section_appearance), sectionColor) {
                    SettingsDropdownRow(
                        label = stringResource(R.string.settings_theme_title),
                        icon = Icons.Filled.DarkMode,
                        options = ThemeMode.entries,
                        selected = themeMode,
                        optionLabel = { mode ->
                            when (mode) {
                                ThemeMode.LIGHT -> stringResource(R.string.settings_theme_light)
                                ThemeMode.DARK -> stringResource(R.string.settings_theme_dark)
                                ThemeMode.SYSTEM -> stringResource(R.string.settings_theme_follow_system)
                            }
                        },
                        onSelect = onThemeModeChange,
                    )
                    SettingsDivider()
                    SettingsDropdownRow(
                        label = stringResource(R.string.settings_list_sort_title),
                        icon = Icons.AutoMirrored.Filled.Sort,
                        options = PasswordListSortOrder.entries,
                        selected = listSort,
                        optionLabel = { order ->
                            when (order) {
                                PasswordListSortOrder.NAME_ASC -> stringResource(R.string.sort_name_asc)
                                PasswordListSortOrder.NAME_DESC -> stringResource(R.string.sort_name_desc)
                                PasswordListSortOrder.TIME_ASC -> stringResource(R.string.sort_time_asc)
                                PasswordListSortOrder.TIME_DESC -> stringResource(R.string.sort_time_desc)
                            }
                        },
                        onSelect = appPreferences::setListSortOrder,
                    )
                }
            }
            item(key = "security") {
                SettingsSection(stringResource(R.string.settings_section_security), sectionColor) {
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_auto_lock_title),
                        summary = stringResource(R.string.settings_auto_lock_summary),
                        icon = Icons.Filled.Lock,
                        checked = autoLockEnabled,
                        onCheckedChange = appPreferences::setAutoLockEnabled,
                    )
                    SettingsDivider()
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_biometric_title),
                        summary = stringResource(R.string.settings_biometric_summary),
                        icon = Icons.Filled.Fingerprint,
                        checked = biometricEnabled,
                        enabled = !authAction.busy,
                        onCheckedChange = { want ->
                            authAction.run {
                                if (!want) {
                                    biometricVault.clear()
                                    appPreferences.setBiometricUnlockEnabled(false)
                                } else if (biometricVault.canEnrollStrongBiometric()) {
                                    enrollPassword = ""
                                    enrollPasswordError = null
                                    showBiometricEnrollDialog = true
                                } else {
                                    Toast.makeText(context, R.string.biometric_unavailable, Toast.LENGTH_SHORT)
                                        .show()
                                }
                            }
                        },
                    )
                    SettingsDivider()
                    SettingsSwitchRow(
                        title = stringResource(R.string.screen_protection_title),
                        summary = stringResource(R.string.screen_protection_summary),
                        icon = Icons.Filled.VisibilityOff,
                        checked = screenProtection,
                        onCheckedChange = appPreferences::setScreenProtectionEnabled,
                    )
                    SettingsDivider()
                    SettingsSwitchRow(
                        title = stringResource(R.string.clipboard_clear_title),
                        summary = stringResource(R.string.clipboard_clear_summary),
                        icon = Icons.Filled.ContentCopy,
                        checked = clipboardClear,
                        onCheckedChange = appPreferences::setClipboardAutoClearEnabled,
                    )
                }
            }
            item(key = "network") {
                SettingsSection(stringResource(R.string.settings_section_network), sectionColor) {
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_network_favicon_title),
                        summary = stringResource(R.string.settings_network_favicon_summary),
                        icon = Icons.Filled.Public,
                        checked = networkFaviconEnabled,
                        onCheckedChange = appPreferences::setNetworkForFaviconEnabled,
                    )
                    SettingsDivider()
                    SettingsDropdownRow(
                        label = stringResource(R.string.settings_sync_dup_policy_title),
                        summary = stringResource(R.string.settings_sync_dup_summary),
                        icon = Icons.Filled.Sync,
                        options = AppPreferences.DuplicatePolicy.entries,
                        selected = dupPolicy,
                        optionLabel = { policy ->
                            when (policy) {
                                AppPreferences.DuplicatePolicy.SKIP ->
                                    stringResource(R.string.settings_sync_dup_skip)
                                AppPreferences.DuplicatePolicy.OVERWRITE_NEWER ->
                                    stringResource(R.string.settings_sync_dup_overwrite)
                                AppPreferences.DuplicatePolicy.KEEP_BOTH ->
                                    stringResource(R.string.settings_sync_dup_keep_both)
                            }
                        },
                        onSelect = appPreferences::setDuplicatePolicy,
                    )
                }
            }
            item(key = "generator") {
                SettingsSection(stringResource(R.string.settings_section_generator), sectionColor) {
                    SettingsDropdownRow(
                        label = stringResource(R.string.settings_generator_length_label),
                        summary = stringResource(R.string.settings_generator_summary),
                        icon = Icons.Filled.Password,
                        options = lengthOptions,
                        selected = genRules.length,
                        optionLabel = { n -> stringResource(R.string.settings_gen_length_bits, n) },
                        onSelect = { n -> updateGeneratorRules(genRules.copy(length = n)) },
                    )
                    SettingsDivider(start = 16.dp)
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_gen_upper),
                        checked = genRules.includeUppercase,
                        onCheckedChange = { v -> updateGeneratorRules(genRules.copy(includeUppercase = v)) },
                    )
                    SettingsDivider(start = 16.dp)
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_gen_lower),
                        checked = genRules.includeLowercase,
                        onCheckedChange = { v -> updateGeneratorRules(genRules.copy(includeLowercase = v)) },
                    )
                    SettingsDivider(start = 16.dp)
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_gen_digits),
                        checked = genRules.includeDigits,
                        onCheckedChange = { v -> updateGeneratorRules(genRules.copy(includeDigits = v)) },
                    )
                    SettingsDivider(start = 16.dp)
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_gen_symbols),
                        checked = genRules.includeSymbols,
                        onCheckedChange = { v -> updateGeneratorRules(genRules.copy(includeSymbols = v)) },
                    )
                }
            }
        }
    }

    if (showBiometricEnrollDialog) {
        AlertDialog(
            onDismissRequest = {
                if (authAction.busy) return@AlertDialog
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
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        enabled = !authAction.busy,
                        value = enrollPassword,
                        onValueChange = {
                            enrollPassword = it
                            enrollPasswordError = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.auth_password_label)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions =
                            KeyboardOptions(
                                keyboardType = KeyboardType.Password,
                                imeAction = ImeAction.Done,
                            ),
                    )
                    enrollPasswordError?.let { err ->
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = err,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !authAction.busy,
                    onClick = {
                        authAction.run {
                            UnlockTimings.event(context, "enroll: submit")
                            val pwd = enrollPassword
                            val verifiedRecord = passwordStore.verifyForBiometricEnrollment(pwd)
                            UnlockTimings.event(
                                context,
                                "enroll: verified=${verifiedRecord != null}",
                            )
                            if (verifiedRecord == null) {
                                enrollPasswordError =
                                    context.getString(R.string.auth_error_wrong_password)
                                return@run
                            }
                            enrollPassword = ""
                            showBiometricEnrollDialog = false
                            enrollPasswordError = null
                            val enrolled =
                                try {
                                    biometricVault.enrollEncryptPassword(activity, pwd)
                                } catch (e: kotlinx.coroutines.CancellationException) {
                                    UnlockTimings.event(context, "enroll: cancelled")
                                    throw e
                                }
                            val ciphertextId = enrolled.getOrNull()
                            if (ciphertextId == null) {
                                val reason =
                                    enrolled.exceptionOrNull()?.let { e ->
                                        messageFor(context, e)
                                    }
                                Toast.makeText(
                                        context,
                                        context.getString(
                                            R.string.biometric_enroll_failed,
                                            reason ?: "",
                                        ),
                                        Toast.LENGTH_LONG,
                                    )
                                    .show()
                                return@run
                            }
                            if (
                                !passwordStore.bindBiometricCredential(
                                    ciphertextId,
                                    verifiedRecord,
                                )
                            ) {
                                UnlockTimings.event(context, "enroll: bind rejected")
                                // Keep the switch off and drop the half-written ciphertext so the
                                // next attempt starts from a clean state.
                                biometricVault.clear()
                                Toast.makeText(
                                        context,
                                        context.getString(R.string.biometric_enroll_failed, ""),
                                        Toast.LENGTH_LONG,
                                    )
                                    .show()
                                return@run
                            }
                            appPreferences.setBiometricUnlockEnabled(true)
                            Toast.makeText(
                                    context,
                                    context.getString(R.string.biometric_enroll_success),
                                    Toast.LENGTH_SHORT,
                                )
                                .show()
                        }
                    },
                ) {
                    if (authAction.busy)
                        androidx.compose.material3.CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                        )
                    else Text(stringResource(R.string.biometric_enroll_continue))
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !authAction.busy,
                    onClick = {
                        showBiometricEnrollDialog = false
                        enrollPassword = ""
                        enrollPasswordError = null
                    },
                ) {
                    Text(stringResource(R.string.dialog_cancel))
                }
            },
        )
    }
}

@Composable
private fun SettingsSection(title: String, color: Color, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 12.dp).semantics { heading() },
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = color,
        ) {
            Column(content = content)
        }
    }
}

@Composable
private fun SettingsDivider(start: Dp = 64.dp) {
    HorizontalDivider(
        modifier = Modifier.padding(start = start, end = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f),
    )
}

@Composable
private fun SettingsIcon(icon: ImageVector) {
    Surface(
        modifier = Modifier.size(32.dp),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    summary: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = summary?.let { text -> { Text(text) } },
        leadingContent = icon?.let { image -> { SettingsIcon(image) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.toggleable(
            value = checked,
            enabled = enabled,
            role = Role.Switch,
            onValueChange = onCheckedChange,
        ),
    )
}

@Composable
private fun <T> SettingsDropdownRow(
    label: String,
    icon: ImageVector,
    options: List<T>,
    selected: T,
    optionLabel: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    summary: String? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = { Text(label) },
            supportingContent = {
                Column {
                    Text(optionLabel(selected), color = MaterialTheme.colorScheme.primary)
                    if (summary != null) Text(summary)
                }
            },
            leadingContent = { SettingsIcon(icon) },
            trailingContent = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.clickable(role = Role.Button, onClick = { expanded = true }),
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                val active = option == selected
                DropdownMenuItem(
                    text = { Text(optionLabel(option)) },
                    trailingIcon = if (active) {
                        { Icon(Icons.Filled.Check, contentDescription = null) }
                    } else null,
                    modifier = Modifier.semantics { this.selected = active },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    },
                )
            }
        }
    }
}

/** BiometricPrompt reports failures as "<code>: <message>"; translate the common ones. */
private fun messageFor(context: android.content.Context, error: Throwable): String {
    val raw = error.message.orEmpty()
    val code = raw.substringBefore(':').trim().toIntOrNull()
    return when {
        raw == "no_data" || raw == "missing_key" ->
            context.getString(R.string.biometric_enroll_state_lost)
        code == 10 || code == 13 -> context.getString(R.string.biometric_enroll_cancelled)
        code != null || raw.contains("not ready", ignoreCase = true) ->
            context.getString(R.string.biometric_enroll_state_lost)
        else -> context.getString(R.string.auth_error_generic)
    }
}
