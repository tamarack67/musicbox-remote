package nz.musicbox.remote.ui.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nz.musicbox.remote.AppContainer
import nz.musicbox.remote.R
import nz.musicbox.remote.data.library.LibrarySync
import nz.musicbox.remote.ui.Navigator
import nz.musicbox.remote.ui.components.AlbumListItem
import nz.musicbox.remote.ui.components.ArtistListItem
import nz.musicbox.remote.ui.components.StatusMessage

private val TABS = listOf("Artists", "Albums", "Folders")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(container: AppContainer, go: Navigator) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    // One scroll position per tab, kept while switching tabs and visiting detail screens.
    val artistsList = rememberLazyListState()
    val albumsList = rememberLazyListState()
    val foldersList = rememberLazyListState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Library") },
                actions = {
                    IconButton(onClick = go::settings) {
                        Icon(painterResource(R.drawable.ic_settings), contentDescription = "Settings")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            PrimaryTabRow(selectedTabIndex = tab) {
                TABS.forEachIndexed { i, title ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(title) })
                }
            }
            SyncBanner(container)
            when (tab) {
                0 -> ArtistsTab(container, go, artistsList)
                1 -> AlbumsTab(container, go, albumsList)
                else -> FolderContent(container, go, path = "", listState = foldersList, showActions = false)
            }
        }
    }
}

@Composable
private fun SyncBanner(container: AppContainer) {
    val sync by container.library.sync.collectAsStateWithLifecycle()
    when (val s = sync) {
        is LibrarySync.Loading -> Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                "Updating library: %,d of %,d songs".format(s.loaded, s.total),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LinearProgressIndicator(
                progress = { if (s.total == 0) 0f else s.loaded.toFloat() / s.total },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
        }
        is LibrarySync.Failed -> Text(
            "Couldn't update the library: ${s.message}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        LibrarySync.Idle -> {}
    }
}

@Composable
private fun EmptyLibrary(container: AppContainer) {
    val sync by container.library.sync.collectAsStateWithLifecycle()
    StatusMessage(
        if (sync is LibrarySync.Loading) {
            "Loading your library for the first time. This takes about half a minute."
        } else {
            "Your library will appear here once the app has connected to the Music Box."
        },
    )
}

@Composable
private fun ArtistsTab(container: AppContainer, go: Navigator, listState: LazyListState) {
    val artists by container.library.artists.collectAsStateWithLifecycle(initialValue = null)
    val list = artists ?: return
    if (list.isEmpty()) return EmptyLibrary(container)
    LazyColumn(state = listState) {
        items(list, key = { it.name }) { artist ->
            ArtistListItem(artist, onClick = { go.artist(artist.name) })
        }
    }
}

@Composable
private fun AlbumsTab(container: AppContainer, go: Navigator, listState: LazyListState) {
    val albums by container.library.albums.collectAsStateWithLifecycle(initialValue = null)
    val list = albums ?: return
    if (list.isEmpty()) return EmptyLibrary(container)
    LazyColumn(state = listState) {
        items(list, key = { it.directory }) { album ->
            AlbumListItem(album, onClick = { go.album(album.directory) })
        }
    }
}
