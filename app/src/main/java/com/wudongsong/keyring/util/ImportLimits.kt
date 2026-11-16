package com.wudongsong.keyring.util

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/** Counts actual bytes rather than trusting archive size headers. */
internal class ImportLimits(
    private val maxTotalBytes: Long = 256L * 1024 * 1024,
    private val maxEntryBytes: Long = 32L * 1024 * 1024,
    private val maxEntries: Int = 4096
) {
    private var totalBytes = 0L
    private var entries = 0

    fun nextEntry() {
        require(++entries <= maxEntries) { "Too many archive entries" }
    }

    fun copy(input: InputStream, output: OutputStream, limit: Long = maxEntryBytes) {
        val buffer = ByteArray(8192)
        var entryBytes = 0L
        while (true) {
            val count = input.read(buffer)
            if (count == -1) break
            entryBytes += count
            totalBytes += count
            require(entryBytes <= limit && totalBytes <= maxTotalBytes) {
                "Import exceeds size limit"
            }
            output.write(buffer, 0, count)
        }
    }

    fun readJson(input: InputStream): String {
        val out = ByteArrayOutputStream()
        copy(input, out, 8L * 1024 * 1024)
        return out.toString(Charsets.UTF_8.name())
    }

    fun discard(input: InputStream) = copy(input, object : OutputStream() {
        override fun write(value: Int) = Unit
        override fun write(bytes: ByteArray, offset: Int, length: Int) = Unit
    })
}
