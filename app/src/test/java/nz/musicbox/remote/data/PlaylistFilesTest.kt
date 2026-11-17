package nz.musicbox.remote.data

import nz.musicbox.mpd.Song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlaylistFilesTest {
    private val library = listOf(
        "B/Babes in Toyland/Fontanelle/01 - Bruise Violet.mp3",
        "T/The Church/Starfish/02 - Under the Milky Way.mp3",
        "F/Frank Sinatra/Where Are You/10 Lover.wav",
    )

    private fun song(file: String, title: String?, artist: String?, album: String?, date: String?, duration: Double?) =
        Song(file, title, artist, album, null, null, null, date, duration, null, null)

    private val songs = listOf(
        song(library[0], "Bruise Violet", "Babes in Toyland", "Fontanelle", "1992-08-11", 178.4),
        song(library[2], null, null, null, null, 200.0),
    )

    @Test
    fun trackListIsReadable() {
        val text = PlaylistFiles.trackList("Friday", songs)
        assertTrue(text.startsWith("Friday (2 songs, 6 min)\n\n"), text)
        assertTrue("1. Babes in Toyland – Bruise Violet (Fontanelle, 1992)\n" in text, text)
        // Untagged: names come from the path.
        assertTrue("2. Frank Sinatra – Lover (Where Are You)\n" in text, text)
    }

    @Test
    fun m3u8RoundTrips() {
        val file = PlaylistFiles.m3u8("Friday", songs)
        assertTrue(file.startsWith("#EXTM3U\n#PLAYLIST:Friday\n#EXTINF:178,Babes in Toyland - Bruise Violet\n"), file)
        val parsed = PlaylistFiles.parse(file)
        assertEquals("Friday", parsed.name)
        assertEquals(listOf(library[0], library[2]), PlaylistFiles.match(parsed.entries, library).uris)
    }

    @Test
    fun matchesPathsFromOtherComputers() {
        val entries = listOf(
            """E:\B\Babes in Toyland\Fontanelle\01 - Bruise Violet.mp3""",
            "/mnt/music/T/The Church/Starfish/02 - Under the Milky Way.mp3",
            "file:///E:/F/Frank%20Sinatra/Where%20Are%20You/10%20Lover.wav",
            "t/the church/starfish/02 - under the milky way.MP3",
            "Z/Nobody/Nothing/01 - Missing.mp3",
        )
        val result = PlaylistFiles.match(entries, library)
        assertEquals(listOf(library[0], library[1], library[2], library[1]), result.uris)
        assertEquals(listOf("Z/Nobody/Nothing/01 - Missing.mp3"), result.missing)
    }

    @Test
    fun readsOldWindowsFiles() {
        val latin = "#EXTM3U\r\nA/Bj\u00f6rk/Post/01 - Army of Me.mp3\r\n".toByteArray(Charsets.ISO_8859_1)
        assertEquals(listOf("A/Bj\u00f6rk/Post/01 - Army of Me.mp3"), PlaylistFiles.parse(PlaylistFiles.decode(latin)).entries)
        val bom = "\uFEFF#EXTM3U\nB/x.mp3\n".toByteArray()
        assertEquals(listOf("B/x.mp3"), PlaylistFiles.parse(PlaylistFiles.decode(bom)).entries)
    }

    @Test
    fun safeFileNames() {
        assertEquals("Friday_Night", PlaylistFiles.safeFileName("Friday/Night"))
        assertEquals("Playlist", PlaylistFiles.safeFileName("  "))
    }
}
