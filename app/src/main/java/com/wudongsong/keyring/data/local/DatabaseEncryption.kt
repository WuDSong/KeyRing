package com.wudongsong.keyring.data.local

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File
import java.security.SecureRandom
import net.zetetic.database.DatabaseErrorHandler
import net.zetetic.database.sqlcipher.SQLiteDatabase

/** Device-bound database key; never recreate a missing key for an encrypted database. */
internal object DatabaseEncryption {
    private val sqliteHeader = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
    private val preserveOnCorruption = DatabaseErrorHandler { _, cause -> throw cause }

    fun prepare(context: Context, name: String): ByteArray {
        System.loadLibrary("sqlcipher")
        val file = context.getDatabasePath(name)
        val recovery = File(file.path + ".plaintext-migration")
        val staged = File(file.path + ".encrypted-migration")
        val prefs =
            EncryptedSharedPreferences.create(
                context,
                "database_key",
                MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        val saved = prefs.getString("key", null)
        check(saved != null || !file.exists() || file.length() == 0L || isPlaintext(file)) {
            "Encrypted database key is missing; restore an encrypted backup"
        }
        val key =
            saved?.let { Base64.decode(it, Base64.NO_WRAP) }
                ?: ByteArray(32).also {
                    SecureRandom().nextBytes(it)
                    check(
                        prefs
                            .edit()
                            .putString("key", Base64.encodeToString(it, Base64.NO_WRAP))
                            .commit()
                    )
                }
        require(key.size == 32)
        // ASCII passphrase avoids embedded NUL ambiguity in SQLCipher attachment keys.
        val passphrase = Base64.encodeToString(key, Base64.NO_WRAP).toByteArray(Charsets.US_ASCII)
        key.fill(0)
        if (!file.exists() && recovery.exists()) {
            check(recovery.renameTo(file)) { "Cannot recover interrupted database migration" }
        }
        if (file.exists() && isPlaintext(file)) {
            check(!recovery.exists()) { "Conflicting database migration files" }
            android.database.sqlite.SQLiteDatabase.deleteDatabase(staged)
            SQLiteDatabase.openDatabase(
                    file.path,
                    "",
                    null,
                    SQLiteDatabase.OPEN_READWRITE,
                    preserveOnCorruption,
                    null,
                )
                .use { db ->
                    db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use {
                        check(it.moveToFirst() && it.getInt(0) == 0) {
                            "Cannot checkpoint legacy database"
                        }
                    }
                    val version = db.version
                    // Bind the key rather than exposing it in SQL text or exception logs.
                    db.execSQL(
                        "ATTACH DATABASE ? AS encrypted KEY ?",
                        arrayOf(staged.path, passphrase.toString(Charsets.US_ASCII)),
                    )
                    try {
                        db.rawQuery("SELECT sqlcipher_export('encrypted')", null).use {
                            it.moveToFirst()
                        }
                        db.execSQL("PRAGMA encrypted.user_version = $version")
                    } finally {
                        db.execSQL("DETACH DATABASE encrypted")
                    }
                }
            verify(staged, passphrase)
            check(file.renameTo(recovery)) { "Cannot stage original database" }
            // WAL has been checkpointed and closed before replacing the main database.
            listOf("-wal", "-shm", "-journal").forEach {
                val sidecar = File(file.path + it)
                check(!sidecar.exists() || sidecar.delete()) {
                    "Cannot remove legacy database journal"
                }
            }
            if (!staged.renameTo(file)) {
                check(recovery.renameTo(file)) { "Cannot roll back database migration" }
                error("Cannot install encrypted database")
            }
        }
        if (recovery.exists()) {
            verify(file, passphrase)
            check(android.database.sqlite.SQLiteDatabase.deleteDatabase(recovery)) {
                "Cannot remove legacy plaintext database"
            }
        }
        return passphrase
    }

    private fun isPlaintext(file: File): Boolean =
        file.inputStream().use {
            val header = ByteArray(sqliteHeader.size)
            it.read(header) == header.size && header.contentEquals(sqliteHeader)
        }

    private fun verify(file: File, passphrase: ByteArray) {
        SQLiteDatabase.openDatabase(
                file.path,
                passphrase,
                null,
                SQLiteDatabase.OPEN_READONLY,
                preserveOnCorruption,
                null,
            )
            .use { db ->
                db.rawQuery("PRAGMA integrity_check", null).use {
                    check(it.moveToFirst() && it.getString(0) == "ok") {
                        "Encrypted database verification failed"
                    }
                }
                db.rawQuery("SELECT count(*) FROM sqlite_master", null).use {
                    check(it.moveToFirst())
                }
            }
    }
}
