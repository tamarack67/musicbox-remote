package nz.musicbox.mpd

import java.util.Locale

// Typed wrappers for the commands the app uses. Version-dependent commands check
// the server version from the greeting line.

suspend fun MpdClient.status(): MpdStatus = MpdStatus.parse(call("status"))

/** The current song, or null if the queue has no current song. */
suspend fun MpdClient.currentSong(): Song? {
    val r = call("currentsong")
    return if (r.fields.isEmpty()) null else Song.parse(r.fields)
}

suspend fun MpdClient.play() {
    call("play")
}

suspend fun MpdClient.setPaused(paused: Boolean) {
    call(Mpd.command("pause", if (paused) 1 else 0))
}

suspend fun MpdClient.next() {
    call("next")
}

suspend fun MpdClient.previous() {
    call("previous")
}

/** Seeks within the current song. Uses `seekcur` (MPD 0.17+), falling back to `seekid`. */
suspend fun MpdClient.seekCurrent(seconds: Double) {
    val v = version
    if (v == null || v.atLeast(0, 17)) {
        call(Mpd.command("seekcur", String.format(Locale.ROOT, "%.3f", seconds.coerceAtLeast(0.0))))
    } else {
        val id = status().songId ?: return
        call(Mpd.command("seekid", id, seconds.toInt().coerceAtLeast(0)))
    }
}

suspend fun MpdClient.setRandom(on: Boolean) {
    call(Mpd.command("random", if (on) 1 else 0))
}

suspend fun MpdClient.setRepeat(on: Boolean) {
    call(Mpd.command("repeat", if (on) 1 else 0))
}
