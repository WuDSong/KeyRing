package com.wudongsong.keyring

import com.wudongsong.keyring.security.BiometricBinding
import org.junit.Assert.*
import org.junit.Test

class BiometricBindingTest {
    @Test
    fun bindingRequiresBothTheCurrentVerifierAndTheAuthenticatedCiphertext() {
        assertTrue(BiometricBinding.matches("verifier-a", "cipher-a", "verifier-a", "cipher-a"))
        assertFalse(BiometricBinding.matches("verifier-b", "cipher-a", "verifier-a", "cipher-a"))
        assertFalse(BiometricBinding.matches("verifier-a", "cipher-b", "verifier-a", "cipher-a"))
    }

    @Test
    fun missingOrEmptyBindingsCannotGrantFastUnlock() {
        assertFalse(BiometricBinding.matches("verifier", "cipher", null, null))
        assertFalse(BiometricBinding.matches("", "cipher", "", "cipher"))
        assertFalse(BiometricBinding.matches("verifier", "", "verifier", ""))
        assertFalse(BiometricBinding.matches("verifier", "cipher", "verifier", null))
    }

    @Test
    fun aDifferentSaltInvalidatesBindingEvenForTheSamePassword() {
        val password = "same master password"
        val first = com.wudongsong.keyring.security.PasswordHash.create(password)
        val second = com.wudongsong.keyring.security.PasswordHash.create(password)
        assertFalse(BiometricBinding.matches(second, "cipher", first, "cipher"))
    }
}
