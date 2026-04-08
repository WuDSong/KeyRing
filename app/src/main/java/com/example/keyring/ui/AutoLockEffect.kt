package com.example.keyring.ui

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.keyring.data.AppPreferences

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
    var isLaunchingActivity by remember { mutableStateOf(false) }

    // 监听Activity启动事件，用于检测是否是因为选择图片等操作而进入后台
    DisposableEffect(context) {
        val activityLifecycleCallbacks = object : android.app.Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: android.os.Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityResumed(activity: Activity) {
                isLaunchingActivity = false
            }
            override fun onActivityPaused(activity: Activity) {
                // 当应用暂停时，检查是否是因为启动了其他Activity（如图片选择器）
                isLaunchingActivity = true
            }
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: android.os.Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        }

        val application = context.applicationContext as android.app.Application
        application.registerActivityLifecycleCallbacks(activityLifecycleCallbacks)

        onDispose {
            application.unregisterActivityLifecycleCallbacks(activityLifecycleCallbacks)
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event != Lifecycle.Event.ON_STOP) return@LifecycleEventObserver
            if (!appPreferences.getAutoLockEnabled()) return@LifecycleEventObserver
            val activity = context as? Activity ?: return@LifecycleEventObserver
            if (activity.isChangingConfigurations) return@LifecycleEventObserver
            // 如果是因为启动其他Activity（如图片选择器）而进入后台，则不触发锁定
            if (isLaunchingActivity) return@LifecycleEventObserver
            onLock()
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
                onLock()
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
