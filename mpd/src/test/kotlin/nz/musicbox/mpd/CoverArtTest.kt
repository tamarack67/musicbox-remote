package nz.musicbox.mpd

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoverArtTest {
    private val image = ByteArray(20_000) { (it * 7 % 256).toByte() }
    private val offsetPattern = Regex("""^(albumart|readpicture) "([^"]*)" "(\d+)"$""")

    /** Serves [data] for [command] in [chunk]-byte pieces, like MPD does. */
    private fun chunkedReply(line: String, command: String, data: ByteArray, chunk: Int): ByteArray? {
        val m = offsetPattern.matchEntire(line) ?: return null
        if (m.groupValues[1] != command) return null
        val offset = m.groupValues[3].toInt()
        val end = minOf(offset + chunk, data.size)
        return ByteArrayOutputStream().apply {
            write("size: ${data.size}\ntype: image/jpeg\nbinary: ${end - offset}\n".toByteArray())
            write(data, offset, end - offset)
            write("\nOK\n".toByteArray())
        }.toByteArray()
    }

    private fun connected(server: FakeMpdServer, body: suspend (MpdClient) -> Unit) = runBlocking {
        val client = MpdClient({ flowOf(server.endpoint) }, options = MpdClient.Options(connectTimeoutMs = 1000))
        val runner = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { client.run() }
        try {
            withTimeout(5000) { while (client.state.value !is ConnectionState.Connected) delay(20) }
            body(client)
        } finally {
            runner.cancelAndJoin()
        }
    }

    @Test
    fun readsFolderCoverInChunks() = FakeMpdServer().use { server ->
        server.rawHandler = { chunkedReply(it, "albumart", image, chunk = 8192) }
        connected(server) { client ->
            val bytes = client.coverArt("A/Artist/Album/01 - x.mp3")
            assertContentEquals(image, bytes)
            assertEquals(
                listOf("0", "8192", "16384"),
                server.received.filter { it.startsWith("albumart") }.map { it.substringAfterLast(" ").trim('"') },
            )
        }
    }

    @Test
    fun setsLargerChunksOnArtConnection() = FakeMpdServer(version = "0.24.0").use { server ->
        server.rawHandler = { chunkedReply(it, "albumart", image, chunk = 1_000_000) }
        connected(server) { client ->
            client.coverArt("a.mp3")
            assertTrue("binarylimit \"1048576\"" in server.received)
            // Idle + art connection; the command connection was never needed.
            assertEquals(2, server.connectionCount.get())
        }
    }

    @Test
    fun noBinarylimitBeforeItExisted() = FakeMpdServer(version = "0.22.0").use { server ->
        server.rawHandler = { chunkedReply(it, "albumart", image, chunk = 8192) }
        connected(server) { client ->
            client.coverArt("a.mp3")
            assertFalse(server.received.any { it.startsWith("binarylimit") })
        }
    }

    @Test
    fun fallsBackToEmbeddedPicture() = FakeMpdServer().use { server ->
        server.handler = { if (it.startsWith("albumart")) "ACK [50@0] {albumart} No file exists" else "" }
        server.rawHandler = { chunkedReply(it, "readpicture", image, chunk = 8192) }
        connected(server) { client ->
            assertContentEquals(image, client.coverArt("a.wav"))
        }
    }

    @Test
    fun noArtAtAll() = FakeMpdServer().use { server ->
        // albumart: no cover file; readpicture: empty reply means no embedded picture.
        server.handler = { if (it.startsWith("albumart")) "ACK [50@0] {albumart} No file exists" else "" }
        connected(server) { client ->
            assertNull(client.coverArt("a.wav"))
            assertTrue(server.received.any { it.startsWith("readpicture") })
        }
    }

    @Test
    fun oldServerWithoutAlbumartGetsNothing() = FakeMpdServer(version = "0.20.0").use { server ->
        connected(server) { client ->
            assertNull(client.coverArt("a.mp3"))
            assertFalse(server.received.any { it.startsWith("albumart") || it.startsWith("readpicture") })
        }
    }
}
