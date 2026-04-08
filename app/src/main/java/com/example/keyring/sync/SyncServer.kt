package com.example.keyring.sync

import android.content.Context
import android.util.Base64
import com.example.keyring.data.AppPreferences
import com.example.keyring.data.PasswordEntryRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.cio.CIO
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.SecretKey

class SyncServer(
    private val appContext: Context,
    private val repository: PasswordEntryRepository,
    private val prefs: AppPreferences
) {
    data class Running(
        val port: Int,
        val engine: ApplicationEngine
    )

    @Volatile
    var currentPairCode: String? = null

    private val pending = ConcurrentHashMap<String, PendingHello>()

    private data class PendingHello(
        val clientDeviceId: String,
        val clientName: String,
        val clientSaltB64: String,
        val serverSaltB64: String
    )

    fun start(port: Int = DEFAULT_PORT): Running {
        val server = embeddedServer(CIO, host = "0.0.0.0", port = port) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            configureRoutes()
        }
        server.start(wait = false)
        return Running(port = port, engine = server)
    }

    fun stop(running: Running) {
        running.engine.stop(gracePeriodMillis = 250, timeoutMillis = 1500)
    }

    private fun Application.configureRoutes() {
        routing {
            get("/ping") { call.respond(mapOf("ok" to true, "name" to prefs.getDeviceName())) }

            post("/pair/hello") {
                val pairCode = currentPairCode
                if (pairCode.isNullOrBlank()) {
                    call.respond(HttpStatusCode.Conflict, mapOf("error" to "pairing_not_enabled"))
                    return@post
                }
                val req = call.receive<PairHelloRequest>()
                val serverSaltB64 = PairingEngine.newSaltB64()
                pending[req.clientDeviceId] = PendingHello(
                    clientDeviceId = req.clientDeviceId,
                    clientName = req.clientName,
                    clientSaltB64 = req.clientSaltB64,
                    serverSaltB64 = serverSaltB64
                )
                call.respond(
                    PairHelloResponse(
                        serverDeviceId = prefs.getDeviceId(),
                        serverName = prefs.getDeviceName(),
                        serverSaltB64 = serverSaltB64
                    )
                )
            }

            post("/pair/confirm") {
                val pairCode = currentPairCode
                if (pairCode.isNullOrBlank()) {
                    call.respond(HttpStatusCode.Conflict, mapOf("error" to "pairing_not_enabled"))
                    return@post
                }
                val req = call.receive<PairConfirmRequest>()
                val hello = pending.remove(req.clientDeviceId)
                if (hello == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "no_hello"))
                    return@post
                }
                val sessionKey: SecretKey = PairingEngine.deriveSessionKey(
                    pairCode = pairCode,
                    serverSaltB64 = hello.serverSaltB64,
                    clientSaltB64 = hello.clientSaltB64
                )
                val aad = "pair_confirm:${prefs.getDeviceId()}:${hello.clientDeviceId}"
                val plain = runCatching {
                    SecureTransport.decryptJson(sessionKey, aad, req.encrypted.toDomain())
                }.getOrNull()
                if (plain == null || plain.size < 8) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "bad_code"))
                    return@post
                }
                val trustedKey = PairingEngine.deriveTrustedDeviceKeyCanonical(
                    sessionKey = sessionKey,
                    id1 = prefs.getDeviceId(),
                    id2 = hello.clientDeviceId
                )
                PairingEngine.storeTrustedDevice(
                    prefs = prefs,
                    remoteDeviceId = hello.clientDeviceId,
                    remoteName = hello.clientName,
                    sharedKey = trustedKey
                )
                val ack = SecureTransport.encryptJson(
                    key = sessionKey,
                    aad = aad,
                    plaintext = "ok".toByteArray(Charsets.UTF_8)
                )
                call.respond(PairConfirmResponse(encrypted = EncryptedPayloadDto.fromDomain(ack)))
            }

            post("/sync/pull") {
                val req = call.receive<EncryptedPayloadDto>()
                val remoteId = call.request.headers["X-KeyRing-DeviceId"].orEmpty()
                val keyBytes = prefs.trustedDeviceKey(remoteId)
                if (remoteId.isBlank() || keyBytes == null) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "untrusted"))
                    return@post
                }
                val key = PairingEngine.asAesKey(keyBytes)
                val aad = "sync_pull:${prefs.getDeviceId()}:$remoteId"
                val plain = runCatching {
                    SecureTransport.decryptJson(key, aad, req.toDomain())
                }.getOrNull() ?: run {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "decrypt_failed"))
                    return@post
                }
                val pullReq = Json { ignoreUnknownKeys = true }.decodeFromString(
                    SyncPullRequest.serializer(),
                    plain.toString(Charsets.UTF_8)
                )
                if (pullReq.clientDeviceId != remoteId) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "device_mismatch"))
                    return@post
                }
                val entries = repository.getAllEntriesUnsorted()
                val archiveBytes = SyncArchive.exportZipBytes(entries)
                val respPlain = Json.encodeToString(
                    SyncPullResponse.serializer(),
                    SyncPullResponse(archiveB64 = Base64.encodeToString(archiveBytes, Base64.NO_WRAP))
                ).toByteArray(Charsets.UTF_8)
                val enc = SecureTransport.encryptJson(key, aad, respPlain)
                call.respond(EncryptedPayloadDto.fromDomain(enc))
            }

            post("/sync/push") {
                val req = call.receive<EncryptedPayloadDto>()
                val remoteId = call.request.headers["X-KeyRing-DeviceId"].orEmpty()
                val keyBytes = prefs.trustedDeviceKey(remoteId)
                if (remoteId.isBlank() || keyBytes == null) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "untrusted"))
                    return@post
                }
                val key = PairingEngine.asAesKey(keyBytes)
                val aad = "sync_push:${prefs.getDeviceId()}:$remoteId"
                val plain = runCatching {
                    SecureTransport.decryptJson(key, aad, req.toDomain())
                }.getOrNull() ?: run {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "decrypt_failed"))
                    return@post
                }
                val pushPlain = Json { ignoreUnknownKeys = true }.decodeFromString(
                    SyncPushPlain.serializer(),
                    plain.toString(Charsets.UTF_8)
                )
                val archiveBytes = Base64.decode(pushPlain.archiveB64, Base64.NO_WRAP)
                val parsed = SyncArchive.parseZipBytes(appContext, archiveBytes)
                val stats = MergeEngine.merge(
                    repository = repository,
                    parsed = parsed,
                    policy = prefs.getDuplicatePolicy()
                )
                val respPlain = Json.encodeToString(MergeStats.serializer(), stats).toByteArray(Charsets.UTF_8)
                val enc = SecureTransport.encryptJson(key, aad, respPlain)
                call.respond(EncryptedPayloadDto.fromDomain(enc))
            }
        }
    }

    companion object {
        const val DEFAULT_PORT = 39277
    }
}

