package nz.musicbox.mpd

/**
 * Artist, album, track number and title guessed from a file path, for files with no tags.
 * Expects `.../<Artist>/<Album>/<NN> - <Title>.<ext>` and degrades gracefully for shallower paths.
 */
data class PathTags(
    val artist: String?,
    val album: String?,
    val track: Int?,
    val title: String,
) {
    companion object {
        // "01 - Title", "1. Title", "01-Title"
        private val NUMBERED = Regex("""^(\d{1,3})\s*[-.]\s*(.+)$""")
        // "01 Title" (zero-padded only, so "99 Luftballons" stays a title)
        private val PADDED = Regex("""^(0\d)\s+(.+)$""")

        fun derive(path: String): PathTags {
            val parts = path.trim('/').split('/').filter { it.isNotEmpty() }
            val fileName = parts.lastOrNull() ?: path
            val base = fileName.substringBeforeLast('.', fileName).trim()
            val match = NUMBERED.matchEntire(base) ?: PADDED.matchEntire(base)
            val track = match?.groupValues?.get(1)?.toIntOrNull()
            val title = match?.groupValues?.get(2)?.trim()?.takeIf { it.isNotEmpty() } ?: base
            return PathTags(
                artist = parts.getOrNull(parts.size - 3),
                album = parts.getOrNull(parts.size - 2),
                track = track,
                title = title,
            )
        }
    }
}
