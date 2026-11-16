package nz.musicbox.remote.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nz.musicbox.mpd.ConnectionState
import nz.musicbox.mpd.MpdClient
import nz.musicbox.mpd.PlayState
import nz.musicbox.remote.R
import nz.musicbox.remote.data.PlayerRepository
import nz.musicbox.remote.data.PlayerState
import nz.musicbox.remote.ui.components.CoverImage
import nz.musicbox.remote.ui.components.formatTime
import nz.musicbox.remote.ui.components.rememberPlaybackPosition

@Composable
fun NowPlayingScreen(
    player: PlayerRepository,
    client: MpdClient,
    onClose: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val state by player.state.collectAsStateWithLifecycle()
    val connection by client.state.collectAsStateWithLifecycle()
    val connected = connection is ConnectionState.Connected
    val status = state?.status

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) {
                    Icon(painterResource(R.drawable.ic_expand_more), contentDescription = "Close")
                }
                ConnectionIndicator(connection, onRetry = client::retryNow, modifier = Modifier.weight(1f))
                IconButton(onClick = onOpenSettings) {
                    Icon(painterResource(R.drawable.ic_settings), contentDescription = "Settings")
                }
            }

            Spacer(Modifier.weight(1f))
            val song = state?.song
            CoverImage(
                directory = song?.file?.substringBeforeLast('/', ""),
                songFile = song?.file,
                size = 320.dp,
                modifier = Modifier.widthIn(max = 320.dp).fillMaxWidth().aspectRatio(1f),
                placeholder = R.drawable.ic_music_note,
            )
            Spacer(Modifier.height(28.dp))
            TrackText(state)
            Spacer(Modifier.height(20.dp))
            SeekBar(state, enabled = connected, onSeek = player::seekTo)
            Spacer(Modifier.height(12.dp))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconToggleButton(
                    checked = status?.random == true,
                    onCheckedChange = player::setShuffle,
                    enabled = connected,
                ) {
                    Icon(painterResource(R.drawable.ic_shuffle), contentDescription = "Shuffle")
                }
                IconButton(onClick = player::previous, enabled = connected, modifier = Modifier.size(56.dp)) {
                    Icon(painterResource(R.drawable.ic_skip_previous), "Previous", Modifier.size(36.dp))
                }
                FilledIconButton(onClick = player::playPause, enabled = connected, modifier = Modifier.size(72.dp)) {
                    val playing = status?.state == PlayState.PLAY
                    Icon(
                        painterResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play),
                        contentDescription = if (playing) "Pause" else "Play",
                        modifier = Modifier.size(40.dp),
                    )
                }
                IconButton(onClick = player::next, enabled = connected, modifier = Modifier.size(56.dp)) {
                    Icon(painterResource(R.drawable.ic_skip_next), "Next", Modifier.size(36.dp))
                }
                IconToggleButton(
                    checked = status?.repeat == true,
                    onCheckedChange = player::setRepeat,
                    enabled = connected,
                ) {
                    Icon(painterResource(R.drawable.ic_repeat), contentDescription = "Repeat")
                }
            }
            Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun TrackText(state: PlayerState?) {
    val song = state?.song
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = song?.displayTitle ?: "Nothing playing",
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = song?.displayArtist.orEmpty(),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = song?.displayAlbum.orEmpty(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SeekBar(state: PlayerState?, enabled: Boolean, onSeek: (Double) -> Unit) {
    val status = state?.status
    val duration = status?.duration?.takeIf { it > 0 }

    val position = rememberPlaybackPosition(state)

    // While dragging, show the thumb position. After letting go, hold the target
    // until MPD reports the new position, so the thumb doesn't jump back.
    var dragging by remember { mutableStateOf<Float?>(null) }
    var pendingSeek by remember { mutableStateOf<Pair<Float, Long>?>(null) }
    val readAt by rememberUpdatedState(state?.readAtMs ?: 0L)
    val pending = pendingSeek?.takeIf { it.second == readAt }?.first

    val max = duration?.toFloat() ?: 1f
    val shown = (dragging ?: pending ?: position.toFloat()).coerceIn(0f, max)

    Column(Modifier.fillMaxWidth()) {
        Slider(
            value = shown,
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let {
                    onSeek(it.toDouble())
                    pendingSeek = it to readAt
                }
                dragging = null
            },
            valueRange = 0f..max,
            enabled = enabled && duration != null && status.state != PlayState.STOP,
        )
        Row(Modifier.fillMaxWidth()) {
            Text(formatTime(shown.toDouble()), style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.weight(1f))
            Text(duration?.let(::formatTime).orEmpty(), style = MaterialTheme.typography.labelMedium)
        }
    }
}
