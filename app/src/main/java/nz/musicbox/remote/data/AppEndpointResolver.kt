package nz.musicbox.remote.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import nz.musicbox.mpd.Endpoint
import nz.musicbox.mpd.EndpointResolver

/** Connection order: last known (or user-entered) address, then the default hostname, then an mDNS search. */
class AppEndpointResolver(
    private val settings: ServerSettings,
    private val mdns: MdnsDiscovery,
) : EndpointResolver {
    override fun candidates(): Flow<Endpoint> = flow {
        val saved = settings.server.value
        if (saved != null) emit(saved)
        emit(Endpoint(ServerSettings.DEFAULT_HOSTNAME, saved?.port ?: ServerSettings.DEFAULT_PORT))
        emitAll(mdns.discover(ServerSettings.MDNS_SERVICE_TYPE, timeoutMs = 6_000).map { it.endpoint })
    }
}
