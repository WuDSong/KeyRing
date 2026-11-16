package com.wudongsong.keyring.sync

import android.content.Context
import android.util.Base64
import com.wudongsong.keyring.data.AppPreferences
import com.wudongsong.keyring.data.PasswordEntryRepository
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.core.readBytes
import io.ktor.utils.io.readRemaining
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.SecretKey
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private suspend inline fun <reified T> ApplicationCall.receiveLimited(limit: Int): T {
    val bytes = receiveChannel().readRemaining(limit + 1L).readBytes()
    if (bytes.size > limit)
        throw io.ktor.server.plugins.BadRequestException("Sync request exceeds size limit")
    return try {
        Json { ignoreUnknownKeys = true }.decodeFromString<T>(bytes.toString(Charsets.UTF_8))
    } catch (e: Exception) {
        throw io.ktor.server.plugins.BadRequestException("Invalid sync request", e)
    }
}

class SyncServer(
    private val appContext: Context,
    private val repository: PasswordEntryRepository,
    private val prefs: AppPreferences,
) {
    data class Running(val port: Int, val engine: ApplicationEngine)

    private val pairLock = Any()
    @Volatile private var pairDeadline = 0L
    private val pairAttempts = java.util.concurrent.atomic.AtomicInteger()
    private val requestGuard = SyncRequestGuard()
    @Volatile
    var currentPairCode: String? = null
        set(value) {
            synchronized(pairLock) {
                field = value
                pairDeadline = System.currentTimeMillis() + 300_000
                pairAttempts.set(0)
                pending.clear()
            }
        }

    private fun activePairCode(): String? =
        currentPairCode?.takeIf {
            System.currentTimeMillis() < pairDeadline && pairAttempts.get() < 5
        }

    private val pending = ConcurrentHashMap<String, PendingHello>()

    private data class PendingHello(
        val clientDeviceId: String,
        val clientName: String,
        val clientSaltB64: String,
        val serverSaltB64: String,
        val createdAt: Long = System.currentTimeMillis(),
    )

    fun start(port: Int = DEFAULT_PORT): Running {
        val server =
            embeddedServer(CIO, host = "0.0.0.0", port = port) {
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
            get("/ping") {
                call.respond(SyncPingResponse(true, prefs.getDeviceName(), protocolVersion = 2))
            }

            post("/pair/hello") {
                val pairCode = activePairCode()
                if (pairCode.isNullOrBlank()) {
                    call.respond(HttpStatusCode.Conflict, mapOf("error" to "pairing_not_enabled"))
                    return@post
                }
                pending.entries.removeAll {
                    System.currentTimeMillis() - it.value.createdAt > 60_000
                }
                if (pending.size >= 32) {
                    call.respond(HttpStatusCode.TooManyRequests)
                    return@post
                }
                val req = call.receiveLimited<PairHelloRequest>(4096)
                if (pairCode != activePairCode()) {
                    call.respond(HttpStatusCode.Conflict)
                    return@post
                }
                if (
                    req.clientDeviceId.length !in 1..128 ||
                        req.clientName.length > 128 ||
                        runCatching { Base64.decode(req.clientSaltB64, Base64.NO_WRAP).size }
                            .getOrNull() != 16
                ) {
                    call.respond(HttpStatusCode.BadRequest)
                    return@post
                }
                val serverSaltB64 = PairingEngine.newSaltB64()
                pending[req.clientDeviceId] =
                    PendingHello(
                        clientDeviceId = req.clientDeviceId,
                        clientName = req.clientName,
                        clientSaltB64 = req.clientSaltB64,
                        serverSaltB64 = serverSaltB64,
                    )
                call.respond(
                    PairHelloResponse(
                        serverDeviceId = prefs.getDeviceId(),
                        serverName = prefs.getDeviceName(),
                        serverSaltB64 = serverSaltB64,
                        protocolVersion = 2,
                    )
                )
            }

            post("/pair/confirm") {
                val pairCode = activePairCode()
                if (pairCode.isNullOrBlank()) {
                    call.respond(HttpStatusCode.Conflict, mapOf("error" to "pairing_not_enabled"))
                    return@post
                }
                val req = call.receiveLimited<PairConfirmRequest>(4096)
                if (pairCode != activePairCode()) {
                    call.respond(HttpStatusCode.Conflict)
                    return@post
                }
                val hello = pending.remove(req.clientDeviceId)
                if (hello == null || System.currentTimeMillis() - hello.createdAt > 60_000) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "no_hello"))
                    return@post
                }
                if (pairAttempts.incrementAndGet() > 5) {
                    call.respond(HttpStatusCode.TooManyRequests)
                    return@post
                }
                val sessionKey: SecretKey =
                    PairingEngine.deriveSessionKey(
                        pairCode = pairCode,
                        serverSaltB64 = hello.serverSaltB64,
                        clientSaltB64 = hello.clientSaltB64,
                    )
                val aad = "pair_confirm:${prefs.getDeviceId()}:${hello.clientDeviceId}"
                val plain =
                    runCatching {
                            SecureTransport.decryptJson(sessionKey, aad, req.encrypted.toDomain())
                        }
                        .getOrNull()
                if (plain == null || plain.size < 8) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "bad_code"))
                    return@post
                }
                val trustedKey =
                    PairingEngine.deriveTrustedDeviceKeyCanonical(
                        sessionKey = sessionKey,
                        id1 = prefs.getDeviceId(),
                        id2 = hello.clientDeviceId,
                    )
                val accepted =
                    synchronized(pairLock) {
                        if (
                            currentPairCode != pairCode ||
                                System.currentTimeMillis() >= pairDeadline
                        )
                            false
                        else {
                            PairingEngine.storeTrustedDevice(
                                prefs,
                                hello.clientDeviceId,
                                hello.clientName,
                                trustedKey,
                            )
                            currentPairCode = null
                            true
                        }
                    }
                if (!accepted) {
                    call.respond(HttpStatusCode.Conflict)
                    return@post
                }
                val ack =
                    SecureTransport.encryptJson(
                        key = sessionKey,
                        aad = aad,
                        plaintext = "ok".toByteArray(Charsets.UTF_8),
                    )
                call.respond(PairConfirmResponse(encrypted = EncryptedPayloadDto.fromDomain(ack)))
            }

            post("/sync/pull") {
                val remoteId = call.request.headers["X-KeyRing-DeviceId"].orEmpty()
                val keyBytes = prefs.trustedDeviceKey(remoteId)
                if (remoteId.isBlank() || keyBytes == null) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "untrusted"))
                    return@post
                }
                val req = call.receiveLimited<EncryptedPayloadDto>(20 * 1024 * 1024)
                val key = PairingEngine.asAesKey(keyBytes)
                val aad = "sync_pull:${prefs.getDeviceId()}:$remoteId"
                val plain =
                    runCatching { SecureTransport.decryptJson(key, aad, req.toDomain()) }
                        .getOrNull()
                        ?: run {
                            call.respond(
                                HttpStatusCode.Unauthorized,
                                mapOf("error" to "decrypt_failed"),
                            )
                            return@post
                        }
                val pullReq =
                    Json { ignoreUnknownKeys = true }
                        .decodeFromString(
                            SyncPullRequest.serializer(),
                            plain.toString(Charsets.UTF_8),
                        )
                if (pullReq.clientDeviceId != remoteId) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "device_mismatch"))
                    return@post
                }
                if (
                    !requestGuard.accept(
                        remoteId,
                        req.nonceB64,
                        pullReq.issuedAt,
                        System.currentTimeMillis(),
                    )
                ) {
                    call.respond(
                        HttpStatusCode.Conflict,
                        mapOf("error" to "stale_or_replayed_request"),
                    )
                    return@post
                }
                val entries = repository.getAllEntriesUnsorted()
                val archiveBytes = SyncArchive.exportZipBytes(entries)
                val respPlain =
                    Json.encodeToString(
                            SyncPullResponse.serializer(),
                            SyncPullResponse(
                                archiveB64 = Base64.encodeToString(archiveBytes, Base64.NO_WRAP)
                            ),
                        )
                        .toByteArray(Charsets.UTF_8)
                val enc =
                    SecureTransport.encryptJson(key, "$aad:response:${req.nonceB64}", respPlain)
                call.respond(EncryptedPayloadDto.fromDomain(enc))
            }

            post("/sync/push") {
                val remoteId = call.request.headers["X-KeyRing-DeviceId"].orEmpty()
                val keyBytes = prefs.trustedDeviceKey(remoteId)
                if (remoteId.isBlank() || keyBytes == null) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "untrusted"))
                    return@post
                }
                val req = call.receiveLimited<EncryptedPayloadDto>(20 * 1024 * 1024)
                val key = PairingEngine.asAesKey(keyBytes)
                val aad = "sync_push:${prefs.getDeviceId()}:$remoteId"
                val plain =
                    runCatching { SecureTransport.decryptJson(key, aad, req.toDomain()) }
                        .getOrNull()
                        ?: run {
                            call.respond(
                                HttpStatusCode.Unauthorized,
                                mapOf("error" to "decrypt_failed"),
                            )
                            return@post
                        }
                val pushPlain =
                    Json { ignoreUnknownKeys = true }
                        .decodeFromString(
                            SyncPushPlain.serializer(),
                            plain.toString(Charsets.UTF_8),
                        )
                if (
                    !requestGuard.accept(
                        remoteId,
                        req.nonceB64,
                        pushPlain.issuedAt,
                        System.currentTimeMillis(),
                    )
                ) {
                    call.respond(
                        HttpStatusCode.Conflict,
                        mapOf("error" to "stale_or_replayed_request"),
                    )
                    return@post
                }
                val archiveBytes = Base64.decode(pushPlain.archiveB64, Base64.NO_WRAP)
                val parsed = SyncArchive.parseZipBytes(appContext, archiveBytes)
                val stats =
                    MergeEngine.merge(
                        repository = repository,
                        parsed = parsed,
                        policy = prefs.getDuplicatePolicy(),
                    )
                val respPlain =
                    Json.encodeToString(MergeStats.serializer(), stats).toByteArray(Charsets.UTF_8)
                val enc =
                    SecureTransport.encryptJson(key, "$aad:response:${req.nonceB64}", respPlain)
                call.respond(EncryptedPayloadDto.fromDomain(enc))
            }
        }
    }

    companion object {
        const val DEFAULT_PORT = 39277
    }
}
