package nz.musicbox.remote.data.library

import nz.musicbox.mpd.Song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LibraryIndexTest {
    private fun song(file: String, vararg tags: Pair<String, String>) =
        Song.parse(listOf("file" to file) + tags)

    @Test
    fun taggedAlbum() {
        val data = buildLibrary(
            listOf(
                song("L/Liz Phair/wcse/01 - A.mp3", "Artist" to "Liz Phair", "AlbumArtist" to "Liz Phair", "Album" to "Whitechocolatespaceegg", "Date" to "1998- 7- 8", "duration" to "100"),
                song("L/Liz Phair/wcse/02 - B.mp3", "Artist" to "Liz Phair", "AlbumArtist" to "Liz Phair", "Album" to "Whitechocolatespaceegg", "duration" to "50"),
            ),
        )
        val album = data.albums.single()
        assertEquals("L/Liz Phair/wcse", album.directory)
        assertEquals("Whitechocolatespaceegg", album.title)
        assertEquals("Liz Phair", album.artist)
        assertEquals("1998", album.year)
        assertEquals(2, album.trackCount)
        assertEquals(150.0, album.duration)
        assertEquals(listOf(1, 2), data.songs.map { it.track })
    }

    @Test
    fun untaggedWavAlbumUsesFolders() {
        val data = buildLibrary(
            listOf(
                song("P/Pixies/Doolittle/01 - Debaser.wav"),
                song("P/Pixies/Doolittle/02 - Tame.wav"),
            ),
        )
        val album = data.albums.single()
        assertEquals("Doolittle", album.title)
        assertEquals("Pixies", album.artist)
        assertEquals(listOf("Debaser", "Tame"), data.songs.map { it.title })
        assertEquals(listOf("Pixies", "Pixies"), data.songs.map { it.artist })
    }

    @Test
    fun compilationWithoutAlbumArtistFallsBackToFolderArtist() {
        val data = buildLibrary(
            listOf(
                song("V/Various/Comp/01 - X.mp3", "Artist" to "One", "Album" to "Comp"),
                song("V/Various/Comp/02 - Y.mp3", "Artist" to "Two", "Album" to "Comp"),
            ),
        )
        assertEquals("Various", data.albums.single().artist)
        assertEquals(listOf("One", "Two"), data.songs.map { it.artist })
    }

    @Test
    fun systemFoldersAreLeftOut() {
        val data = buildLibrary(
            listOf(
                song("\$RECYCLE.BIN/S-1-5-21-1/\$R0HE4ML.mp3"),
                song("B/Band/Album/.hidden.mp3"),
                song("B/Band/.Trashes/x.mp3"),
                song("B/Band/Album/01 - Real.mp3"),
            ),
        )
        assertEquals(listOf("B/Band/Album/01 - Real.mp3"), data.songs.map { it.file })
        assertEquals(listOf("B/Band/Album"), data.albums.map { it.directory })
    }

    @Test
    fun hiddenPathRules() {
        assertTrue(isHiddenPath("\$RECYCLE.BIN/a.mp3"))
        assertTrue(isHiddenPath("A/B/._01 - Song.mp3"))
        assertTrue(isHiddenPath("System Volume Information/x.mp3"))
        assertTrue(isHiddenPath("\$Recycle.Bin/x.mp3"))
        assertFalse(isHiddenPath("0/0.5 Seconds/x.mp3"))
        assertFalse(isHiddenPath("S/\$uicideboy\$/Album/01 - x.mp3"))
        assertFalse(isHiddenPath("A/...And You Will Know Us by the Trail of Dead/Madonna/01 - x.mp3"))
    }

    @Test
    fun searchKeyFoldsCaseAndAccents() {
        assertEquals("bjork homogenic", searchKey("Björk Homogénic"))
    }

    @Test
    fun yearFromVariousDates() {
        assertEquals("1998", yearOf("1998- 7- 8"))
        assertEquals("2001", yearOf("2001-02-03"))
        assertEquals(null, yearOf("unknown"))
    }
}
