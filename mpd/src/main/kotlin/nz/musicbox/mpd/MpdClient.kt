package nz.musicbox.mpd

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

data class Endpoint(val host: String, val port: Int) {
    override fun toString() = if (':' in host) "[$host]:$port" else "$host:$port"
}

/** Supplies addresses to try, in order of preference. Collected lazily: later ones are only produced if earlier ones fail. */
fun interface EndpointResolver {
    fun candidates(): Flow<Endpoint>
}

sealed interface ConnectionState {
    /** [MpdClient.run] is not active. */
    data object Stopped : ConnectionState

    data object Connecting : ConnectionState

    data class Connected(val endpoint: Endpoint, val version: MpdVersion) : ConnectionState

    /** No server could be reached; the next attempt is in [retryInMs]. */
    data class Waiting(val retryInMs: Long, val reason: String) : ConnectionState
}

/**
 * MPD client with two connections:
 * - an idle connection, owned by [run], that waits for changes and reports them on [changes];
 * - a command connection for requests, opened on demand.
 *
 * [run] keeps reconnecting with backoff until cancelled. After every (re)connect it emits all
 * subsystems on [changes], so listeners re-read full state and the UI is never stale.
 */
class MpdClient(
    private val resolver: EndpointResolver,
    private val onConnected: suspend (Endpoint) -> Unit = {},
    private val options: Options = Options(),
) {
    data class Options(
        val connectTimeoutMs: Int = 4_000,
        val readTimeoutMs: Int = 10_000,
        val heartbeatMs: Int = 30_000,
        val minBackoffMs: Long = 1_000,
        val maxBackoffMs: Long = 30_000,
    )

    private class Session(val endpoint: Endpoint, val idle: MpdConnection)

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Stopped)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _changes = MutableSharedFlow<Set<String>>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Sets of changed subsystem names (see [Mpd.SUBSYSTEMS]). */
    val changes: SharedFlow<Set<String>> = _changes.asSharedFlow()

    @Volatile
    private var session: Session? = null

    /** Version of the connected server, or null when disconnected. */
    val version: MpdVersion? get() = session?.idle?.version

    /**
     * A lazily opened, reused connection with its own lock. If a request fails because the server
     * closed a quiet connection (MPD's connection_timeout), it reopens once and retries.
     */
    private inner class Slot(private val onOpen: (MpdConnection) -> Unit = {}) {
        private val lock = Mutex()
        @Volatile
        private var connection: MpdConnection? = null

        suspend fun <T> use(block: (MpdConnection) -> T): T = lock.withLock {
            withContext(Dispatchers.IO) {
                val current = session ?: throw NotConnectedException()
                val existing = connection
                if (existing != null) {
                    try {
                        return@withContext block(existing)
                    } catch (_: IOException) {
                        existing.close()
                        connection = null
                    }
                }
                val fresh = open(current.endpoint)
                try {
                    onOpen(fresh)
                    connection = fresh
                    block(fresh)
                } catch (e: IOException) {
                    fresh.close()
                    connection = null
                    throw e
                }
            }
        }

        suspend fun drop() {
            connection?.close() // unblocks a request stuck in a read
            lock.withLock {
                connection?.close()
                connection = null
            }
        }
    }

    private val commandSlot = Slot()

    // Cover art has its own connection so image transfers never delay playback commands.
    // Larger binary chunks (MPD 0.22.4+) mean fewer round trips per image.
    private val artSlot = Slot { conn ->
        if (conn.version.atLeast(0, 22, 4)) conn.call(Mpd.command("binarylimit", ART_CHUNK_BYTES))
    }

    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val running = Mutex()

    /** Connects and stays connected until cancelled. Only one call may be active at a time. */
    suspend fun run() {
        check(running.tryLock()) { "MpdClient.run() is already active" }
        try {
            var failures = 0
            while (true) {
                wake.tryReceive()
                _state.value = ConnectionState.Connecting
                var lastError = "No server found"
                val newSession = connectFirst { lastError = it }
                if (newSession == null) {
                    failures++
                    waitBeforeRetry(backoff(failures), lastError)
                    continue
                }
                failures = 0
                session = newSession
                try {
                    try {
                        onConnected(newSession.endpoint)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // Saving the address is best-effort.
                    }
                    _state.value = ConnectionState.Connected(newSession.endpoint, newSession.idle.version)
                    _changes.emit(Mpd.SUBSYSTEMS.toSet())
                    idleLoop(newSession)
                } catch (e: IOException) {
                    lastError = e.message ?: "Connection lost"
                } finally {
                    session = null
                    newSession.idle.close()
                    withContext(NonCancellable) {
                        commandSlot.drop()
                        artSlot.drop()
                    }
                }
                waitBeforeRetry(options.minBackoffMs, lastError)
            }
        } finally {
            _state.value = ConnectionState.Stopped
            running.unlock()
        }
    }

    /** Skips the current backoff wait, e.g. when the network comes back. */
    fun retryNow() {
        if (_state.value is ConnectionState.Waiting) wake.trySend(Unit)
    }

    /** Drops the current connection and starts again, e.g. after the server address changed. */
    fun reconnect() {
        wake.trySend(Unit)
        session?.idle?.close()
    }

    /** Runs [block] on the command connection, opening it if needed. */
    suspend fun <T> withConnection(block: (MpdConnection) -> T): T = commandSlot.use(block)

    /** Runs [block] on the cover-art connection, opening it if needed. */
    suspend fun <T> withArtConnection(block: (MpdConnection) -> T): T = artSlot.use(block)

    /** Sends one command and returns its reply. */
    suspend fun call(command: String): MpdResponse = withConnection { it.call(command) }

    /**
     * Runs [block] on a new connection of its own, closed afterwards. For long jobs (loading the
     * whole library) so they don't hold up playback commands or change the command connection's state.
     */
    suspend fun <T> withDedicatedConnection(block: (MpdConnection) -> T): T {
        val endpoint = session?.endpoint ?: throw NotConnectedException()
        return withContext(Dispatchers.IO) {
            open(endpoint).use { conn ->
                coroutineScope {
                    val closer = launch(start = CoroutineStart.UNDISPATCHED) {
                        try {
                            awaitCancellation()
                        } finally {
                            conn.close()
                        }
                    }
                    try {
                        block(conn)
                    } finally {
                        closer.cancel()
                    }
                }
            }
        }
    }

    private fun open(endpoint: Endpoint) =
        MpdConnection.open(endpoint.host, endpoint.port, options.connectTimeoutMs, options.readTimeoutMs)

    private suspend fun connectFirst(onError: (String) -> Unit): Session? {
        val tried = HashSet<Endpoint>()
        return resolver.candidates()
            .catch { onError(it.message ?: "Server lookup failed") }
            .filter { tried.add(it) }
            .map { endpoint ->
                try {
                    Session(endpoint, withContext(Dispatchers.IO) { open(endpoint) })
                } catch (e: IOException) {
                    onError("$endpoint: ${e.message ?: e.javaClass.simpleName}")
                    null
                }
            }
            .firstOrNull { it != null }
    }

    private suspend fun idleLoop(s: Session) = coroutineScope {
        // Blocking socket reads ignore coroutine cancellation, so close the socket to end them.
        val closer = launch {
            try {
                awaitCancellation()
            } finally {
                s.idle.close()
            }
        }
        try {
            withContext(Dispatchers.IO) {
                while (true) {
                    val changed = s.idle.idle(Mpd.SUBSYSTEMS, options.heartbeatMs)
                    if (changed.isNotEmpty()) _changes.emit(changed)
                }
            }
        } finally {
            closer.cancel()
        }
    }

    private suspend fun waitBeforeRetry(delayMs: Long, reason: String) {
        _state.value = ConnectionState.Waiting(delayMs, reason)
        withTimeoutOrNull(delayMs) { wake.receive() }
    }

    private companion object {
        const val ART_CHUNK_BYTES = 1024 * 1024
    }

    private fun backoff(failures: Int): Long {
        val shift = (failures - 1).coerceIn(0, 10)
        return (options.minBackoffMs shl shift).coerceAtMost(options.maxBackoffMs)
    }
}
