package tw.rokidha.data

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import tw.rokidha.homeassistant.discovery.DiscoveredHaInstance
import tw.rokidha.homeassistant.discovery.HaDiscovery
import tw.rokidha.homeassistant.discovery.ZeroconfParser
import java.util.concurrent.Executors

/**
 * mDNS/DNS-SD discovery of `_home-assistant._tcp` via [NsdManager].
 *
 * Limitations (README "Troubleshooting"): finds nothing when the phone and Home Assistant are on
 * different VLANs/subnets, behind Wi-Fi client isolation, over most VPNs, or when the router
 * filters multicast. Manual URL entry is always available.
 */
class NsdHaDiscovery(context: Context) : HaDiscovery {
    private val nsd = context.applicationContext.getSystemService(NsdManager::class.java)
    private val executor = Executors.newSingleThreadExecutor()

    override fun discover(timeoutMs: Long): Flow<DiscoveredHaInstance> = callbackFlow {
        // Legacy resolveService() allows only one resolve at a time.
        val resolveLock = Mutex()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                launch {
                    val resolved = resolveLock.withLock { withTimeoutOrNull(RESOLVE_TIMEOUT_MS) { resolve(info) } }
                    resolved?.let { trySend(toInstance(it)) }
                }
            }
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                close(IllegalStateException("mDNS discovery could not start (error $errorCode)"))
            }
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onServiceLost(info: NsdServiceInfo) = Unit
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        launch {
            delay(timeoutMs)
            close()
        }
        awaitClose { runCatching { nsd.stopServiceDiscovery(listener) } }
    }

    private suspend fun resolve(info: NsdServiceInfo): NsdServiceInfo? {
        val result = CompletableDeferred<NsdServiceInfo?>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val cb = object : NsdManager.ServiceInfoCallback {
                override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) { result.complete(null) }
                override fun onServiceUpdated(serviceInfo: NsdServiceInfo) { result.complete(serviceInfo) }
                override fun onServiceLost() { result.complete(null) }
                override fun onServiceInfoCallbackUnregistered() { result.complete(null) }
            }
            nsd.registerServiceInfoCallback(info, executor, cb)
            try {
                return result.await()
            } finally {
                runCatching { nsd.unregisterServiceInfoCallback(cb) }
            }
        } else {
            @Suppress("DEPRECATION")
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    Log.w(TAG, "resolve failed: $errorCode")
                    result.complete(null)
                }
                override fun onServiceResolved(serviceInfo: NsdServiceInfo) { result.complete(serviceInfo) }
            })
            return result.await()
        }
    }

    private fun toInstance(info: NsdServiceInfo): DiscoveredHaInstance {
        val addresses = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            info.hostAddresses.mapNotNull { it.hostAddress }
        } else {
            @Suppress("DEPRECATION")
            listOfNotNull(info.host?.hostAddress)
        }
        val txt = info.attributes.mapValues { (_, v) -> v?.toString(Charsets.UTF_8) }
        return ZeroconfParser.parse(info.serviceName, addresses, info.port, txt)
    }

    private companion object {
        const val TAG = "NsdHaDiscovery"
        const val SERVICE_TYPE = "_home-assistant._tcp"
        const val RESOLVE_TIMEOUT_MS = 5_000L
    }
}
