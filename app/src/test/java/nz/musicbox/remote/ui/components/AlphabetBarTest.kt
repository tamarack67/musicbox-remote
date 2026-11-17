package nz.musicbox.remote.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals

class AlphabetBarTest {
    // In the order SQLite's NOCASE sort gives: symbols and digits, A to Z, then accented letters.
    private val names = listOf("13th Chime", "28 Days", "Acadie", "abba", "Big Country", "Daniel Lanois", "The Clash", "Élan")

    @Test
    fun lettersOfNames() {
        assertEquals('#', indexLetter("1080"))
        assertEquals('A', indexLetter("abba"))
        assertEquals('E', indexLetter("Élan"))
        assertEquals('#', indexLetter(""))
    }

    @Test
    fun jumpsToFirstItemOrNextLetter() {
        val p = letterPositions(names)
        assertEquals(0, p['#'])
        assertEquals(2, p['A'])
        assertEquals(4, p['B'])
        assertEquals(5, p['C']) // no C: next is D
        assertEquals(5, p['D'])
        assertEquals(6, p['E']) // accented "Élan" at the end doesn't count: next is T
        assertEquals(6, p['T'])
        assertEquals(7, p['Z']) // past the last letter: end of the list
    }

    @Test
    fun emptyList() {
        assertEquals(0, letterPositions(emptyList<String>())['M'])
    }
}
