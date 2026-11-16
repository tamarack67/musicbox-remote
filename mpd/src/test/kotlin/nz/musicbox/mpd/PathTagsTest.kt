package nz.musicbox.mpd

import kotlin.test.Test
import kotlin.test.assertEquals

class PathTagsTest {
    @Test
    fun standardLayout() {
        assertEquals(
            PathTags("Babes in Toyland", "Fontanelle", 1, "Bruise Violet"),
            PathTags.derive("B/Babes in Toyland/Fontanelle/01 - Bruise Violet.mp3"),
        )
    }

    @Test
    fun topLevelZeroZeroFolder() {
        assertEquals(
            PathTags("Some Artist", "Some Album", 12, "Song"),
            PathTags.derive("00/Some Artist/Some Album/12 - Song.wav"),
        )
    }

    @Test
    fun otherNumberingStyles() {
        assertEquals(3, PathTags.derive("A/B/C/3. Three.wav").track)
        assertEquals("Three", PathTags.derive("A/B/C/3. Three.wav").title)
        assertEquals(4, PathTags.derive("A/B/C/04 Four.wav").track)
        assertEquals("Four", PathTags.derive("A/B/C/04 Four.wav").title)
    }

    @Test
    fun numbersThatAreTitles() {
        assertEquals(PathTags(null, null, null, "99 Luftballons"), PathTags.derive("99 Luftballons.mp3"))
        assertEquals("1979", PathTags.derive("S/Smashing Pumpkins/Mellon Collie/1979.mp3").title)
    }

    @Test
    fun dotsInTitleAndShallowPaths() {
        val t = PathTags.derive("Album/05 - Mr. Brightside.mp3")
        assertEquals(PathTags(null, "Album", 5, "Mr. Brightside"), t)
        assertEquals("No Extension", PathTags.derive("No Extension").title)
    }
}
