package com.example.keyring.util

import android.net.Uri
import java.util.Locale

object UrlValidation {
    /**
     * 空字符串视为未填写（允许）；非空则须为带 http/https 协议且含主机名的 URL。
     */
    fun isAcceptableUrlOrEmpty(input: String): Boolean {
        val s = input.trim()
        if (s.isEmpty()) return true
        val uri = Uri.parse(s)
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if (scheme != "http" && scheme != "https") return false
        return !uri.host.isNullOrBlank()
    }
}
