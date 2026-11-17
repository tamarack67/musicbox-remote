package nz.musicbox.remote

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import nz.musicbox.mpd.ConnectionState
import nz.musicbox.mpd.MpdClient
import nz.musicbox.remote.data.AppEndpointResolver
import nz.musicbox.remote.data.AppPreferences
import nz.musicbox.remote.data.CoverArtRepository
import nz.musicbox.remote.data.MdnsDiscovery
import nz.musicbox.remote.data.PlayerRepository
import nz.musicbox.remote.data.PlaylistRepository
import nz.musicbox.remote.data.QueueRepository
import nz.musicbox.remote.data.ServerSettings
import nz.musicbox.remote.data.library.LibraryDatabase
import nz.musicbox.remote.data.library.LibraryRepository
import nz.musicbox.remote.playback.PlaybackService

/** Manual dependency wiring: one instance of each for the whole app. */
class AppContainer(context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings = ServerSettings(context)
    val preferences = AppPreferences(context)
    val mdns = MdnsDiscovery(context)
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

    private val _appVisible = MutableStateFlow(false)

    /** True while any of the app's screens is on screen. */
    val appVisible: StateFlow<Boolean> = _appVisible.asStateFlow()

    /** True while the lock-screen / notification controls are showing (see [PlaybackService]). */
    val controlsShowing = MutableStateFlow(false)

    internal fun setAppVisible(visible: Boolean) {
        _appVisible.value = visible
    }
}

class MusicBoxApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        val c = AppContainer(this)
        container = c

        val process = ProcessLifecycleOwner.get().lifecycle
        process.addObserver(
            LifecycleEventObserver { _, _ -> c.setAppVisible(process.currentState.isAtLeast(Lifecycle.State.STARTED)) },
        )

        // Stay connected while the app is on screen or its playback controls are showing;
        // otherwise disconnect, so nothing runs in the background.
        c.appScope.launch {
            combine(c.appVisible, c.controlsShowing) { visible, controls -> visible || controls }
                .distinctUntilChanged()
                .collectLatest { needed -> if (needed) c.client.run() }
        }

        // Show the lock-screen and notification controls once something is loaded on the
        // Music Box. A foreground service can only be started while the app is on screen.
        c.appScope.launch {
            combine(c.appVisible, c.player.state, c.client.state) { visible, state, connection ->
                visible && connection is ConnectionState.Connected && state?.song != null
            }
                .distinctUntilChanged()
                .collect { show ->
                    if (show && !c.controlsShowing.value) {
                        try {
                            PlaybackService.start(this@MusicBoxApp)
                        } catch (e: IllegalStateException) {
                            // The app went to the background in the meantime.
                            Log.w("MusicBoxApp", "Couldn't start playback controls: ${e.message}")
                        }
                    }
                }
        }

        // When Wi-Fi comes back, retry straight away instead of waiting out the backoff.
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    c.client.retryNow()
                }
            },
        )
    }
}
