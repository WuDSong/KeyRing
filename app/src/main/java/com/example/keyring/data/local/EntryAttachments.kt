package com.example.keyring.data.local

import org.json.JSONArray

fun PasswordEntry.allAttachmentPaths(): List<String> {
    val fromJson = runCatching {
        val a = JSONArray(attachmentsJson)
        (0 until a.length()).map { a.getString(it) }.filter { it.isNotBlank() }
    }.getOrElse { emptyList() }
    if (fromJson.isNotEmpty()) return fromJson.distinct()
    val legacy = imagePath?.takeIf { it.isNotBlank() }
    return listOfNotNull(legacy)
}

fun encodeAttachmentPathsJson(paths: List<String>): String {
    val a = JSONArray()
    paths.filter { it.isNotBlank() }.distinct().forEach { a.put(it) }
    return a.toString()
}
