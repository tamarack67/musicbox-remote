package nz.musicbox.remote.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.SystemClock
import androidx.compose.ui.graphics.asAndroidBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import nz.musicbox.mpd.ConnectionState
import nz.musicbox.mpd.PlayState
import nz.musicbox.remote.AppContainer
import nz.musicbox.remote.MusicBoxApp
import nz.musicbox.remote.R
import nz.musicbox.remote.data.PlayerState
import nz.musicbox.remote.data.monotonicMs

/**
 * Lock-screen and notification controls for the Music Box.
 *
 * The phone plays no audio; this service holds a media session that mirrors MPD's state and
 * sends the buttons back to MPD. It runs as a foreground service so the app stays connected
 * while the phone is locked. It stops itself when the queue is empty, or after the music has
 * been paused for [IDLE_TIMEOUT_MS] with the app in the background.
 */
class PlaybackService : Service() {
    private lateinit var container: AppContainer
    private lateinit var session: MediaSession
    private lateinit var notifications: NotificationManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var art: Bitmap? = null
    private var artDirectory: String? = null
    private var artJob: Job? = null

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        container = (application as MusicBoxApp).container
        notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Playback controls", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Play, pause and skip on the Music Box from the lock screen"
                setShowBadge(false)
            },
        )
        session = MediaSession(this, "MusicBox").apply {
            setCallback(SessionCallback())
            setSessionActivity(openAppIntent())
            isActive = true
        }
        container.controlsShowing.value = true

        scope.launch {
            container.player.state.collect { state ->
                loadArtFor(state)
                publish(state)
            }
        }
        scope.launch { stopWhenIdle() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Required promptly after every startForegroundService().
        startForeground(NOTIFICATION_ID, buildNotification(container.player.state.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> container.player.playPause()
            ACTION_NEXT -> container.player.next()
            ACTION_PREVIOUS -> container.player.previous()
        }
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the app away while the music is paused also clears the controls.
        if (container.player.state.value?.status?.state != PlayState.PLAY) stop()
    }

    override fun onDestroy() {
        scope.cancel()
        session.isActive = false
        session.release()
        container.controlsShowing.value = false
        super.onDestroy()
    }

    private suspend fun stopWhenIdle() {
        combine(container.player.state, container.client.state, container.appVisible) { state, connection, visible ->
            val connected = connection is ConnectionState.Connected
            when {
                connected && state?.song == null -> Idle.EMPTY
                visible || (connected && state?.status?.state == PlayState.PLAY) -> Idle.NO
                else -> Idle.PAUSED_IN_BACKGROUND
            }
        }
            .distinctUntilChanged()
            .collectLatest { idle ->
                when (idle) {
                    Idle.EMPTY -> stop()
                    Idle.PAUSED_IN_BACKGROUND -> {
                        delay(IDLE_TIMEOUT_MS)
                        stop()
                    }
                    Idle.NO -> {}
                }
            }
    }

    private enum class Idle { NO, EMPTY, PAUSED_IN_BACKGROUND }

    private fun stop() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun loadArtFor(state: PlayerState?) {
        val file = state?.song?.file ?: return
        val directory = file.substringBeforeLast('/', "")
        if (directory == artDirectory) return
        artDirectory = directory
        art = null
        artJob?.cancel()
        artJob = scope.launch {
            val image = container.covers.load(directory, file, ART_SIZE) ?: return@launch
            art = scaled(image.asAndroidBitmap())
            publish(container.player.state.value)
        }
    }

    /** Keeps the bitmap small: it is copied to the system for the lock screen and notification. */
    private fun scaled(bitmap: Bitmap): Bitmap {
        val scale = ART_SIZE.toFloat() / maxOf(bitmap.width, bitmap.height)
        if (scale >= 1f) return bitmap
        return Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
    }

    private fun publish(state: PlayerState?) {
        val status = state?.status
        val song = state?.song
        val playing = status?.state == PlayState.PLAY
        val positionMs = ((state?.elapsedAt(monotonicMs()) ?: 0.0) * 1000).toLong()
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_STOP,
                )
                .setState(
                    when (status?.state) {
                        PlayState.PLAY -> PlaybackState.STATE_PLAYING
                        PlayState.PAUSE -> PlaybackState.STATE_PAUSED
                        else -> PlaybackState.STATE_STOPPED
                    },
                    positionMs,
                    if (playing) 1f else 0f,
                    SystemClock.elapsedRealtime(),
                )
                .build(),
        )
        val durationS = status?.duration ?: song?.duration
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, song?.displayTitle ?: "Nothing playing")
                .putString(MediaMetadata.METADATA_KEY_ARTIST, song?.displayArtist.orEmpty())
                .putString(MediaMetadata.METADATA_KEY_ALBUM, song?.displayAlbum.orEmpty())
                .putLong(MediaMetadata.METADATA_KEY_DURATION, ((durationS ?: 0.0) * 1000).toLong())
                .apply { art?.let { putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it) } }
                .build(),
        )
        notifications.notify(NOTIFICATION_ID, buildNotification(state))
    }

    private fun buildNotification(state: PlayerState?): Notification {
        val song = state?.song
        val playing = state?.status?.state == PlayState.PLAY
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_music_note)
            .setContentTitle(song?.displayTitle ?: "Music Box")
            .setContentText(listOfNotNull(song?.displayArtist, song?.displayAlbum).joinToString(" · "))
            .setLargeIcon(art)
            .setContentIntent(openAppIntent())
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setOngoing(true)
            .addAction(action(R.drawable.ic_skip_previous, "Previous", ACTION_PREVIOUS))
            .addAction(
                if (playing) action(R.drawable.ic_pause, "Pause", ACTION_PLAY_PAUSE)
                else action(R.drawable.ic_play, "Play", ACTION_PLAY_PAUSE),
            )
            .addAction(action(R.drawable.ic_skip_next, "Next", ACTION_NEXT))
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .build()
    }

    private fun action(icon: Int, title: String, action: String): Notification.Action {
        val intent = PendingIntent.getService(
            this,
            action.hashCode(),
            Intent(this, PlaybackService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Action.Builder(Icon.createWithResource(this, icon), title, intent).build()
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        packageManager.getLaunchIntentForPackage(packageName),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Buttons on the lock screen, notification, headset or car, sent on to MPD. */
    private inner class SessionCallback : MediaSession.Callback() {
        override fun onPlay() = container.player.play()
        override fun onPause() = container.player.pause()
        override fun onStop() = container.player.pause()
        override fun onSkipToNext() = container.player.next()
        override fun onSkipToPrevious() = container.player.previous()
        override fun onSeekTo(pos: Long) = container.player.seekTo(pos / 1000.0)
    }

    companion object {
        private const val CHANNEL_ID = "playback"
        private const val NOTIFICATION_ID = 1
        private const val ART_SIZE = 320
        private const val IDLE_TIMEOUT_MS = 10 * 60 * 1000L
        private const val ACTION_PLAY_PAUSE = "nz.musicbox.remote.PLAY_PAUSE"
        private const val ACTION_NEXT = "nz.musicbox.remote.NEXT"
        private const val ACTION_PREVIOUS = "nz.musicbox.remote.PREVIOUS"

        /** Call only while the app is on screen. */
        fun start(context: Context) {
            context.startForegroundService(Intent(context, PlaybackService::class.java))
        }
    }
}
