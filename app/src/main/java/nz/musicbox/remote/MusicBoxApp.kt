package nz.musicbox.remote

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import nz.musicbox.mpd.MpdClient
import nz.musicbox.remote.data.AppEndpointResolver
import nz.musicbox.remote.data.CoverArtRepository
import nz.musicbox.remote.data.MdnsDiscovery
import nz.musicbox.remote.data.PlayerRepository
import nz.musicbox.remote.data.PlaylistRepository
import nz.musicbox.remote.data.QueueRepository
import nz.musicbox.remote.data.ServerSettings
import nz.musicbox.remote.data.library.LibraryDatabase
import nz.musicbox.remote.data.library.LibraryRepository

/** Manual dependency wiring: one instance of each for the whole app. */
class AppContainer(context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings = ServerSettings(context)
    private val mdns = MdnsDiscovery(context)
    val client = MpdClient(
        resolver = AppEndpointResolver(settings, mdns),
        onConnected = { settings.save(it) },
    )
    val player = PlayerRepository(client, appScope)
    val covers = CoverArtRepository(client, context)
    val library = LibraryRepository(
        client,
        LibraryDatabase.create(context),
        appScope,
        // Covers may have changed too (e.g. after retagging): fetch them afresh.
        onLibraryChanged = covers::clear,
    )
    val queue = QueueRepository(client, player, appScope)
    val playlists = PlaylistRepository(client, player, appScope)
}

class MusicBoxApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)

        // Stay connected while the app is on screen; disconnect shortly after it goes to the background.
        val process = ProcessLifecycleOwner.get()
        process.lifecycleScope.launch {
            process.repeatOnLifecycle(Lifecycle.State.STARTED) {
                container.client.run()
            }
        }

        // When Wi-Fi comes back, retry straight away instead of waiting out the backoff.
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    container.client.retryNow()
                }
            },
        )
    }
}
