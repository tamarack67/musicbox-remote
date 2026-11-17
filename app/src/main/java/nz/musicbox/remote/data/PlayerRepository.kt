package nz.musicbox.remote.data

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
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
import nz.musicbox.mpd.addToQueue
import nz.musicbox.mpd.currentSong
import nz.musicbox.mpd.next
import nz.musicbox.mpd.play
import nz.musicbox.mpd.previous
import nz.musicbox.mpd.replaceQueueAndPlay
import nz.musicbox.mpd.seekCurrent
import nz.musicbox.mpd.setPaused
import nz.musicbox.mpd.setRandom
import nz.musicbox.mpd.setRepeat
import nz.musicbox.mpd.setVolume
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
 * one at a time, in order. Connection failures are dropped quietly (the status indicator already
 * shows them); refusals from MPD are reported on [messages].
 *
 * The volume only changes through [setVolume], which only the volume slider calls.
 */
class PlayerRepository(
    private val client: MpdClient,
    scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<PlayerState?>(null)
    val state: StateFlow<PlayerState?> = _state.asStateFlow()

    private val commands = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    // Only the latest slider position matters, so unsent ones are dropped.
    private val volumeRequests = Channel<Int>(Channel.CONFLATED)

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** Short confirmations and errors for the user (shown as snackbars). */
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    init {
        scope.launch {
            client.changes
                .filter { changed -> changed.any { it in WATCHED } }
                .conflate()
                .collect { refresh() }
        }
        scope.launch {
            for (command in commands) {
                try {
                    command()
                } catch (e: IOException) {
                    Log.d(TAG, "MPD request failed: ${e.message}")
                } catch (e: MpdException) {
                    Log.w(TAG, "MPD refused request: ${e.message}")
                    _messages.tryEmit("The Music Box refused that: ${e.serverMessage}")
                }
            }
        }
        scope.launch {
            // Sent directly rather than queued, so a fast drag doesn't pile up requests.
            for (volume in volumeRequests) {
                try {
                    client.setVolume(volume)
                } catch (e: IOException) {
                    Log.d(TAG, "Volume change failed: ${e.message}")
                } catch (e: MpdException) {
                    _messages.tryEmit("The Music Box refused that: ${e.serverMessage}")
                }
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

    /** Plays (or resumes) without toggling, for lock-screen and headset buttons. */
    fun play() = enqueue {
        if (_state.value?.status?.state == PlayState.PAUSE) client.setPaused(false) else client.play()
    }

    fun pause() = enqueue {
        if (_state.value?.status?.state == PlayState.PLAY) client.setPaused(true)
    }

    /**
     * Sets MPD's volume, kept within [VolumeRange]. Call this only for a change the user made
     * on the volume slider, never to restore or initialise a value.
     */
    fun setVolume(mpdVolume: Int) {
        volumeRequests.trySend(mpdVolume.coerceIn(VolumeRange.MIN, VolumeRange.MAX))
    }

    fun next() = enqueue { client.next() }
    fun previous() = enqueue { client.previous() }
    fun seekTo(seconds: Double) = enqueue { client.seekCurrent(seconds) }
    fun setShuffle(on: Boolean) = enqueue { client.setRandom(on) }
    fun setRepeat(on: Boolean) = enqueue { client.setRepeat(on) }

    /** Replaces the queue with [uris] (files or folders) and plays from [startIndex]. */
    fun playNow(uris: List<String>, startIndex: Int = 0) = enqueue {
        client.replaceQueueAndPlay(uris, startIndex)
    }

    /** Appends [uris] to the queue; [what] describes them in the confirmation, e.g. "12 songs". */
    fun addToQueue(uris: List<String>, what: String) = enqueue {
        client.addToQueue(uris)
        _messages.tryEmit("Added $what to the queue")
    }

    /**
     * Queues any MPD action behind the other commands, showing [done] as a confirmation
     * if it succeeds.
     */
    fun perform(done: String? = null, action: suspend MpdClient.() -> Unit) = enqueue {
        client.action()
        if (done != null) _messages.tryEmit(done)
    }

    private fun enqueue(command: suspend () -> Unit) {
        commands.trySend(command)
    }

    private suspend fun refresh() {
        try {
            val status = client.status()
            val song = client.currentSong()
            _state.value = PlayerState(status, song, monotonicMs())
        } catch (e: IOException) {
            Log.d(TAG, "Status refresh failed: ${e.message}")
        } catch (e: MpdException) {
            Log.w(TAG, "Status refresh refused: ${e.message}")
        }
    }

    private companion object {
        const val TAG = "PlayerRepository"
        val WATCHED = setOf("player", "options", "playlist", "mixer")
    }
}
