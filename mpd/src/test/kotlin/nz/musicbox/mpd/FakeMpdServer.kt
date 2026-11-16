package nz.musicbox.mpd

import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * A small in-process MPD stand-in on a random localhost port.
 *
 * [handler] maps a command line to the reply body (the lines before `OK`), or to a full
 * `ACK ...` line for an error. Idle is handled here: `idle` waits until [fireIdle], `noidle`
 * ends it (unless [answerNoidle] is false, simulating a server that has gone away).
 */
class FakeMpdServer(private val version: String = "0.24.4") : Closeable {
    private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    private val clients = CopyOnWriteArrayList<Client>()

    val port: Int get() = server.localPort
    val endpoint: Endpoint get() = Endpoint("127.0.0.1", port)

    /** Every command line received, in order, from all connections. */
    val received = CopyOnWriteArrayList<String>()
    val connectionCount = AtomicInteger()

    @Volatile var handler: (String) -> String = { "" }

    /** If this returns bytes for a command, they are sent as the complete reply (including OK). */
    @Volatile var rawHandler: (String) -> ByteArray? = { null }
    @Volatile var answerNoidle = true

    init {
        thread(isDaemon = true, name = "fake-mpd-accept") {
            while (!server.isClosed) {
                val socket = try {
                    server.accept()
                } catch (_: IOException) {
                    break
                }
                val client = Client(socket)
                clients += client
                connectionCount.incrementAndGet()
                thread(isDaemon = true, name = "fake-mpd-client") { client.serve() }
            }
        }
    }

    fun idlingCount(): Int = clients.count { it.idling }

    /** Wakes every idling connection with the given changed subsystems. */
    fun fireIdle(vararg subsystems: String) {
        clients.forEach { it.fire(subsystems.toList()) }
    }

    fun dropConnections() {
        clients.forEach { it.close() }
    }

    /** Drops connections that are not currently idling (the command connection). */
    fun dropNonIdle() {
        clients.filter { !it.idling }.forEach { it.close() }
    }

    override fun close() {
        server.close()
        dropConnections()
    }

    private inner class Client(private val socket: Socket) {
        private val out = socket.getOutputStream()
        private val lock = Any()

        @Volatile var idling = false

        private fun write(text: String) = synchronized(lock) {
            out.write(text.toByteArray(Charsets.UTF_8))
            out.flush()
        }

        fun serve() {
            try {
                write("OK MPD $version\n")
                val reader = socket.getInputStream().bufferedReader(Charsets.UTF_8)
                var commandList: MutableList<String>? = null
                while (true) {
                    val line = reader.readLine() ?: break
                    received += line
                    val list = commandList
                    when {
                        line == "command_list_begin" -> commandList = mutableListOf()
                        line == "command_list_end" && list != null -> {
                            commandList = null
                            val reply = StringBuilder()
                            var failed = false
                            for (cmd in list) {
                                val body = handler(cmd)
                                if (body.startsWith("ACK ")) {
                                    reply.append(body).append('\n')
                                    failed = true
                                    break
                                }
                                reply.append(body)
                            }
                            if (!failed) reply.append("OK\n")
                            write(reply.toString())
                        }
                        list != null -> list += line
                        line == "idle" || line.startsWith("idle ") -> idling = true
                        line == "noidle" -> synchronized(lock) {
                            if (idling && answerNoidle) {
                                idling = false
                                write("OK\n")
                            }
                        }
                        else -> {
                            val raw = rawHandler(line)
                            if (raw != null) {
                                synchronized(lock) {
                                    out.write(raw)
                                    out.flush()
                                }
                            } else {
                                val body = handler(line)
                                write(if (body.startsWith("ACK ")) "$body\n" else "${body}OK\n")
                            }
                        }
                    }
                }
            } catch (_: IOException) {
            } finally {
                close()
            }
        }

        fun fire(subsystems: List<String>) = synchronized(lock) {
            if (!idling) return@synchronized
            idling = false
            try {
                write(subsystems.joinToString("") { "changed: $it\n" } + "OK\n")
            } catch (_: IOException) {
            }
        }

        fun close() {
            idling = false
            clients -= this
            try {
                socket.close()
            } catch (_: IOException) {
            }
        }
    }
}

/** A localhost port with nothing listening on it. */
fun closedPort(): Int = ServerSocket(0).use { it.localPort }
