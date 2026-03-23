package com.example.keyring.util

import android.content.Context
import android.net.Uri
import com.example.keyring.data.PasswordEntryRepository
import com.example.keyring.data.local.PasswordEntry
import com.example.keyring.data.local.allAttachmentPaths
import com.example.keyring.data.local.encodeAttachmentPathsJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object PasswordBackup {
    private const val JSON_ENTRY = "mypasswords_backup.json"
    private const val FORMAT_VERSION = 2
    private const val IMG_PREFIX = "img/"

    suspend fun exportToZip(
        context: Context,
        uri: Uri,
        entries: List<PasswordEntry>
    ) = withContext(Dispatchers.IO) {
        val out = context.contentResolver.openOutputStream(uri)
            ?: error("Cannot open destination")
        BufferedOutputStream(out).use { buffered ->
            ZipOutputStream(buffered).use { zos ->
                val jsonArray = JSONArray()
                val fileEntries = mutableListOf<Pair<String, File>>()

                for (entry in entries) {
                    val o = JSONObject()
                    o.put("title", entry.title)
                    o.put("accountName", entry.accountName)
                    o.put("username", entry.username)
                    o.put("password", entry.password)
                    o.put("url", entry.url)
                    o.put("description", entry.description)
                    o.put("tags", entry.tags)
                    o.put("createdAt", entry.createdAt)
                    o.put("updatedAt", entry.updatedAt)

                    var avatarRef: String? = null
                    entry.avatarImagePath?.let { path ->
                        val f = File(path)
                        if (f.isFile) {
                            val ext = f.extension.ifBlank { "jpg" }
                            val name = "${IMG_PREFIX}av_${UUID.randomUUID()}.$ext"
                            avatarRef = name
                            fileEntries += name to f
                        }
                    }
                    o.put("avatarRef", avatarRef ?: JSONObject.NULL)

                    val attachmentRefs = JSONArray()
                    entry.allAttachmentPaths().forEach { path ->
                        val f = File(path)
                        if (f.isFile) {
                            val ext = f.extension.ifBlank { "jpg" }
                            val name = "${IMG_PREFIX}att_${UUID.randomUUID()}.$ext"
                            attachmentRefs.put(name)
                            fileEntries += name to f
                        }
                    }
                    o.put("attachmentRefs", attachmentRefs)
                    o.put("attachmentRef", JSONObject.NULL)

                    jsonArray.put(o)
                }

                val root = JSONObject()
                root.put("formatVersion", FORMAT_VERSION)
                root.put("exportedAt", System.currentTimeMillis())
                root.put("entries", jsonArray)

                val jsonBytes = root.toString(2).toByteArray(Charsets.UTF_8)
                zos.putNextEntry(ZipEntry(JSON_ENTRY))
                zos.write(jsonBytes)
                zos.closeEntry()

                for ((zipPath, file) in fileEntries) {
                    zos.putNextEntry(ZipEntry(zipPath))
                    file.inputStream().use { input -> input.copyTo(zos) }
                    zos.closeEntry()
                }
            }
        }
    }

    suspend fun importFrom(
        context: Context,
        uri: Uri,
        repository: PasswordEntryRepository
    ): Int = withContext(Dispatchers.IO) {
        val input = context.contentResolver.openInputStream(uri) ?: return@withContext 0
        val tempFile = File(context.cacheDir, "import_${UUID.randomUUID()}.bin")
        try {
            FileOutputStream(tempFile).use { out -> input.copyTo(out) }
            when {
                isZipMagic(tempFile) -> importZipFromFile(context, tempFile, repository)
                else -> importJsonFromFile(context, tempFile, repository)
            }
        } finally {
            tempFile.delete()
        }
    }

    private fun isZipMagic(file: File): Boolean {
        if (!file.exists() || file.length() < 4) return false
        return RandomAccessFile(file, "r").use { raf ->
            raf.readByte() == 0x50.toByte() && raf.readByte() == 0x4B.toByte()
        }
    }

    private suspend fun importJsonFromFile(
        context: Context,
        file: File,
        repository: PasswordEntryRepository
    ): Int {
        val json = file.readText(Charsets.UTF_8)
        val root = JSONObject(json)
        val entries = parseEntriesFromJson(root, null, context)
        repository.insertEntries(entries)
        return entries.size
    }

    private suspend fun importZipFromFile(
        context: Context,
        file: File,
        repository: PasswordEntryRepository
    ): Int {
        val cacheDir = File(context.cacheDir, "import_${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val pathToFile = mutableMapOf<String, File>()
            var jsonString: String? = null
            ZipInputStream(FileInputStream(file)).use { zis ->
                while (true) {
                    val entry = zis.nextEntry ?: break
                    try {
                        if (entry.isDirectory) continue
                        val name = entry.name
                        if (name.endsWith(".json", ignoreCase = true)) {
                            jsonString = String(zis.readBytes(), Charsets.UTF_8)
                        } else {
                            val safeName = File(name).name.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                            val outFile = File(cacheDir, "${UUID.randomUUID()}_$safeName")
                            FileOutputStream(outFile).use { out -> zis.copyTo(out) }
                            pathToFile[name] = outFile
                        }
                    } finally {
                        zis.closeEntry()
                    }
                }
            }
            if (jsonString.isNullOrBlank()) return 0
            val root = JSONObject(jsonString)
            val entries = parseEntriesFromJson(root, pathToFile, context)
            repository.insertEntries(entries)
            return entries.size
        } finally {
            cacheDir.deleteRecursively()
        }
    }

    private fun parseEntriesFromJson(
        root: JSONObject,
        fileMap: Map<String, File>?,
        context: Context
    ): List<PasswordEntry> {
        val version = root.optInt("formatVersion", 1)
        if (version > FORMAT_VERSION) {
            throw IllegalArgumentException("Unsupported backup format version: $version")
        }
        val arr = root.optJSONArray("entries") ?: JSONArray()
        val list = mutableListOf<PasswordEntry>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            list.add(parseEntryObject(o, fileMap, context))
        }
        return list
    }

    private fun parseEntryObject(
        o: JSONObject,
        fileMap: Map<String, File>?,
        context: Context
    ): PasswordEntry {
        val avatarRef = readNullableString(o, "avatarRef")

        val avatarPath = avatarRef?.let { ref ->
            fileMap?.get(ref)?.let { ImageStorage.copyFromFile(context, it) }
        }

        val importedPaths = mutableListOf<String>()
        when {
            o.has("attachmentRefs") && !o.isNull("attachmentRefs") -> {
                val arr = o.getJSONArray("attachmentRefs")
                for (i in 0 until arr.length()) {
                    val ref = arr.optString(i, "")
                    if (ref.isBlank()) continue
                    fileMap?.get(ref)?.let { ImageStorage.copyFromFile(context, it) }?.let {
                        importedPaths.add(it)
                    }
                }
            }
            else -> {
                readNullableString(o, "attachmentRef")?.let { ref ->
                    fileMap?.get(ref)?.let { ImageStorage.copyFromFile(context, it) }?.let {
                        importedPaths.add(it)
                    }
                }
            }
        }

        return PasswordEntry(
            id = 0,
            title = o.optString("title", ""),
            accountName = o.optString("accountName", ""),
            username = o.optString("username", ""),
            password = o.optString("password", ""),
            url = o.optString("url", ""),
            description = o.optString("description", ""),
            tags = o.optString("tags", ""),
            imagePath = null,
            attachmentsJson = encodeAttachmentPathsJson(importedPaths),
            avatarImagePath = avatarPath,
            createdAt = o.optLong("createdAt", System.currentTimeMillis()),
            updatedAt = o.optLong("updatedAt", System.currentTimeMillis())
        )
    }

    private fun readNullableString(o: JSONObject, key: String): String? {
        if (!o.has(key) || o.isNull(key)) return null
        val s = o.optString(key, "")
        return if (s.isBlank()) null else s
    }
}
