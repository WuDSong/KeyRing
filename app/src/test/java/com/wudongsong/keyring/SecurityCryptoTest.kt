package com.wudongsong.keyring

import com.wudongsong.keyring.security.BackupCipher
import com.wudongsong.keyring.security.PasswordHash
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry
import java.security.MessageDigest
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class SecurityCryptoTest {
    private val password = "separate backup password 中文".toCharArray()

    private fun encrypt(bytes: ByteArray): ByteArray =
        ByteArrayOutputStream()
            .also { output -> BackupCipher.encrypt(output, password) { it.write(bytes) } }
            .toByteArray()

    private fun decrypt(bytes: ByteArray, secret: CharArray = password): ByteArray =
        BackupCipher.decrypt(ByteArrayInputStream(bytes), secret).use { it.readBytes() }

    @Test
    fun zipCloseAndEarlyEndOfZipStillRequireAuthenticatedFinalRecord() {
        val output = ByteArrayOutputStream()
        BackupCipher.encrypt(output, password) { encrypted ->
            ZipOutputStream(encrypted).use { zip ->
                zip.putNextEntry(ZipEntry("entries.json"))
                zip.write("private ZIP contents".toByteArray())
                zip.closeEntry()
            }
        }
        fun parse(bytes: ByteArray): String =
            BackupCipher.decrypt(ByteArrayInputStream(bytes), password).use { plain ->
                val nonClosing = object : FilterInputStream(plain) { override fun close() = Unit }
                val value = ZipInputStream(nonClosing).use { zip ->
                    assertEquals("entries.json", zip.nextEntry.name)
                    val text = zip.readBytes().toString(Charsets.UTF_8)
                    assertNull(zip.nextEntry)
                    text
                }
                plain.readBytes() // ZIP end alone does not prove the encrypted container is complete.
                value
            }
        val bytes = output.toByteArray()
        assertEquals("private ZIP contents", parse(bytes))
        assertThrows(Exception::class.java) { parse(bytes.copyOf(bytes.size - 20)) }
    }

    @Test
    fun saltedHashesDifferAndSupportUnicode() {
        val first = PasswordHash.create("主密码 long password")
        val second = PasswordHash.create("主密码 long password")
        assertNotEquals(first, second)
        assertTrue(PasswordHash.verify("主密码 long password", first))
        assertFalse(PasswordHash.verify("incorrect", first))
        assertFalse(PasswordHash.isLegacy(first))
    }

    @Test
    fun acceptsLegacyVerifierButRejectsMalformedAndExpensiveRecords() {
        val legacy =
            Base64.getEncoder()
                .encodeToString(
                    MessageDigest.getInstance("SHA-256").digest("old password".toByteArray())
                )
        assertTrue(PasswordHash.isLegacy(legacy))
        assertTrue(PasswordHash.verify("old password", legacy))
        assertFalse(PasswordHash.verify("wrong", legacy))
        for (record in
            listOf(
                "garbage",
                "pbkdf2-sha256\$999999999\$AA==\$AA==",
                "pbkdf2-sha256\$1\$AA==\$AA==",
            )) {
            assertFalse(PasswordHash.verify("test", record))
        }
    }

    @Test
    fun encryptedBackupRoundTripsAcrossChunkBoundariesAndHidesPlaintext() {
        val original = ByteArray(150_013) { (it % 251).toByte() }
        val first = encrypt(original)
        val second = encrypt(original)
        assertFalse(first.contentEquals(second))
        assertArrayEquals(original, decrypt(first))
        assertArrayEquals(ByteArray(0), decrypt(encrypt(ByteArray(0))))
    }

    @Test
    fun wrongPasswordAndTamperingAreRejected() {
        val bytes = encrypt("private password field".toByteArray())
        assertFalse(bytes.toString(Charsets.ISO_8859_1).contains("private password field"))
        assertThrows(Exception::class.java) { decrypt(bytes, "incorrect password".toCharArray()) }
        for (index in listOf(12, 32, 48, bytes.lastIndex)) {
            val changed = bytes.copyOf()
            changed[index] = (changed[index].toInt() xor 1).toByte()
            assertThrows(Exception::class.java) { decrypt(changed) }
        }
    }

    @Test
    fun truncatedAndAppendedBackupsAreRejected() {
        val bytes = encrypt(ByteArray(70_000) { 42 })
        for (size in listOf(0, 8, 35, 40, bytes.size - 1, bytes.size - 20)) {
            assertThrows(Exception::class.java) { decrypt(bytes.copyOf(size)) }
        }
        assertThrows(Exception::class.java) { decrypt(bytes + byteArrayOf(1)) }
    }

    @Test
    fun swappingRecordsFailsAuthentication() {
        val bytes = encrypt(ByteArray(140_000) { (it % 127).toByte() })
        val recordSize = 4 + 65_536 + 16
        val header = 36
        val swapped = bytes.copyOf()
        bytes.copyInto(swapped, header, header + recordSize, header + 2 * recordSize)
        bytes.copyInto(swapped, header + recordSize, header, header + recordSize)
        assertThrows(Exception::class.java) { decrypt(swapped) }
    }
}
