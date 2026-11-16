package com.wudongsong.keyring

import com.wudongsong.keyring.sync.PairingEngine
import com.wudongsong.keyring.sync.SecureTransport
import org.junit.Assert.*
import org.junit.Test

class SyncCryptoTest {
    @Test
    fun pairingCodesAreGroupedAndLegacyShortCodesAreRejected() {
        val code = PairingEngine.generatePairCode()
        assertEquals(listOf(4, 4, 4, 4), code.split('-').map { it.length })
        assertTrue(PairingEngine.isValidPairCode(code))
        assertTrue(PairingEngine.isValidPairCode(code.lowercase()))
        assertFalse(PairingEngine.isValidPairCode("123456"))
        assertFalse(PairingEngine.isValidPairCode("OOOO-OOOO-OOOO-OOOO"))
        assertNotEquals(code, PairingEngine.generatePairCode())
    }

    @Test
    fun sessionKeysNormalizeGroupedInputAndDependOnBothSalts() {
        val code = PairingEngine.generatePairCode()
        val first = PairingEngine.newSaltB64()
        val second = PairingEngine.newSaltB64()
        val key = PairingEngine.deriveSessionKey(code, first, second)
        assertArrayEquals(
            key.encoded,
            PairingEngine.deriveSessionKey(code.replace("-", "").lowercase(), first, second).encoded,
        )
        assertFalse(
            key.encoded.contentEquals(
                PairingEngine.deriveSessionKey(code, first, PairingEngine.newSaltB64()).encoded
            )
        )
    }

    @Test
    fun trustKeyDerivationIsIndependentOfDeviceOrdering() {
        val key = PairingEngine.asAesKey(ByteArray(32) { 23 })
        assertArrayEquals(
            PairingEngine.deriveTrustedDeviceKeyCanonical(key, "A", "B"),
            PairingEngine.deriveTrustedDeviceKeyCanonical(key, "B", "A"),
        )
        assertFalse(
            PairingEngine.deriveTrustedDeviceKeyCanonical(key, "A", "B")
                .contentEquals(PairingEngine.deriveTrustedDeviceKeyCanonical(key, "A", "C"))
        )
    }

    @Test
    fun responsesCannotBeSubstitutedForADifferentRequest() {
        val key = PairingEngine.asAesKey(ByteArray(32) { 42 })
        val aad = "sync_pull:server:client:response:first-request-nonce"
        val response = SecureTransport.encryptJson(key, aad, "archive".toByteArray())
        assertEquals(
            "archive",
            SecureTransport.decryptJson(key, aad, response).toString(Charsets.UTF_8),
        )
        assertThrows(Exception::class.java) {
            SecureTransport.decryptJson(
                key,
                "sync_pull:server:client:response:other-request-nonce",
                response,
            )
        }
    }
}
