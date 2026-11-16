package com.wudongsong.keyring

import com.wudongsong.keyring.data.ImportPolicy
import com.wudongsong.keyring.data.local.PasswordEntry
import com.wudongsong.keyring.data.planImport
import com.wudongsong.keyring.sync.SyncRequestGuard
import com.wudongsong.keyring.util.DraftFiles
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class ReliabilityTest {
    private fun entry(id: Long = 0, uuid: String = "uuid", time: Long = 1) =
        PasswordEntry(
            id = id,
            uuid = uuid,
            title = "Example",
            accountName = "account",
            username = "user",
            password = "secret",
            url = "https://example.com",
            description = "notes",
            tags = "",
            imagePath = null,
            createdAt = 1,
            updatedAt = time,
        )

    @Test
    fun repeatedImportSkipsUuidAndLegacyFingerprintDuplicates() {
        val local = entry(id = 7)
        val plan =
            planImport(
                listOf(local),
                listOf(entry(), entry(uuid = "old-backup-uuid")),
                ImportPolicy.SKIP,
            )
        assertEquals(2, plan.skipped)
        assertTrue(plan.inserts.isEmpty())
        assertTrue(plan.updates.isEmpty())
    }

    @Test
    fun duplicatesWithinOneBackupAreCollapsedBeforeInsert() {
        val plan = planImport(emptyList(), listOf(entry(), entry(time = 2)), ImportPolicy.NEWER)
        assertEquals(1, plan.inserts.size)
        assertEquals(2L, plan.inserts.single().updatedAt)
        assertEquals(0L, plan.inserts.single().id)
    }

    @Test
    fun overwriteNewerPreservesDatabaseIdAndSkipsOlder() {
        val local = entry(id = 8, time = 5)
        val plan =
            planImport(listOf(local), listOf(entry(time = 4), entry(time = 6)), ImportPolicy.NEWER)
        assertEquals(1, plan.skipped)
        assertEquals(8L, plan.updates.single().id)
        assertEquals(local, plan.replaced.single())
    }

    @Test
    fun keepBothCreatesDistinctUuidsAndTitles() {
        val plan =
            planImport(listOf(entry(id = 3)), listOf(entry(), entry()), ImportPolicy.KEEP_BOTH)
        assertEquals(2, plan.copies)
        assertEquals(2, plan.inserts.map { it.uuid }.toSet().size)
        assertEquals(2, plan.inserts.map { it.title }.toSet().size)
        assertTrue(plan.updates.isEmpty())
    }

    @Test
    fun cancelDraftDeletesOnlyNewFiles() {
        val original = Files.createTempFile("keyring-original", ".png").toFile()
        val replacement = Files.createTempFile("keyring-draft", ".png").toFile()
        try {
            val files = DraftFiles()
            files.register(replacement.path)
            files.discard()
            assertTrue(original.exists())
            assertFalse(replacement.exists())
        } finally {
            original.delete()
            replacement.delete()
        }
    }

    @Test
    fun successfulCommitPreservesNewImageAcrossLaterDisposal() {
        val original = Files.createTempFile("keyring-original", ".png").toFile()
        val replacement = Files.createTempFile("keyring-draft", ".png").toFile()
        val unused = Files.createTempFile("keyring-unused", ".png").toFile()
        try {
            val files = DraftFiles()
            files.register(replacement.path)
            files.register(unused.path)
            files.committed(listOf(original.path), listOf(replacement.path))
            files.discard()
            assertFalse(original.exists())
            assertFalse(unused.exists())
            assertTrue(replacement.exists())
        } finally {
            original.delete()
            replacement.delete()
            unused.delete()
        }
    }

    @Test
    fun requestGuardRejectsReplayExpiredAndFutureRequests() {
        val guard = SyncRequestGuard()
        assertTrue(guard.accept("device", "nonce", 1_000_000, 1_000_000))
        assertFalse(guard.accept("device", "nonce", 1_000_000, 1_000_001))
        assertFalse(guard.accept("device", "other", 1, 1_000_000))
        assertFalse(guard.accept("device", "other", 2_000_000, 1_000_000))
        assertFalse(guard.accept("device", "other", 0, 1_000_000))
    }

    @Test
    fun requestGuardFailsClosedWhenFullAndFreesOnlyExpiredSlots() {
        val guard = SyncRequestGuard(capacity = 1)
        assertTrue(guard.accept("one", "nonce", 1_000_000, 1_000_000))
        assertFalse(guard.accept("two", "nonce", 1_000_001, 1_000_001))
        assertTrue(guard.accept("two", "nonce", 1_200_000, 1_200_000))
    }
}
