package com.wudongsong.keyring.sync

/** Authenticated requests only: bounded nonce cache plus a freshness window. */
class SyncRequestGuard(private val capacity: Int = 4096) {
    private val seen = linkedMapOf<String, Long>()

    @Synchronized
    fun accept(device: String, nonce: String, issuedAt: Long, now: Long): Boolean {
        if (issuedAt <= 0 || issuedAt < now - 120_000 || issuedAt > now + 30_000) return false
        seen.entries.removeAll { it.value < now }
        val key = "$device:$nonce"
        if (key in seen || seen.size >= capacity) return false
        seen[key] = now + 180_000
        return true
    }
}
