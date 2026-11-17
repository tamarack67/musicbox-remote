package nz.musicbox.mpd

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ClientTest {
    private val fastOptions = MpdClient.Options(
        connectTimeoutMs = 1000,
        readTimeoutMs = 2000,
        heartbeatMs = 5000,
        minBackoffMs = 50,
        maxBackoffMs = 200,
    )
    private val all = Mpd.SUBSYSTEMS.toSet()

    private suspend fun waitFor(what: String, timeoutMs: Long = 5000, condition: () -> Boolean) {
        try {
            withTimeout(timeoutMs) { while (!condition()) delay(20) }
        } catch (e: Exception) {
            throw AssertionError("Timed out waiting for: $what", e)
        }
    }

    /** Runs [client] and collects its change events for the duration of [body]. */
    private fun withRunning(
        client: MpdClient,
        body: suspend (events: List<Set<String>>) -> Unit,
    ) = runBlocking {
        coroutineScope {
            val events = CopyOnWriteArrayList<Set<String>>()
            val collector = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                client.changes.collect { events += it }
            }
            val runner = launch(Dispatchers.Default) { client.run() }
            try {
                body(events)
            } finally {
                runner.cancelAndJoin()
                collector.cancelAndJoin()
            }
        }
    }

    @Test
    fun connectsAndAnnouncesFullRefresh() = FakeMpdServer().use { server ->
        val client = MpdClient({ flowOf(server.endpoint) }, options = fastOptions)
        withRunning(client) { events ->
            waitFor("connected") { client.state.value is ConnectionState.Connected }
            val state = client.state.value
            assertIs<ConnectionState.Connected>(state)
            assertEquals(server.endpoint, state.endpoint)
            assertEquals(MpdVersion(0, 24, 4), state.version)
            waitFor("full refresh event") { events.isNotEmpty() }
            assertEquals(all, events.first())
        }
        assertEquals(ConnectionState.Stopped, client.state.value)
    }

    @Test
    fun forwardsIdleEvents() = FakeMpdServer().use { server ->
        val client = MpdClient({ flowOf(server.endpoint) }, options = fastOptions)
        withRunning(client) { events ->
            waitFor("idling") { server.idlingCount() == 1 }
            server.fireIdle("player")
            waitFor("player event") { setOf("player") in events }
            // Goes back into idle for the next change.
            waitFor("idling again") { server.idlingCount() == 1 }
            server.fireIdle("options")
            waitFor("options event") { setOf("options") in events }
        }
    }

    @Test
    fun runsCommandsOnSeparateConnection() = FakeMpdServer().use { server ->
        server.handler = { if (it == "status") "state: pause\nelapsed: 10.5\n" else "" }
        val client = MpdClient({ flowOf(server.endpoint) }, options = fastOptions)
        withRunning(client) {
            waitFor("idling") { server.idlingCount() == 1 }
            val status = client.status()
            assertEquals(PlayState.PAUSE, status.state)
            assertEquals(10.5, status.elapsed)
            client.setPaused(false)
            assertTrue("pause \"0\"" in server.received)
            assertEquals(2, server.connectionCount.get())
        }
    }

    @Test
    fun usesSeekcurOnModernServers() = FakeMpdServer().use { server ->
        val client = MpdClient({ flowOf(server.endpoint) }, options = fastOptions)
        withRunning(client) {
            waitFor("connected") { client.state.value is ConnectionState.Connected }
            client.seekCurrent(83.5)
            assertTrue("seekcur \"83.500\"" in server.received, "received: ${server.received}")
        }
    }

    @Test
    fun setsVolumeOnlyWhenAsked() = FakeMpdServer().use { server ->
        val client = MpdClient({ flowOf(server.endpoint) }, options = fastOptions)
        withRunning(client) {
            waitFor("connected") { client.state.value is ConnectionState.Connected }
            client.status()
            assertTrue(server.received.none { it.startsWith("setvol") }, "received: ${server.received}")
            client.setVolume(85)
            assertTrue("setvol \"85\"" in server.received, "received: ${server.received}")
        }
    }

    @Test
    fun usesSeekidOnOldServers() = FakeMpdServer(version = "0.16.0").use { server ->
        server.handler = { if (it == "status") "state: play\nsongid: 7\n" else "" }
        val client = MpdClient({ flowOf(server.endpoint) }, options = fastOptions)
        withRunning(client) {
            waitFor("connected") { client.state.value is ConnectionState.Connected }
            client.seekCurrent(83.5)
            assertTrue("seekid \"7\" \"83\"" in server.received, "received: ${server.received}")
        }
    }

    @Test
    fun fallsBackToNextCandidateAndReportsWhichWorked() = FakeMpdServer().use { server ->
        val dead = Endpoint("127.0.0.1", closedPort())
        val saved = CopyOnWriteArrayList<Endpoint>()
        val client = MpdClient(
            resolver = { flowOf(dead, server.endpoint) },
            onConnected = { saved += it },
            options = fastOptions,
        )
        withRunning(client) {
            waitFor("connected") { client.state.value is ConnectionState.Connected }
            assertEquals(listOf(server.endpoint), saved)
        }
    }

    @Test
    fun waitsAndRetriesWhenNothingReachable() {
        val client = MpdClient({ flowOf(Endpoint("127.0.0.1", closedPort())) }, options = fastOptions)
        withRunning(client) {
            waitFor("waiting state") { client.state.value is ConnectionState.Waiting }
        }
    }

    @Test
    fun reconnectsAfterServerDropsAndRefreshesAgain() = FakeMpdServer().use { server ->
        val client = MpdClient({ flowOf(server.endpoint) }, options = fastOptions)
        withRunning(client) { events ->
            waitFor("idling") { server.idlingCount() == 1 }
            server.dropConnections()
            waitFor("second full refresh") { events.count { it == all } >= 2 }
            waitFor("connected again") { client.state.value is ConnectionState.Connected }
            waitFor("idling again") { server.idlingCount() == 1 }
        }
    }

    @Test
    fun retriesCommandWhenServerClosedQuietConnection() = FakeMpdServer().use { server ->
        server.handler = { if (it == "status") "state: stop\n" else "" }
        val client = MpdClient({ flowOf(server.endpoint) }, options = fastOptions)
        withRunning(client) {
            waitFor("idling") { server.idlingCount() == 1 }
            client.status()
            // Simulate MPD's connection_timeout closing the quiet command connection.
            server.dropNonIdle()
            delay(100)
            assertEquals(PlayState.STOP, client.status().state)
            assertEquals(2, server.received.count { it == "status" })
            assertIs<ConnectionState.Connected>(client.state.value)
        }
    }

    @Test
    fun commandsFailFastWhenDisconnected() = runBlocking {
        val client = MpdClient({ flowOf() }, options = fastOptions)
        try {
            client.status()
            throw AssertionError("expected NotConnectedException")
        } catch (_: NotConnectedException) {
        }
    }
}
