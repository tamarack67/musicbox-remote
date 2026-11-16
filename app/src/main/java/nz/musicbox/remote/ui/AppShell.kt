package nz.musicbox.remote.ui

import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import nz.musicbox.remote.AppContainer
import nz.musicbox.remote.R
import nz.musicbox.remote.ui.library.AlbumScreen
import nz.musicbox.remote.ui.library.ArtistScreen
import nz.musicbox.remote.ui.library.FolderScreen
import nz.musicbox.remote.ui.library.LibraryScreen
import nz.musicbox.remote.ui.library.SearchScreen
import nz.musicbox.remote.ui.queue.PlaylistScreen
import nz.musicbox.remote.ui.queue.PlaylistsScreen
import nz.musicbox.remote.ui.queue.QueueScreen

private object Routes {
    const val LIBRARY = "library"
    const val SEARCH = "search"
    const val QUEUE = "queue"
    const val PLAYLISTS = "playlists"
    const val PLAYLIST = "playlist/{name}"
    const val NOW_PLAYING = "now_playing"
    const val SETTINGS = "settings"
    const val ARTIST = "artist/{name}"
    const val ALBUM = "album/{directory}"
    const val FOLDER = "folder/{path}"
}

private val TABS = listOf(
    Triple(Routes.LIBRARY, R.drawable.ic_library_music, "Library"),
    Triple(Routes.SEARCH, R.drawable.ic_search, "Search"),
    Triple(Routes.QUEUE, R.drawable.ic_queue_music, "Queue"),
    Triple(Routes.PLAYLISTS, R.drawable.ic_playlist_play, "Playlists"),
)

/** Navigation shared by all screens. */
class Navigator(private val nav: NavController) {
    fun artist(name: String) = nav.navigate("artist/${Uri.encode(name)}")
    fun album(directory: String) = nav.navigate("album/${Uri.encode(directory)}")
    fun folder(path: String) = nav.navigate("folder/${Uri.encode(path)}")
    fun playlist(name: String) = nav.navigate("playlist/${Uri.encode(name)}")
    fun nowPlaying() = nav.navigate(Routes.NOW_PLAYING) { launchSingleTop = true }
    fun settings() = nav.navigate(Routes.SETTINGS) { launchSingleTop = true }
    fun back() = nav.popBackStack()
}

/** Top-level layout: screens above a mini player and the bottom navigation bar. */
@Composable
fun AppShell(container: AppContainer) {
    val nav = rememberNavController()
    val go = remember(nav) { Navigator(nav) }
    val backStack by nav.currentBackStackEntryAsState()
    val onNowPlaying = backStack?.destination?.route == Routes.NOW_PLAYING
    var tab by rememberSaveable { mutableStateOf(Routes.LIBRARY) }

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        container.player.messages.collect { snackbar.showSnackbar(it) }
    }

    fun selectTab(route: String) {
        if (tab == route) {
            // Already on this tab: go back to its top.
            nav.popBackStack(route, inclusive = false)
        } else {
            tab = route
            nav.navigate(route) {
                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (!onNowPlaying) {
                Column {
                    MiniPlayer(container.player, container.client, onOpen = go::nowPlaying)
                    NavigationBar {
                        for ((route, icon, label) in TABS) {
                            NavigationBarItem(
                                selected = tab == route,
                                onClick = { selectTab(route) },
                                icon = { Icon(painterResource(icon), contentDescription = null) },
                                label = { Text(label) },
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = Routes.LIBRARY,
            modifier = Modifier.padding(padding).consumeWindowInsets(padding),
        ) {
            composable(Routes.LIBRARY) {
                LibraryScreen(container, go)
            }
            composable(Routes.SEARCH) {
                SearchScreen(container, go)
            }
            composable(Routes.QUEUE) {
                QueueScreen(container, go)
            }
            composable(Routes.PLAYLISTS) {
                PlaylistsScreen(container, go)
            }
            composable(Routes.PLAYLIST) { entry ->
                PlaylistScreen(container, go, name = entry.arguments?.getString("name").orEmpty())
            }
            composable(Routes.ARTIST) { entry ->
                ArtistScreen(container, go, name = entry.arguments?.getString("name").orEmpty())
            }
            composable(Routes.ALBUM) { entry ->
                AlbumScreen(container, go, directory = entry.arguments?.getString("directory").orEmpty())
            }
            composable(Routes.FOLDER) { entry ->
                FolderScreen(container, go, path = entry.arguments?.getString("path").orEmpty())
            }
            composable(Routes.NOW_PLAYING) {
                NowPlayingScreen(
                    player = container.player,
                    client = container.client,
                    onClose = go::back,
                    onOpenSettings = go::settings,
                )
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(settings = container.settings, client = container.client, onBack = go::back)
            }
        }
    }
}
