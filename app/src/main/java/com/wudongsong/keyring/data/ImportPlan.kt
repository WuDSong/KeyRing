package com.wudongsong.keyring.data

import com.wudongsong.keyring.data.local.PasswordEntry
import java.util.UUID

enum class ImportPolicy {
    SKIP,
    NEWER,
    KEEP_BOTH,
}

data class ImportPlan(
    val inserts: List<PasswordEntry>,
    val updates: List<PasswordEntry>,
    val skipped: Int,
    val replaced: List<PasswordEntry>,
    val copies: Int = 0,
)

fun planImport(
    local: List<PasswordEntry>,
    incoming: List<PasswordEntry>,
    policy: ImportPolicy,
): ImportPlan {
    fun fingerprint(e: PasswordEntry): List<String> =
        listOf(e.title, e.accountName, e.username, e.url).map {
            it.trim().lowercase(java.util.Locale.ROOT).replace(Regex("\\s+"), " ")
        }
    val working = local.toMutableList()
    val inserts = mutableListOf<PasswordEntry>()
    val updates = linkedMapOf<Long, PasswordEntry>()
    val replaced = linkedMapOf<Long, PasswordEntry>()
    var skipped = 0
    var copies = 0
    for (raw in incoming) {
        val entry = raw.ensureUuid().copy(id = 0)
        val match =
            working.firstOrNull { it.uuid == entry.uuid }
                ?: working.firstOrNull { fingerprint(it) == fingerprint(entry) }
        if (match == null) {
            val virtual = entry.copy(id = -(inserts.size + 1L))
            inserts += virtual
            working += virtual
        } else if (policy == ImportPolicy.KEEP_BOTH) {
            copies++
            var number = 1
            var title: String
            do {
                title = "${entry.title} (${if (number == 1) "copy" else "copy $number"})"
                number++
            } while (working.any { it.title == title })
            val copy =
                entry.copy(
                    id = -(inserts.size + 1L),
                    uuid = UUID.randomUUID().toString(),
                    title = title,
                )
            inserts += copy
            working += copy
        } else if (policy == ImportPolicy.NEWER && entry.updatedAt > match.updatedAt) {
            val next = entry.copy(id = match.id)
            if (match.id < 0) inserts[-match.id.toInt() - 1] = next
            else {
                replaced.putIfAbsent(match.id, match)
                updates[match.id] = next
            }
            working[working.indexOf(match)] = next
        } else skipped++
    }
    return ImportPlan(
        inserts.map { it.copy(id = 0) },
        updates.values.toList(),
        skipped,
        replaced.values.toList(),
        copies,
    )
}
