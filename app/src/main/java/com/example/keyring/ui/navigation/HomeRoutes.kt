package com.example.keyring.ui.navigation

object HomeRoutes {
    const val LIST = "list"
    const val SEARCH = "search"
    const val ADD = "add"
    const val SETTINGS = "settings"
    const val LANGUAGE = "language"
    const val ABOUT = "about"
    const val DETAIL = "detail/{entryId}"
    const val EDIT = "edit/{entryId}"

    fun detail(entryId: Long) = "detail/$entryId"
    fun edit(entryId: Long) = "edit/$entryId"
}
