package com.wudongsong.keyring.sync

import com.wudongsong.keyring.data.AppPreferences
import com.wudongsong.keyring.data.PasswordEntryRepository
import com.wudongsong.keyring.data.local.PasswordEntry
import kotlinx.serialization.Serializable

@Serializable
data class MergeStats(
    val inserted: Int,
    val updated: Int,
    val skipped: Int,
    val duplicatedAsCopy: Int,
)

object MergeEngine {

    fun fingerprint(entry: PasswordEntry): String {
        fun norm(s: String) = s.trim().lowercase().replace(Regex("\\s+"), " ")
        return "${norm(entry.title)}|${norm(entry.accountName)}|${norm(entry.username)}|${norm(entry.url)}"
    }

    /** @param parsed 来自 [SyncArchive.parseZipBytes]，已包含复制到本机的头像/附件路径。 */
    suspend fun merge(
        repository: PasswordEntryRepository,
        parsed: List<SyncArchive.ParsedEntry>,
        policy: AppPreferences.DuplicatePolicy,
    ): MergeStats {
        val policyForImport =
            when (policy) {
                AppPreferences.DuplicatePolicy.SKIP -> com.wudongsong.keyring.data.ImportPolicy.SKIP
                AppPreferences.DuplicatePolicy.OVERWRITE_NEWER ->
                    com.wudongsong.keyring.data.ImportPolicy.NEWER
                AppPreferences.DuplicatePolicy.KEEP_BOTH ->
                    com.wudongsong.keyring.data.ImportPolicy.KEEP_BOTH
            }
        return com.wudongsong.keyring.util.PasswordBackup.PreparedImport(parsed.map { it.entry })
            .use {
                val plan = it.commit(repository, policyForImport)
                MergeStats(
                    plan.inserts.size - plan.copies,
                    plan.updates.size,
                    plan.skipped,
                    plan.copies,
                )
            }
    }
}
