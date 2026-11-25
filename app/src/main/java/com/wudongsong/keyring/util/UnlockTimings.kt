package com.wudongsong.keyring.util

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.SystemClock
import android.util.Log

/** Debug-only timings; never log passwords, identifiers, ciphertext or verifier records. */
internal object UnlockTimings {
    fun event(context: Context, stage: String) {
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            Log.d("KeyRingPerf", stage)
        }
    }

    /** Records the exception type and stack; never logs passwords or ciphertext. */
    fun failure(context: Context, stage: String, error: Throwable) {
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            Log.d("KeyRingPerf", "$stage: ${error.javaClass.simpleName}: ${error.message}", error)
        }
    }

    suspend fun <T> measure(context: Context, stage: String, block: suspend () -> T): T {
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return block()
        val start = SystemClock.elapsedRealtimeNanos()
        return try {
            block()
        } finally {
            Log.d(
                "KeyRingPerf",
                "$stage: ${(SystemClock.elapsedRealtimeNanos() - start) / 1_000_000} ms",
            )
        }
    }
}
