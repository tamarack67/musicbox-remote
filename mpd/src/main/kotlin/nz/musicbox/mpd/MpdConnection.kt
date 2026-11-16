package nz.musicbox.mpd

import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * One TCP connection to MPD. All methods block, so call them from `Dispatchers.IO`.
 * Not thread-safe: one request at a time. [close] may be called from any thread and
 * makes a blocked read fail promptly.
 */
class MpdConnection private constructor(
    private val socket: Socket,
    private val reader: MpdReader,
    val version: MpdVersion,
    private val readTimeoutMs: Int,
) : Closeable {

    private val output = BufferedOutputStream(socket.getOutputStream())

    fun send(line: String) {
        require('\n' !in line) { "A command must be a single line" }
        output.write(line.toByteArray(Charsets.UTF_8))
        output.write('\n'.code)
        output.flush()
    }

    /** Reads one reply up to `OK`. Throws [MpdException] on `ACK`, [IOException] if the connection fails. */
    fun readResponse(): MpdResponse {
        val fields = ArrayList<Pair<String, String>>()
        var binary: ByteArray? = null
        while (true) {
            val line = reader.readLine()
            when {
                line == "OK" -> return MpdResponse(fields, binary)
                line.startsWith("ACK ") -> throw MpdException.parse(line)
                else -> {
                    val sep = line.indexOf(": ")
                    if (sep <= 0) throw MpdProtocolException("Unexpected line from MPD: $line")
                    val key = line.substring(0, sep)
                    val value = line.substring(sep + 2)
                    if (key == "binary") {
                        val size = value.toIntOrNull()
                            ?: throw MpdProtocolException("Bad binary size: $value")
                        binary = reader.readBytes(size)
                        reader.expectNewline()
                    } else {
                        fields += key to value
                    }
                }
            }
        }
    }

    fun call(command: String): MpdResponse {
        send(command)
        return readResponse()
    }

    /**
     * Enters idle and blocks until one of [subsystems] changes, returning the changed names.
     *
     * If nothing happens for [heartbeatMs], sends `noidle` to check the server is still there
     * and returns an empty set. If that check gets no answer within the normal read timeout,
     * the connection is dead and this throws [SocketTimeoutException].
     */
    fun idle(subsystems: List<String>, heartbeatMs: Int): Set<String> {
        send("idle " + subsystems.joinToString(" "))
        socket.soTimeout = heartbeatMs
        try {
            val response = try {
                readResponse()
            } catch (_: SocketTimeoutException) {
                socket.soTimeout = readTimeoutMs
                send("noidle")
                readResponse()
            }
            return response.values("changed").toSet()
        } finally {
            if (!socket.isClosed) socket.soTimeout = readTimeoutMs
        }
    }

    override fun close() {
        try {
            socket.close()
        } catch (_: IOException) {
        }
    }

    companion object {
        /** Connects and reads the greeting. */
        fun open(host: String, port: Int, connectTimeoutMs: Int, readTimeoutMs: Int): MpdConnection {
            val socket = Socket()
            try {
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
                socket.soTimeout = readTimeoutMs
                val reader = MpdReader(socket.getInputStream())
                val greeting = reader.readLine()
                if (!greeting.startsWith("OK MPD ")) {
                    throw MpdProtocolException("Not an MPD server (greeting was \"$greeting\")")
                }
                val version = MpdVersion.parse(greeting.removePrefix("OK MPD "))
                return MpdConnection(socket, reader, version, readTimeoutMs)
            } catch (e: Throwable) {
                socket.close()
                throw e
            }
        }
    }
}
