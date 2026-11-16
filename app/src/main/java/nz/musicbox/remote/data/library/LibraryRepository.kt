package nz.musicbox.remote.data.library

import android.util.Log
import androidx.room.withTransaction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import nz.musicbox.mpd.LsEntry
import nz.musicbox.mpd.MpdClient
import nz.musicbox.mpd.MpdException
import nz.musicbox.mpd.loadAllSongs
import nz.musicbox.mpd.lsinfo
import nz.musicbox.mpd.stats
import java.io.IOException

sealed interface LibrarySync {
    data object Idle : LibrarySync
    data class Loading(val loaded: Int, val total: Int) : LibrarySync
    data class Failed(val message: String) : LibrarySync
}

data class SearchResults(
    val artists: List<ArtistRow> = emptyList(),
    val albums: List<AlbumEntity> = emptyList(),
    val songs: List<SongEntity> = emptyList(),
) {
    val isEmpty get() = artists.isEmpty() && albums.isEmpty() && songs.isEmpty()
}

/**
 * The library, served from a Room cache so browsing and search are instant.
 * The cache is reloaded when MPD's `db_update` changes, checked on every connect and on
 * every `database` idle event.
 */
class LibraryRepository(
    private val client: MpdClient,
    private val db: LibraryDatabase,
    scope: CoroutineScope,
) {
    private val dao = db.dao()
    private val refreshLock = Mutex()

    private val _sync = MutableStateFlow<LibrarySync>(LibrarySync.Idle)
    val sync: StateFlow<LibrarySync> = _sync.asStateFlow()

    init {
        scope.launch {
            client.changes
                .filter { "database" in it }
                .conflate()
                .collect { refreshIfChanged() }
        }
    }

    val artists: Flow<List<ArtistRow>> = dao.artists()
    val albums: Flow<List<AlbumEntity>> = dao.albums()
    val songCount: Flow<Int> = dao.songCount()
    fun albumsBy(artist: String) = dao.albumsBy(artist)
    fun album(directory: String) = dao.album(directory)
    fun tracks(directory: String) = dao.tracks(directory)

    /** Folder contents, read live from the server. */
    suspend fun folder(path: String): List<LsEntry> = client.lsinfo(path)

    suspend fun search(query: String): SearchResults {
        val terms = searchKey(query).split(WHITESPACE).filter { it.isNotEmpty() }
        if (terms.isEmpty()) return SearchResults()
        // SQL narrows by the longest word; the rest are checked here.
        val lead = terms.maxBy { it.length }
        fun matchesAll(text: String) = terms.all { it in text }
        return SearchResults(
            artists = dao.searchArtists(lead, 200).filter { matchesAll(searchKey(it.name)) }.take(20),
            albums = dao.searchAlbums(lead, 1000).filter { matchesAll(it.searchText) }.take(50),
            songs = dao.searchSongs(lead, 3000).filter { matchesAll(it.searchText) }.take(200),
        )
    }

    suspend fun refreshIfChanged() = refreshLock.withLock {
        try {
            val stats = client.stats()
            val stamp = "${stats.dbUpdate}/${stats.songs}"
            if (dao.meta(KEY_STAMP) == stamp) return@withLock
            Log.i(TAG, "Library changed ($stamp), reloading ${stats.songs} songs")
            _sync.value = LibrarySync.Loading(0, stats.songs)
            val songs = client.loadAllSongs { _sync.value = LibrarySync.Loading(it, stats.songs) }
            val data = withContext(Dispatchers.Default) { buildLibrary(songs) }
            db.withTransaction {
                dao.clearSongs()
                dao.clearAlbums()
                dao.insertSongs(data.songs)
                dao.insertAlbums(data.albums)
                dao.putMeta(MetaEntity(KEY_STAMP, stamp))
            }
            Log.i(TAG, "Library cached: ${data.songs.size} songs, ${data.albums.size} albums")
            _sync.value = LibrarySync.Idle
        } catch (e: IOException) {
            // Connection dropped; the reconnect triggers another check.
            Log.d(TAG, "Library refresh interrupted: ${e.message}")
            _sync.value = LibrarySync.Idle
        } catch (e: MpdException) {
            Log.w(TAG, "Library refresh refused", e)
            _sync.value = LibrarySync.Failed(e.serverMessage)
        }
    }

    private companion object {
        const val TAG = "LibraryRepository"
        const val KEY_STAMP = "db_stamp"
        val WHITESPACE = Regex("\\s+")
    }
}
