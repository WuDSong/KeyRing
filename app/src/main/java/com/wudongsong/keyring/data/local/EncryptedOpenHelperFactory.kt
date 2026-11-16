package com.wudongsong.keyring.data.local

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/** A key/IO/corruption error must never trigger SQLite's default delete-and-recreate handler. */
internal class EncryptedOpenHelperFactory(private val passphrase: ByteArray) :
    SupportSQLiteOpenHelper.Factory {
    override fun create(
        configuration: SupportSQLiteOpenHelper.Configuration
    ): SupportSQLiteOpenHelper {
        val original = configuration.callback
        val callback =
            object : SupportSQLiteOpenHelper.Callback(original.version) {
                override fun onConfigure(db: SupportSQLiteDatabase) = original.onConfigure(db)

                override fun onCreate(db: SupportSQLiteDatabase) = original.onCreate(db)

                override fun onUpgrade(
                    db: SupportSQLiteDatabase,
                    oldVersion: Int,
                    newVersion: Int,
                ) = original.onUpgrade(db, oldVersion, newVersion)

                override fun onDowngrade(
                    db: SupportSQLiteDatabase,
                    oldVersion: Int,
                    newVersion: Int,
                ) = original.onDowngrade(db, oldVersion, newVersion)

                override fun onOpen(db: SupportSQLiteDatabase) = original.onOpen(db)

                override fun onCorruption(db: SupportSQLiteDatabase) {
                    error("Encrypted database cannot be read; data has been preserved")
                }
            }
        return SupportOpenHelperFactory(passphrase)
            .create(
                SupportSQLiteOpenHelper.Configuration.builder(configuration.context)
                    .name(configuration.name)
                    .callback(callback)
                    .build()
            )
    }
}
