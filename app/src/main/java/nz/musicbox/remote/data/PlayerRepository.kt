package nz.musicbox.remote.data

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import nz.musicbox.mpd.MpdClient
import nz.musicbox.mpd.MpdException
import nz.musicbox.mpd.MpdStatus
import nz.musicbox.mpd.PlayState
import nz.musicbox.mpd.Song
import nz.musicbox.mpd.currentSong
import nz.musicbox.mpd.next
import nz.musicbox.mpd.play
import nz.musicbox.mpd.previous
import nz.musicbox.mpd.seekCurrent
import nz.musicbox.mpd.setPaused
import nz.musicbox.mpd.setRandom
import nz.musicbox.mpd.setRepeat
import nz.musicbox.mpd.status
import java.io.IOException

/** What is playing, as of [readAtMs] (a monotonic clock, see [monotonicMs]). */
data class PlayerState(
    val status: MpdStatus,
    val song: Song?,
    val readAtMs: Long,
) {
    /** Position in seconds at time [nowMs], counting forward while playing. */
    fun elapsedAt(nowMs: Long): Double {
        val base = status.elapsed ?: 0.0
        if (status.state != PlayState.PLAY) return base
        val position = base + (nowMs - readAtMs) / 1000.0
        return status.duration?.let { position.coerceAtMost(it) } ?: position
    }
}

fun monotonicMs(): Long = System.nanoTime() / 1_000_000

/**
 * Player state as a Flow, kept current by MPD's idle events. Commands are queued and sent
 * one at a time, in order. Failures are logged and dropped: connection problems already show
 * in the status indicator, and the next idle event corrects the display.
 *
 * Nothing here ever changes the volume.
 */
class PlayerRepository(
    private val client: MpdClient,
    scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<PlayerState?>(null)
    val state: StateFlow<PlayerState?> = _state.asStateFlow()

    private val commands = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    init {
        scope.launch {
            client.changes
                .filter { changed -> changed.any { it in WATCHED } }
                .conflate()
                .collect { refresh() }
        }
        scope.launch {
            for (command in commands) {
                attempt { command() }
            }
        }
    }

    fun playPause() = enqueue {
        when (_state.value?.status?.state) {
            PlayState.PLAY -> client.setPaused(true)
            PlayState.PAUSE -> client.setPaused(false)
            else -> client.play()
        }
    }

    fun next() = enqueue { client.next() }
    fun previous() = enqueue { client.previous() }
    fun seekTo(seconds: Double) = enqueue { client.seekCurrent(seconds) }
    fun setShuffle(on: Boolean) = enqueue { client.setRandom(on) }
    fun setRepeat(on: Boolean) = enqueue { client.setRepeat(on) }

    private fun enqueue(command: suspend () -> Unit) {
        commands.trySend(command)
    }

    private suspend fun refresh() = attempt {
        val status = client.status()
        val song = client.currentSong()
        _state.value = PlayerState(status, song, monotonicMs())
    }

    private suspend fun attempt(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: IOException) {
            Log.d(TAG, "MPD request failed: ${e.message}")
        } catch (e: MpdException) {
            Log.w(TAG, "MPD refused request: ${e.message}")
        }
    }

    private companion object {
        const val TAG = "PlayerRepository"
        val WATCHED = setOf("player", "options", "playlist")
    }
}
