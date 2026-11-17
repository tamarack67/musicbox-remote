package nz.musicbox.remote.data

import nz.musicbox.mpd.Song
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** Writing playlists out as text or .m3u8, and reading .m3u / .m3u8 files back in. */
object PlaylistFiles {

    /** A readable list for sharing: "1. Artist – Title (Album, 1992)". */
    fun trackList(name: String, songs: List<Song>): String = buildString {
        val count = if (songs.size == 1) "1 song" else "${songs.size} songs"
        val minutes = (songs.sumOf { it.duration ?: 0.0 } / 60).toLong()
        val length = if (minutes >= 60) "${minutes / 60} hr ${minutes % 60} min" else "$minutes min"
        append("$name ($count, $length)\n\n")
        songs.forEachIndexed { i, song ->
            append("${i + 1}. ")
            song.displayArtist?.let { append(it).append(" – ") }
            append(song.displayTitle)
            val details = listOfNotNull(song.displayAlbum, song.date?.take(4)?.takeIf { it.all(Char::isDigit) })
            if (details.isNotEmpty()) append(" (").append(details.joinToString(", ")).append(")")
            append('\n')
        }
    }

    /**
     * An extended M3U playlist (UTF-8). Paths are relative to the music library's top folder,
     * as MPD stores them, so the file works in other players if placed in that top folder.
     */
    fun m3u8(name: String, songs: List<Song>): String = buildString {
        append("#EXTM3U\n")
        append("#PLAYLIST:").append(name.replace('\n', ' ')).append('\n')
        for (song in songs) {
            val seconds = song.duration?.toLong() ?: -1
            val label = listOfNotNull(song.displayArtist, song.displayTitle).joinToString(" - ")
            append("#EXTINF:").append(seconds).append(',').append(label.replace('\n', ' ')).append('\n')
            append(song.file).append('\n')
        }
    }

    /** A file name that's safe on phones and PCs. */
    fun safeFileName(name: String): String =
        name.replace(Regex("""[\\/:*?"<>|\r\n]"""), "_").trim().ifEmpty { "Playlist" }

    /** Text of a playlist file: UTF-8 if it is valid UTF-8, otherwise Windows-1252 (old .m3u files). */
    fun decode(bytes: ByteArray): String {
        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            String(bytes, Charset.forName("windows-1252"))
        }
        return text.removePrefix("\uFEFF")
    }

    data class Parsed(val name: String?, val entries: List<String>)

    /** The paths in an .m3u / .m3u8 file, and its #PLAYLIST name if it has one. */
    fun parse(text: String): Parsed {
        var name: String? = null
        val entries = ArrayList<String>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.isEmpty() -> {}
                line.startsWith("#PLAYLIST:", ignoreCase = true) -> name = line.substringAfter(':').trim().ifEmpty { null }
                line.startsWith("#") -> {}
                else -> entries += line
            }
        }
        return Parsed(name, entries)
    }

    data class Matched(val uris: List<String>, val missing: List<String>)

    /**
     * Matches playlist [entries] to songs in the library ([libraryFiles], MPD paths like
     * "B/Babes in Toyland/Fontanelle/01 - Bruise Violet.mp3").
     *
     * Entries may be MPD paths, or full paths from another computer ("E:\B\Babes in Toyland\…",
     * "/mnt/music/B/…", "file:///…"): the longest tail of the path that is a library path wins.
     * Letter case is ignored if there's no exact match.
     */
    fun match(entries: List<String>, libraryFiles: Collection<String>): Matched {
        val exact = libraryFiles.toHashSet()
        val folded = HashMap<String, String>(libraryFiles.size * 2)
        for (f in libraryFiles) folded.putIfAbsent(f.lowercase(), f)

        val uris = ArrayList<String>()
        val missing = ArrayList<String>()
        for (entry in entries) {
            val found = candidates(normalise(entry)).firstNotNullOfOrNull { path ->
                path.takeIf { it in exact } ?: folded[path.lowercase()]
            }
            if (found != null) uris += found else missing += entry
        }
        return Matched(uris, missing)
    }

    private fun normalise(entry: String): String {
        var path = entry.trim()
        if (path.startsWith("file:", ignoreCase = true)) {
            path = path.substringAfter(':').trimStart('/')
            path = try {
                URLDecoder.decode(path.replace("+", "%2B"), "UTF-8")
            } catch (_: IllegalArgumentException) {
                path
            }
        }
        return path.replace('\\', '/').removePrefix("./")
    }

    /** The whole path, then with leading folders removed one at a time. */
    private fun candidates(path: String): Sequence<String> {
        val parts = path.split('/').filter { it.isNotEmpty() && it != "." }
        return (parts.indices).asSequence().map { parts.subList(it, parts.size).joinToString("/") }
    }
}
