package com.wudongsong.keyring

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.room.Room
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.test.platform.app.InstrumentationRegistry
import com.wudongsong.keyring.data.AppPreferences
import com.wudongsong.keyring.data.PasswordEntryRepository
import com.wudongsong.keyring.data.PasswordStore
import com.wudongsong.keyring.data.local.AppDatabase
import com.wudongsong.keyring.data.local.DatabaseEncryption
import com.wudongsong.keyring.data.local.EncryptedOpenHelperFactory
import com.wudongsong.keyring.data.local.PasswordEntry
import com.wudongsong.keyring.security.PasswordHash
import com.wudongsong.keyring.util.PasswordBackup
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SecurityMigrationTest {
    // Each test gets isolated databases, preferences and images, never the user's vault.
    private fun isolatedContext(): Context {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "security_test_${UUID.randomUUID()}_"
        return object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this

            override fun getDatabasePath(name: String): File = base.getDatabasePath(prefix + name)

            override fun getSharedPreferences(name: String, mode: Int) =
                base.getSharedPreferences(prefix + name, mode)

            override fun getFilesDir(): File =
                File(base.cacheDir, prefix + "files").apply { mkdirs() }

            override fun getCacheDir(): File =
                File(base.cacheDir, prefix + "cache").apply { mkdirs() }
        }
    }

    private fun entry() =
        PasswordEntry(
            uuid = UUID.randomUUID().toString(),
            title = "测试账户",
            accountName = "account",
            username = "username",
            password = "never-plaintext-secret-测试",
            url = "https://example.com",
            description = "description",
            detailedDescription = "long notes",
            tags = "test",
            imagePath = null,
        )

    private fun encryptedDb(context: Context, name: String): AppDatabase {
        val key = DatabaseEncryption.prepare(context, name)
        return Room.databaseBuilder(context, AppDatabase::class.java, name)
            .openHelperFactory(EncryptedOpenHelperFactory(key))
            .build()
    }

    @Test
    fun migratesPlaintextRoomAndRetainsFieldsAndIdentity() = runBlocking {
        val context = isolatedContext()
        val original = entry()
        val plain = Room.databaseBuilder(context, AppDatabase::class.java, "migration.db").build()
        val id = plain.passwordEntryDao().insert(original)
        plain.close()
        val path = context.getDatabasePath("migration.db")
        assertTrue(
            path.readBytes().toString(Charsets.ISO_8859_1).contains("never-plaintext-secret")
        )
        val encrypted = encryptedDb(context, "migration.db")
        try {
            assertEquals(original.copy(id = id), encrypted.passwordEntryDao().getById(id))
            encrypted.passwordEntryDao().update(original.copy(id = id, password = "updated-secret"))
        } finally {
            encrypted.close()
        }
        assertFalse(
            path.readBytes().toString(Charsets.ISO_8859_1).contains("never-plaintext-secret")
        )
        assertFalse(
            path
                .readBytes()
                .take(16)
                .toByteArray()
                .contentEquals("SQLite format 3\u0000".toByteArray())
        )
        assertFalse(File(path.path + ".plaintext-migration").exists())
        val reopened = encryptedDb(context, "migration.db")
        try {
            assertEquals("updated-secret", reopened.passwordEntryDao().getById(id)?.password)
        } finally {
            reopened.close()
        }
    }

    @Test
    fun resumesInterruptedMigrationWithoutResettingData() = runBlocking {
        val context = isolatedContext()
        val original = entry()
        val plain = Room.databaseBuilder(context, AppDatabase::class.java, "interrupted.db").build()
        val id = plain.passwordEntryDao().insert(original)
        plain.close()
        val path = context.getDatabasePath("interrupted.db")
        check(path.renameTo(File(path.path + ".plaintext-migration")))
        val recovered = encryptedDb(context, "interrupted.db")
        try {
            assertEquals(original.copy(id = id), recovered.passwordEntryDao().getById(id))
        } finally {
            recovered.close()
        }
    }

    @Test
    fun missingDatabaseKeyFailsWithoutReplacingVault() = runBlocking {
        val context = isolatedContext()
        val db = encryptedDb(context, "missing-key.db")
        db.passwordEntryDao().insert(entry())
        db.close()
        val file = context.getDatabasePath("missing-key.db")
        val before = file.readBytes()
        val prefs =
            EncryptedSharedPreferences.create(
                context,
                "database_key",
                MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        check(prefs.edit().remove("key").commit())
        assertThrows(IllegalStateException::class.java) {
            DatabaseEncryption.prepare(context, "missing-key.db")
        }
        assertArrayEquals(before, file.readBytes())
        assertNull(prefs.getString("key", null))
    }

    @Test
    fun legacyPlaintextZipStillImportsIntoEncryptedDatabase() = runBlocking {
        val context = isolatedContext()
        val db = encryptedDb(context, "legacy-backup.db")
        try {
            val repository = PasswordEntryRepository(db.passwordEntryDao(), AppPreferences(context))
            val legacy = File(context.filesDir, "legacy.zip")
            java.util.zip.ZipOutputStream(legacy.outputStream()).use { zip ->
                zip.putNextEntry(java.util.zip.ZipEntry("mypasswords_backup.json"))
                zip.write(
                    """{"formatVersion":1,"entries":[{"title":"legacy","password":"legacy secret"}]}"""
                        .toByteArray()
                )
                zip.closeEntry()
            }
            assertEquals(
                1,
                PasswordBackup.importFrom(context, Uri.fromFile(legacy), repository, CharArray(0)),
            )
            assertEquals("legacy secret", repository.getAllEntriesUnsorted().single().password)
        } finally {
            db.close()
        }
    }

    @Test
    fun upgradesLegacyVerifierAndChangingPasswordRemainsCompatible() = runBlocking {
        val context = isolatedContext()
        val prefs =
            EncryptedSharedPreferences.create(
                context,
                "auth_secure_prefs",
                MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        val legacy =
            Base64.getEncoder()
                .encodeToString(MessageDigest.getInstance("SHA-256").digest("1234".toByteArray()))
        check(prefs.edit().putString("password_hash", legacy).commit())
        val store = PasswordStore(context)
        assertFalse(store.verifyPassword("wrong"))
        assertEquals(legacy, prefs.getString("password_hash", null))
        assertTrue(store.verifyPassword("1234"))
        assertFalse(PasswordHash.isLegacy(prefs.getString("password_hash", null)!!))
        assertTrue(store.changePassword("1234", "new master password").isSuccess)
        assertFalse(store.verifyPassword("1234"))
        assertTrue(store.verifyPassword("new master password"))
    }

    @Test
    fun encryptedBackupRoundTripRejectsBadPasswordAndTruncationBeforeWritingRows() = runBlocking {
        val context = isolatedContext()
        val db = encryptedDb(context, "backup.db")
        try {
            val repository = PasswordEntryRepository(db.passwordEntryDao(), AppPreferences(context))
            val image =
                File(context.filesDir, "avatar.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val original = entry().copy(avatarImagePath = image.path)
            val backup = File(context.filesDir, "test.krbackup")
            val password = "portable backup password".toCharArray()
            PasswordBackup.exportToZip(context, Uri.fromFile(backup), listOf(original), password)
            assertFalse(
                backup.readBytes().toString(Charsets.ISO_8859_1).contains(original.password)
            )
            assertThrows(Exception::class.java) {
                runBlocking {
                    PasswordBackup.importFrom(
                        context,
                        Uri.fromFile(backup),
                        repository,
                        "wrong".toCharArray(),
                    )
                }
            }
            assertTrue(repository.getAllEntriesUnsorted().isEmpty())
            val truncated =
                File(context.filesDir, "truncated.krbackup").apply {
                    writeBytes(backup.readBytes().dropLast(1).toByteArray())
                }
            assertThrows(Exception::class.java) {
                runBlocking {
                    PasswordBackup.importFrom(
                        context,
                        Uri.fromFile(truncated),
                        repository,
                        password,
                    )
                }
            }
            assertTrue(repository.getAllEntriesUnsorted().isEmpty())
            assertEquals(
                1,
                PasswordBackup.importFrom(context, Uri.fromFile(backup), repository, password),
            )
            val restored = repository.getAllEntriesUnsorted().single()
            assertEquals(original.password, restored.password)
            assertEquals(original.uuid, restored.uuid)
            assertEquals(original.detailedDescription, restored.detailedDescription)
            assertArrayEquals(image.readBytes(), File(restored.avatarImagePath!!).readBytes())
        } finally {
            db.close()
        }
    }

    @Test
    fun previewIsReadOnlyAndRepeatedBackupImportSkipsDuplicates() = runBlocking {
        val context = isolatedContext()
        val db = encryptedDb(context, "preview.db")
        try {
            val repository = PasswordEntryRepository(db.passwordEntryDao(), AppPreferences(context))
            val image =
                File(context.filesDir, "original.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val backup = File(context.filesDir, "preview.krbackup")
            val password = "independent backup password".toCharArray()
            PasswordBackup.exportToZip(
                context,
                Uri.fromFile(backup),
                listOf(entry().copy(avatarImagePath = image.path)),
                password,
            )
            val preview = PasswordBackup.prepareImport(context, Uri.fromFile(backup), password)
            val previewImage = File(preview.entries.single().avatarImagePath!!)
            assertTrue(previewImage.exists())
            assertTrue(repository.getAllEntriesUnsorted().isEmpty())
            preview.close()
            assertFalse(previewImage.exists())
            assertTrue(image.exists())
            assertEquals(
                1,
                PasswordBackup.importFrom(context, Uri.fromFile(backup), repository, password),
            )
            assertEquals(
                0,
                PasswordBackup.importFrom(context, Uri.fromFile(backup), repository, password),
            )
            assertEquals(1, repository.getAllEntriesUnsorted().size)
            assertTrue(File(repository.getAllEntriesUnsorted().single().avatarImagePath!!).exists())
        } finally {
            db.close()
        }
    }

    @Test
    fun importFailureRollsBackWholeBatch() = runBlocking {
        val context = isolatedContext()
        val db = encryptedDb(context, "rollback.db")
        try {
            db.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER reject_test BEFORE INSERT ON password_entries WHEN NEW.title = 'FAIL' BEGIN SELECT RAISE(ABORT, 'test failure'); END"
            )
            val repository = PasswordEntryRepository(db.passwordEntryDao(), AppPreferences(context))
            val parsed =
                PasswordBackup.PreparedImport(listOf(entry(), entry().copy(title = "FAIL")))
            assertThrows(Exception::class.java) {
                runBlocking {
                    parsed.commit(repository, com.wudongsong.keyring.data.ImportPolicy.SKIP)
                }
            }
            parsed.close()
            assertTrue(repository.getAllEntriesUnsorted().isEmpty())
        } finally {
            db.close()
        }
    }

    @Test
    fun legacyTrustedDeviceKeysMoveToEncryptedPreferences() {
        val context = isolatedContext()
        val key = Base64.getEncoder().encodeToString(ByteArray(32) { 42 })
        val device = AppPreferences.TrustedDevice("remote-id", "remote name", key, 1)
        val json =
            kotlinx.serialization.json.Json.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(
                    AppPreferences.TrustedDevice.serializer()
                ),
                listOf(device),
            )
        val old = context.getSharedPreferences("app_ui_prefs", Context.MODE_PRIVATE)
        check(old.edit().putString("sync_trusted_devices_json", json).commit())
        val prefs = AppPreferences(context)
        assertEquals(listOf(device), prefs.getTrustedDevices())
        assertNull(old.getString("sync_trusted_devices_json", null))
        assertArrayEquals(ByteArray(32) { 42 }, prefs.trustedDeviceKey("remote-id"))
        prefs.removeTrustedDevice("remote-id")
        assertTrue(prefs.getTrustedDevices().isEmpty())
    }

    @Test
    fun legacyBiometricVerificationCreatesPersistentBindingAndRejectsChangedPassword() =
        runBlocking {
            val context = isolatedContext()
            val store = PasswordStore(context)
            store.setPassword("original master").getOrThrow()
            val proof =
                com.wudongsong.keyring.security.BiometricCredential(
                    "original master",
                    "authenticated-cipher-a",
                )
            assertTrue(store.verifyBiometricCredential(proof))
            val restarted = PasswordStore(context)
            assertTrue(restarted.verifyBiometricCredential(proof))
            restarted.changePassword("original master", "new master password").getOrThrow()
            assertFalse(restarted.verifyBiometricCredential(proof))
        }

    @Test
    fun enrollmentBindingCannotAttachToAChangedMasterPassword() = runBlocking {
        val context = isolatedContext()
        val store = PasswordStore(context)
        store.setPassword("original master").getOrThrow()
        val record = store.verifyForBiometricEnrollment("original master")!!
        assertTrue(store.bindBiometricCredential("cipher-a", record))
        assertTrue(
            store.verifyBiometricCredential(
                com.wudongsong.keyring.security.BiometricCredential("original master", "cipher-a")
            )
        )
        store.changePassword("original master", "new master password").getOrThrow()
        assertFalse(store.bindBiometricCredential("cipher-a", record))
        assertFalse(
            store.verifyBiometricCredential(
                com.wudongsong.keyring.security.BiometricCredential("original master", "cipher-a")
            )
        )
    }
}
