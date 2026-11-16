package nz.musicbox.mpd

enum class PlayState { PLAY, PAUSE, STOP }

/** Reply to `status`. */
data class MpdStatus(
    val state: PlayState,
    val songPos: Int?,
    val songId: Int?,
    /** Seconds into the current song. */
    val elapsed: Double?,
    /** Length of the current song in seconds. */
    val duration: Double?,
    val random: Boolean,
    val repeat: Boolean,
    /** "0", "1" or "oneshot". */
    val single: String,
    val consume: String,
    val playlistVersion: Long,
    val playlistLength: Int,
    /** 0 to 100, or null if MPD has no mixer. */
    val volume: Int?,
    val error: String?,
    /** Job id while a database update is running. */
    val updatingDb: Int?,
) {
    companion object {
        fun parse(r: MpdResponse): MpdStatus {
            // Older servers only send "time: elapsed:total" (whole seconds).
            val time = r["time"]?.split(':')
            return MpdStatus(
                state = when (r["state"]) {
                    "play" -> PlayState.PLAY
                    "pause" -> PlayState.PAUSE
                    else -> PlayState.STOP
                },
                songPos = r["song"]?.toIntOrNull(),
                songId = r["songid"]?.toIntOrNull(),
                elapsed = r["elapsed"]?.toDoubleOrNull() ?: time?.getOrNull(0)?.toDoubleOrNull(),
                duration = r["duration"]?.toDoubleOrNull() ?: time?.getOrNull(1)?.toDoubleOrNull(),
                random = r["random"] == "1",
                repeat = r["repeat"] == "1",
                single = r["single"] ?: "0",
                consume = r["consume"] ?: "0",
                playlistVersion = r["playlist"]?.toLongOrNull() ?: 0,
                playlistLength = r["playlistlength"]?.toIntOrNull() ?: 0,
                volume = r["volume"]?.toIntOrNull()?.takeIf { it >= 0 },
                error = r["error"],
                updatingDb = r["updating_db"]?.toIntOrNull(),
            )
        }
    }
}

/** One song, as returned by `currentsong`, `playlistinfo`, `find` and similar. */
data class Song(
    val file: String,
    val title: String?,
    val artist: String?,
    val album: String?,
    val albumArtist: String?,
    val track: Int?,
    val disc: Int?,
    val date: String?,
    /** Seconds. */
    val duration: Double?,
    /** Position in the queue, if the song came from the queue. */
    val pos: Int?,
    /** Queue id, if the song came from the queue. */
    val id: Int?,
) {
    private val fromPath by lazy { PathTags.derive(file) }

    // Display values: tags where present, otherwise derived from the file path.
    val displayTitle: String get() = title ?: fromPath.title
    val displayArtist: String? get() = artist ?: albumArtist ?: fromPath.artist
    val displayAlbum: String? get() = album ?: fromPath.album
    val displayTrack: Int? get() = track ?: fromPath.track

    companion object {
        fun parse(fields: List<Pair<String, String>>): Song {
            fun first(key: String) =
                fields.firstOrNull { it.first.equals(key, ignoreCase = true) }?.second?.takeIf { it.isNotBlank() }
            return Song(
                file = first("file") ?: "",
                title = first("Title"),
                artist = first("Artist"),
                album = first("Album"),
                albumArtist = first("AlbumArtist"),
                track = leadingNumber(first("Track")),
                disc = leadingNumber(first("Disc")),
                date = first("Date"),
                duration = first("duration")?.toDoubleOrNull() ?: first("Time")?.toDoubleOrNull(),
                pos = first("Pos")?.toIntOrNull(),
                id = first("Id")?.toIntOrNull(),
            )
        }

        /** "3", "03" and "3/12" all give 3. */
        private fun leadingNumber(text: String?): Int? =
            text?.trim()?.takeWhile(Char::isDigit)?.toIntOrNull()
    }
}
