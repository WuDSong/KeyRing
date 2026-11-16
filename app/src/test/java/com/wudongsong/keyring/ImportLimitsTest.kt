package com.wudongsong.keyring

import com.wudongsong.keyring.util.ImportLimits
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ImportLimitsTest {
    @Test fun acceptsExactLimitAndPreservesBytes() {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val output = ByteArrayOutputStream()
        ImportLimits(maxTotalBytes = 4, maxEntryBytes = 4).copy(ByteArrayInputStream(bytes), output)
        assertArrayEquals(bytes, output.toByteArray())
    }

    @Test fun rejectsOversizedEntryWithoutSizeMetadata() {
        assertThrows(IllegalArgumentException::class.java) {
            ImportLimits(maxEntryBytes = 3).copy(ByteArrayInputStream(ByteArray(4)), ByteArrayOutputStream())
        }
    }

    @Test fun limitsCombinedSizeAcrossEntries() {
        val limits = ImportLimits(maxTotalBytes = 5, maxEntryBytes = 4)
        limits.copy(ByteArrayInputStream(ByteArray(3)), ByteArrayOutputStream())
        assertThrows(IllegalArgumentException::class.java) {
            limits.copy(ByteArrayInputStream(ByteArray(3)), ByteArrayOutputStream())
        }
    }

    @Test fun limitsEntryCountIncludingEmptyEntries() {
        val limits = ImportLimits(maxEntries = 1)
        limits.nextEntry()
        assertThrows(IllegalArgumentException::class.java) { limits.nextEntry() }
    }
}
