package nz.musicbox.remote.ui.queue

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nz.musicbox.mpd.Song
import nz.musicbox.remote.AppContainer
import nz.musicbox.remote.R
import nz.musicbox.remote.data.PlaylistFiles
import java.io.File
import java.io.IOException

private const val M3U_TYPE = "audio/x-mpegurl"

/** Share menu for a playlist: as a readable track list, as an .m3u8 file, or saved as a file. */
@Composable
fun SharePlaylistButton(name: String, songs: List<Song>) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val current by rememberUpdatedState(songs)
    var open by remember { mutableStateOf(false) }

    val saveFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(M3U_TYPE)) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = PlaylistFiles.m3u8(name, current)
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } != null
                } catch (_: IOException) {
                    false
                }
            }
            Toast.makeText(context, if (ok) "Saved “$name”" else "Couldn't save the file", Toast.LENGTH_SHORT).show()
        }
    }

    Box {
        IconButton(onClick = { open = true }, enabled = songs.isNotEmpty()) {
            Icon(painterResource(R.drawable.ic_share), contentDescription = "Share or export")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Share as a track list") },
                onClick = {
                    open = false
                    shareText(context, name, PlaylistFiles.trackList(name, current))
                },
            )
            DropdownMenuItem(
                text = { Text("Share as a playlist file") },
                onClick = {
                    open = false
                    scope.launch { shareFile(context, name, PlaylistFiles.m3u8(name, current)) }
                },
            )
            DropdownMenuItem(
                text = { Text("Save playlist file…") },
                onClick = {
                    open = false
                    saveFile.launch(PlaylistFiles.safeFileName(name) + ".m3u8")
                },
            )
        }
    }
}

private fun shareText(context: Context, name: String, text: String) {
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, name)
        .putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(send, "Share “$name”"))
}

private suspend fun shareFile(context: Context, name: String, text: String) {
    val file = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() } // only ever keep the latest export
        File(dir, PlaylistFiles.safeFileName(name) + ".m3u8").apply { writeText(text) }
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val send = Intent(Intent.ACTION_SEND)
        .setType(M3U_TYPE)
        .putExtra(Intent.EXTRA_SUBJECT, name)
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    send.clipData = ClipData.newRawUri(name, uri)
    context.startActivity(Intent.createChooser(send, "Share “$name”"))
}

/** What an imported file turned out to contain, for the confirmation dialog. */
data class ImportPreview(
    val suggestedName: String,
    val total: Int,
    val uris: List<String>,
    val missing: List<String>,
)

/** "Import playlist file" button: pick an .m3u / .m3u8 file, check it, then confirm. */
@Composable
fun ImportPlaylistButton(container: AppContainer) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var preview by remember { mutableStateOf<ImportPreview?>(null) }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) { readPlaylist(context, uri, container) }
            if (result == null) {
                Toast.makeText(context, "Couldn't read that file", Toast.LENGTH_SHORT).show()
            } else {
                preview = result
            }
        }
    }

    IconButton(onClick = { pick.launch(arrayOf("*/*")) }) {
        Icon(painterResource(R.drawable.ic_file_download), contentDescription = "Import playlist file")
    }

    preview?.let { p ->
        ImportDialog(
            container = container,
            preview = p,
            onImport = { name ->
                preview = null
                container.playlists.import(name, p.uris)
            },
            onDismiss = { preview = null },
        )
    }
}

private suspend fun readPlaylist(context: Context, uri: Uri, container: AppContainer): ImportPreview? {
    val bytes = try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
                if (out.size() > MAX_IMPORT_BYTES) return null // not a playlist
            }
            out.toByteArray()
        }
    } catch (_: IOException) {
        null
    } ?: return null
    val parsed = PlaylistFiles.parse(PlaylistFiles.decode(bytes))
    val fileName = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { if (it.moveToFirst()) it.getString(0) else null }
    val matched = PlaylistFiles.match(parsed.entries, container.library.allFiles())
    return ImportPreview(
        suggestedName = (parsed.name ?: fileName?.substringBeforeLast('.'))?.replace("/", "-")?.trim()
            ?.ifEmpty { null } ?: "Imported playlist",
        total = parsed.entries.size,
        uris = matched.uris,
        missing = matched.missing,
    )
}

private const val MAX_IMPORT_BYTES = 10 * 1024 * 1024

@Composable
private fun ImportDialog(
    container: AppContainer,
    preview: ImportPreview,
    onImport: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val playlists by container.playlists.playlists.collectAsStateWithLifecycle()
    var name by remember { mutableStateOf(preview.suggestedName) }
    val trimmed = name.trim()
    val exists = playlists.orEmpty().any { it.name == trimmed }
    val canImport = preview.uris.isNotEmpty() && trimmed.isNotEmpty() && '/' !in trimmed

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import playlist") },
        text = {
            Column {
                val found = preview.uris.size
                Text(
                    when {
                        preview.total == 0 -> "That file doesn't list any songs."
                        found == preview.total -> "All $found songs were found in your library."
                        found == 0 -> "None of the ${preview.total} songs were found in your library."
                        else -> "$found of ${preview.total} songs were found in your library. " +
                            "The rest will be left out."
                    },
                )
                if (preview.missing.isNotEmpty()) {
                    val shown = preview.missing.take(4).joinToString("\n") { it.substringAfterLast('/').substringAfterLast('\\') }
                    val more = preview.missing.size - 4
                    Text(
                        "Not found:\n$shown" + if (more > 0) "\n…and $more more" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                if (preview.uris.isNotEmpty()) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it.replace("\n", "") },
                        label = { Text("Playlist name") },
                        singleLine = true,
                        isError = '/' in trimmed,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        modifier = Modifier.padding(top = 16.dp),
                    )
                    if (exists) {
                        Text(
                            "A playlist called “$trimmed” already exists. Importing will replace it.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (preview.uris.isNotEmpty()) {
                TextButton(onClick = { onImport(trimmed) }, enabled = canImport) {
                    Text(if (exists) "Replace" else "Import")
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
