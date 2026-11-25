package com.wudongsong.keyring.security

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Versioned, streaming encrypted ZIP container. A final authenticated empty record is required. */
object BackupCipher {
    val MAGIC: ByteArray
        get() = "KRBACK01".toByteArray(Charsets.US_ASCII)

    const val MIN_PASSWORD_LENGTH = 8

    /**
     * Backup archives leave the device and can be attacked offline, so their KDF stays at the
     * stronger setting even though the app-unlock verifier uses a lower one. The header records
     * the count, so files written with any count within [MIN_ITERATIONS]..[MAX_ITERATIONS] still
     * open.
     */
    private const val ITERATIONS = 600_000
    private const val MIN_ITERATIONS = 100_000
    private const val MAX_ITERATIONS = 2_000_000

    private const val CHUNK = 64 * 1024
    private const val HEADER_SIZE = 8 + 4 + 16 + 8

    fun encrypt(output: OutputStream, password: CharArray, write: (OutputStream) -> Unit) {
        require(password.size >= MIN_PASSWORD_LENGTH) {
            "Backup password must contain at least 8 characters"
        }
        val random = SecureRandom()
        val salt = ByteArray(16).also(random::nextBytes)
        val prefix = ByteArray(8).also(random::nextBytes)
        val header =
            ByteBuffer.allocate(HEADER_SIZE)
                .put(MAGIC)
                .putInt(ITERATIONS)
                .put(salt)
                .put(prefix)
                .array()
        val key = PasswordHash.derive(password, salt, ITERATIONS)
        val target = DataOutputStream(output)
        target.write(header)
        var index = 0
        val buffer = ByteArray(CHUNK)
        var used = 0
        var total = 0L
        fun record(length: Int) {
            check(index < Int.MAX_VALUE)
            val encrypted =
                crypt(
                    Cipher.ENCRYPT_MODE,
                    key,
                    header,
                    prefix,
                    index++,
                    length,
                    buffer.copyOf(length),
                )
            target.writeInt(length)
            target.write(encrypted)
        }
        val stream =
            object : OutputStream() {
                override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)

                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
                    total += length
                    require(total <= 256L * 1024 * 1024) { "Backup exceeds size limit" }
                    var position = offset
                    var remaining = length
                    while (remaining > 0) {
                        val count = minOf(CHUNK - used, remaining)
                        bytes.copyInto(buffer, used, position, position + count)
                        used += count
                        position += count
                        remaining -= count
                        if (used == CHUNK) {
                            record(used)
                            used = 0
                        }
                    }
                }

                // ZIP may close its stream; the owner writes the final record afterwards.
                override fun close() = flush()

                override fun flush() = target.flush()
            }
        try {
            write(stream)
            if (used > 0) record(used)
            record(0)
            target.flush()
        } finally {
            key.fill(0)
            buffer.fill(0)
        }
    }

    fun decrypt(input: InputStream, password: CharArray): InputStream {
        val source = DataInputStream(input)
        val header = ByteArray(HEADER_SIZE).also(source::readFully)
        val fields = ByteBuffer.wrap(header)
        val magic = ByteArray(8).also(fields::get)
        require(magic.contentEquals(MAGIC)) { "Unsupported encrypted backup format" }
        val iterations = fields.int
        require(iterations in MIN_ITERATIONS..MAX_ITERATIONS) {
            "Invalid backup KDF parameters"
        }
        val salt = ByteArray(16).also(fields::get)
        val prefix = ByteArray(8).also(fields::get)
        val key = PasswordHash.derive(password, salt, iterations)
        return object : InputStream() {
            var index = 0
            var buffer = ByteArray(0)
            var position = 0
            var ended = false
            var total = 0L

            override fun read(): Int {
                val one = ByteArray(1)
                return if (read(one, 0, 1) == -1) -1 else one[0].toInt() and 255
            }

            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
                if (length == 0) return 0
                if (ended) return -1
                if (position == buffer.size) {
                    val count = source.readInt() // EOF before the final record is an error.
                    require(count in 0..CHUNK && index < Int.MAX_VALUE) {
                        "Invalid encrypted backup record"
                    }
                    total += count
                    require(total <= 256L * 1024 * 1024) { "Backup exceeds size limit" }
                    val encrypted = ByteArray(count + 16).also(source::readFully)
                    buffer.fill(0)
                    buffer =
                        crypt(Cipher.DECRYPT_MODE, key, header, prefix, index++, count, encrypted)
                    position = 0
                    if (count == 0) {
                        require(source.read() == -1) { "Trailing backup data" }
                        ended = true
                        key.fill(0)
                        return -1
                    }
                }
                val count = minOf(length, buffer.size - position)
                buffer.copyInto(bytes, offset, position, position + count)
                position += count
                return count
            }

            override fun close() {
                key.fill(0)
                buffer.fill(0)
                source.close()
            }
        }
    }

    private fun crypt(
        mode: Int,
        key: ByteArray,
        header: ByteArray,
        prefix: ByteArray,
        index: Int,
        size: Int,
        bytes: ByteArray,
    ): ByteArray {
        val nonce = ByteBuffer.allocate(12).put(prefix).putInt(index).array()
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            updateAAD(header)
            updateAAD(ByteBuffer.allocate(8).putInt(index).putInt(size).array())
            doFinal(bytes)
        }
    }
}
