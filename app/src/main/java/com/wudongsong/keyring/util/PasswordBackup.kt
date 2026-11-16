package com.wudongsong.keyring.util

import android.content.Context
import android.net.Uri
import com.wudongsong.keyring.data.ImportPlan
import com.wudongsong.keyring.data.ImportPolicy
import com.wudongsong.keyring.data.PasswordEntryRepository
import com.wudongsong.keyring.data.local.PasswordEntry
import com.wudongsong.keyring.data.local.allAttachmentPaths
import com.wudongsong.keyring.data.local.encodeAttachmentPathsJson
import com.wudongsong.keyring.security.BackupCipher
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.io.PushbackInputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

object PasswordBackup {
    private const val JSON_ENTRY = "mypasswords_backup.json"
    private const val FORMAT_VERSION = 3
    private const val IMG_PREFIX = "img/"

    suspend fun exportToZip(
        context: Context,
        uri: Uri,
        entries: List<PasswordEntry>,
        password: CharArray,
    ) =
        withContext(Dispatchers.IO) {
            val out =
                context.contentResolver.openOutputStream(uri, "wt")
                    ?: error("Cannot open destination")
            BufferedOutputStream(out).use { buffered ->
                BackupCipher.encrypt(buffered, password) { encrypted ->
                    ZipOutputStream(encrypted).use { zos ->
                        val jsonArray = JSONArray()
                        val fileEntries = mutableListOf<Pair<String, File>>()

                        for (entry in entries) {
                            val o = JSONObject()
                            o.put("uuid", entry.uuid)
                            o.put("title", entry.title)
                            o.put("accountName", entry.accountName)
                            o.put("username", entry.username)
                            o.put("password", entry.password)
                            o.put("url", entry.url)
                            o.put("description", entry.description)
                            if (!entry.detailedDescription.isNullOrBlank()) {
                                o.put("detailedDescription", entry.detailedDescription)
                            }
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
                        require(jsonBytes.size <= 8 * 1024 * 1024 && fileEntries.size < 4096) {
                            "Backup exceeds size limit"
                        }
                        require(
                            fileEntries.all { it.second.length() <= 32L * 1024 * 1024 } &&
                                fileEntries.sumOf { it.second.length() } + jsonBytes.size <=
                                    256L * 1024 * 1024
                        ) {
                            "Backup attachments exceed size limit"
                        }
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
        }

    class PreparedImport(val entries: List<PasswordEntry>) : AutoCloseable {
        private val owned =
            entries.flatMap { it.allAttachmentPaths() + listOfNotNull(it.avatarImagePath) }.toSet()
        private var retained = emptySet<String>()
        private var committing = false
        private var closed = false

        suspend fun commit(repository: PasswordEntryRepository, policy: ImportPolicy): ImportPlan =
            withContext(NonCancellable + Dispatchers.IO) {
                synchronized(this@PreparedImport) {
                    check(!closed && !committing)
                    committing = true
                }
                try {
                    val plan = repository.importBackup(entries, policy)
                    retained =
                        (plan.inserts + plan.updates)
                            .flatMap { it.allAttachmentPaths() + listOfNotNull(it.avatarImagePath) }
                            .toSet()
                    val old =
                        plan.replaced
                            .flatMap { it.allAttachmentPaths() + listOfNotNull(it.avatarImagePath) }
                            .toSet()
                    (old - retained).forEach(ImageStorage::deleteIfExists)
                    plan
                } finally {
                    synchronized(this@PreparedImport) {
                        committing = false
                        closed = true
                        cleanup()
                    }
                }
            }

        private fun cleanup() {
            (owned - retained).forEach(ImageStorage::deleteIfExists)
        }

        @Synchronized
        override fun close() {
            closed = true
            if (!committing) cleanup()
        }
    }

    suspend fun importFrom(
        context: Context,
        uri: Uri,
        repository: PasswordEntryRepository,
        password: CharArray,
    ): Int =
        prepareImport(context, uri, password).use { prepared ->
            val plan = prepared.commit(repository, ImportPolicy.SKIP)
            plan.inserts.size + plan.updates.size
        }

    suspend fun prepareImport(context: Context, uri: Uri, password: CharArray): PreparedImport {
        var prepared: PreparedImport? = null
        try {
            return withContext(Dispatchers.IO) {
                val input =
                    context.contentResolver.openInputStream(uri) ?: error("Cannot open backup")
                PushbackInputStream(input, 8).use { source ->
                    val prefix = ByteArray(8)
                    var count = 0
                    while (count < prefix.size) {
                        val n = source.read(prefix, count, prefix.size - count)
                        if (n == -1) break
                        count += n
                    }
                    source.unread(prefix, 0, count)
                    val entries =
                        when {
                            prefix.contentEquals(BackupCipher.MAGIC) ->
                                BackupCipher.decrypt(source, password).use { plain ->
                                    importZipFromStream(context, plain)
                                }
                            count >= 2 &&
                                prefix[0] == 0x50.toByte() &&
                                prefix[1] == 0x4b.toByte() -> importZipFromStream(context, source)
                            else -> {
                                val root = JSONObject(ImportLimits().readJson(source))
                                parseEntriesFromJson(root, null, context)
                            }
                        }
                    PreparedImport(entries).also { prepared = it }
                }
            }
        } catch (e: Exception) {
            prepared?.close()
            throw e
        }
    }

    private suspend fun importZipFromStream(
        context: Context,
        input: InputStream,
    ): List<PasswordEntry> {
        val cacheDir = File(context.cacheDir, "import_${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val limits = ImportLimits()
            val pathToFile = mutableMapOf<String, File>()
            var jsonString: String? = null
            // Leave the authenticated stream open until the required final record is verified.
            val nonClosing =
                object : FilterInputStream(input) {
                    override fun close() = Unit
                }
            ZipInputStream(nonClosing).use { zis ->
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
                    if (name.endsWith(".json", ignoreCase = true)) {
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
            ImportLimits().discard(input)
            require(!jsonString.isNullOrBlank()) { "Missing backup manifest" }
            val root = JSONObject(jsonString)
            val entries = parseEntriesFromJson(root, pathToFile, context)
            return entries
        } finally {
            cacheDir.deleteRecursively()
        }
    }

    private fun parseEntriesFromJson(
        root: JSONObject,
        fileMap: Map<String, File>?,
        context: Context,
    ): List<PasswordEntry> {
        val version = root.optInt("formatVersion", 1)
        if (version > FORMAT_VERSION) {
            throw IllegalArgumentException("Unsupported backup format version: $version")
        }
        val arr = root.getJSONArray("entries")
        val list = mutableListOf<PasswordEntry>()
        val created = mutableListOf<String>()
        try {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(parseEntryObject(o, fileMap, context, created))
            }
        } catch (e: Exception) {
            created.forEach(ImageStorage::deleteIfExists)
            throw e
        }
        return list
    }

    private fun parseEntryObject(
        o: JSONObject,
        fileMap: Map<String, File>?,
        context: Context,
        created: MutableList<String>,
    ): PasswordEntry {
        val avatarRef = readNullableString(o, "avatarRef")

        val avatarPath =
            avatarRef?.let { ref ->
                fileMap?.get(ref)?.let {
                    ImageStorage.copyFromFile(context, it)?.also(created::add)
                        ?: error("Cannot copy backup image")
                }
            }

        val importedPaths = mutableListOf<String>()
        when {
            o.has("attachmentRefs") && !o.isNull("attachmentRefs") -> {
                val arr = o.getJSONArray("attachmentRefs")
                for (i in 0 until arr.length()) {
                    val ref = arr.optString(i, "")
                    if (ref.isBlank()) continue
                    fileMap
                        ?.get(ref)
                        ?.let {
                            ImageStorage.copyFromFile(context, it)?.also(created::add)
                                ?: error("Cannot copy backup image")
                        }
                        ?.let { importedPaths.add(it) }
                }
            }
            else -> {
                readNullableString(o, "attachmentRef")?.let { ref ->
                    fileMap
                        ?.get(ref)
                        ?.let {
                            ImageStorage.copyFromFile(context, it)?.also(created::add)
                                ?: error("Cannot copy backup image")
                        }
                        ?.let { importedPaths.add(it) }
                }
            }
        }

        return PasswordEntry(
            id = 0,
            uuid = o.optString("uuid", "").ifBlank { UUID.randomUUID().toString() },
            title = o.optString("title", ""),
            accountName = o.optString("accountName", ""),
            username = o.optString("username", ""),
            password = o.optString("password", ""),
            url = o.optString("url", ""),
            description = o.optString("description", ""),
            detailedDescription = readNullableString(o, "detailedDescription"),
            tags = o.optString("tags", ""),
            imagePath = null,
            attachmentsJson = encodeAttachmentPathsJson(importedPaths),
            avatarImagePath = avatarPath,
            createdAt = o.optLong("createdAt", System.currentTimeMillis()),
            updatedAt = o.optLong("updatedAt", System.currentTimeMillis()),
        )
    }

    private fun readNullableString(o: JSONObject, key: String): String? {
        if (!o.has(key) || o.isNull(key)) return null
        val s = o.optString(key, "")
        return if (s.isBlank()) null else s
    }
}
