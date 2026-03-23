package com.example.keyring.data

import android.content.Context
import com.example.keyring.data.local.PasswordEntry
import com.example.keyring.data.local.PasswordEntryDao
import com.example.keyring.data.local.allAttachmentPaths
import com.example.keyring.util.ImageStorage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import java.text.Collator
import java.util.Locale

class PasswordEntryRepository(
    private val dao: PasswordEntryDao,
    private val appPreferences: AppPreferences
) {
    private val titleCollator: Collator = Collator.getInstance(Locale.getDefault())

    fun observeEntries(): Flow<List<PasswordEntry>> =
        combine(dao.observeAll(), appPreferences.observeListSortOrder()) { list, order ->
            sortEntries(list, order)
        }

    fun searchEntries(query: String): Flow<List<PasswordEntry>> {
        val q = query.trim()
        if (q.isEmpty()) return flowOf(emptyList())
        return combine(dao.searchEntriesLike(q), appPreferences.observeListSortOrder()) { list, order ->
            sortEntries(list, order)
        }
    }

    suspend fun getEntry(id: Long): PasswordEntry? = dao.getById(id)

    suspend fun insert(entry: PasswordEntry): Long = dao.insert(entry)

    suspend fun getAllEntries(): List<PasswordEntry> =
        sortEntries(dao.getAllEntriesSync(), appPreferences.getListSortOrder())

    fun observeEntryCount(): Flow<Int> = dao.observeEntryCount()

    suspend fun insertEntries(entries: List<PasswordEntry>) {
        if (entries.isEmpty()) return
        dao.insertAll(entries)
    }

    suspend fun updateEntry(entry: PasswordEntry) {
        dao.update(entry)
    }

    suspend fun deleteEntry(context: Context, id: Long) {
        val entry = dao.getById(id) ?: return
        entry.allAttachmentPaths().forEach { ImageStorage.deleteIfExists(it) }
        ImageStorage.deleteIfExists(entry.imagePath)
        ImageStorage.deleteIfExists(entry.avatarImagePath)
        dao.deleteById(id)
    }

    private fun sortEntries(list: List<PasswordEntry>, order: PasswordListSortOrder): List<PasswordEntry> {
        return when (order) {
            PasswordListSortOrder.NAME_ASC ->
                list.sortedWith { a, b -> titleCollator.compare(a.title, b.title) }
            PasswordListSortOrder.NAME_DESC ->
                list.sortedWith { a, b -> titleCollator.compare(b.title, a.title) }
            PasswordListSortOrder.TIME_ASC -> list.sortedBy { it.updatedAt }
            PasswordListSortOrder.TIME_DESC -> list.sortedByDescending { it.updatedAt }
        }
    }
}
