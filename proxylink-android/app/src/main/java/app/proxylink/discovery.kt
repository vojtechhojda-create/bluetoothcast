package app.proxylink

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

suspend fun discoverhost(ctx: Context, pcname: String, timeoutms: Long = 8000): String? {
    val nsd = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
    var active: NsdManager.DiscoveryListener? = null
    return try {
        withTimeoutOrNull(timeoutms) {
            suspendCancellableCoroutine { cont ->
                var resolving = false
                val listener = object : NsdManager.DiscoveryListener {
                    override fun onDiscoveryStarted(t: String) {}
                    override fun onDiscoveryStopped(t: String) {}
                    override fun onStartDiscoveryFailed(t: String, e: Int) { if (cont.isActive) cont.resume(null) }
                    override fun onStopDiscoveryFailed(t: String, e: Int) {}
                    override fun onServiceLost(i: NsdServiceInfo) {}
                    override fun onServiceFound(info: NsdServiceInfo) {
                        if (resolving || !info.serviceName.startsWith("proxylink-$pcname", ignoreCase = true)) return
                        resolving = true
                        @Suppress("DEPRECATION")
                        nsd.resolveService(info, object : NsdManager.ResolveListener {
                            override fun onResolveFailed(i: NsdServiceInfo, e: Int) { resolving = false }
                            override fun onServiceResolved(i: NsdServiceInfo) {
                                @Suppress("DEPRECATION")
                                val host = i.host?.hostAddress
                                if (host != null && cont.isActive) cont.resume(host)
                            }
                        })
                    }
                }
                active = listener
                nsd.discoverServices("_proxylink._tcp.", NsdManager.PROTOCOL_DNS_SD, listener)
            }
        }
    } finally {
        active?.let { runCatching { nsd.stopServiceDiscovery(it) } }
    }
}
