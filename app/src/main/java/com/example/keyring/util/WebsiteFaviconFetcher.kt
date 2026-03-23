package com.example.keyring.util

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset

/** 从网站拉取 favicon 并保存到本地；调用方需已允许网络并已声明 INTERNET 权限。 */
object WebsiteFaviconFetcher {

    suspend fun fetchAndSave(context: Context, pageUrlInput: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val pageUrl = normalizeUrl(pageUrlInput) ?: error("bad url")
                val uri = Uri.parse(pageUrl)
                val scheme = uri.scheme?.lowercase() ?: "https"
                val host = uri.host ?: error("no host")
                val portPart =
                    if (uri.port != -1 && uri.port != 80 && uri.port != 443) ":${uri.port}" else ""
                val origin = "$scheme://$host$portPart"

                val candidates = LinkedHashSet<String>()

                val html = downloadBytes(pageUrl)?.let { decodeHtml(it) }
                if (html != null) {
                    extractIconHrefs(html).forEach { href ->
                        resolveAgainstPage(origin, pageUrl, href)?.let { candidates.add(it) }
                    }
                }

                candidates.add("$origin/favicon.ico")
                candidates.add("$origin/apple-touch-icon.png")

                for (candidate in candidates) {
                    val bytes = downloadBytes(candidate) ?: continue
                    if (bytes.size < 32) continue
                    if (!isRasterImageMagic(bytes)) continue
                    val ext = guessExtension(bytes)
                    val path =
                        ImageStorage.saveBytes(context, bytes, ext) ?: continue
                    return@runCatching path
                }
                error("no image")
            }
        }

    private fun normalizeUrl(input: String): String? {
        val t = input.trim()
        if (t.isEmpty()) return null
        if (!UrlValidation.isAcceptableUrlOrEmpty(t)) return null
        return if (t.startsWith("http://") || t.startsWith("https://")) {
            t
        } else {
            "https://$t"
        }
    }

    private fun decodeHtml(bytes: ByteArray): String {
        val utf8 = Charset.forName("UTF-8")
        val probe = String(bytes.take(512).toByteArray(), utf8)
        val cs = when {
            probe.contains("charset=utf-8", ignoreCase = true) -> utf8
            probe.contains("charset=gbk", ignoreCase = true) ->
                try {
                    Charset.forName("GBK")
                } catch (_: Exception) {
                    utf8
                }

            else -> utf8
        }
        return String(bytes, cs)
    }

    private val linkTagRegex = Regex("""<link[^>]+>""", RegexOption.IGNORE_CASE)

    private fun extractIconHrefs(html: String): List<String> {
        val out = mutableListOf<String>()
        for (m in linkTagRegex.findAll(html)) {
            val tag = m.value
            if (!tag.contains("icon", ignoreCase = true)) continue
            if (tag.contains("mask-icon", ignoreCase = true)) continue
            val href = Regex("""href\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                .find(tag)?.groupValues?.get(1)
                ?: Regex("""href\s*=\s*([^\s>]+)""", RegexOption.IGNORE_CASE)
                    .find(tag)?.groupValues?.get(1)
                ?: continue
            if (href.startsWith("data:", ignoreCase = true)) continue
            out.add(href.trim())
        }
        return out
    }

    private fun resolveAgainstPage(origin: String, pageUrl: String, href: String): String? {
        val h = href.trim()
        if (h.startsWith("data:", ignoreCase = true)) return null
        return try {
            when {
                h.startsWith("http://", ignoreCase = true) ||
                    h.startsWith("https://", ignoreCase = true) -> h

                h.startsWith("//") -> {
                    val s = Uri.parse(pageUrl).scheme ?: "https"
                    "$s:$h"
                }

                h.startsWith("/") -> {
                    val u = Uri.parse(origin)
                    "${u.scheme}://${u.host}${if (u.port != -1) ":${u.port}" else ""}$h"
                }

                else -> URL(URL(pageUrl), h).toString()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun downloadBytes(urlString: String, maxRedirects: Int = 5): ByteArray? {
        var current = urlString
        var redirects = 0
        while (redirects < maxRedirects) {
            val conn = URL(current).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.instanceFollowRedirects = false
            conn.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36"
            )
            conn.setRequestProperty("Accept", "*/*")
            val code = conn.responseCode
            when (code) {
                HttpURLConnection.HTTP_MOVED_PERM,
                HttpURLConnection.HTTP_MOVED_TEMP,
                HttpURLConnection.HTTP_SEE_OTHER,
                307,
                308 -> {
                    val loc = conn.getHeaderField("Location") ?: run {
                        conn.disconnect()
                        return null
                    }
                    conn.disconnect()
                    current = try {
                        URL(URL(current), loc).toString()
                    } catch (_: Exception) {
                        loc
                    }
                    redirects++
                }

                HttpURLConnection.HTTP_OK -> {
                    val stream = conn.inputStream ?: run {
                        conn.disconnect()
                        return null
                    }
                    val bytes = stream.use { it.readBytes() }
                    conn.disconnect()
                    return bytes
                }

                else -> {
                    conn.disconnect()
                    return null
                }
            }
        }
        return null
    }

    private fun isRasterImageMagic(bytes: ByteArray): Boolean {
        if (bytes.size < 12) return false
        if (bytes[0] == 0x3C.toByte()) return false // HTML / SVG text
        // PNG
        if (bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()
        ) {
            return true
        }
        // JPEG
        if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) return true
        // GIF
        if (bytes[0] == 0x47.toByte() && bytes[1] == 0x49.toByte() &&
            bytes[2] == 0x46.toByte()
        ) {
            return true
        }
        // ICO
        if (bytes[0] == 0x00.toByte() && bytes[1] == 0x00.toByte() &&
            bytes[2] == 0x01.toByte() && bytes[3] == 0x00.toByte()
        ) {
            return true
        }
        // WEBP (RIFF....WEBP)
        if (bytes.size >= 12 &&
            bytes[0] == 0x52.toByte() && bytes[1] == 0x49.toByte() &&
            bytes[2] == 0x46.toByte() && bytes[3] == 0x46.toByte()
        ) {
            return true
        }
        return false
    }

    private fun guessExtension(bytes: ByteArray): String {
        return when {
            bytes.size >= 4 &&
                bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() -> "png"

            bytes.size >= 2 &&
                bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "jpg"

            bytes.size >= 3 &&
                bytes[0] == 0x47.toByte() && bytes[1] == 0x49.toByte() -> "gif"

            bytes.size >= 4 &&
                bytes[0] == 0x00.toByte() && bytes[1] == 0x00.toByte() -> "ico"

            bytes.size >= 12 &&
                bytes[0] == 0x52.toByte() && bytes[1] == 0x49.toByte() -> "webp"

            else -> "png"
        }
    }
}
