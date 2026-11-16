package nz.musicbox.mpd

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelsTest {
    private fun response(text: String) = MpdResponse(
        text.trimIndent().lines().filter { it.isNotBlank() }.map {
            val i = it.indexOf(": ")
            it.substring(0, i) to it.substring(i + 2)
        },
    )

    @Test
    fun parsesPlayingStatus() {
        val s = MpdStatus.parse(
            response(
                """
                volume: 100
                repeat: 1
                random: 0
                single: 0
                consume: 0
                playlist: 12
                playlistlength: 14
                state: play
                song: 3
                songid: 17
                elapsed: 61.250
                duration: 184.320
                bitrate: 320
                audio: 44100:24:2
                """,
            ),
        )
        assertEquals(PlayState.PLAY, s.state)
        assertEquals(3, s.songPos)
        assertEquals(17, s.songId)
        assertEquals(61.25, s.elapsed)
        assertEquals(184.32, s.duration)
        assertTrue(s.repeat)
        assertFalse(s.random)
        assertEquals(14, s.playlistLength)
        assertEquals(12L, s.playlistVersion)
        assertEquals(100, s.volume)
    }

    @Test
    fun statusFallsBackToTimeFieldAndHandlesNoMixer() {
        val s = MpdStatus.parse(response("state: pause\ntime: 42:300\nvolume: -1"))
        assertEquals(PlayState.PAUSE, s.state)
        assertEquals(42.0, s.elapsed)
        assertEquals(300.0, s.duration)
        assertNull(s.volume)
    }

    @Test
    fun stoppedStatusHasNoSong() {
        val s = MpdStatus.parse(response("state: stop\nplaylistlength: 0"))
        assertEquals(PlayState.STOP, s.state)
        assertNull(s.songPos)
        assertNull(s.elapsed)
    }

    @Test
    fun parsesTaggedSong() {
        val song = Song.parse(
            response(
                """
                file: B/Babes in Toyland/Fontanelle/01 - Bruise Violet.mp3
                Artist: Babes in Toyland
                Album: Fontanelle
                Title: Bruise Violet
                Track: 1/12
                Date: 1992
                duration: 179.565
                Pos: 0
                Id: 5
                """,
            ).fields,
        )
        assertEquals("Bruise Violet", song.displayTitle)
        assertEquals("Babes in Toyland", song.displayArtist)
        assertEquals("Fontanelle", song.displayAlbum)
        assertEquals(1, song.track)
        assertEquals(179.565, song.duration)
        assertEquals(0, song.pos)
        assertEquals(5, song.id)
    }

    @Test
    fun untaggedSongFallsBackToPath() {
        val song = Song.parse(
            response(
                """
                file: P/Pixies/Doolittle/07 - Monkey Gone to Heaven.wav
                Time: 176
                duration: 176.000
                """,
            ).fields,
        )
        assertNull(song.title)
        assertEquals("Monkey Gone to Heaven", song.displayTitle)
        assertEquals("Pixies", song.displayArtist)
        assertEquals("Doolittle", song.displayAlbum)
        assertEquals(7, song.displayTrack)
    }

    @Test
    fun partialTagsMixWithPath() {
        val song = Song.parse(listOf("file" to "X/Artist/Album/02 - Path Title.wav", "Title" to "Tag Title"))
        assertEquals("Tag Title", song.displayTitle)
        assertEquals("Artist", song.displayArtist)
    }

    @Test
    fun albumArtistUsedWhenArtistMissing() {
        val song = Song.parse(listOf("file" to "a.mp3", "AlbumArtist" to "Various"))
        assertEquals("Various", song.displayArtist)
    }
}
