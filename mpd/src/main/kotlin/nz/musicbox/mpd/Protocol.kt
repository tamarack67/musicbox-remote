package nz.musicbox.mpd

import java.io.IOException

/** An error reply from MPD: `ACK [error@command_listNum] {current_command} message_text`. */
class MpdException(
    val code: Int,
    val command: String,
    val serverMessage: String,
) : Exception("MPD error $code in '$command': $serverMessage") {
    companion object {
        // Parsed by hand rather than with a regex: Android's regex engine (ICU) is stricter than
        // the JVM's, and a pattern that passed the desktop tests crashed on the phone.
        fun parse(line: String): MpdException {
            val unknown = MpdException(-1, "", line)
            if (!line.startsWith("ACK [")) return unknown
            val at = line.indexOf('@')
            val close = line.indexOf(']')
            if (at < 0 || close < at) return unknown
            val code = line.substring(5, at).toIntOrNull() ?: return unknown
            val rest = line.substring(close + 1).trimStart()
            if (!rest.startsWith("{")) return MpdException(code, "", rest)
            val endCommand = rest.indexOf('}')
            if (endCommand < 0) return MpdException(code, "", rest)
            return MpdException(code, rest.substring(1, endCommand), rest.substring(endCommand + 1).trim())
        }
    }
}

/** The server sent something that is not valid MPD protocol. Treated like a broken connection. */
class MpdProtocolException(message: String) : IOException(message)

/** A command was attempted while there is no connection to MPD. */
class NotConnectedException : IOException("Not connected to MPD")

/** MPD protocol version, read from the greeting line `OK MPD x.y.z`. */
data class MpdVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<MpdVersion> {
    override fun compareTo(other: MpdVersion): Int =
        compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })

    fun atLeast(major: Int, minor: Int, patch: Int = 0): Boolean =
        this >= MpdVersion(major, minor, patch)

    override fun toString() = "$major.$minor.$patch"

    companion object {
        fun parse(text: String): MpdVersion {
            val parts = text.trim().split('.').map { part ->
                part.takeWhile(Char::isDigit).toIntOrNull() ?: 0
            }
            return MpdVersion(parts.getOrElse(0) { 0 }, parts.getOrElse(1) { 0 }, parts.getOrElse(2) { 0 })
        }
    }
}

/**
 * A successful reply: the `key: value` lines in order (keys can repeat, e.g. one `file` per song
 * in a list), plus the payload of a `binary:` section if there was one.
 */
class MpdResponse(
    val fields: List<Pair<String, String>>,
    val binary: ByteArray? = null,
) {
    /** First value for [key], ignoring case. */
    operator fun get(key: String): String? =
        fields.firstOrNull { it.first.equals(key, ignoreCase = true) }?.second

    /** All values for [key], ignoring case. */
    fun values(key: String): List<String> =
        fields.filter { it.first.equals(key, ignoreCase = true) }.map { it.second }
}

/** Command-line building helpers. */
object Mpd {
    /** Quotes one argument, escaping backslashes and double quotes as the protocol requires. */
    fun quote(arg: String): String =
        "\"" + arg.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    /** Builds `name "arg1" "arg2"`. Null arguments are left out. */
    fun command(name: String, vararg args: Any?): String = buildString {
        append(name)
        for (arg in args) {
            if (arg == null) continue
            append(' ')
            append(quote(arg.toString()))
        }
    }

    /** The subsystems the idle connection listens to. */
    val SUBSYSTEMS: List<String> =
        listOf("player", "mixer", "playlist", "stored_playlist", "database", "update", "options")
}
