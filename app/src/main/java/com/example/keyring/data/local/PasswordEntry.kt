package com.example.keyring.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "password_entries")
data class PasswordEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Stable id for cross-device sync/merge. */
    @ColumnInfo(defaultValue = "")
    val uuid: String = "",
    val title: String,
    val accountName: String,
    val username: String,
    val password: String,
    val url: String,
    /** 简短描述 */
    val description: String,
    /** 详细描述（v6 新增） */
    val detailedDescription: String? = null,
    val tags: String,
    /** Legacy column; data lives in [attachmentsJson]. Kept for Room schema. */
    val imagePath: String?,
    @ColumnInfo(defaultValue = "[]")
    val attachmentsJson: String = "[]",
    val avatarImagePath: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    fun ensureUuid(): PasswordEntry =
        if (uuid.isNotBlank()) this else copy(uuid = UUID.randomUUID().toString())
}
