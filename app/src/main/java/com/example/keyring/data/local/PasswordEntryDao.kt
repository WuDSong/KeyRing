package com.example.keyring.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface PasswordEntryDao {
    @Query("SELECT * FROM password_entries")
    fun observeAll(): Flow<List<PasswordEntry>>

    @Query(
        """
        SELECT * FROM password_entries 
        WHERE title LIKE '%' || :q || '%' 
           OR accountName LIKE '%' || :q || '%'
           OR username LIKE '%' || :q || '%'
           OR description LIKE '%' || :q || '%'
        """
    )
    fun searchEntriesLike(q: String): Flow<List<PasswordEntry>>

    @Query("SELECT * FROM password_entries WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): PasswordEntry?

    @Query("SELECT * FROM password_entries ORDER BY id ASC")
    suspend fun getAllEntriesSync(): List<PasswordEntry>

    @Insert
    suspend fun insert(entry: PasswordEntry): Long

    @Insert
    suspend fun insertAll(entries: List<PasswordEntry>)

    @Update
    suspend fun update(entry: PasswordEntry)

    @Query("DELETE FROM password_entries WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT COUNT(*) FROM password_entries")
    fun observeEntryCount(): Flow<Int>
}
