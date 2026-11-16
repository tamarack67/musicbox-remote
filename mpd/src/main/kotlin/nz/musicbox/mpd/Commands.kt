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

suspend fun MpdClient.stats(): MpdStats = MpdStats.parse(call("stats"))

/** Contents of one directory ("" for the top level). */
suspend fun MpdClient.lsinfo(path: String): List<LsEntry> =
    LsEntry.parseList(call(Mpd.command("lsinfo", path.ifEmpty { null })).fields)

/** Tags the library cache needs. Asking for only these keeps the full load small. */
val LIBRARY_TAGS = listOf("Artist", "AlbumArtist", "Album", "Title", "Track", "Disc", "Date")

/**
 * Every song in the database, fetched [chunkSize] at a time on a connection of its own
 * (a single reply for a large library would exceed MPD's output buffer).
 * [onProgress] is called with the running total.
 */
suspend fun MpdClient.loadAllSongs(chunkSize: Int = 5000, onProgress: (Int) -> Unit = {}): List<Song> =
    withDedicatedConnection { conn ->
        val v = conn.version
        if (!v.atLeast(0, 21)) {
            // Filter expressions with window need 0.21; older servers get one big listing.
            return@withDedicatedConnection parseSongs(conn.call("listallinfo").fields).also { onProgress(it.size) }
        }
        conn.call("tagtypes clear")
        conn.call(Mpd.command("tagtypes", "enable", *LIBRARY_TAGS.toTypedArray()))
        val all = ArrayList<Song>()
        var start = 0
        while (true) {
            val reply = conn.call(Mpd.command("find", "(file != '')", "window", "$start:${start + chunkSize}"))
            val batch = parseSongs(reply.fields)
            all += batch
            onProgress(all.size)
            if (batch.size < chunkSize) break
            start += chunkSize
        }
        all
    }

/** Replaces the queue with [uris] (files or folders) and starts playing at [playFrom]. */
suspend fun MpdClient.replaceQueueAndPlay(uris: List<String>, playFrom: Int = 0) {
    withConnection { conn ->
        conn.commandList(listOf("clear") + uris.map { Mpd.command("add", it) } + Mpd.command("play", playFrom))
    }
}

/** Appends [uris] (files or folders) to the queue. */
suspend fun MpdClient.addToQueue(uris: List<String>) {
    if (uris.isEmpty()) return
    withConnection { conn -> conn.commandList(uris.map { Mpd.command("add", it) }) }
}

// --- Queue ---

/**
 * The whole queue, fetched [chunkSize] songs at a time so a very long queue
 * (say, the whole library) doesn't exceed MPD's output buffer.
 */
suspend fun MpdClient.queue(chunkSize: Int = 1000): List<Song> {
    val all = ArrayList<Song>()
    while (true) {
        val start = all.size
        val batch = parseSongs(call(Mpd.command("playlistinfo", "$start:${start + chunkSize}")).fields)
        all += batch
        if (batch.size < chunkSize) return all
    }
}

suspend fun MpdClient.playId(id: Int) {
    call(Mpd.command("playid", id))
}

suspend fun MpdClient.deleteId(id: Int) {
    call(Mpd.command("deleteid", id))
}

/** Moves the song with queue id [id] to position [to]. */
suspend fun MpdClient.moveId(id: Int, to: Int) {
    call(Mpd.command("moveid", id, to))
}

/** Inserts [uri] at queue position [position]. Returns the new song's id. */
suspend fun MpdClient.addAt(uri: String, position: Int): Int? =
    call(Mpd.command("addid", uri, position))["Id"]?.toIntOrNull()

suspend fun MpdClient.clearQueue() {
    call("clear")
}

// --- Stored playlists ---

suspend fun MpdClient.playlists(): List<StoredPlaylist> = StoredPlaylist.parseList(call("listplaylists").fields)

suspend fun MpdClient.playlistSongs(name: String): List<Song> =
    parseSongs(call(Mpd.command("listplaylistinfo", name)).fields)

/** Appends a stored playlist to the queue. */
suspend fun MpdClient.loadPlaylist(name: String) {
    call(Mpd.command("load", name))
}

/** Replaces the queue with a stored playlist and plays from [playFrom]. */
suspend fun MpdClient.playPlaylist(name: String, playFrom: Int = 0) {
    withConnection { it.commandList(listOf("clear", Mpd.command("load", name), Mpd.command("play", playFrom))) }
}

/**
 * Saves the queue as [name]. With [replace], overwrites an existing playlist of that name
 * (`save NAME replace` on MPD 0.24+, `rm` then `save` before that); otherwise MPD refuses
 * if it exists.
 */
suspend fun MpdClient.saveQueueAs(name: String, replace: Boolean) {
    val v = version
    when {
        !replace -> call(Mpd.command("save", name))
        v != null && v.atLeast(0, 24) -> call(Mpd.command("save", name, "replace"))
        else -> {
            // rm fails if the playlist doesn't exist; that's fine.
            try {
                call(Mpd.command("rm", name))
            } catch (_: MpdException) {
            }
            call(Mpd.command("save", name))
        }
    }
}

suspend fun MpdClient.deletePlaylist(name: String) {
    call(Mpd.command("rm", name))
}
