package nz.musicbox.remote.data

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import nz.musicbox.mpd.Endpoint
import java.io.IOException
import kotlin.coroutines.resume

/** A server found on the network, with the name it advertises (e.g. "Music Box"). */
data class FoundServer(val name: String, val endpoint: Endpoint)

/** Finds servers on the local network with Android's built-in mDNS / DNS-SD (no Google services). */
class MdnsDiscovery(context: Context) {
    private val nsd = context.getSystemService(NsdManager::class.java)

    /** Emits each service of [serviceType] found within [timeoutMs], resolved to an address and port. */
    fun discover(serviceType: String, timeoutMs: Long): Flow<FoundServer> = channelFlow {
        val found = Channel<NsdServiceInfo>(Channel.UNLIMITED)
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                found.trySend(info)
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                found.close(IOException("Network search failed (code $errorCode)"))
            }

            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        nsd.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
        try {
            withTimeoutOrNull(timeoutMs) {
                for (info in found) {
                    resolve(info)?.let { send(it) }
                }
            }
        } finally {
            try {
                nsd.stopServiceDiscovery(listener)
            } catch (_: IllegalArgumentException) {
                // Discovery never started.
            }
        }
    }

    // resolveService is deprecated from API 34 but still works, and is the only option on API 31-33.
    @Suppress("DEPRECATION")
    private suspend fun resolve(info: NsdServiceInfo): FoundServer? = suspendCancellableCoroutine { cont ->
        nsd.resolveService(
            info,
            object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                    if (cont.isActive) cont.resume(null)
                }

                override fun onServiceResolved(info: NsdServiceInfo) {
                    val host = info.host?.hostAddress
                    if (cont.isActive) cont.resume(host?.let { FoundServer(info.serviceName, Endpoint(it, info.port)) })
                }
            },
        )
    }
}
