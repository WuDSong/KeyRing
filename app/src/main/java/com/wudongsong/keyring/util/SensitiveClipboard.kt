package com.wudongsong.keyring.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.os.SystemClock
import java.util.UUID

/** Application-owned timeout survives navigation and locking; never clears someone else's clip. */
class SensitiveClipboard(context: Context) {
    private val clipboard = context.getSystemService(ClipboardManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var token: String? = null
    private var deadline = 0L
    private val clear = Runnable { clearIfExpired() }

    fun copy(label: String, text: String, autoClear: Boolean) {
        handler.removeCallbacks(clear)
        val id = UUID.randomUUID().toString()
        val clip = ClipData.newPlainText(label, text)
        clip.description.extras =
            PersistableBundle().apply {
                putBoolean("android.content.extra.IS_SENSITIVE", true)
                putString("keyring_owner", id)
            }
        clipboard.setPrimaryClip(clip)
        token = if (autoClear) id else null
        deadline = SystemClock.elapsedRealtime() + 30_000
        if (autoClear) handler.postDelayed(clear, 30_000)
    }

    fun clearIfExpired() {
        val owned = token ?: return
        if (SystemClock.elapsedRealtime() < deadline) return
        // Some Android versions deny clipboard reads in the background. Retry on resume.
        runCatching {
            val description = clipboard.primaryClipDescription ?: return
            if (description.extras?.getString("keyring_owner") == owned)
                clipboard.clearPrimaryClip()
            token = null
        }
    }
}
