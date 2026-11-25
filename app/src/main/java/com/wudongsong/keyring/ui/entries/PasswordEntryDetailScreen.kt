package com.wudongsong.keyring.ui.entries

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.wudongsong.keyring.R
import com.wudongsong.keyring.data.PasswordEntryRepository
import com.wudongsong.keyring.data.local.PasswordEntry
import com.wudongsong.keyring.data.local.allAttachmentPaths
import com.wudongsong.keyring.ui.theme.MyPasswordsTheme
import com.wudongsong.keyring.util.DateTimeFormatters
import com.wudongsong.keyring.util.RelativeTimeFormatter
import java.io.File
import kotlinx.coroutines.flow.map

private data class DetailState(val loaded: Boolean = false, val entry: PasswordEntry? = null)

@Composable
fun PasswordEntryDetailScreen(
    entryId: Long,
    repository: PasswordEntryRepository,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by
        remember(entryId, repository) {
                repository.observeEntry(entryId).map { DetailState(true, it) }
            }
            .collectAsStateWithLifecycle(initialValue = DetailState())
    val context = LocalContext.current
    val app = context.applicationContext as com.wudongsong.keyring.MyPasswordsApplication
    LaunchedEffect(state.loaded, state.entry?.id) {
        if (state.loaded && state.entry == null) onBack()
    }
    val entry = state.entry
    if (entry == null)
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    else
        PasswordEntryDetailContent(
            entry,
            onCopy = { label, text ->
                app.sensitiveClipboard.copy(
                    label,
                    text,
                    app.appPreferences.getClipboardAutoClearEnabled(),
                )
                Toast.makeText(
                        context,
                        context.getString(R.string.detail_copied_toast, label),
                        Toast.LENGTH_SHORT,
                    )
                    .show()
            },
            modifier = modifier,
        )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PasswordEntryDetailContent(
    entry: PasswordEntry,
    onCopy: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var zoomFile by remember(entry.id) { mutableStateOf<File?>(null) }
    val attachmentPaths =
        remember(entry.attachmentsJson, entry.imagePath) { entry.allAttachmentPaths() }
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(top = 12.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        // The identity stays compact so credentials remain visible above the fold.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            PasswordListRowAvatar(entry.title, entry.avatarImagePath, Modifier.size(64.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(entry.title, style = MaterialTheme.typography.headlineSmall)
                if (entry.description.isNotBlank())
                    SelectionContainer {
                        Text(
                            entry.description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
            }
        }
        if (entry.password.isNotBlank())
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                DetailSectionLabel(stringResource(R.string.field_password))
                Card(
                    shape = RoundedCornerShape(22.dp),
                    colors =
                        CardDefaults.cardColors(
                            containerColor =
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                        ),
                ) {
                    CredentialRow(
                        stringResource(R.string.field_password),
                        entry.password,
                        Icons.Outlined.Key,
                        onCopy,
                        isPassword = true,
                    )
                }
            }
        val fields =
            listOf(
                    Triple(
                        R.string.field_account_name,
                        entry.accountName,
                        Icons.Outlined.AlternateEmail,
                    ),
                    Triple(R.string.field_username, entry.username, Icons.Outlined.PersonOutline),
                    Triple(R.string.field_url, entry.url, Icons.Outlined.Language),
                )
                .filter { it.second.isNotBlank() }
        if (fields.isNotEmpty())
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                DetailSectionLabel(stringResource(R.string.detail_account_section))
                Card(
                    shape = RoundedCornerShape(22.dp),
                    colors =
                        CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                        ),
                ) {
                    fields.forEachIndexed { index, (label, value, icon) ->
                        if (index > 0)
                            HorizontalDivider(
                                Modifier.padding(start = 60.dp, end = 16.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
                            )
                        CredentialRow(stringResource(label), value, icon, onCopy)
                    }
                }
            }
        if (!entry.detailedDescription.isNullOrBlank())
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                DetailSectionLabel(stringResource(R.string.field_detailed_description))
                Card(
                    shape = RoundedCornerShape(22.dp),
                    colors =
                        CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                        ),
                ) {
                    SelectionContainer {
                        Text(
                            entry.detailedDescription,
                            Modifier.padding(18.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        val tags =
            remember(entry.tags) {
                entry.tags.split(',', '，').map(String::trim).filter(String::isNotEmpty)
            }
        if (tags.isNotEmpty())
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val tagsLabel = stringResource(R.string.dialog_tags_label)
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DetailSectionLabel(tagsLabel)
                    TextButton(onClick = { onCopy(tagsLabel, entry.tags.trim()) }) {
                        Text(stringResource(R.string.action_copy))
                    }
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    tags.forEach { tag ->
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
                        ) {
                            Text(
                                tag,
                                Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                }
            }
        if (attachmentPaths.isNotEmpty())
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DetailSectionLabel(
                        stringResource(R.string.detail_attachment_count, attachmentPaths.size)
                    )
                    Text(
                        stringResource(R.string.detail_tap_image_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    itemsIndexed(attachmentPaths, key = { index, path -> "$index:$path" }) {
                        index,
                        path ->
                        val file = remember(path) { File(path) }
                        Column(
                            Modifier.width(144.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                            ) {
                                AsyncImage(
                                    file,
                                    contentDescription =
                                        stringResource(R.string.detail_image_number, index + 1),
                                    contentScale = ContentScale.Crop,
                                    modifier =
                                        Modifier.size(144.dp, 176.dp).clickable { zoomFile = file },
                                )
                            }
                            Text(
                                stringResource(R.string.detail_image_number, index + 1),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            Icon(
                Icons.Outlined.Schedule,
                contentDescription = null,
                Modifier.size(15.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(
                    R.string.detail_last_updated_line,
                    DateTimeFormatters.formatDateTime(entry.updatedAt),
                    RelativeTimeFormatter.formatPast(context, entry.updatedAt),
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    zoomFile?.let { ImageAttachmentZoomDialog(it, onDismiss = { zoomFile = null }) }
}

@Composable
private fun DetailSectionLabel(label: String) {
    Text(
        label,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun CredentialRow(
    label: String,
    value: String,
    icon: ImageVector,
    onCopy: (String, String) -> Unit,
    isPassword: Boolean = false,
) {
    var visible by remember(value) { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val text = if (isPassword && !visible) "•••• •••• ••••" else value
            Text(
                text,
                style = MaterialTheme.typography.bodyLarge,
                fontFamily = if (isPassword) FontFamily.Monospace else null,
            )
        }
        Row {
            if (isPassword)
                IconButton(onClick = { visible = !visible }) {
                    Icon(
                        if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        contentDescription =
                            stringResource(
                                if (visible) R.string.detail_hide_password_desc
                                else R.string.detail_show_password_desc
                            ),
                        modifier = Modifier.size(20.dp),
                    )
                }
            IconButton(onClick = { onCopy(label, value) }) {
                Icon(
                    Icons.Outlined.ContentCopy,
                    contentDescription = stringResource(R.string.detail_copy_field, label),
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 393, heightDp = 852)
@Composable
private fun DetailDesignPreview() {
    MyPasswordsTheme {
        PasswordEntryDetailContent(
            PasswordEntry(
                id = 1,
                title = "示例服务",
                accountName = "hello@example.com",
                username = "demo_user",
                password = "Example-Only-123",
                url = "https://example.com/account",
                description = "个人账号 · 日常使用",
                detailedDescription = "这是用于预览的示例条目。",
                tags = "个人,常用",
                imagePath = null,
            ),
            { _, _ -> },
        )
    }
}

@Preview(showBackground = true, widthDp = 393, heightDp = 852, fontScale = 1.3f)
@Composable
private fun DetailLargeTextPreview() {
    MyPasswordsTheme(darkTheme = true) {
        PasswordEntryDetailContent(
            PasswordEntry(
                id = 1,
                title = "Example service",
                accountName = "long.account.name@example.com",
                username = "example_user",
                password = "Example-Only-123",
                url = "https://example.com/very/long/account/address",
                description = "Personal account",
                detailedDescription = null,
                tags = "Personal,Daily",
                imagePath = null,
            ),
            { _, _ -> },
        )
    }
}
