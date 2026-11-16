package nz.musicbox.remote.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import nz.musicbox.remote.AppContainer

private object Routes {
    const val NOW_PLAYING = "now_playing"
    const val SETTINGS = "settings"
}

@Composable
fun MusicBoxNavHost(container: AppContainer) {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = Routes.NOW_PLAYING) {
        composable(Routes.NOW_PLAYING) {
            NowPlayingScreen(
                player = container.player,
                client = container.client,
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                settings = container.settings,
                client = container.client,
                onBack = { nav.popBackStack() },
            )
        }
    }
}
