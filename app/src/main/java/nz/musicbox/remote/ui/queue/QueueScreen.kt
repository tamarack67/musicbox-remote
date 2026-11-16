package nz.musicbox.remote.ui.queue

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import nz.musicbox.mpd.PlayState
import nz.musicbox.mpd.Song
import nz.musicbox.remote.AppContainer
import nz.musicbox.remote.R
import nz.musicbox.remote.ui.Navigator
import nz.musicbox.remote.ui.components.StatusMessage
import nz.musicbox.remote.ui.components.formatLength
import nz.musicbox.remote.ui.components.formatTime
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

private fun Song.key(): Any = id ?: file

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueScreen(container: AppContainer, @Suppress("UNUSED_PARAMETER") go: Navigator) {
    val queue = container.queue
    val serverSongs by queue.songs.collectAsStateWithLifecycle()
    val playerState by container.player.state.collectAsStateWithLifecycle()
    val currentId = playerState?.status?.songId
    val playing = playerState?.status?.state == PlayState.PLAY

    // Local copy, so drags and swipes show instantly. Server updates replace it, except while
    // dragging, or while waiting for the server to reflect our own last edit.
    val songs = remember { mutableStateListOf<Song>() }
    var dragging by remember { mutableStateOf(false) }
    var awaitingServer by remember { mutableStateOf(false) }
    var lastSynced by remember { mutableStateOf<List<Song>?>(null) }
    LaunchedEffect(serverSongs, dragging) {
        val latest = serverSongs ?: return@LaunchedEffect
        if (dragging || (awaitingServer && latest === lastSynced)) return@LaunchedEffect
        awaitingServer = false
        lastSynced = latest
        songs.clear()
        songs.addAll(latest)
    }

    val listState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        songs.add(to.index, songs.removeAt(from.index))
    }

    // Start with the current song in view.
    var scrolledToCurrent by remember { mutableStateOf(false) }
    LaunchedEffect(songs.isNotEmpty(), currentId) {
        if (scrolledToCurrent || songs.isEmpty()) return@LaunchedEffect
        val index = songs.indexOfFirst { it.id == currentId }
        if (index > 2) listState.scrollToItem(index - 2)
        scrolledToCurrent = true
    }

    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var confirmClear by remember { mutableStateOf(false) }
    var showSave by remember { mutableStateOf(false) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Queue")
                        if (songs.isNotEmpty()) {
                            val count = if (songs.size == 1) "1 song" else "%,d songs".format(songs.size)
                            Text(
                                "$count · ${formatLength(songs.sumOf { it.duration ?: 0.0 })}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { showSave = true }, enabled = songs.isNotEmpty()) {
                        Icon(painterResource(R.drawable.ic_save), contentDescription = "Save as playlist")
                    }
                    IconButton(onClick = { confirmClear = true }, enabled = songs.isNotEmpty()) {
                        Icon(painterResource(R.drawable.ic_delete_sweep), contentDescription = "Clear queue")
                    }
                },
            )
        },
    ) { padding ->
        if (serverSongs == null) return@Scaffold
        if (songs.isEmpty()) {
            return@Scaffold StatusMessage(
                "The queue is empty. Add music from the Library or Search.",
                Modifier.padding(padding),
            )
        }
        LazyColumn(Modifier.padding(padding), state = listState) {
            items(songs, key = { it.key() }) { song ->
                ReorderableItem(reorderState, key = song.key()) { isDragging ->
                    val dismissState = rememberSwipeToDismissBoxState()
                    var dragFrom by remember { mutableIntStateOf(-1) }
                    SwipeToDismissBox(
                        state = dismissState,
                        gesturesEnabled = !isDragging,
                        backgroundContent = { RemoveBackground() },
                        onDismiss = {
                            val position = songs.indexOf(song)
                            if (position < 0) return@SwipeToDismissBox
                            songs.removeAt(position)
                            awaitingServer = true
                            queue.remove(song)
                            scope.launch {
                                val result = snackbar.showSnackbar(
                                    "Removed “${song.displayTitle}”",
                                    actionLabel = "Undo",
                                    duration = SnackbarDuration.Short,
                                )
                                if (result == SnackbarResult.ActionPerformed) queue.reinsert(song, position)
                            }
                        },
                    ) {
                        val handle = Modifier.draggableHandle(
                            onDragStarted = {
                                dragging = true
                                dragFrom = songs.indexOf(song)
                            },
                            onDragStopped = {
                                val to = songs.indexOf(song)
                                if (to >= 0 && to != dragFrom) {
                                    awaitingServer = true
                                    queue.move(song, to)
                                }
                                dragging = false
                            },
                        )
                        QueueRow(
                            song = song,
                            number = songs.indexOf(song) + 1,
                            isCurrent = song.id == currentId,
                            playing = playing,
                            raised = isDragging,
                            onClick = { queue.play(song) },
                            handleModifier = handle,
                        )
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear the queue?") },
            text = { Text("This removes all ${songs.size} songs from the queue and stops playback.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    queue.clear()
                }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }

    if (showSave) {
        val existing by container.playlists.playlists.collectAsStateWithLifecycle()
        SavePlaylistDialog(
            existingNames = existing.orEmpty().map { it.name },
            onSave = { name, replace ->
                showSave = false
                queue.saveAs(name, replace)
            },
            onDismiss = { showSave = false },
        )
    }
}

@Composable
private fun RemoveBackground() {
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Text("Remove", color = MaterialTheme.colorScheme.onErrorContainer)
    }
}

@Composable
private fun QueueRow(
    song: Song,
    number: Int,
    isCurrent: Boolean,
    playing: Boolean,
    raised: Boolean,
    onClick: () -> Unit,
    handleModifier: Modifier,
) {
    val accent = MaterialTheme.colorScheme.primary
    ListItem(
        colors = ListItemDefaults.colors(
            containerColor = if (raised) {
                MaterialTheme.colorScheme.surfaceContainerHighest
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
        headlineContent = {
            Text(
                song.displayTitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (isCurrent) accent else MaterialTheme.colorScheme.onSurface,
                fontWeight = if (isCurrent) FontWeight.SemiBold else null,
            )
        },
        supportingContent = {
            Text(
                listOfNotNull(song.displayArtist, song.displayAlbum).joinToString(" · "),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingContent = {
            Box(Modifier.widthIn(min = 28.dp), contentAlignment = Alignment.CenterEnd) {
                if (isCurrent) {
                    Icon(
                        painterResource(if (playing) R.drawable.ic_equalizer else R.drawable.ic_pause),
                        contentDescription = if (playing) "Playing" else "Paused",
                        tint = accent,
                        modifier = Modifier.size(20.dp),
                    )
                } else {
                    Text(
                        number.toString(),
                        textAlign = TextAlign.End,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
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
