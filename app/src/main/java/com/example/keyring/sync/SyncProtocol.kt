package com.example.keyring.sync

import kotlinx.serialization.Serializable

@Serializable
data class SyncPullRequest(
    val clientDeviceId: String
)

@Serializable
data class SyncPullResponse(
    /** base64(zip bytes) */
    val archiveB64: String
)

@Serializable
data class SyncPushPlain(
    val archiveB64: String
)

