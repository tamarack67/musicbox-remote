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
        assertEquals(PathTags(null, null, null, "1979"), PathTags.derive("1979.mp3"))
        assertEquals("1979", PathTags.derive("S/Smashing Pumpkins/Mellon Collie/1979.mp3").title)
        assertEquals(1 to "15 beers", derived("SWITCHES - i just wanna - 01 15 beers.wav"))
        assertEquals(16 to "1863", derived("the glimmer room - A Diary - 16 1863.wav"))
    }

    @Test
    fun twoDigitTrackWithoutDash() {
        assertEquals(10 to "Lover", derived("10 Lover.wav"))
        assertEquals(1 to "Day By Day", derived("01 Day By Day.wav"))
    }

    @Test
    fun artistAndAlbumBeforeTheNumber() {
        assertEquals(1 to "Sunglass Party", derived("L'Amour - Look To The Artist- 1978-1981 - 01 Sunglass Party.wav"))
        assertEquals(1 to "T.I.E.L.", derived("DA'AT - The Hissing House - 01 T.I.E.L..wav"))
        assertEquals(1 to "Gran Turismo", derived("TOKYO ROSE - Chases 2 - 01 Gran Turismo.wav"))
        assertEquals(1 to "Glad I Don't Know", derived("Lemonheads - Hate Your Friends - 01 - Glad I Don't Know.mp3"))
        assertEquals(8 to "2012", derived("72 Megabytes - The Battle for Planet Earth - 08 2012.wav"))
        assertEquals(1 to "Colors - In Absentia", derived("Whitewash - Fraud in Lisbon - 01 Colors - In Absentia.wav"))
    }

    @Test
    fun hyphenatedArtistAlbumNumber() {
        assertEquals(1 to "Ballad to the East", derived("Peterson Oscar Trio-Canadiana Suite-01-Ballad to the East.mp3"))
        assertEquals(7 to "untitled", derived("Lint-Lint-07-untitled.mp3"))
        assertEquals(1 to "This Sporting Life", derived("Mekons The-This Sporting Life EP-1-This Sporting Life.mp3"))
        assertEquals(1 to "Call Me", derived("Throwing Muses-In A Doghouse-disc1-01-Call Me.mp3"))
        assertEquals(1 to "16 Shells From a Thirty-Ought-Six", derived("Waits Tom-Big Time-01-16 Shells From a Thirty-Ought-Six.mp3"))
    }

    @Test
    fun datesAndNamesWithoutNumbers() {
        val bows = "Bows - Blush - Big Wings - CJSW FM - 1999-10-18"
        assertEquals(null to bows, derived("$bows.mp3"))
        assertEquals(null to "Out Out - Rememory", derived("Out Out - Rememory.mov"))
        assertEquals(1 to "Title - 02 Remix", derived("01 - Title - 02 Remix.mp3"))
    }

    private fun derived(name: String) = PathTags.derive("A/Artist/Album/$name").let { it.track to it.title }

    @Test
    fun dotsInTitleAndShallowPaths() {
        val t = PathTags.derive("Album/05 - Mr. Brightside.mp3")
        assertEquals(PathTags(null, "Album", 5, "Mr. Brightside"), t)
        assertEquals("No Extension", PathTags.derive("No Extension").title)
    }
}
