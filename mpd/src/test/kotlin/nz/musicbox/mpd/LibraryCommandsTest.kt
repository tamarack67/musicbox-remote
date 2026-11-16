package nz.musicbox.mpd

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LibraryCommandsTest {
    private val options = MpdClient.Options(connectTimeoutMs = 1000, readTimeoutMs = 2000, minBackoffMs = 50)

    private fun connected(server: FakeMpdServer, body: suspend (MpdClient) -> Unit) = runBlocking {
        val client = MpdClient({ flowOf(server.endpoint) }, options = options)
        val runner = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { client.run() }
        try {
            withTimeout(5000) { while (client.state.value !is ConnectionState.Connected) delay(20) }
            body(client)
        } finally {
            runner.cancelAndJoin()
        }
    }

    @Test
    fun parsesStats() {
        val s = MpdStats.parse(
            MpdResponse(listOf("artists" to "2154", "albums" to "3133", "songs" to "43798", "db_update" to "1794714337")),
        )
        assertEquals(43798, s.songs)
        assertEquals(1794714337L, s.dbUpdate)
    }

    @Test
    fun parsesLsinfoEntries() {
        val entries = LsEntry.parseList(
            listOf(
                "directory" to "B/Babes in Toyland",
                "Last-Modified" to "2026-11-14T07:10:45Z",
                "file" to "B/one.mp3",
                "Title" to "One",
                "file" to "B/two.wav",
                "duration" to "12.5",
                "playlist" to "Favourites",
                "Last-Modified" to "2026-01-01T00:00:00Z",
            ),
        )
        assertEquals(4, entries.size)
        val dir = assertIs<LsEntry.Directory>(entries[0])
        assertEquals("Babes in Toyland", dir.name)
        assertEquals("One", assertIs<LsEntry.File>(entries[1]).song.title)
        val two = assertIs<LsEntry.File>(entries[2]).song
        assertEquals("B/two.wav", two.file)
        assertEquals(12.5, two.duration)
        assertEquals(LsEntry.Playlist("Favourites"), entries[3])
    }

    @Test
    fun lsinfoOfTopLevelSendsNoArgument() = FakeMpdServer().use { server ->
        server.handler = { if (it.startsWith("lsinfo")) "directory: 00\ndirectory: A\n" else "" }
        connected(server) { client ->
            assertEquals(listOf("00", "A"), client.lsinfo("").map { (it as LsEntry.Directory).path })
            client.lsinfo("A")
            assertTrue("lsinfo" in server.received)
            assertTrue("lsinfo \"A\"" in server.received)
        }
    }

    @Test
    fun loadsWholeLibraryInWindows() = FakeMpdServer().use { server ->
        val total = 12
        val window = Regex(""""window" "(\d+):(\d+)"""")
        server.handler = { line ->
            val m = window.find(line)
            if (line.startsWith("find") && m != null) {
                val from = m.groupValues[1].toInt()
                val to = minOf(m.groupValues[2].toInt(), total)
                (from until to).joinToString("") { "file: A/Artist/Album/%02d - Song.mp3\nTitle: Song $it\n".format(it) }
            } else {
                ""
            }
        }
        connected(server) { client ->
            val progress = CopyOnWriteArrayList<Int>()
            val songs = client.loadAllSongs(chunkSize = 5) { progress += it }
            assertEquals(total, songs.size)
            assertEquals("Song 11", songs.last().title)
            assertEquals(listOf(5, 10, 12), progress)
            assertTrue("tagtypes clear" in server.received)
            assertTrue(server.received.any { it.startsWith("tagtypes \"enable\" \"Artist\"") })
            assertEquals(3, server.received.count { it.startsWith("find \"(file != '')\" \"window\"") })
            // Ran on its own connection: idle + dedicated, command connection never opened.
            assertEquals(2, server.connectionCount.get())
        }
    }

    @Test
    fun oldServersGetSingleListing() = FakeMpdServer(version = "0.20.0").use { server ->
        server.handler = { if (it == "listallinfo") "directory: A\nfile: A/x.mp3\nfile: A/y.mp3\n" else "" }
        connected(server) { client ->
            assertEquals(2, client.loadAllSongs().size)
            assertTrue(server.received.none { it.startsWith("tagtypes") || it.startsWith("find") })
        }
    }

    @Test
    fun replaceQueueIsOneCommandList() = FakeMpdServer().use { server ->
        connected(server) { client ->
            client.replaceQueueAndPlay(listOf("A/1.mp3", "A/2.mp3"), playFrom = 1)
            val start = server.received.indexOf("command_list_begin")
            assertEquals(
                listOf(
                    "command_list_begin",
                    "clear",
                    "add \"A/1.mp3\"",
                    "add \"A/2.mp3\"",
                    "play \"1\"",
                    "command_list_end",
                ),
                server.received.subList(start, start + 6),
            )
        }
    }

    @Test
    fun commandListErrorIsReported() = FakeMpdServer().use { server ->
        server.handler = { if (it.startsWith("add \"missing")) "ACK [50@1] {add} No such directory" else "" }
        connected(server) { client ->
            val e = assertFailsWith<MpdException> { client.addToQueue(listOf("ok.mp3", "missing")) }
            assertEquals(50, e.code)
            // The connection is still in step afterwards.
            server.handler = { "state: stop\n" }
            assertEquals(PlayState.STOP, client.status().state)
        }
    }
}
