package com.wudongsong.keyring.ui.entries

import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import com.wudongsong.keyring.data.local.PasswordEntry
import com.wudongsong.keyring.data.local.allAttachmentPaths

/** Memory-only drafts survive rotation and app locking, never a saved-state bundle. */
class EntryEditorViewModel : ViewModel() {
    val title = mutableStateOf("")
    val accountName = mutableStateOf("")
    val username = mutableStateOf("")
    val password = mutableStateOf("")
    val confirmPassword = mutableStateOf("")
    val url = mutableStateOf("")
    val description = mutableStateOf("")
    val detailedDescription = mutableStateOf("")
    val tags = mutableStateOf("")
    val attachmentPaths = mutableStateOf<List<String>>(emptyList())
    val avatarImagePath = mutableStateOf<String?>(null)
    val saving = mutableStateOf(false)
    val loaded = mutableStateOf(false)
    var original: PasswordEntry? = null
    var baseline: List<Any?> = emptyList()
    private val files = com.wudongsong.keyring.util.DraftFiles()
    private var cleared = false

    fun snapshot(): List<Any?> =
        listOf(
            title.value,
            accountName.value,
            username.value,
            password.value,
            confirmPassword.value,
            url.value,
            description.value,
            detailedDescription.value,
            tags.value,
            attachmentPaths.value,
            avatarImagePath.value,
        )

    fun register(path: String): String = files.register(path)

    fun initialize(entry: PasswordEntry?) {
        if (loaded.value) return
        original = entry
        title.value = entry?.title.orEmpty()
        accountName.value = entry?.accountName.orEmpty()
        username.value = entry?.username.orEmpty()
        password.value = entry?.password.orEmpty()
        confirmPassword.value = password.value
        url.value = entry?.url.orEmpty()
        description.value = entry?.description.orEmpty()
        detailedDescription.value = entry?.detailedDescription.orEmpty()
        tags.value = entry?.tags.orEmpty()
        attachmentPaths.value = entry?.allAttachmentPaths().orEmpty()
        avatarImagePath.value = entry?.avatarImagePath
        baseline = snapshot()
        loaded.value = true
    }

    fun committed(entry: PasswordEntry) {
        val kept = entry.allAttachmentPaths().toSet() + listOfNotNull(entry.avatarImagePath)
        val old =
            original?.let { it.allAttachmentPaths() + listOfNotNull(it.avatarImagePath) }.orEmpty()
        files.committed(old, kept)
        original = entry
        baseline = snapshot()
        loaded.value = false
    }

    fun discard() {
        if (saving.value) return
        cleanup()
        loaded.value = false
        original = null
        initialize(null)
        loaded.value = false
    }

    fun finishSaving() {
        saving.value = false
        if (cleared) cleanup()
    }

    private fun cleanup() = files.discard()

    override fun onCleared() {
        cleared = true
        if (!saving.value) cleanup()
    }
}
