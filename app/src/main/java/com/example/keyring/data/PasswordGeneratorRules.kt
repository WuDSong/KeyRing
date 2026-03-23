package com.example.keyring.data

/** 随机密码生成规则（长度与字符组成）。 */
data class PasswordGeneratorRules(
    val length: Int,
    val includeUppercase: Boolean,
    val includeLowercase: Boolean,
    val includeDigits: Boolean,
    val includeSymbols: Boolean
) {
    fun hasAnyCharset(): Boolean =
        includeUppercase || includeLowercase || includeDigits || includeSymbols

    companion object {
        const val MIN_LENGTH = 8
        const val MAX_LENGTH = 32

        fun default(): PasswordGeneratorRules = PasswordGeneratorRules(
            length = 16,
            includeUppercase = true,
            includeLowercase = true,
            includeDigits = true,
            includeSymbols = true
        )
    }
}
