package com.wudongsong.keyring.util

import java.io.File

/** New files belong to the draft until a database commit transfers ownership. */
class DraftFiles {
    private val created = mutableSetOf<String>()

    fun register(path: String): String {
        created += path
        return path
    }

    fun committed(oldPaths: Collection<String>, keptPaths: Collection<String>) {
        val kept = keptPaths.toSet()
        created.removeAll(kept)
        (oldPaths.toSet() - kept).forEach(::delete)
        discard()
    }

    fun discard() {
        created.forEach(::delete)
        created.clear()
    }

    private fun delete(path: String) {
        runCatching { File(path).delete() }
    }
}
