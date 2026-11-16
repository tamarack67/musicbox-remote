package nz.musicbox.mpd

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream

/**
 * Reads MPD replies, which are UTF-8 text lines that may contain a block of raw bytes
 * (`binary: N` followed by exactly N bytes and a newline).
 *
 * A partly read line is kept between calls, so a read timeout in the middle of a line
 * (used by the idle heartbeat) does not lose data.
 */
internal class MpdReader(private val input: InputStream) {
    private val buffer = ByteArray(16 * 1024)
    private var pos = 0
    private var len = 0
    private val partialLine = ByteArrayOutputStream(128)

    private fun fill() {
        val n = input.read(buffer)
        if (n < 0) throw EOFException("Connection closed by MPD")
        pos = 0
        len = n
    }

    fun readLine(): String {
        while (true) {
            if (pos >= len) fill()
            var i = pos
            while (i < len && buffer[i] != NEWLINE) i++
            partialLine.write(buffer, pos, i - pos)
            if (i < len) {
                pos = i + 1
                val line = partialLine.toString(Charsets.UTF_8)
                partialLine.reset()
                return line
            }
            pos = len
        }
    }

    fun readBytes(count: Int): ByteArray {
        val out = ByteArray(count)
        var filled = 0
        while (filled < count) {
            if (pos >= len) fill()
            val n = minOf(count - filled, len - pos)
            System.arraycopy(buffer, pos, out, filled, n)
            pos += n
            filled += n
        }
        return out
    }

    /** Consumes the newline that follows a binary block. */
    fun expectNewline() {
        if (pos >= len) fill()
        if (buffer[pos] != NEWLINE) throw MpdProtocolException("Missing newline after binary data")
        pos++
    }

    private companion object {
        const val NEWLINE = '\n'.code.toByte()
    }
}
