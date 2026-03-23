package com.example.keyring.util

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import com.example.keyring.data.AppPreferences
import java.util.Locale

private fun resolveLocale(language: String): Locale? {
    return when (language) {
        AppPreferences.LANG_ZH_CN -> Locale.SIMPLIFIED_CHINESE
        AppPreferences.LANG_EN -> Locale.ENGLISH
        else -> null
    }
}

fun wrapContextWithAppLanguage(context: Context, language: String): Context {
    val locale = resolveLocale(language) ?: return context
    Locale.setDefault(locale)
    val config = Configuration(context.resources.configuration)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        config.setLocales(LocaleList(locale))
    } else {
        @Suppress("DEPRECATION")
        config.locale = locale
    }
    return context.createConfigurationContext(config)
}

fun applyAppLanguage(context: Context, language: String) {
    val locale = resolveLocale(language)
    val config = Configuration(context.resources.configuration)
    if (locale == null) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            config.setLocales(LocaleList.getDefault())
        } else {
            @Suppress("DEPRECATION")
            config.locale = Locale.getDefault()
        }
    } else {
        Locale.setDefault(locale)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            config.setLocales(LocaleList(locale))
        } else {
            @Suppress("DEPRECATION")
            config.locale = locale
        }
    }
    context.resources.updateConfiguration(config, context.resources.displayMetrics)
}
