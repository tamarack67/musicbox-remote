package nz.musicbox.mpd

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ConnectionTest {
    private fun FakeMpdServer.open(readTimeoutMs: Int = 2000) =
        MpdConnection.open("127.0.0.1", port, 1000, readTimeoutMs)

    @Test
    fun readsVersionFromGreeting() {
        FakeMpdServer(version = "0.23.5").use { server ->
            server.open().use { assertEquals(MpdVersion(0, 23, 5), it.version) }
        }
    }

    @Test
    fun rejectsNonMpdServer() {
        java.net.ServerSocket(0).use { listener ->
            Thread {
                listener.accept().use { it.getOutputStream().write("SSH-2.0-OpenSSH\n".toByteArray()) }
            }.start()
            assertFailsWith<MpdProtocolException> {
                MpdConnection.open("127.0.0.1", listener.localPort, 1000, 1000)
            }
        }
    }

    @Test
    fun sendsCommandAndParsesFields() {
        FakeMpdServer().use { server ->
            server.handler = { if (it == "status") "state: play\nsong: 2\n" else "" }
            server.open().use { conn ->
                val r = conn.call("status")
                assertEquals("play", r["state"])
                assertEquals("2", r["song"])
            }
            assertEquals(listOf("status"), server.received)
        }
    }

    @Test
    fun ackBecomesMpdExceptionAndConnectionStaysUsable() {
        FakeMpdServer().use { server ->
            server.handler = {
                if (it.startsWith("play")) "ACK [50@0] {play} song doesn't exist" else "state: stop\n"
            }
            server.open().use { conn ->
                val e = assertFailsWith<MpdException> { conn.call("play \"99\"") }
                assertEquals(50, e.code)
                assertEquals("stop", conn.call("status")["state"])
            }
        }
    }

    @Test
    fun readsBinaryChunk() {
        // 40 kB, larger than the read buffer, and full of bytes that look like '\n'.
        val payload = ByteArray(40_000) { (it % 256).toByte() }
        java.net.ServerSocket(0).use { listener ->
            Thread {
                listener.accept().use { s ->
                    val out = s.getOutputStream()
                    out.write("OK MPD 0.24.4\n".toByteArray())
                    s.getInputStream().bufferedReader().readLine() // the albumart command
                    out.write("size: 40000\ntype: image/jpeg\nbinary: 40000\n".toByteArray())
                    out.write(payload)
                    out.write("\nOK\n".toByteArray())
                    out.flush()
                }
            }.start()
            MpdConnection.open("127.0.0.1", listener.localPort, 1000, 2000).use { conn ->
                val r = conn.call(Mpd.command("albumart", "A/B/C/01 - x.mp3", 0))
                assertEquals("40000", r["size"])
                assertEquals("image/jpeg", r["type"])
                assertContentEquals(payload, r.binary)
            }
        }
    }

    @Test
    fun idleReturnsChangedSubsystems() {
        FakeMpdServer().use { server ->
            server.open().use { conn ->
                val waker = Thread {
                    while (server.idlingCount() == 0) Thread.sleep(10)
                    server.fireIdle("player", "mixer")
                }
                waker.start()
                assertEquals(setOf("player", "mixer"), conn.idle(listOf("player", "mixer"), heartbeatMs = 5000))
                waker.join()
            }
        }
    }

    @Test
    fun idleHeartbeatChecksServerWhenQuiet() {
        FakeMpdServer().use { server ->
            server.open().use { conn ->
                assertEquals(emptySet(), conn.idle(Mpd.SUBSYSTEMS, heartbeatMs = 200))
                assertTrue("noidle" in server.received)
                // Still usable afterwards.
                server.handler = { "state: stop\n" }
                assertEquals("stop", conn.call("status")["state"])
            }
        }
    }

    @Test
    fun idleHeartbeatDetectsDeadServer() {
        FakeMpdServer().use { server ->
            server.answerNoidle = false
            server.open(readTimeoutMs = 300).use { conn ->
                assertFailsWith<IOException> { conn.idle(Mpd.SUBSYSTEMS, heartbeatMs = 200) }
            }
        }
    }
}
