package nz.musicbox.remote.ui.queue

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import kotlinx.coroutines.launch
import nz.musicbox.remote.ui.components.formatLength
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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

/** A playlist entry with a key that is unique on screen (a playlist can hold the same song twice). */
private data class Entry(val key: String, val song: Song)

/**
 * Keys include [generation], a count of server reloads, so a song that comes back (Undo) is a
 * new item. Otherwise the list would restore its old "swiped away" state and remove it again.
 */
private fun keyed(songs: List<Song>, generation: Int): List<Entry> {
    val seen = HashMap<String, Int>()
    return songs.map { song ->
        val n = seen.merge(song.file, 1, Int::plus)!!
        Entry("${song.file}#$n@$generation", song)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistScreen(container: AppContainer, go: Navigator, name: String) {
    val repo = container.playlists
    // Reload the songs whenever the stored playlists change (after each edit, or from elsewhere).
    val playlists by repo.playlists.collectAsStateWithLifecycle()
    val serverSongs by produceState<List<Song>?>(initialValue = null, name, playlists) {
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

    // Local copy, so drags and swipes show instantly; the server's version replaces it
    // when it arrives, except mid-drag.
    val entries = remember { mutableStateListOf<Entry>() }
    var dragging by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    var generation by remember { mutableIntStateOf(0) }
    LaunchedEffect(serverSongs, dragging) {
        val latest = serverSongs ?: return@LaunchedEffect
        if (dragging) return@LaunchedEffect
        entries.clear()
        entries.addAll(keyed(latest, ++generation))
        loaded = true
    }

    val listState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        // Item 0 is the Play / Add buttons.
        if (from.index > 0 && to.index > 0) entries.add(to.index - 1, entries.removeAt(from.index - 1))
    }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (entries.isNotEmpty()) {
                            val count = if (entries.size == 1) "1 song" else "%,d songs".format(entries.size)
                            Text(
                                "$count · ${formatLength(entries.sumOf { it.song.duration ?: 0.0 })}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
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
        if (!loaded) return@Scaffold
        LazyColumn(Modifier.padding(padding), state = listState) {
            item(key = "actions") {
                Column {
                    PlayAddButtons(
                        onPlay = { repo.play(name) },
                        onAdd = { repo.addToQueue(name) },
                        enabled = entries.isNotEmpty(),
                    )
                    Text(
                        if (entries.isEmpty()) {
                            "This playlist is empty. Add songs with the ⋮ button next to any song or album."
                        } else {
                            "Drag ≡ to reorder. Swipe a song sideways to remove it."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }
            items(entries, key = { it.key }) { entry ->
                ReorderableItem(reorderState, key = entry.key) { isDragging ->
                    val dismissState = rememberSwipeToDismissBoxState()
                    var dragFrom by remember { mutableIntStateOf(-1) }
                    SwipeToDismissBox(
                        state = dismissState,
                        gesturesEnabled = !isDragging,
                        backgroundContent = { RemoveBackground() },
                        onDismiss = {
                            val position = entries.indexOf(entry)
                            if (position < 0) return@SwipeToDismissBox
                            entries.removeAt(position)
                            repo.remove(name, position)
                            scope.launch {
                                val result = snackbar.showSnackbar(
                                    "Removed “${entry.song.displayTitle}”",
                                    actionLabel = "Undo",
                                    duration = SnackbarDuration.Short,
                                )
                                if (result == SnackbarResult.ActionPerformed) {
                                    repo.reinsert(name, entry.song.file, position)
                                }
                            }
                        },
                    ) {
                        val handle = Modifier.draggableHandle(
                            onDragStarted = {
                                dragging = true
                                dragFrom = entries.indexOf(entry)
                            },
                            onDragStopped = {
                                val to = entries.indexOf(entry)
                                if (dragFrom >= 0 && to >= 0 && to != dragFrom) repo.move(name, dragFrom, to)
                                dragging = false
                            },
                        )
                        PlaylistRow(
                            song = entry.song,
                            number = entries.indexOf(entry) + 1,
                            raised = isDragging,
                            onClick = { repo.play(name, entries.indexOf(entry)) },
                            handleModifier = handle,
                        )
                    }
                }
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

@Composable
private fun PlaylistRow(song: Song, number: Int, raised: Boolean, onClick: () -> Unit, handleModifier: Modifier) {
    ListItem(
        colors = ListItemDefaults.colors(
            containerColor = if (raised) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surface,
        ),
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
                number.toString(),
                modifier = Modifier.widthIn(min = 28.dp),
                textAlign = TextAlign.End,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                song.duration?.let { Text(formatTime(it), style = MaterialTheme.typography.bodySmall) }
                Icon(
                    painterResource(R.drawable.ic_drag_handle),
                    contentDescription = "Drag to reorder",
                    modifier = handleModifier.padding(8.dp),
                )
            }
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}
