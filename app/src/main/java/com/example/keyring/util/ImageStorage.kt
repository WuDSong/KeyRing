package com.example.keyring.util

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

object ImageStorage {
    private const val DIR = "entry_images"

    fun copyFromUri(context: Context, uri: Uri): String? {
        return try {
            val input = context.contentResolver.openInputStream(uri) ?: return null
            input.use { inp ->
                val dir = File(context.filesDir, DIR).apply { mkdirs() }
                val ext = context.contentResolver.getType(uri)?.let { type ->
                    when {
                        type.contains("png") -> "png"
                        type.contains("webp") -> "webp"
                        else -> "jpg"
                    }
                } ?: "jpg"
                val file = File(dir, "${UUID.randomUUID()}.$ext")
                FileOutputStream(file).use { out -> inp.copyTo(out) }
                file.absolutePath
            }
        } catch (_: Exception) {
            null
        }
    }

    fun deleteIfExists(path: String?) {
        path?.let { File(it).takeIf { f -> f.exists() }?.delete() }
    }

    fun saveBytes(context: Context, bytes: ByteArray, extension: String): String? {
        return try {
            val dir = File(context.filesDir, DIR).apply { mkdirs() }
            val ext = extension.lowercase().removePrefix(".")
            val file = File(dir, "${UUID.randomUUID()}.$ext")
            FileOutputStream(file).use { it.write(bytes) }
            file.absolutePath
        } catch (_: Exception) {
            null
        }
    }

    fun copyFromFile(context: Context, source: File): String? {
        if (!source.exists() || !source.isFile) return null
        return try {
            val dir = File(context.filesDir, DIR).apply { mkdirs() }
            val ext = source.extension.ifBlank { "jpg" }
            val file = File(dir, "${UUID.randomUUID()}.$ext")
            source.copyTo(file, overwrite = true)
            file.absolutePath
        } catch (_: Exception) {
            null
        }
    }
}
