package nz.musicbox.remote.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import nz.musicbox.mpd.PlayState
import nz.musicbox.remote.R
import nz.musicbox.remote.data.PlayerState
import nz.musicbox.remote.data.library.AlbumEntity
import nz.musicbox.remote.data.library.ArtistRow
import nz.musicbox.remote.data.monotonicMs

/** Square placeholder where cover art will go (stage 4). */
@Composable
fun CoverThumb(size: Dp, modifier: Modifier = Modifier, @DrawableRes icon: Int = R.drawable.ic_album) {
    Surface(
        modifier = modifier.size(size),
        shape = RoundedCornerShape(if (size > 64.dp) 12.dp else 6.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painterResource(icon),
                contentDescription = null,
                modifier = Modifier.size(size * 0.5f),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
        }
    }
}

@Composable
fun AlbumListItem(album: AlbumEntity, onClick: () -> Unit, showArtist: Boolean = true) {
    val details = listOfNotNull(album.artist.takeIf { showArtist }, album.year).joinToString(" · ")
    ListItem(
        headlineContent = { Text(album.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(details, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingContent = { CoverImage(album.directory, album.coverFile, 48.dp) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
fun ArtistListItem(artist: ArtistRow, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(artist.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(if (artist.albumCount == 1) "1 album" else "${artist.albumCount} albums") },
        leadingContent = {
            CoverImage(
                artist.coverDirectory,
                artist.coverFile,
                48.dp,
                shape = CircleShape,
                placeholder = R.drawable.ic_person,
            )
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

/** "Play" and "Add to queue" buttons for an album or folder. */
@Composable
fun PlayAddButtons(onPlay: () -> Unit, onAdd: () -> Unit, enabled: Boolean = true) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Button(onClick = onPlay, enabled = enabled, modifier = Modifier.weight(1f)) {
            Icon(painterResource(R.drawable.ic_play), contentDescription = null, modifier = Modifier.size(20.dp))
            Text("  Play")
        }
        OutlinedButton(onClick = onAdd, enabled = enabled, modifier = Modifier.weight(1f)) {
            Icon(painterResource(R.drawable.ic_playlist_add), contentDescription = null, modifier = Modifier.size(20.dp))
            Text("  Add to queue")
        }
    }
}

/** Centred grey text for empty, loading and error states. */
@Composable
fun StatusMessage(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(
            text,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Current position in seconds, ticking forward while playing. */
@Composable
fun rememberPlaybackPosition(state: PlayerState?): Double {
    val playing = state?.status?.state == PlayState.PLAY
    var now by remember { mutableLongStateOf(monotonicMs()) }
    LaunchedEffect(playing, state?.readAtMs) {
        now = monotonicMs()
        while (playing) {
            delay(250)
            now = monotonicMs()
        }
    }
    return state?.elapsedAt(now) ?: 0.0
}

/** 0:42, 4:05, 1:02:03 */
fun formatTime(seconds: Double): String {
    val total = seconds.toLong().coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** 43 min, 1 hr 12 min */
fun formatLength(seconds: Double): String {
    val minutes = (seconds / 60).toLong()
    return if (minutes >= 60) "${minutes / 60} hr ${minutes % 60} min" else "$minutes min"
}
