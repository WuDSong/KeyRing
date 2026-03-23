package com.example.keyring.util

import com.example.keyring.data.PasswordGeneratorRules
import java.security.SecureRandom
import java.util.Collections

object PasswordGenerator {
    private val rng = SecureRandom()

    /** 与常见密码规则兼容的符号集。 */
    private const val SYMBOLS = "!@#$%&*+-=?_^.,:;[]{}()~/\\|"

    private fun List<Char>.pick(): Char = this[rng.nextInt(size)]

    fun generate(rules: PasswordGeneratorRules): String {
        if (!rules.hasAnyCharset()) return ""

        val len = rules.length.coerceIn(
            PasswordGeneratorRules.MIN_LENGTH,
            PasswordGeneratorRules.MAX_LENGTH
        )

        val upper = if (rules.includeUppercase) ('A'..'Z').toList() else emptyList()
        val lower = if (rules.includeLowercase) ('a'..'z').toList() else emptyList()
        val digits = if (rules.includeDigits) ('0'..'9').toList() else emptyList()
        val symbols = if (rules.includeSymbols) SYMBOLS.toList() else emptyList()

        val union = upper + lower + digits + symbols

        val mandatory = buildList {
            if (upper.isNotEmpty()) add(upper.pick())
            if (lower.isNotEmpty()) add(lower.pick())
            if (digits.isNotEmpty()) add(digits.pick())
            if (symbols.isNotEmpty()) add(symbols.pick())
        }

        if (mandatory.size >= len) {
            val m = mandatory.toMutableList()
            Collections.shuffle(m, rng)
            return m.take(len).joinToString("")
        }

        val out = mandatory.toMutableList()
        repeat(len - mandatory.size) {
            out.add(union.pick())
        }
        Collections.shuffle(out, rng)
        return out.joinToString("")
    }
}
