package nz.musicbox.remote

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import nz.musicbox.remote.ui.AppShell
import nz.musicbox.remote.ui.theme.MusicBoxTheme

class MainActivity : ComponentActivity() {
    private val container get() = (application as MusicBoxApp).container

    private val askPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        // Connecting may have failed for want of the local-network permission.
        container.client.retryNow()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MusicBoxTheme {
                AppShell(container)
            }
        }
        if (savedInstanceState == null) askForPermissions()
    }

    /**
     * Android 17 asks before an app may reach devices on the home network, and Android 13
     * before it shows notifications. Older phones (like Android 12) ask for neither.
     */
    private fun askForPermissions() {
        val wanted = buildList {
            if (Build.VERSION.SDK_INT >= 37) add(Manifest.permission.ACCESS_LOCAL_NETWORK)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED &&
                // Ask once for notifications; the network one is essential, so ask until granted.
                (it == Manifest.permission.ACCESS_LOCAL_NETWORK || container.preferences.firstTimeAsking(it))
        }
        if (wanted.isNotEmpty()) askPermissions.launch(wanted.toTypedArray())
    }
}
