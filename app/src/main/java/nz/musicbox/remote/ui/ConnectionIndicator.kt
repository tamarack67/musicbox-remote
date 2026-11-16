package nz.musicbox.remote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import nz.musicbox.mpd.ConnectionState

/** A quiet one-line status shown only while not connected. Tap to retry immediately. */
@Composable
fun ConnectionIndicator(state: ConnectionState, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val text = when (state) {
        ConnectionState.Connecting -> "Connecting…"
        is ConnectionState.Waiting -> "Can't reach the Music Box. Retrying…"
        is ConnectionState.Connected, ConnectionState.Stopped -> null
    }
    if (text == null) {
        Spacer(modifier)
        return
    }
    val dot = if (state is ConnectionState.Waiting) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline
    Row(
        modifier = modifier
            .clickable(onClick = onRetry)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).background(dot, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Longer description, for Settings. */
fun describe(state: ConnectionState): String = when (state) {
    is ConnectionState.Connected -> "Connected to ${state.endpoint} (MPD ${state.version})"
    ConnectionState.Connecting -> "Connecting…"
    is ConnectionState.Waiting -> "Not connected: ${state.reason}"
    ConnectionState.Stopped -> "Not connected"
}
