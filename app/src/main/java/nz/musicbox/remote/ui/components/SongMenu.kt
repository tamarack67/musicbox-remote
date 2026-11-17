package nz.musicbox.remote.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nz.musicbox.remote.AppContainer
import nz.musicbox.remote.R

/**
 * A ⋮ button offering "Add to queue" and "Add to playlist…" for [uris] (one song, an album's
 * songs, or a folder). [what] names them in confirmations, e.g. “Bruise Violet”.
 */
@Composable
fun AddMenuButton(container: AppContainer, uris: List<String>, what: String, enabled: Boolean = true) {
    var open by remember { mutableStateOf(false) }
    var pickPlaylist by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, enabled = enabled && uris.isNotEmpty()) {
            Icon(painterResource(R.drawable.ic_more_vert), contentDescription = "More options")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Add to queue") },
                leadingIcon = { Icon(painterResource(R.drawable.ic_queue_music), contentDescription = null) },
                onClick = {
                    open = false
                    container.player.addToQueue(uris, what)
                },
            )
            DropdownMenuItem(
                text = { Text("Add to playlist…") },
                leadingIcon = { Icon(painterResource(R.drawable.ic_playlist_add), contentDescription = null) },
                onClick = {
                    open = false
                    pickPlaylist = true
                },
            )
        }
    }
    if (pickPlaylist) {
        AddToPlaylistDialog(
            container = container,
            onPick = { name ->
                pickPlaylist = false
                container.playlists.add(name, uris, what)
            },
            onDismiss = { pickPlaylist = false },
        )
    }
}

/** Pick a saved playlist, or name a new one. */
@Composable
fun AddToPlaylistDialog(container: AppContainer, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val playlists by container.playlists.playlists.collectAsStateWithLifecycle()
    val names = playlists.orEmpty().map { it.name }
    var creating by rememberSaveable { mutableStateOf(names.isEmpty()) }
    var newName by rememberSaveable { mutableStateOf("") }
    val trimmed = newName.trim()
    // MPD playlist names can't contain slashes or newlines.
    val valid = trimmed.isNotEmpty() && '/' !in trimmed

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (creating) "New playlist" else "Add to playlist") },
        text = {
            if (creating) {
                val focus = remember { FocusRequester() }
                LaunchedEffect(Unit) { focus.requestFocus() }
                Column {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it.replace("\n", "") },
                        label = { Text("Playlist name") },
                        singleLine = true,
                        isError = '/' in trimmed,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Sentences,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(onDone = { if (valid) onPick(trimmed) }),
                        modifier = Modifier.focusRequester(focus),
                    )
                    val note = when {
                        '/' in trimmed -> "Names can't contain /"
                        trimmed in names -> "“$trimmed” already exists; the songs will be added to the end of it."
                        else -> null
                    }
                    if (note != null) {
                        Text(
                            note,
                            style = MaterialTheme.typography.bodySmall,
                            color = if ('/' in trimmed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            } else {
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    item {
                        PickRow("New playlist…", R.drawable.ic_playlist_add) { creating = true }
                    }
                    items(names, key = { it }) { name ->
                        PickRow(name, R.drawable.ic_queue_music) { onPick(name) }
                    }
                }
            }
        },
        confirmButton = {
            if (creating) {
                TextButton(onClick = { onPick(trimmed) }, enabled = valid) { Text("Add") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun PickRow(text: String, icon: Int, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingContent = { Icon(painterResource(icon), contentDescription = null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
    )
}
