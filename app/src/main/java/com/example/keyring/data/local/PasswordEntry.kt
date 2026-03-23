package com.example.keyring.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "password_entries")
data class PasswordEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val accountName: String,
    val username: String,
    val password: String,
    val url: String,
    val description: String,
    val tags: String,
    /** Legacy column; data lives in [attachmentsJson]. Kept for Room schema. */
    val imagePath: String?,
    @ColumnInfo(defaultValue = "[]")
    val attachmentsJson: String = "[]",
    val avatarImagePath: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
