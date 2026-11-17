package nz.musicbox.remote.data

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import nz.musicbox.mpd.MpdClient
import nz.musicbox.mpd.MpdException
import nz.musicbox.mpd.Song
import nz.musicbox.mpd.StoredPlaylist
import nz.musicbox.mpd.addToPlaylist
import nz.musicbox.mpd.deletePlaylist
import nz.musicbox.mpd.loadPlaylist
import nz.musicbox.mpd.moveInPlaylist
import nz.musicbox.mpd.playPlaylist
import nz.musicbox.mpd.playlistSongs
import nz.musicbox.mpd.playlists
import nz.musicbox.mpd.removeFromPlaylist
import nz.musicbox.mpd.writePlaylist
import java.io.IOException

/** Stored playlists on the server, re-read whenever MPD reports a `stored_playlist` change. */
class PlaylistRepository(
    private val client: MpdClient,
    private val player: PlayerRepository,
    scope: CoroutineScope,
) {
    private val _playlists = MutableStateFlow<List<StoredPlaylist>?>(null)

    /** Sorted by name; null until first loaded. */
    val playlists: StateFlow<List<StoredPlaylist>?> = _playlists.asStateFlow()

    init {
        scope.launch {
            client.changes
                .filter { "stored_playlist" in it }
                .conflate()
                .collect { refresh() }
        }
    }

    private suspend fun refresh() {
        try {
            _playlists.value = client.playlists().sortedBy { it.name.lowercase() }
        } catch (e: IOException) {
            Log.d(TAG, "Playlist refresh failed: ${e.message}")
        } catch (e: MpdException) {
            Log.w(TAG, "Playlist refresh refused: ${e.message}")
        }
    }

    suspend fun songs(name: String): List<Song> = client.playlistSongs(name)

    fun play(name: String, startIndex: Int = 0) = player.perform { playPlaylist(name, startIndex) }

    fun addToQueue(name: String) = player.perform("Added “$name” to the queue") { loadPlaylist(name) }

    fun delete(name: String) = player.perform("Deleted “$name”") { deletePlaylist(name) }

    /** Appends [uris] (songs or folders) to playlist [name], creating it if it's new. [what] is for the confirmation. */
    fun add(name: String, uris: List<String>, what: String) =
        player.perform("Added $what to “$name”") { addToPlaylist(name, uris) }

    /** Removes the song at [position] (0-based). */
    fun remove(name: String, position: Int) = player.perform { removeFromPlaylist(name, position) }

    /** Puts a removed song back at [position] (for Undo). */
    fun reinsert(name: String, uri: String, position: Int) =
        player.perform { addToPlaylist(name, listOf(uri), position) }

    fun move(name: String, from: Int, to: Int) = player.perform { moveInPlaylist(name, from, to) }

    /** Creates playlist [name] from [uris], replacing one of the same name. */
    fun import(name: String, uris: List<String>) {
        val count = if (uris.size == 1) "1 song" else "${uris.size} songs"
        player.perform("Imported “$name” ($count)") { writePlaylist(name, uris) }
    }

    private companion object {
        const val TAG = "PlaylistRepository"
    }
}
