package com.example.keyring.util

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object DateTimeFormatters {
    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    fun formatDateTime(timestampMs: Long): String {
        val z = ZoneId.systemDefault()
        val instant = Instant.ofEpochMilli(timestampMs)
        return formatter.format(instant.atZone(z).toLocalDateTime())
    }
}
