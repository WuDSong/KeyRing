package com.wudongsong.keyring.sync

import kotlinx.serialization.Serializable

@Serializable data class SyncPullRequest(val clientDeviceId: String, val issuedAt: Long = 0)

@Serializable
data class SyncPullResponse(
    /** base64(zip bytes) */
    val archiveB64: String
)

@Serializable data class SyncPushPlain(val archiveB64: String, val issuedAt: Long = 0)

@Serializable
data class SyncPingResponse(val ok: Boolean, val name: String, val protocolVersion: Int = 1)
