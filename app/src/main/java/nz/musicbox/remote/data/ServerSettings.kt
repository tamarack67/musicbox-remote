package nz.musicbox.remote.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import nz.musicbox.mpd.Endpoint

/**
 * The server address: whatever last worked, or what the user typed in Settings.
 * Null means "find it automatically".
 */
class ServerSettings(context: Context) {
    private val prefs = context.getSharedPreferences("server", Context.MODE_PRIVATE)

    private val _server = MutableStateFlow(read())
    val server: StateFlow<Endpoint?> = _server.asStateFlow()

    fun save(endpoint: Endpoint?) {
        if (endpoint == _server.value) return
        prefs.edit {
            if (endpoint == null) {
                remove(KEY_HOST)
                remove(KEY_PORT)
            } else {
                putString(KEY_HOST, endpoint.host)
                putInt(KEY_PORT, endpoint.port)
            }
        }
        _server.value = endpoint
    }

    private fun read(): Endpoint? {
        val host = prefs.getString(KEY_HOST, null)?.takeIf { it.isNotBlank() } ?: return null
        return Endpoint(host, prefs.getInt(KEY_PORT, DEFAULT_PORT))
    }

    companion object {
        // Defaults. Everything else about the server is discovered or entered in Settings.
        const val DEFAULT_HOSTNAME = "musicbox.local"
        const val DEFAULT_PORT = 6600
        const val MDNS_SERVICE_TYPE = "_mpd._tcp"

        private const val KEY_HOST = "host"
        private const val KEY_PORT = "port"
    }
}
