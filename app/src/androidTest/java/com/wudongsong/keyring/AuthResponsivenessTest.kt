package com.wudongsong.keyring

import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import androidx.test.platform.app.InstrumentationRegistry
import com.wudongsong.keyring.data.BiometricPasswordVault
import com.wudongsong.keyring.data.PasswordStore
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Test

class AuthResponsivenessTest {
    @Test
    fun firstVaultReadDoesNotBlockUiEvents() = assertUiRemainsResponsive { context ->
        BiometricPasswordVault(context).hasStoredCipherText()
    }

    @Test
    fun firstMasterPasswordCheckDoesNotBlockUiEvents() = assertUiRemainsResponsive { context ->
        PasswordStore(context).isPasswordSet()
    }

    /**
     * Hold first preference access until a UI event can run, without timing-based frame assertions.
     */
    private fun assertUiRemainsResponsive(read: suspend (Context) -> Boolean) = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "responsiveness_test_${UUID.randomUUID()}_"
        val entered = CompletableDeferred<Unit>()
        val released = CountDownLatch(1)
        val context =
            object : ContextWrapper(base) {
                override fun getApplicationContext(): Context = this

                override fun getSharedPreferences(
                    name: String,
                    mode: Int,
                ): android.content.SharedPreferences {
                    check(Looper.myLooper() != Looper.getMainLooper()) {
                        "Preferences accessed on UI thread"
                    }
                    entered.complete(Unit)
                    check(released.await(3, TimeUnit.SECONDS)) {
                        "UI event could not run while loading preferences"
                    }
                    return base.getSharedPreferences(prefix + name, mode)
                }
            }
        try {
            withContext(Dispatchers.Main.immediate) {
                val result = async { read(context) }
                withTimeout(3_000) { entered.await() }
                val uiEvent = CompletableDeferred<Unit>()
                Handler(Looper.getMainLooper()).post {
                    released.countDown()
                    uiEvent.complete(Unit)
                }
                withTimeout(3_000) { uiEvent.await() }
                assertFalse(result.await())
            }
        } finally {
            released.countDown()
        }
    }
}
