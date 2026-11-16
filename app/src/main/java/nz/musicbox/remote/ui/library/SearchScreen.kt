package nz.musicbox.remote.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import nz.musicbox.remote.AppContainer
import nz.musicbox.remote.R
import nz.musicbox.remote.data.library.SearchResults
import nz.musicbox.remote.ui.Navigator
import nz.musicbox.remote.ui.components.AlbumListItem
import nz.musicbox.remote.ui.components.ArtistListItem
import nz.musicbox.remote.ui.components.SectionHeader
import nz.musicbox.remote.ui.components.StatusMessage

@Composable
fun SearchScreen(container: AppContainer, go: Navigator) {
    var query by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    var autoFocused by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!autoFocused) {
            focus.requestFocus()
            autoFocused = true
        }
    }

    // Re-runs whenever the query changes; the delay means typing doesn't search on every key.
    val results by produceState<SearchResults?>(initialValue = null, query) {
        if (query.isBlank()) {
            value = null
            return@produceState
        }
        delay(250)
        value = container.library.search(query)
    }

    Column(Modifier.statusBarsPadding()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Artists, albums, songs") },
            leadingIcon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) {
                        Icon(painterResource(R.drawable.ic_close), contentDescription = "Clear")
                    }
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .focusRequester(focus),
        )

        val r = results
        when {
            query.isBlank() -> StatusMessage("Search across artists, albums and song titles.")
            r == null -> {}
            r.isEmpty -> StatusMessage("Nothing found for “$query”.")
            else -> LazyColumn {
                if (r.artists.isNotEmpty()) {
                    item { SectionHeader("Artists") }
                    items(r.artists, key = { "a:" + it.name }) { artist ->
                        ArtistListItem(artist, onClick = { go.artist(artist.name) })
                    }
                }
                if (r.albums.isNotEmpty()) {
                    item { SectionHeader("Albums") }
                    items(r.albums, key = { "b:" + it.directory }) { album ->
                        AlbumListItem(album, onClick = { go.album(album.directory) })
                    }
                }
                if (r.songs.isNotEmpty()) {
                    item { SectionHeader("Songs") }
                    items(r.songs, key = { "s:" + it.file }) { song ->
                        ListItem(
                            headlineContent = { Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = {
                                Text("${song.artist} · ${song.album}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            },
                            trailingContent = {
                                IconButton(
                                    onClick = {
                                        container.player.addToQueue(listOf(song.file), "“${song.title}”")
                                    },
                                ) {
                                    Icon(painterResource(R.drawable.ic_playlist_add), contentDescription = "Add to queue")
                                }
                            },
                            modifier = Modifier.clickable { go.album(song.directory) },
                        )
                    }
                }
            }
        }
    }
}
