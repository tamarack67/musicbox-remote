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
import nz.musicbox.mpd.addAt
import nz.musicbox.mpd.clearQueue
import nz.musicbox.mpd.deleteId
import nz.musicbox.mpd.moveId
import nz.musicbox.mpd.playId
import nz.musicbox.mpd.queue
import nz.musicbox.mpd.saveQueueAs
import java.io.IOException

/** The play queue, re-read whenever MPD reports a `playlist` change. Edits go through [PlayerRepository]'s command queue. */
class QueueRepository(
    private val client: MpdClient,
    private val player: PlayerRepository,
    scope: CoroutineScope,
) {
    private val _songs = MutableStateFlow<List<Song>?>(null)

    /** Null until first loaded. Each song has its queue [Song.id] and [Song.pos]. */
    val songs: StateFlow<List<Song>?> = _songs.asStateFlow()

    init {
        scope.launch {
            client.changes
                .filter { "playlist" in it }
                .conflate()
                .collect { refresh() }
        }
    }

    private suspend fun refresh() {
        try {
            _songs.value = client.queue()
        } catch (e: IOException) {
            Log.d(TAG, "Queue refresh failed: ${e.message}")
        } catch (e: MpdException) {
            Log.w(TAG, "Queue refresh refused: ${e.message}")
        }
    }

    fun play(song: Song) {
        val id = song.id ?: return
        player.perform { playId(id) }
    }

    fun remove(song: Song) {
        val id = song.id ?: return
        player.perform { deleteId(id) }
    }

    /** Puts a removed song back at [position] (for Undo). */
    fun reinsert(song: Song, position: Int) = player.perform { addAt(song.file, position) }

    fun move(song: Song, to: Int) {
        val id = song.id ?: return
        player.perform { moveId(id, to) }
    }

    fun clear() = player.perform("Queue cleared") { clearQueue() }

    fun saveAs(name: String, replace: Boolean) =
        player.perform("Saved the queue as “$name”") { saveQueueAs(name, replace) }

    private companion object {
        const val TAG = "QueueRepository"
    }
}
