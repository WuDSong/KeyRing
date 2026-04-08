package com.example.keyring.sync

import com.example.keyring.data.AppPreferences
import com.example.keyring.data.PasswordEntryRepository
import com.example.keyring.data.local.PasswordEntry
import com.example.keyring.data.local.allAttachmentPaths
import com.example.keyring.util.ImageStorage
import java.util.UUID
import kotlinx.serialization.Serializable

@Serializable
data class MergeStats(
    val inserted: Int,
    val updated: Int,
    val skipped: Int,
    val duplicatedAsCopy: Int
)

object MergeEngine {

    fun fingerprint(entry: PasswordEntry): String {
        fun norm(s: String) = s.trim().lowercase().replace(Regex("\\s+"), " ")
        return "${norm(entry.title)}|${norm(entry.accountName)}|${norm(entry.username)}|${norm(entry.url)}"
    }

    /**
     * @param parsed 来自 [SyncArchive.parseZipBytes]，已包含复制到本机的头像/附件路径。
     */
    suspend fun merge(
        repository: PasswordEntryRepository,
        parsed: List<SyncArchive.ParsedEntry>,
        policy: AppPreferences.DuplicatePolicy
    ): MergeStats {
        if (parsed.isEmpty()) {
            return MergeStats(0, 0, 0, 0)
        }

        var inserted = 0
        var updated = 0
        var skipped = 0
        var duplicatedAsCopy = 0

        val locals = repository.getAllEntriesUnsorted().toMutableList()

        for (p in parsed) {
            val incoming = p.entry.ensureUuid()
            val local = findMatchingLocal(locals, incoming)

            when {
                local == null -> {
                    val id = repository.insert(incoming)
                    locals.add(incoming.copy(id = id))
                    inserted++
                }
                policy == AppPreferences.DuplicatePolicy.SKIP -> {
                    discardImportedFiles(p)
                    skipped++
                }
                policy == AppPreferences.DuplicatePolicy.KEEP_BOTH -> {
                    val titles = locals.map { it.title }.toSet()
                    val uniqueTitle = uniquifyTitle(titles, incoming.title)
                    val copy = incoming.copy(
                        id = 0,
                        uuid = UUID.randomUUID().toString(),
                        title = uniqueTitle
                    )
                    val id = repository.insert(copy)
                    locals.add(copy.copy(id = id))
                    duplicatedAsCopy++
                }
                policy == AppPreferences.DuplicatePolicy.OVERWRITE_NEWER -> {
                    if (incoming.updatedAt > local.updatedAt) {
                        deletePathsNotKept(local, incoming)
                        val merged = incoming.copy(id = local.id)
                        repository.updateEntry(merged)
                        val idx = locals.indexOfFirst { it.id == local.id }
                        if (idx >= 0) locals[idx] = merged
                        updated++
                    } else {
                        discardImportedFiles(p)
                        skipped++
                    }
                }
            }
        }

        return MergeStats(
            inserted = inserted,
            updated = updated,
            skipped = skipped,
            duplicatedAsCopy = duplicatedAsCopy
        )
    }

    private fun findMatchingLocal(locals: List<PasswordEntry>, incoming: PasswordEntry): PasswordEntry? {
        if (incoming.uuid.isNotBlank()) {
            locals.firstOrNull { it.uuid == incoming.uuid }?.let { return it }
        }
        val fp = fingerprint(incoming)
        return locals
            .filter { fingerprint(it) == fp }
            .maxByOrNull { it.updatedAt }
    }

    private fun uniquifyTitle(existingTitles: Set<String>, base: String): String {
        var candidate = "$base (副本)"
        var i = 2
        while (candidate in existingTitles) {
            candidate = "$base (副本$i)"
            i++
        }
        return candidate
    }

    private fun discardImportedFiles(p: SyncArchive.ParsedEntry) {
        ImageStorage.deleteIfExists(p.entry.avatarImagePath)
        p.entry.allAttachmentPaths().forEach { ImageStorage.deleteIfExists(it) }
    }

    private fun deletePathsNotKept(old: PasswordEntry, new: PasswordEntry) {
        val oldPaths = old.allAttachmentPaths().toMutableSet()
        old.avatarImagePath?.let { oldPaths += it }
        val newPaths = new.allAttachmentPaths().toMutableSet()
        new.avatarImagePath?.let { newPaths += it }
        (oldPaths - newPaths).forEach { ImageStorage.deleteIfExists(it) }
    }
}
