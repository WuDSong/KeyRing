package com.example.keyring.sync

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

/**
 * 局域网内通过 NSD 广播/发现 KeyRing 同步服务（`_keyring._tcp`）。
 */
object LanSyncNsd {
    private const val TAG = "LanSyncNsd"
    const val SERVICE_TYPE = "_keyring._tcp."

    data class ResolvedService(
        val serviceName: String,
        val host: String,
        val port: Int
    )

    fun register(
        context: Context,
        port: Int,
        serviceLabel: String,
        onRegistered: (() -> Unit)? = null,
        onFailed: ((Int) -> Unit)? = null
    ): NsdManager.RegistrationListener {
        val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
        val safeName = serviceLabel.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(63)
        @Suppress("DEPRECATION")
        val info = NsdServiceInfo().apply {
            serviceName = safeName
            serviceType = SERVICE_TYPE
            setPort(port)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(NsdServiceInfo: NsdServiceInfo) {
                Log.d(TAG, "NSD registered: ${NsdServiceInfo.serviceName}")
                onRegistered?.invoke()
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                Log.w(TAG, "NSD registration failed: $errorCode")
                onFailed?.invoke(errorCode)
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo?) {
                Log.d(TAG, "NSD unregistered")
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                Log.w(TAG, "NSD unregister failed: $errorCode")
            }
        }
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        return listener
    }

    fun unregister(context: Context, listener: NsdManager.RegistrationListener) {
        val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
        runCatching { nsd.unregisterService(listener) }
    }

    fun startDiscovery(
        context: Context,
        onResolved: (ResolvedService) -> Unit,
        onLost: (String) -> Unit
    ): NsdManager.DiscoveryListener {
        val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String?) {}
            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                Log.w(TAG, "discovery start failed: $errorCode")
            }

            override fun onDiscoveryStopped(serviceType: String?) {}

            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {
                Log.w(TAG, "discovery stop failed: $errorCode")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                        Log.w(TAG, "resolve failed: $errorCode")
                    }

                    override fun onServiceResolved(resolved: NsdServiceInfo) {
                        val host = resolved.host?.hostAddress ?: return
                        onResolved(
                            ResolvedService(
                                serviceName = resolved.serviceName ?: "",
                                host = host,
                                port = resolved.port
                            )
                        )
                    }
                })
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                onLost(serviceInfo.serviceName ?: "")
            }
        }
        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        return listener
    }

    fun stopDiscovery(context: Context, listener: NsdManager.DiscoveryListener) {
        val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
        runCatching { nsd.stopServiceDiscovery(listener) }
    }
}
