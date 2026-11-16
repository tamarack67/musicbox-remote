package nz.musicbox.remote.data.library

import nz.musicbox.mpd.PathTags
import nz.musicbox.mpd.Song
import java.text.Normalizer

class LibraryData(val songs: List<SongEntity>, val albums: List<AlbumEntity>)

/**
 * Turns the server's song list into cache rows. Each folder of songs is one album, which suits
 * the library's `<Letter>/<Artist>/<Album>/` layout and copes with untagged files.
 */
fun buildLibrary(songs: List<Song>): LibraryData {
    val songRows = ArrayList<SongEntity>(songs.size)
    val albumRows = ArrayList<AlbumEntity>()
    val visible = songs.filterNot { isHiddenPath(it.file) }
    for ((directory, tracks) in visible.groupBy { it.file.substringBeforeLast('/', "") }) {
        val fromPath = PathTags.derive(tracks.first().file)
        val artist = mostCommon(tracks.mapNotNull { it.albumArtist })
            ?: tracks.mapNotNull { it.artist }.distinct().singleOrNull()
            ?: fromPath.artist
            ?: tracks.firstNotNullOfOrNull { it.artist }
            ?: UNKNOWN_ARTIST
        val title = mostCommon(tracks.mapNotNull { it.album })
            ?: directory.substringAfterLast('/').ifEmpty { UNKNOWN_ALBUM }
        val year = tracks.mapNotNull { yearOf(it.date) }.minOrNull()
        val first = tracks.minWith(compareBy({ it.disc ?: 0 }, { it.displayTrack ?: Int.MAX_VALUE }, { it.file }))

        albumRows += AlbumEntity(
            directory = directory,
            title = title,
            artist = artist,
            year = year,
            trackCount = tracks.size,
            duration = tracks.sumOf { it.duration ?: 0.0 },
            searchText = searchKey("$title $artist"),
            artistSearchText = searchKey(artist),
            coverFile = first.file,
        )
        for (song in tracks) {
            val songTitle = song.displayTitle
            val songArtist = song.artist ?: artist
            songRows += SongEntity(
                file = song.file,
                title = songTitle,
                artist = songArtist,
                album = title,
                albumArtist = artist,
                track = song.displayTrack,
                disc = song.disc,
                year = yearOf(song.date) ?: year,
                duration = song.duration,
                directory = directory,
                searchText = searchKey("$songTitle $songArtist $title"),
            )
        }
    }
    return LibraryData(songRows, albumRows)
}

/**
 * True if any folder or file in [path] is a system or hidden one, which never holds music
 * the owner wants to see:
 * - Windows system folders such as `$RECYCLE.BIN` and `System Volume Information`;
 * - Unix/macOS hidden names starting with a single dot (`.Trashes`, `._song.mp3`).
 *
 * Deliberately narrow, so real names like "$uicideboy$" or "...And You Will Know Us by the
 * Trail of Dead" stay visible.
 */
fun isHiddenPath(path: String): Boolean = path.split('/').any { name ->
    name.lowercase() in WINDOWS_SYSTEM_FOLDERS ||
        (name.length > 1 && name[0] == '.' && name[1] != '.')
}

private val WINDOWS_SYSTEM_FOLDERS = setOf("\$recycle.bin", "recycler", "system volume information")

/** Lower-case with accents removed, so "Björk" matches "bjork". */
fun searchKey(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFD)
        .replace(COMBINING_MARKS, "")
        .lowercase()

/** "1998- 7- 8", "1998-07-08" and "1998" all give "1998". */
fun yearOf(date: String?): String? = date?.let { YEAR.find(it)?.value }

private fun mostCommon(values: List<String>): String? =
    values.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key

private val COMBINING_MARKS = Regex("\\p{Mn}+")
private val YEAR = Regex("\\d{4}")
const val UNKNOWN_ARTIST = "Unknown artist"
const val UNKNOWN_ALBUM = "Unknown album"
