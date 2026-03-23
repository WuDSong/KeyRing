package com.example.keyring.data

/** 应用界面主题：浅色、深色，或与系统一致。 */
enum class ThemeMode {
    /** 始终浅色（相当于关闭深色模式） */
    LIGHT,

    /** 始终深色（相当于开启深色模式） */
    DARK,

    /** 跟随系统深浅色设置 */
    SYSTEM
}

fun ThemeMode.isDark(systemInDarkTheme: Boolean): Boolean = when (this) {
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
    ThemeMode.SYSTEM -> systemInDarkTheme
}
