package com.example.keyring.util

import android.content.Context
import com.example.keyring.R

object RelativeTimeFormatter {
    fun formatPast(context: Context, timestampMs: Long, nowMs: Long = System.currentTimeMillis()): String {
        val diff = (nowMs - timestampMs).coerceAtLeast(0L)
        val seconds = diff / 1000L
        if (seconds < 60) {
            return context.getString(R.string.time_just_now)
        }
        val minutes = seconds / 60L
        if (minutes < 60) {
            return context.getString(R.string.time_minutes_ago, minutes.toInt())
        }
        val hours = minutes / 60L
        if (hours < 24) {
            return context.getString(R.string.time_hours_ago, hours.toInt())
        }
        val days = hours / 24L
        return context.getString(R.string.time_days_ago, days.toInt())
    }
}
