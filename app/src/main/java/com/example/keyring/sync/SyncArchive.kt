package com.example.keyring.sync

import android.content.Context
import com.example.keyring.data.local.PasswordEntry
import com.example.keyring.data.local.allAttachmentPaths
import com.example.keyring.data.local.encodeAttachmentPathsJson
import com.example.keyring.util.ImageStorage
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object SyncArchive {
    private const val JSON_ENTRY = "keyring_sync.json"
    private const val FORMAT_VERSION = 1
    private const val IMG_PREFIX = "img/"

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    @Serializable
    private data class Root(
        val formatVersion: Int,
        val exportedAt: Long,
        val entries: List<Entry>
    )

    @Serializable
    private data class Entry(
        val uuid: String,
        val title: String,
        val accountName: String,
        val username: String,
        val password: String,
        val url: String,
        val description: String,
        val tags: String,
        val createdAt: Long,
        val updatedAt: Long,
        val avatarRef: String? = null,
        val attachmentRefs: List<String> = emptyList()
    )

    data class ParsedEntry(
        val entry: PasswordEntry,
        val importedAvatarPath: String?,
        val importedAttachmentPaths: List<String>
    )

    fun exportZipBytes(entries: List<PasswordEntry>): ByteArray {
        val baos = ByteArrayOutputStream()
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
                    rootEntries += Entry(
                        uuid = e.uuid,
                        title = e.title,
                        accountName = e.accountName,
                        username = e.username,
                        password = e.password,
                        url = e.url,
                        description = e.description,
                        tags = e.tags,
                        createdAt = e.createdAt,
                        updatedAt = e.updatedAt,
                        avatarRef = avatarRef,
                        attachmentRefs = attachmentRefs
                    )
                }

                val root = Root(
                    formatVersion = FORMAT_VERSION,
                    exportedAt = System.currentTimeMillis(),
                    entries = rootEntries
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
        val cacheDir = File(context.cacheDir, "sync_import_${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val pathToFile = mutableMapOf<String, File>()
            var jsonString: String? = null
            ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
                while (true) {
                    val entry = zis.nextEntry ?: break
                    try {
                        if (entry.isDirectory) continue
                        val name = entry.name
                        if (name == JSON_ENTRY) {
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
            if (jsonString.isNullOrBlank()) return emptyList()
            val root = json.decodeFromString(Root.serializer(), jsonString!!)
            if (root.formatVersion > FORMAT_VERSION) error("Unsupported sync format: ${root.formatVersion}")

            return root.entries.map { e ->
                val avatarPath = e.avatarRef?.let { ref ->
                    pathToFile[ref]?.let { ImageStorage.copyFromFile(context, it) }
                }
                val attPaths = e.attachmentRefs.mapNotNull { ref ->
                    pathToFile[ref]?.let { ImageStorage.copyFromFile(context, it) }
                }
                val pe = PasswordEntry(
                    id = 0,
                    uuid = e.uuid.ifBlank { UUID.randomUUID().toString() },
                    title = e.title,
                    accountName = e.accountName,
                    username = e.username,
                    password = e.password,
                    url = e.url,
                    description = e.description,
                    tags = e.tags,
                    imagePath = null,
                    attachmentsJson = encodeAttachmentPathsJson(attPaths),
                    avatarImagePath = avatarPath,
                    createdAt = e.createdAt,
                    updatedAt = e.updatedAt
                )
                ParsedEntry(entry = pe, importedAvatarPath = avatarPath, importedAttachmentPaths = attPaths)
            }
        } finally {
            cacheDir.deleteRecursively()
        }
    }
}

