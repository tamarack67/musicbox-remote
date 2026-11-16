package nz.musicbox.remote

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import nz.musicbox.remote.ui.MusicBoxNavHost
import nz.musicbox.remote.ui.theme.MusicBoxTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as MusicBoxApp).container
        setContent {
            MusicBoxTheme {
                MusicBoxNavHost(container)
            }
        }
    }
}
