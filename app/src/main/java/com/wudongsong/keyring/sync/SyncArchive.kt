package com.wudongsong.keyring.sync

import android.content.Context
import com.wudongsong.keyring.data.local.PasswordEntry
import com.wudongsong.keyring.data.local.allAttachmentPaths
import com.wudongsong.keyring.data.local.encodeAttachmentPathsJson
import com.wudongsong.keyring.util.ImageStorage
import com.wudongsong.keyring.util.ImportLimits
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object SyncArchive {
    const val MAX_ARCHIVE_BYTES = 8 * 1024 * 1024
    private const val JSON_ENTRY = "keyring_sync.json"
    private const val FORMAT_VERSION = 1
    private const val IMG_PREFIX = "img/"

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    @Serializable
    private data class Root(val formatVersion: Int, val exportedAt: Long, val entries: List<Entry>)

    @Serializable
    private data class Entry(
        val uuid: String,
        val title: String,
        val accountName: String,
        val username: String,
        val password: String,
        val url: String,
        val description: String,
        val detailedDescription: String? = null,
        val tags: String,
        val createdAt: Long,
        val updatedAt: Long,
        val avatarRef: String? = null,
        val attachmentRefs: List<String> = emptyList(),
    )

    data class ParsedEntry(
        val entry: PasswordEntry,
        val importedAvatarPath: String?,
        val importedAttachmentPaths: List<String>,
    )

    fun exportZipBytes(entries: List<PasswordEntry>): ByteArray {
        val baos =
            object : ByteArrayOutputStream() {
                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    require(size().toLong() + length <= MAX_ARCHIVE_BYTES) {
                        "Sync archive exceeds 8 MiB"
                    }
                    super.write(bytes, offset, length)
                }

                override fun write(value: Int) {
                    require(size() < MAX_ARCHIVE_BYTES) { "Sync archive exceeds 8 MiB" }
                    super.write(value)
                }
            }
        BufferedOutputStream(baos).use { buffered ->
            ZipOutputStream(buffered).use { zos ->
                val rootEntries = mutableListOf<Entry>()
                val fileEntries = mutableListOf<Pair<String, File>>()

                for (e0 in entries) {
                    val e = e0.ensureUuid()
                    var avatarRef: String? = null
                    e.avatarImagePath?.let { path ->
                        val f = File(path)
                        if (f.isFile) {
                            val ext = f.extension.ifBlank { "jpg" }
                            val name = "${IMG_PREFIX}av_${UUID.randomUUID()}.$ext"
                            avatarRef = name
                            fileEntries += name to f
                        }
                    }
                    val attachmentRefs = mutableListOf<String>()
                    e.allAttachmentPaths().forEach { path ->
                        val f = File(path)
                        if (f.isFile) {
                            val ext = f.extension.ifBlank { "jpg" }
                            val name = "${IMG_PREFIX}att_${UUID.randomUUID()}.$ext"
                            attachmentRefs += name
                            fileEntries += name to f
                        }
                    }
                    rootEntries +=
                        Entry(
                            uuid = e.uuid,
                            title = e.title,
                            accountName = e.accountName,
                            username = e.username,
                            password = e.password,
                            url = e.url,
                            description = e.description,
                            detailedDescription = e.detailedDescription,
                            tags = e.tags,
                            createdAt = e.createdAt,
                            updatedAt = e.updatedAt,
                            avatarRef = avatarRef,
                            attachmentRefs = attachmentRefs,
                        )
                }

                val root =
                    Root(
                        formatVersion = FORMAT_VERSION,
                        exportedAt = System.currentTimeMillis(),
                        entries = rootEntries,
                    )
                val jsonBytes = json.encodeToString(root).toByteArray(Charsets.UTF_8)
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
        return baos.toByteArray()
    }

    fun parseZipBytes(context: Context, bytes: ByteArray): List<ParsedEntry> {
        require(bytes.size <= MAX_ARCHIVE_BYTES) { "Sync archive exceeds 8 MiB" }
        val cacheDir = File(context.cacheDir, "sync_import_${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val limits = ImportLimits()
            val pathToFile = mutableMapOf<String, File>()
            var jsonString: String? = null
            ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
                while (true) {
                    val entry = zis.nextEntry ?: break
                    limits.nextEntry()
                    if (entry.isDirectory) {
                        limits.discard(zis)
                        zis.closeEntry()
                        continue
                    }
                    val name = entry.name
                    require(name !in pathToFile) { "Duplicate archive entry" }
                    if (name == JSON_ENTRY) {
                        require(jsonString == null) { "Duplicate archive manifest" }
                        jsonString = limits.readJson(zis)
                    } else {
                        val safeName = File(name).name.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                        val outFile = File(cacheDir, "${UUID.randomUUID()}_$safeName")
                        FileOutputStream(outFile).use { out -> limits.copy(zis, out) }
                        pathToFile[name] = outFile
                    }
                    zis.closeEntry()
                }
            }
            require(!jsonString.isNullOrBlank()) { "Missing sync manifest" }
            val root = json.decodeFromString(Root.serializer(), jsonString!!)
            if (root.formatVersion > FORMAT_VERSION)
                error("Unsupported sync format: ${root.formatVersion}")

            return root.entries.map { e ->
                val avatarPath =
                    e.avatarRef?.let { ref ->
                        pathToFile[ref]?.let { ImageStorage.copyFromFile(context, it) }
                    }
                val attPaths =
                    e.attachmentRefs.mapNotNull { ref ->
                        pathToFile[ref]?.let { ImageStorage.copyFromFile(context, it) }
                    }
                val pe =
                    PasswordEntry(
                        id = 0,
                        uuid = e.uuid.ifBlank { UUID.randomUUID().toString() },
                        title = e.title,
                        accountName = e.accountName,
                        username = e.username,
                        password = e.password,
                        url = e.url,
                        description = e.description,
                        detailedDescription = e.detailedDescription,
                        tags = e.tags,
                        imagePath = null,
                        attachmentsJson = encodeAttachmentPathsJson(attPaths),
                        avatarImagePath = avatarPath,
                        createdAt = e.createdAt,
                        updatedAt = e.updatedAt,
                    )
                ParsedEntry(
                    entry = pe,
                    importedAvatarPath = avatarPath,
                    importedAttachmentPaths = attPaths,
                )
            }
        } finally {
            cacheDir.deleteRecursively()
        }
    }
}
