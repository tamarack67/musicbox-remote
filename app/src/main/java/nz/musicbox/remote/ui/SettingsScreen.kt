package nz.musicbox.remote.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import nz.musicbox.mpd.Endpoint
import nz.musicbox.remote.AppContainer
import nz.musicbox.remote.R
import nz.musicbox.remote.data.FoundServer
import nz.musicbox.remote.data.ServerSettings
import nz.musicbox.remote.data.VolumeRange
import java.io.IOException

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(container: AppContainer, onBack: () -> Unit) {
    val settings = container.settings
    val client = container.client
    val saved by settings.server.collectAsStateWithLifecycle()
    val connection by client.state.collectAsStateWithLifecycle()
    val volumeSlider by container.preferences.volumeSlider.collectAsStateWithLifecycle()
    var host by rememberSaveable { mutableStateOf(saved?.host.orEmpty()) }
    var port by rememberSaveable { mutableStateOf((saved?.port ?: ServerSettings.DEFAULT_PORT).toString()) }
    val focus = LocalFocusManager.current

    fun useServer(endpoint: Endpoint?) {
        focus.clearFocus()
        settings.save(endpoint)
        client.reconnect()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Music Box server", style = MaterialTheme.typography.titleMedium)
            Text(
                describe(connection),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            FindMusicBox(container) { found ->
                host = found.endpoint.host
                port = found.endpoint.port.toString()
                useServer(found.endpoint)
            }

            OutlinedTextField(
                value = host,
                onValueChange = { host = it.trim() },
                label = { Text("Address") },
                placeholder = { Text("Find automatically") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = port,
                onValueChange = { port = it.filter(Char::isDigit).take(5) },
                label = { Text("Port") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Leave the address blank to find the Music Box automatically " +
                    "(${ServerSettings.DEFAULT_HOSTNAME}, then a network search). " +
                    "Whichever address works is remembered here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = {
                    val p = port.toIntOrNull()?.takeIf { it in 1..65535 } ?: ServerSettings.DEFAULT_PORT
                    port = p.toString()
                    useServer(host.takeIf { it.isNotBlank() }?.let { Endpoint(it, p) })
                },
            ) {
                Text("Save and reconnect")
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            Text("Volume", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Volume slider on Now playing", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Off: use the amplifier's knob, and the Music Box stays at full line level. " +
                            "On: a slider covering the Music Box's ${VolumeRange.MIN} to ${VolumeRange.MAX} " +
                            "(below ${VolumeRange.MIN} is silent). The app only changes the volume " +
                            "when you move the slider.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(16.dp))
                Switch(checked = volumeSlider, onCheckedChange = container.preferences::setVolumeSlider)
            }
        }
    }
}

/** "Find my Music Box": searches the network for MPD servers and lists them to pick from. */
@Composable
private fun FindMusicBox(container: AppContainer, onPick: (FoundServer) -> Unit) {
    val scope = rememberCoroutineScope()
    var search by remember { mutableStateOf<Job?>(null) }
    var searched by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val found = remember { mutableStateListOf<FoundServer>() }
    val searching = search?.isActive == true

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedButton(
            enabled = !searching,
            onClick = {
                found.clear()
                error = null
                searched = false
                search = scope.launch {
                    try {
                        container.mdns.discover(ServerSettings.MDNS_SERVICE_TYPE, timeoutMs = 8_000).collect { server ->
                            // The same server can be seen several times (IPv4 and IPv6, or two networks).
                            val existing = found.indexOfFirst { it.name == server.name }
                            when {
                                existing < 0 -> found += server
                                // Prefer an IPv4 address: easier to read, and works everywhere.
                                ':' in found[existing].endpoint.host && ':' !in server.endpoint.host ->
                                    found[existing] = server
                            }
                        }
                    } catch (e: IOException) {
                        error = e.message
                    } finally {
                        searched = true
                        search = null
                    }
                }
            },
        ) {
            if (searching) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Text("  Searching the network…")
            } else {
                Icon(painterResource(R.drawable.ic_search), contentDescription = null, modifier = Modifier.size(18.dp))
                Text("  Find my Music Box")
            }
        }
        for (server in found) {
            ListItem(
                headlineContent = { Text(server.name) },
                supportingContent = { Text("${server.endpoint.host}, port ${server.endpoint.port} · tap to use") },
                leadingContent = { Icon(painterResource(R.drawable.ic_equalizer), contentDescription = null) },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                modifier = Modifier.fillMaxWidth().clickable { onPick(server) },
            )
        }
        val message = when {
            error != null -> "The network search didn't work: $error"
            searched && found.isEmpty() ->
                "No Music Box found. Check that the phone is on the same Wi-Fi as the Music Box, and that it's switched on."
            else -> null
        }
        if (message != null) {
            Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
