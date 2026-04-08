package com.example.keyring.ui.entries

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.activity.result.contract.ActivityResultContracts
import coil.compose.AsyncImage
import com.example.keyring.R
import com.example.keyring.data.AppPreferences
import com.example.keyring.data.PasswordEntryRepository
import com.example.keyring.data.local.PasswordEntry
import com.example.keyring.data.local.allAttachmentPaths
import com.example.keyring.data.local.encodeAttachmentPathsJson
import com.example.keyring.util.ImageStorage
import com.example.keyring.util.PasswordGenerator
import com.example.keyring.util.UrlValidation
import com.example.keyring.util.WebsiteFaviconFetcher
import java.util.UUID
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun AddPasswordScreen(
    repository: PasswordEntryRepository,
    appPreferences: AppPreferences,
    onSaved: () -> Unit,
    modifier: Modifier = Modifier,
    entryId: Long? = null,
    onRegisterTopBarSave: ((() -> Unit)?) -> Unit = {},
    onOpenSettings: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var title by remember { mutableStateOf("") }
    var accountName by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var confirmPasswordVisible by remember { mutableStateOf(false) }
    var url by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var tags by remember { mutableStateOf("") }
    var attachmentPaths by remember { mutableStateOf<List<String>>(emptyList()) }
    var avatarImagePath by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var faviconLoading by remember { mutableStateOf(false) }
    var showNetworkFaviconDialog by remember { mutableStateOf(false) }

    LaunchedEffect(entryId) {
        if (entryId == null) return@LaunchedEffect
        val e = repository.getEntry(entryId) ?: return@LaunchedEffect
        title = e.title
        accountName = e.accountName
        username = e.username
        password = e.password
        confirmPassword = e.password
        url = e.url
        description = e.description
        tags = e.tags
        attachmentPaths = e.allAttachmentPaths()
        avatarImagePath = e.avatarImagePath
    }

    SideEffect {
        onRegisterTopBarSave {
            if (title.isBlank()) {
                errorMessage = context.getString(R.string.error_title_required)
                return@onRegisterTopBarSave
            }
            if (entryId == null && !UrlValidation.isAcceptableUrlOrEmpty(url)) {
                errorMessage = context.getString(R.string.error_url_invalid)
                return@onRegisterTopBarSave
            }
            if (password != confirmPassword) {
                errorMessage = context.getString(R.string.error_password_confirm_mismatch)
                return@onRegisterTopBarSave
            }
            scope.launch {
                if (entryId == null) {
                    val now = System.currentTimeMillis()
                    val entry = PasswordEntry(
                        uuid = UUID.randomUUID().toString(),
                        title = title.trim(),
                        accountName = accountName.trim(),
                        username = username.trim(),
                        password = password,
                        url = url.trim(),
                        description = description.trim(),
                        tags = tags.trim(),
                        imagePath = null,
                        attachmentsJson = encodeAttachmentPathsJson(attachmentPaths),
                        avatarImagePath = avatarImagePath,
                        createdAt = now,
                        updatedAt = now
                    )
                    repository.insert(entry)
                } else {
                    val existing = repository.getEntry(entryId) ?: return@launch
                    val oldAttachmentPaths = existing.allAttachmentPaths()
                    oldAttachmentPaths.filter { it !in attachmentPaths }.forEach {
                        ImageStorage.deleteIfExists(it)
                    }
                    if (existing.avatarImagePath != avatarImagePath) {
                        ImageStorage.deleteIfExists(existing.avatarImagePath)
                    }
                    val now = System.currentTimeMillis()
                    repository.updateEntry(
                        existing.copy(
                            title = title.trim(),
                            accountName = accountName.trim(),
                            username = username.trim(),
                            password = password,
                            url = url.trim(),
                            description = description.trim(),
                            tags = tags.trim(),
                            imagePath = null,
                            attachmentsJson = encodeAttachmentPathsJson(attachmentPaths),
                            avatarImagePath = avatarImagePath,
                            updatedAt = now
                        )
                    )
                }
                onSaved()
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            onRegisterTopBarSave(null)
        }
    }

    val multiImagePicker = rememberLauncherForActivityResult(
        contract = PickMultipleVisualMedia()
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val newPaths = uris.mapNotNull { ImageStorage.copyFromUri(context, it) }
        if (newPaths.isNotEmpty()) {
            attachmentPaths = attachmentPaths + newPaths
        }
    }

    val avatarPicker = rememberLauncherForActivityResult(
        contract = PickVisualMedia()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val path = ImageStorage.copyFromUri(context, uri)
        if (path != null) {
            ImageStorage.deleteIfExists(avatarImagePath)
            avatarImagePath = path
        }
    }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: android.net.Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        val path = ImageStorage.copyFromUri(context, uri)
        if (path != null) {
            ImageStorage.deleteIfExists(avatarImagePath)
            avatarImagePath = path
        }
    }

    val multiFilePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris: List<android.net.Uri> ->
        val newPaths = uris.mapNotNull { ImageStorage.copyFromUri(context, it) }
        if (newPaths.isNotEmpty()) {
            attachmentPaths = attachmentPaths + newPaths
        }
    }

    val scroll = rememberScrollState()
    val urlInvalidMessage = stringResource(R.string.error_url_invalid)

    var showAvatarPickerDialog by remember { mutableStateOf(false) }
    var showAttachmentPickerDialog by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .verticalScroll(scroll)
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TitleAvatarSection(
            title = title,
            avatarImagePath = avatarImagePath,
            onEditAvatarClick = {
                showAvatarPickerDialog = true
            }
        )

        if (showAvatarPickerDialog) {
            AlertDialog(
                onDismissRequest = { showAvatarPickerDialog = false },
                title = { Text("选择图片来源") },
                text = { Text("请选择从相册还是文件管理器选择图片") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showAvatarPickerDialog = false
                            avatarPicker.launch(
                                PickVisualMediaRequest(PickVisualMedia.ImageOnly)
                            )
                        }
                    ) {
                        Text("相册")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            showAvatarPickerDialog = false
                            filePicker.launch("image/*")
                        }
                    ) {
                        Text("文件管理器")
                    }
                }
            )
        }
        OutlinedTextField(
            value = title,
            onValueChange = {
                title = it
                errorMessage = null
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.field_title)) },
            singleLine = true
        )
        OutlinedTextField(
            value = url,
            onValueChange = {
                url = it
                errorMessage = null
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.field_url)) },
            singleLine = true,
            isError = errorMessage == urlInvalidMessage,
            supportingText = if (errorMessage == urlInvalidMessage) {
                { Text(urlInvalidMessage) }
            } else {
                null
            }
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Start
            ) {
                TextButton(
                    onClick = {
                        if (!appPreferences.getNetworkForFaviconEnabled()) {
                            showNetworkFaviconDialog = true
                            return@TextButton
                        }
                        val u = url.trim()
                        if (u.isEmpty() || !UrlValidation.isAcceptableUrlOrEmpty(u)) {
                            Toast.makeText(
                                context,
                                context.getString(R.string.error_favicon_url_required),
                                Toast.LENGTH_SHORT
                            ).show()
                            return@TextButton
                        }
                        scope.launch {
                            faviconLoading = true
                            val result = WebsiteFaviconFetcher.fetchAndSave(context, u)
                            faviconLoading = false
                            result.fold(
                                onSuccess = { path ->
                                    ImageStorage.deleteIfExists(avatarImagePath)
                                    avatarImagePath = path
                                },
                                onFailure = {
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.error_favicon_fetch_failed),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            )
                        }
                    },
                    enabled = !faviconLoading
                ) {
                    Text(stringResource(R.string.action_use_site_icon))
                }
                if (faviconLoading) {
                    Spacer(modifier = Modifier.width(4.dp))
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                }
            }
            TextButton(
                onClick = {
                    ImageStorage.deleteIfExists(avatarImagePath)
                    avatarImagePath = null
                },
                enabled = !faviconLoading
            ) {
                Text(stringResource(R.string.action_use_default_icon))
            }
        }
        OutlinedTextField(
            value = accountName,
            onValueChange = { accountName = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.field_account_name)) },
            singleLine = true
        )
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.field_username)) },
            singleLine = true
        )
        OutlinedTextField(
            value = password,
            onValueChange = {
                password = it
                errorMessage = null
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.field_password)) },
            singleLine = true,
            visualTransformation = if (passwordVisible) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            trailingIcon = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End
                ) {
                    IconButton(
                        onClick = { passwordVisible = !passwordVisible }
                    ) {
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
                    TextButton(
                        onClick = {
                            val generated = PasswordGenerator.generate(
                                appPreferences.getPasswordGeneratorRules()
                            )
                            password = generated
                            confirmPassword = generated
                            errorMessage = null
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.generator_action_generate),
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1
                        )
                    }
                }
            }
        )
        OutlinedTextField(
            value = confirmPassword,
            onValueChange = {
                confirmPassword = it
                errorMessage = null
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.field_confirm_password)) },
            singleLine = true,
            visualTransformation = if (confirmPasswordVisible) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            trailingIcon = {
                IconButton(
                    onClick = { confirmPasswordVisible = !confirmPasswordVisible }
                ) {
                    Icon(
                        imageVector = if (confirmPasswordVisible) {
                            Icons.Filled.VisibilityOff
                        } else {
                            Icons.Filled.Visibility
                        },
                        contentDescription = stringResource(
                            if (confirmPasswordVisible) {
                                R.string.detail_hide_password_desc
                            } else {
                                R.string.detail_show_password_desc
                            }
                        )
                    )
                }
            }
        )
        OutlinedTextField(
            value = description,
            onValueChange = { description = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.field_description)) },
            minLines = 3
        )

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.add_advanced_section),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedTextField(
            value = tags,
            onValueChange = { tags = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.dialog_tags_label)) },
            supportingText = { Text(stringResource(R.string.field_tags_hint)) },
            minLines = 2,
            maxLines = 4
        )
        FilledTonalButton(
            onClick = {
                showAttachmentPickerDialog = true
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Image,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.action_attach_image))
            }
        }

        if (showAttachmentPickerDialog) {
            AlertDialog(
                onDismissRequest = { showAttachmentPickerDialog = false },
                title = { Text("选择图片来源") },
                text = { Text("请选择从相册还是文件管理器选择图片") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showAttachmentPickerDialog = false
                            multiImagePicker.launch(
                                PickVisualMediaRequest(PickVisualMedia.ImageOnly)
                            )
                        }
                    ) {
                        Text("相册")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            showAttachmentPickerDialog = false
                            multiFilePicker.launch("image/*")
                        }
                    ) {
                        Text("文件管理器")
                    }
                }
            )
        }

        if (attachmentPaths.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                itemsIndexed(
                    items = attachmentPaths,
                    key = { index, path -> "$index-$path" }
                ) { index, path ->
                    Box {
                        AsyncImage(
                            model = File(path),
                            contentDescription = stringResource(R.string.add_image_preview_desc),
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(120.dp)
                        )
                        IconButton(
                            onClick = {
                                ImageStorage.deleteIfExists(path)
                                attachmentPaths = attachmentPaths.filterIndexed { i, _ -> i != index }
                            },
                            modifier = Modifier.align(Alignment.TopEnd)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = stringResource(R.string.action_remove_one_attachment)
                            )
                        }
                    }
                }
            }
        }

        if (errorMessage != null && errorMessage != urlInvalidMessage) {
            Text(
                text = errorMessage!!,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }

        Spacer(modifier = Modifier.height(24.dp))
    }

    if (showNetworkFaviconDialog) {
        AlertDialog(
            onDismissRequest = { showNetworkFaviconDialog = false },
            title = { Text(stringResource(R.string.dialog_network_favicon_title)) },
            text = { Text(stringResource(R.string.dialog_network_favicon_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showNetworkFaviconDialog = false
                        onOpenSettings()
                    }
                ) {
                    Text(stringResource(R.string.dialog_go_to_settings))
                }
            },
            dismissButton = {
                TextButton(onClick = { showNetworkFaviconDialog = false }) {
                    Text(stringResource(R.string.dialog_cancel))
                }
            }
        )
    }
}
