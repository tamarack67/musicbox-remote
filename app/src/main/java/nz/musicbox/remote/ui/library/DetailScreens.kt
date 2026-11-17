package nz.musicbox.remote.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nz.musicbox.mpd.ConnectionState
import nz.musicbox.mpd.LsEntry
import nz.musicbox.mpd.MpdException
import nz.musicbox.remote.AppContainer
import nz.musicbox.remote.R
import nz.musicbox.remote.ui.Navigator
import nz.musicbox.remote.ui.components.AddMenuButton
import nz.musicbox.remote.ui.components.AlbumListItem
import nz.musicbox.remote.ui.components.CoverImage
import nz.musicbox.remote.ui.components.PlayAddButtons
import nz.musicbox.remote.ui.components.StatusMessage
import nz.musicbox.remote.ui.components.formatLength
import nz.musicbox.remote.ui.components.formatTime
import java.io.IOException

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailScaffold(
    title: String,
    go: Navigator,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (Modifier) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = { go.back() }) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Back")
                    }
                },
                actions = actions,
            )
        },
    ) { padding -> content(Modifier.padding(padding)) }
}

@Composable
fun ArtistScreen(container: AppContainer, go: Navigator, name: String) {
    val albumsFlow = remember(name) { container.library.albumsBy(name) }
    val albums by albumsFlow.collectAsStateWithLifecycle(initialValue = null)
    DetailScaffold(name, go) { modifier ->
        val list = albums ?: return@DetailScaffold
        if (list.isEmpty()) return@DetailScaffold StatusMessage("No albums found for $name.", modifier)
        LazyColumn(modifier) {
            items(list, key = { it.directory }) { album ->
                AlbumListItem(album, onClick = { go.album(album.directory) }, showArtist = false)
            }
        }
    }
}

@Composable
fun AlbumScreen(container: AppContainer, go: Navigator, directory: String) {
    val albumFlow = remember(directory) { container.library.album(directory) }
    val tracksFlow = remember(directory) { container.library.tracks(directory) }
    val album by albumFlow.collectAsStateWithLifecycle(initialValue = null)
    val tracks by tracksFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val player = container.player
    val files = tracks.map { it.file }

    DetailScaffold(
        album?.title.orEmpty(),
        go,
        actions = { album?.let { AddMenuButton(container, files, "“${it.title}”") } },
    ) { modifier ->
        val a = album ?: return@DetailScaffold StatusMessage("Loading…", modifier)
        LazyColumn(modifier) {
            item {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    CoverImage(a.directory, a.coverFile, 140.dp)
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(a.title, style = MaterialTheme.typography.titleLarge)
                        Text(
                            a.artist,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable { go.artist(a.artist) },
                        )
                        val tracksText = if (a.trackCount == 1) "1 track" else "${a.trackCount} tracks"
                        Text(
                            listOfNotNull(a.year, tracksText, formatLength(a.duration)).joinToString(" · "),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                PlayAddButtons(
                    onPlay = { player.playNow(files) },
                    onAdd = { player.addToQueue(files, "“${a.title}”") },
                    enabled = files.isNotEmpty(),
                )
            }
            itemsIndexed(tracks, key = { _, t -> t.file }) { index, track ->
                ListItem(
                    headlineContent = { Text(track.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    supportingContent = if (track.artist != a.artist) {
                        { Text(track.artist, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    } else {
                        null
                    },
                    leadingContent = {
                        Text(
                            (track.track ?: (index + 1)).toString(),
                            modifier = Modifier.widthIn(min = 24.dp),
                            textAlign = TextAlign.End,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            track.duration?.let { Text(formatTime(it)) }
                            AddMenuButton(container, listOf(track.file), "“${track.title}”")
                        }
                    },
                    modifier = Modifier.clickable { player.playNow(files, index) },
                )
            }
        }
    }
}

@Composable
fun FolderScreen(container: AppContainer, go: Navigator, path: String) {
    val folderName = path.substringAfterLast('/')
    DetailScaffold(
        folderName,
        go,
        actions = { AddMenuButton(container, listOf(path), "“$folderName”") },
    ) { modifier ->
        FolderContent(container, go, path, rememberLazyListState(), showActions = true, modifier = modifier)
    }
}

/** A folder's contents, read live from the server: subfolders first, then songs. */
@Composable
fun FolderContent(
    container: AppContainer,
    go: Navigator,
    path: String,
    listState: LazyListState,
    showActions: Boolean,
    modifier: Modifier = Modifier,
) {
    val connection by container.client.state.collectAsStateWithLifecycle()
    val connected = connection is ConnectionState.Connected
    var entries by remember(path) { mutableStateOf<List<LsEntry>?>(null) }
    var failed by remember(path) { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }

    LaunchedEffect(path, connected, attempt) {
        if (!connected) return@LaunchedEffect
        failed = false
        try {
            entries = container.library.folder(path)
        } catch (_: IOException) {
            failed = true
        } catch (_: MpdException) {
            failed = true
        }
    }

    val list = entries
    if (list == null) {
        if (failed) {
            Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Couldn't load this folder.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { attempt++ }) { Text("Try again") }
            }
        } else if (!connected) {
            StatusMessage("Folders will appear here once the app has connected to the Music Box.", modifier)
        }
        return
    }

    val folders = list.filterIsInstance<LsEntry.Directory>()
    val songs = list.filterIsInstance<LsEntry.File>().map { it.song }
    val files = songs.map { it.file }
    val player = container.player

    LazyColumn(modifier, state = listState) {
        if (showActions) {
            item {
                PlayAddButtons(
                    onPlay = { player.playNow(listOf(path)) },
                    onAdd = { player.addToQueue(listOf(path), "“${path.substringAfterLast('/')}”") },
                )
            }
        }
        items(folders, key = { "d:" + it.path }) { folder ->
            ListItem(
                headlineContent = { Text(folder.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingContent = { Icon(painterResource(R.drawable.ic_folder), contentDescription = null) },
                trailingContent = { AddMenuButton(container, listOf(folder.path), "“${folder.name}”") },
                modifier = Modifier.clickable { go.folder(folder.path) },
            )
        }
        itemsIndexed(songs, key = { _, s -> "f:" + s.file }) { index, song ->
            ListItem(
                headlineContent = { Text(song.displayTitle, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                supportingContent = song.displayArtist?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
                leadingContent = {
                    Text(
                        (song.displayTrack ?: (index + 1)).toString(),
                        modifier = Modifier.widthIn(min = 24.dp),
                        textAlign = TextAlign.End,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                trailingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        song.duration?.let { Text(formatTime(it)) }
                        AddMenuButton(container, listOf(song.file), "“${song.displayTitle}”")
                    }
                },
                modifier = Modifier.clickable { player.playNow(files, index) },
            )
        }
    }
}
