package nz.musicbox.mpd

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProtocolTest {
    @Test
    fun quotesAndEscapesArguments() {
        assertEquals("\"plain\"", Mpd.quote("plain"))
        assertEquals("\"say \\\"hi\\\"\"", Mpd.quote("say \"hi\""))
        assertEquals("\"back\\\\slash\"", Mpd.quote("back\\slash"))
    }

    @Test
    fun lineBreaksCannotStartAnotherCommand() {
        assertEquals("rm \"Mix  clear\"", Mpd.command("rm", "Mix\r\nclear"))
    }

    @Test
    fun buildsCommandLines() {
        assertEquals("pause \"1\"", Mpd.command("pause", 1))
        assertEquals("add \"B/Babes in Toyland/Fontanelle\"", Mpd.command("add", "B/Babes in Toyland/Fontanelle"))
        assertEquals("status", Mpd.command("status", null))
    }

    @Test
    fun parsesAckLines() {
        val e = MpdException.parse("ACK [50@0] {play} song doesn't exist: \"10\"")
        assertEquals(50, e.code)
        assertEquals("play", e.command)
        assertEquals("song doesn't exist: \"10\"", e.serverMessage)

        val odd = MpdException.parse("ACK something unexpected")
        assertEquals(-1, odd.code)

        val rm = MpdException.parse("ACK [50@0] {rm} No such playlist")
        assertEquals(50, rm.code)
        assertEquals("rm", rm.command)
        assertEquals("No such playlist", rm.serverMessage)

        val noCommand = MpdException.parse("ACK [5@0] {} unknown command \"foo\"")
        assertEquals(5, noCommand.code)
        assertEquals("", noCommand.command)
        assertEquals("unknown command \"foo\"", noCommand.serverMessage)
    }

    @Test
    fun parsesAndComparesVersions() {
        val v = MpdVersion.parse("0.24.4")
        assertEquals(MpdVersion(0, 24, 4), v)
        assertTrue(v.atLeast(0, 17))
        assertTrue(v.atLeast(0, 24, 4))
        assertFalse(v.atLeast(0, 25))
        assertEquals(MpdVersion(0, 23, 0), MpdVersion.parse("0.23"))
        assertEquals(MpdVersion(0, 25, 0), MpdVersion.parse("0.25.0~git"))
    }

    @Test
    fun responseLookupIgnoresCaseAndKeepsRepeats() {
        val r = MpdResponse(listOf("Artist" to "A", "artist" to "B", "Title" to "T"))
        assertEquals("A", r["artist"])
        assertEquals(listOf("A", "B"), r.values("Artist"))
        assertNull(r["Album"])
    }
}
