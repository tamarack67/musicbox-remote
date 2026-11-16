package nz.musicbox.remote.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nz.musicbox.mpd.ConnectionState
import nz.musicbox.mpd.MpdClient
import nz.musicbox.mpd.PlayState
import nz.musicbox.remote.R
import nz.musicbox.remote.data.PlayerRepository
import nz.musicbox.remote.ui.components.CoverThumb
import nz.musicbox.remote.ui.components.rememberPlaybackPosition

/** Compact player above the bottom navigation. Also carries the connection status line. */
@Composable
fun MiniPlayer(player: PlayerRepository, client: MpdClient, onOpen: () -> Unit) {
    val state by player.state.collectAsStateWithLifecycle()
    val connection by client.state.collectAsStateWithLifecycle()
    val connected = connection is ConnectionState.Connected
    val song = state?.song
    if (connected && song == null) return

    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column {
            if (!connected) {
                ConnectionIndicator(
                    connection,
                    onRetry = client::retryNow,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                )
            }
            if (song != null) {
                val duration = state?.status?.duration?.takeIf { it > 0 }
                val position = rememberPlaybackPosition(state)
                LinearProgressIndicator(
                    progress = { if (duration == null) 0f else (position / duration).toFloat().coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                    strokeCap = StrokeCap.Butt,
                    gapSize = 0.dp,
                    drawStopIndicator = {},
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpen)
                        .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CoverThumb(40.dp, icon = R.drawable.ic_music_note)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            song.displayTitle,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            song.displayArtist.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    val playing = state?.status?.state == PlayState.PLAY
                    IconButton(onClick = player::playPause, enabled = connected) {
                        Icon(
                            painterResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play),
                            contentDescription = if (playing) "Pause" else "Play",
                        )
                    }
                    IconButton(onClick = player::next, enabled = connected) {
                        Icon(painterResource(R.drawable.ic_skip_next), contentDescription = "Next")
                    }
                }
            }
        }
    }
}
