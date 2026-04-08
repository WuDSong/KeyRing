package com.example.keyring.sync

import kotlinx.serialization.Serializable

@Serializable
data class PairHelloRequest(
    val clientDeviceId: String,
    val clientName: String,
    val clientSaltB64: String
)

@Serializable
data class PairHelloResponse(
    val serverDeviceId: String,
    val serverName: String,
    val serverSaltB64: String
)

@Serializable
data class PairConfirmRequest(
    val clientDeviceId: String,
    val encrypted: EncryptedPayloadDto
)

@Serializable
data class PairConfirmResponse(
    val encrypted: EncryptedPayloadDto
)

@Serializable
data class EncryptedPayloadDto(
    val nonceB64: String,
    val ciphertextB64: String
) {
    fun toDomain(): EncryptedPayload = EncryptedPayload(nonceB64 = nonceB64, ciphertextB64 = ciphertextB64)

    companion object {
        fun fromDomain(p: EncryptedPayload): EncryptedPayloadDto =
            EncryptedPayloadDto(nonceB64 = p.nonceB64, ciphertextB64 = p.ciphertextB64)
    }
}

