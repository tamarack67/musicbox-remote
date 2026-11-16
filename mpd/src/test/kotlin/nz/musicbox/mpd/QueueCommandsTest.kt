package nz.musicbox.mpd

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QueueCommandsTest {
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
    fun idleIncludesStoredPlaylists() = FakeMpdServer().use { server ->
        connected(server) {
            withTimeout(5000) { while (server.idlingCount() == 0) delay(20) }
            assertTrue(server.received.any { it.startsWith("idle ") && "stored_playlist" in it })
        }
    }

    @Test
    fun fetchesLongQueueInChunks() = FakeMpdServer().use { server ->
        val total = 7
        val range = Regex("""playlistinfo "(\d+):(\d+)"""")
        server.handler = { line ->
            val m = range.find(line)
            if (m == null) {
                ""
            } else {
                val from = m.groupValues[1].toInt()
                val to = minOf(m.groupValues[2].toInt(), total)
                (from until to).joinToString("") { "file: s$it.mp3\nPos: $it\nId: ${100 + it}\n" }
            }
        }
        connected(server) { client ->
            val queue = client.queue(chunkSize = 3)
            assertEquals((0 until total).toList(), queue.map { it.pos })
            assertEquals(106, queue.last().id)
            assertEquals(
                listOf("playlistinfo \"0:3\"", "playlistinfo \"3:6\"", "playlistinfo \"6:9\""),
                server.received.filter { it.startsWith("playlistinfo") },
            )
        }
    }

    @Test
    fun queueEditingCommands() = FakeMpdServer().use { server ->
        server.handler = { if (it.startsWith("addid")) "Id: 55\n" else "" }
        connected(server) { client ->
            client.playId(12)
            client.deleteId(12)
            client.moveId(13, 0)
            assertEquals(55, client.addAt("A/x.mp3", 4))
            client.clearQueue()
            assertEquals(
                listOf("playid \"12\"", "deleteid \"12\"", "moveid \"13\" \"0\"", "addid \"A/x.mp3\" \"4\"", "clear"),
                server.received.filterNot { it.startsWith("idle") || it == "noidle" },
            )
        }
    }

    @Test
    fun parsesPlaylistList() {
        val list = StoredPlaylist.parseList(
            listOf(
                "playlist" to "Friday",
                "Last-Modified" to "2026-11-01T10:00:00Z",
                "playlist" to "Old",
            ),
        )
        assertEquals(listOf(StoredPlaylist("Friday", "2026-11-01T10:00:00Z"), StoredPlaylist("Old", null)), list)
    }

    @Test
    fun saveReplaceUsesModeOnNewServers() = FakeMpdServer(version = "0.24.0").use { server ->
        connected(server) { client ->
            client.saveQueueAs("Mix", replace = false)
            client.saveQueueAs("Mix", replace = true)
            assertTrue("save \"Mix\"" in server.received)
            assertTrue("save \"Mix\" \"replace\"" in server.received)
        }
    }

    @Test
    fun saveReplaceRemovesFirstOnOldServers() = FakeMpdServer(version = "0.23.5").use { server ->
        server.handler = { if (it.startsWith("rm")) "ACK [50@0] {rm} No such playlist" else "" }
        connected(server) { client ->
            client.saveQueueAs("Mix", replace = true)
            val sent = server.received.filter { it.startsWith("rm") || it.startsWith("save") }
            assertEquals(listOf("rm \"Mix\"", "save \"Mix\""), sent)
        }
    }

    @Test
    fun playPlaylistIsOneCommandList() = FakeMpdServer().use { server ->
        connected(server) { client ->
            client.playPlaylist("Friday", playFrom = 2)
            val start = server.received.indexOf("command_list_begin")
            assertEquals(
                listOf("command_list_begin", "clear", "load \"Friday\"", "play \"2\"", "command_list_end"),
                server.received.subList(start, start + 5),
            )
        }
    }
}
