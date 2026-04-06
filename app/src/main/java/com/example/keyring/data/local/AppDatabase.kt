package com.example.keyring.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [PasswordEntry::class],
    version = 5,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun passwordEntryDao(): PasswordEntryDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE password_entries ADD COLUMN avatarImagePath TEXT"
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE password_entries ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "UPDATE password_entries SET updatedAt = createdAt"
                )
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE password_entries ADD COLUMN attachmentsJson TEXT NOT NULL DEFAULT '[]'"
                )
                val cursor = db.query("SELECT id, imagePath FROM password_entries")
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    val path = cursor.getString(1)
                    val json = if (path.isNullOrBlank()) {
                        "[]"
                    } else {
                        "[\"${escapeForJsonString(path)}\"]"
                    }
                    val stmt = db.compileStatement(
                        "UPDATE password_entries SET attachmentsJson = ? WHERE id = ?"
                    )
                    stmt.bindString(1, json)
                    stmt.bindLong(2, id)
                    stmt.executeUpdateDelete()
                    stmt.close()
                }
                cursor.close()
                db.execSQL("UPDATE password_entries SET imagePath = NULL")
            }

            private fun escapeForJsonString(s: String): String = buildString {
                for (ch in s) {
                    when (ch) {
                        '\\', '"' -> append('\\').append(ch)
                        '\n', '\r' -> append(' ')
                        else -> append(ch)
                    }
                }
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE password_entries ADD COLUMN uuid TEXT NOT NULL DEFAULT ''"
                )
                val cursor = db.query("SELECT id FROM password_entries")
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    val stmt = db.compileStatement(
                        "UPDATE password_entries SET uuid = ? WHERE id = ?"
                    )
                    stmt.bindString(1, java.util.UUID.randomUUID().toString())
                    stmt.bindLong(2, id)
                    stmt.executeUpdateDelete()
                    stmt.close()
                }
                cursor.close()
            }
        }

        @Volatile
        private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "mypasswords.db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                    .build().also { instance = it }
            }
        }
    }
}
