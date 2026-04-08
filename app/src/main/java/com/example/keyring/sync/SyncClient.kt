package com.example.keyring.sync

import android.util.Base64
import com.example.keyring.data.AppPreferences
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class SyncClient(private val prefs: AppPreferences) {
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }

    suspend fun pairHello(baseUrl: String, clientName: String, clientSaltB64: String): PairHelloResponse {
        return client.post("$baseUrl/pair/hello") {
            contentType(ContentType.Application.Json)
            setBody(
                PairHelloRequest(
                    clientDeviceId = prefs.getDeviceId(),
                    clientName = clientName,
                    clientSaltB64 = clientSaltB64
                )
            )
        }.body()
    }

    suspend fun pairConfirm(
        baseUrl: String,
        remoteServerDeviceId: String,
        remoteServerName: String,
        pairCode: String,
        serverSaltB64: String,
        clientSaltB64: String
    ): Boolean {
        val sessionKey = PairingEngine.deriveSessionKey(pairCode, serverSaltB64, clientSaltB64)
        val aad = "pair_confirm:$remoteServerDeviceId:${prefs.getDeviceId()}"
        val encrypted = SecureTransport.encryptJson(sessionKey, aad, PairingEngine.makeConfirmPlaintext())
        val resp: PairConfirmResponse = client.post("$baseUrl/pair/confirm") {
            contentType(ContentType.Application.Json)
            setBody(
                PairConfirmRequest(
                    clientDeviceId = prefs.getDeviceId(),
                    encrypted = EncryptedPayloadDto.fromDomain(encrypted)
                )
            )
        }.body()

        val ack = runCatching { SecureTransport.decryptJson(sessionKey, aad, resp.encrypted.toDomain()) }.getOrNull()
            ?: return false
        if (ack.toString(Charsets.UTF_8) != "ok") return false

        val trustedKey = PairingEngine.deriveTrustedDeviceKeyCanonical(
            sessionKey = sessionKey,
            id1 = prefs.getDeviceId(),
            id2 = remoteServerDeviceId
        )
        PairingEngine.storeTrustedDevice(
            prefs,
            remoteServerDeviceId,
            remoteName = remoteServerName.ifBlank { "Remote" },
            sharedKey = trustedKey
        )
        return true
    }

    suspend fun pullArchive(baseUrl: String, remoteDeviceId: String): ByteArray {
        val keyBytes = prefs.trustedDeviceKey(remoteDeviceId) ?: error("untrusted")
        val key = PairingEngine.asAesKey(keyBytes)
        val aad = "sync_pull:$remoteDeviceId:${prefs.getDeviceId()}"
        val plain = Json.encodeToString(
            SyncPullRequest.serializer(),
            SyncPullRequest(clientDeviceId = prefs.getDeviceId())
        ).toByteArray(Charsets.UTF_8)
        val encryptedReq = SecureTransport.encryptJson(key, aad, plain)
        val resp: EncryptedPayloadDto = client.post("$baseUrl/sync/pull") {
            contentType(ContentType.Application.Json)
            header("X-KeyRing-DeviceId", prefs.getDeviceId())
            setBody(EncryptedPayloadDto.fromDomain(encryptedReq))
        }.body()
        val respPlain = SecureTransport.decryptJson(key, aad, resp.toDomain())
        val pullResp = Json { ignoreUnknownKeys = true }.decodeFromString(
            SyncPullResponse.serializer(),
            respPlain.toString(Charsets.UTF_8)
        )
        return Base64.decode(pullResp.archiveB64, Base64.NO_WRAP)
    }

    suspend fun pushArchive(baseUrl: String, remoteDeviceId: String, archiveBytes: ByteArray): MergeStats {
        val keyBytes = prefs.trustedDeviceKey(remoteDeviceId) ?: error("untrusted")
        val key = PairingEngine.asAesKey(keyBytes)
        val aad = "sync_push:$remoteDeviceId:${prefs.getDeviceId()}"
        val plain = Json.encodeToString(
            SyncPushPlain.serializer(),
            SyncPushPlain(archiveB64 = Base64.encodeToString(archiveBytes, Base64.NO_WRAP))
        ).toByteArray(Charsets.UTF_8)
        val encryptedReq = SecureTransport.encryptJson(key, aad, plain)
        val resp: EncryptedPayloadDto = client.post("$baseUrl/sync/push") {
            contentType(ContentType.Application.Json)
            header("X-KeyRing-DeviceId", prefs.getDeviceId())
            setBody(EncryptedPayloadDto.fromDomain(encryptedReq))
        }.body()
        val respPlain = SecureTransport.decryptJson(key, aad, resp.toDomain())
        return Json.decodeFromString(MergeStats.serializer(), respPlain.toString(Charsets.UTF_8))
    }

    fun close() {
        client.close()
    }
}

