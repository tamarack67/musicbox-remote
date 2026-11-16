package nz.musicbox.remote.ui.queue

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nz.musicbox.mpd.MpdException
import nz.musicbox.mpd.Song
import nz.musicbox.remote.AppContainer
import nz.musicbox.remote.R
import nz.musicbox.remote.ui.Navigator
import nz.musicbox.remote.ui.components.CoverThumb
import nz.musicbox.remote.ui.components.PlayAddButtons
import nz.musicbox.remote.ui.components.StatusMessage
import nz.musicbox.remote.ui.components.formatTime
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val DATE = DateTimeFormatter.ofPattern("d MMM yyyy")

private fun savedDate(iso: String?): String? = try {
    iso?.let { DATE.format(Instant.parse(it).atZone(ZoneId.systemDefault())) }
} catch (_: Exception) {
    null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistsScreen(container: AppContainer, go: Navigator) {
    val playlists by container.playlists.playlists.collectAsStateWithLifecycle()
    Scaffold(topBar = { TopAppBar(title = { Text("Playlists") }) }) { padding ->
        val list = playlists ?: return@Scaffold
        if (list.isEmpty()) {
            return@Scaffold StatusMessage(
                "No saved playlists yet. Use the save button on the Queue tab to save the current queue.",
                Modifier.padding(padding),
            )
        }
        LazyColumn(Modifier.padding(padding)) {
            items(list, key = { it.name }) { playlist ->
                ListItem(
                    headlineContent = { Text(playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = savedDate(playlist.lastModified)?.let { { Text("Saved $it") } },
                    leadingContent = { CoverThumb(48.dp, icon = R.drawable.ic_queue_music) },
                    modifier = Modifier.clickable { go.playlist(playlist.name) },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistScreen(container: AppContainer, go: Navigator, name: String) {
    val repo = container.playlists
    // Reload the songs whenever the stored playlists change (e.g. it was replaced).
    val playlists by repo.playlists.collectAsStateWithLifecycle()
    val songs by produceState<List<Song>?>(initialValue = null, name, playlists) {
        value = try {
            repo.songs(name)
        } catch (_: IOException) {
            value
        } catch (_: MpdException) {
            emptyList()
        }
    }
    var confirmDelete by remember { mutableStateOf(false) }

    // Deleted from elsewhere (another app, mpc): leave the screen.
    LaunchedEffect(playlists) {
        val names = playlists ?: return@LaunchedEffect
        if (names.none { it.name == name }) go.back()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = { go.back() }) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { confirmDelete = true }) {
                        Icon(painterResource(R.drawable.ic_delete), contentDescription = "Delete playlist")
                    }
                },
            )
        },
    ) { padding ->
        val list = songs ?: return@Scaffold
        LazyColumn(Modifier.padding(padding)) {
            item {
                PlayAddButtons(
                    onPlay = { repo.play(name) },
                    onAdd = { repo.addToQueue(name) },
                    enabled = list.isNotEmpty(),
                )
            }
            if (list.isEmpty()) {
                item { StatusMessage("This playlist is empty.") }
            }
            itemsIndexed(list) { index, song ->
                ListItem(
                    headlineContent = { Text(song.displayTitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        Text(
                            listOfNotNull(song.displayArtist, song.displayAlbum).joinToString(" · "),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    leadingContent = {
                        Text(
                            (index + 1).toString(),
                            modifier = Modifier.widthIn(min = 28.dp),
                            textAlign = TextAlign.End,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                    trailingContent = { song.duration?.let { Text(formatTime(it), style = MaterialTheme.typography.bodySmall) } },
                    modifier = Modifier.clickable { repo.play(name, index) },
                )
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete “$name”?") },
            text = { Text("The playlist is removed from the Music Box. The songs themselves aren't affected.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    // The screen closes itself once the server confirms (see LaunchedEffect above).
                    repo.delete(name)
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}
