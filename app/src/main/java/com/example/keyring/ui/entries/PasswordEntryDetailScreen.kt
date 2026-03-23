package com.example.keyring.ui.entries

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.widget.Toast
import coil.compose.AsyncImage
import com.example.keyring.R
import com.example.keyring.data.PasswordEntryRepository
import com.example.keyring.data.local.PasswordEntry
import com.example.keyring.data.local.allAttachmentPaths
import com.example.keyring.util.DateTimeFormatters
import com.example.keyring.util.RelativeTimeFormatter
import java.io.File

@Composable
fun PasswordEntryDetailScreen(
    entryId: Long,
    repository: PasswordEntryRepository,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val entries by repository.observeEntries().collectAsStateWithLifecycle(initialValue = emptyList())
    val entry = entries.find { it.id == entryId }
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    LaunchedEffect(entries, entryId) {
        if (entries.isNotEmpty() && entries.none { it.id == entryId }) {
            onBack()
        }
    }

    when {
        entries.isEmpty() -> {
            Box(
                modifier = modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        }
        entry == null -> {
            Box(
                modifier = modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        }
        else -> {
            PasswordEntryDetailContent(
                entry = entry,
                modifier = modifier,
                onCopy = { label, text ->
                    clipboard.setText(AnnotatedString(text))
                    Toast.makeText(
                        context,
                        context.getString(R.string.detail_copied_toast, label),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PasswordEntryDetailContent(
    entry: PasswordEntry,
    onCopy: (label: String, text: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scroll = rememberScrollState()
    var zoomFile by remember { mutableStateOf<File?>(null) }

    Column(
        modifier = modifier
            .verticalScroll(scroll)
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 8.dp)
    ) {
        TitleAvatarSection(
            title = entry.title,
            avatarImagePath = entry.avatarImagePath,
            onEditAvatarClick = {},
            showEditBadge = false
        )
        Text(
            text = entry.title,
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = entry.description,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))
        HorizontalDivider()

        DetailCopyableSection(
            label = stringResource(R.string.field_account_name),
            value = entry.accountName,
            onCopy = onCopy
        )
        DetailCopyableSection(
            label = stringResource(R.string.field_username),
            value = entry.username,
            onCopy = onCopy
        )
        DetailCopyableSection(
            label = stringResource(R.string.field_password),
            value = entry.password,
            onCopy = onCopy,
            isPasswordField = true
        )
        DetailCopyableSection(
            label = stringResource(R.string.field_url),
            value = entry.url,
            onCopy = onCopy
        )
        DetailTagsSection(
            tagsRaw = entry.tags,
            onCopy = onCopy
        )

        val attachmentPaths = entry.allAttachmentPaths().filter { File(it).exists() }
        if (attachmentPaths.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.detail_attachment_title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            attachmentPaths.forEach { path ->
                val file = File(path)
                Spacer(modifier = Modifier.height(8.dp))
                AsyncImage(
                    model = file,
                    contentDescription = stringResource(R.string.add_image_preview_desc),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .clickable { zoomFile = file }
                )
            }
        }

        zoomFile?.let { file ->
            ImageAttachmentZoomDialog(imageFile = file, onDismiss = { zoomFile = null })
        }

        Spacer(modifier = Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(
                R.string.detail_last_updated_line,
                DateTimeFormatters.formatDateTime(entry.updatedAt),
                RelativeTimeFormatter.formatPast(context, entry.updatedAt)
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun DetailCopyableSection(
    label: String,
    value: String,
    onCopy: (label: String, text: String) -> Unit,
    isPasswordField: Boolean = false
) {
    if (value.isBlank()) return

    var passwordVisible by remember { mutableStateOf(false) }

    Spacer(modifier = Modifier.height(8.dp))
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                val displayText =
                    if (isPasswordField && !passwordVisible) {
                        "\u2022".repeat(value.length)
                    } else {
                        value
                    }
                Text(
                    text = displayText,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isPasswordField) {
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(
                            imageVector = if (passwordVisible) {
                                Icons.Filled.VisibilityOff
                            } else {
                                Icons.Filled.Visibility
                            },
                            contentDescription = stringResource(
                                if (passwordVisible) {
                                    R.string.detail_hide_password_desc
                                } else {
                                    R.string.detail_show_password_desc
                                }
                            )
                        )
                    }
                }
                TextButton(onClick = { onCopy(label, value) }) {
                    Text(stringResource(R.string.action_copy))
                }
            }
        }
    }
}

private fun parseTagList(tagsRaw: String): List<String> =
    tagsRaw.split(',', '，').map { it.trim() }.filter { it.isNotEmpty() }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailTagsSection(
    tagsRaw: String,
    onCopy: (label: String, text: String) -> Unit
) {
    if (tagsRaw.isBlank()) return

    val tags = parseTagList(tagsRaw)
    if (tags.isEmpty()) return

    val tagsLabel = stringResource(R.string.dialog_tags_label)

    Spacer(modifier = Modifier.height(8.dp))
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = tagsLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    tags.forEach { tag ->
                        AssistChip(
                            onClick = { },
                            label = { Text(tag) },
                            enabled = false
                        )
                    }
                }
            }
            TextButton(
                onClick = { onCopy(tagsLabel, tagsRaw.trim()) }
            ) {
                Text(stringResource(R.string.action_copy))
            }
        }
    }
}
