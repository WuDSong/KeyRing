package com.wudongsong.keyring.ui

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.wudongsong.keyring.data.AppPreferences

/**
 * 在已解锁主界面生效：开启自动锁定时，在离开应用（Activity 进入后台）或熄屏时触发 [onLock]。
 * 旋转等配置变更导致的 [Activity.onStop] 不会触发锁定。
 * 选择图片等系统操作导致的后台切换也不会触发锁定。
 */
@Composable
fun AutoLockEffect(
    appPreferences: AppPreferences,
    onLock: () -> Unit
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val externalActivity = LocalExternalActivityState.current
    val currentOnLock by androidx.compose.runtime.rememberUpdatedState(onLock)

    DisposableEffect(lifecycleOwner, context, appPreferences, externalActivity) {
        val observer = LifecycleEventObserver { _, event ->
            if (event != Lifecycle.Event.ON_STOP) return@LifecycleEventObserver
            if (!appPreferences.getAutoLockEnabled()) return@LifecycleEventObserver
            val activity = context as? Activity ?: return@LifecycleEventObserver
            if (activity.isChangingConfigurations) return@LifecycleEventObserver
            // 如果是因为启动其他Activity（如图片选择器）而进入后台，则不触发锁定
            if (externalActivity.pending) return@LifecycleEventObserver
            currentOnLock()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action != Intent.ACTION_SCREEN_OFF) return
                if (!appPreferences.getAutoLockEnabled()) return
                // 熄屏时仍然触发锁定
                currentOnLock()
            }
        }
        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(receiver, filter)
        }
        onDispose {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }
}
