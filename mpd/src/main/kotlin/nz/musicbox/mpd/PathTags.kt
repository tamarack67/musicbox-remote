package nz.musicbox.mpd

/**
 * Artist, album, track number and title guessed from a file path, for files with no tags.
 * Expects `.../<Artist>/<Album>/<NN> - <Title>.<ext>` and degrades gracefully for shallower paths.
 *
 * File names seen in the library, all handled:
 * - `01 - Title`, `1. Title`, `01-Title`, `01 Title`, `10 Title`
 * - `Artist - Album - 01 Title`, `Artist - Album - 01 - Title`
 * - `Artist-Album-01-Title`, `Artist-Album EP-1-Title`, `Artist-Album-disc1-01-Title`
 *
 * A bare two-digit number followed by a space counts as a track number, because that is how
 * this library names files; an untagged "99 Luftballons.wav" would lose its "99".
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
        // "01 Title", "12 Title"
        private val PADDED = Regex("""^(\d{2})\s+(.+)$""")
        // "Artist - Album - 01 Title", "Artist-Album-01-Title", "Artist-Album-1-Title".
        // The shortest prefix wins. A prefix ending in a four-digit number is skipped, so a
        // date such as "1999-10-18" isn't read as track 10.
        private val INFIX = Regex(
            """^(.+?)(?<!\d{4})(?:(?:\s*-\s*|_)(\d{2,3})(?:\s*-\s*|\s+|\.\s+|_)|-(\d)-)(.+)$""",
        )

        fun derive(path: String): PathTags {
            val parts = path.trim('/').split('/').filter { it.isNotEmpty() }
            val fileName = parts.lastOrNull() ?: path
            val base = fileName.substringBeforeLast('.', fileName).trim()
            val (track, title) = numberAndTitle(base)
            return PathTags(
                artist = parts.getOrNull(parts.size - 3),
                album = parts.getOrNull(parts.size - 2),
                track = track,
                title = title,
            )
        }

        private fun numberAndTitle(base: String): Pair<Int?, String> {
            NUMBERED.matchEntire(base)?.let { m ->
                val rest = m.groupValues[2].trim()
                if (rest.isNotEmpty()) return m.groupValues[1].toInt() to rest
            }
            PADDED.matchEntire(base)?.let { m ->
                val rest = m.groupValues[2].trim()
                // "72 Megabytes - Album - 08 Title": the leading number belongs to the artist.
                if (rest.isNotEmpty() && INFIX.matchEntire(rest) == null) return m.groupValues[1].toInt() to rest
            }
            val infix = INFIX.matchEntire(base)
            if (infix != null) {
                val number = infix.groupValues[2].ifEmpty { infix.groupValues[3] }
                val rest = infix.groupValues[4].trim()
                if (rest.isNotEmpty()) return number.toInt() to rest
            }
            return null to base
        }
    }
}
