package nz.musicbox.remote.data.library

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/** One song. Text fields hold display values (tags, or path-derived when untagged). */
@Entity(tableName = "songs", indices = [Index("directory")])
data class SongEntity(
    @PrimaryKey val file: String,
    val title: String,
    val artist: String,
    val album: String,
    /** The artist of the album this song is in (see [AlbumEntity.artist]). */
    val albumArtist: String,
    val track: Int?,
    val disc: Int?,
    val year: String?,
    val duration: Double?,
    /** Folder holding the file; identifies the album. */
    val directory: String,
    /** Lower-case, accent-free "title artist album" for search. */
    val searchText: String,
)

/** One album = one folder of songs. */
@Entity(tableName = "albums", indices = [Index("artist")])
data class AlbumEntity(
    @PrimaryKey val directory: String,
    val title: String,
    val artist: String,
    val year: String?,
    val trackCount: Int,
    /** Seconds. */
    val duration: Double,
    val searchText: String,
    val artistSearchText: String,
    /** A song in the folder, used to ask MPD for the album's cover art. */
    val coverFile: String,
)

@Entity(tableName = "meta")
data class MetaEntity(
    @PrimaryKey val name: String,
    val value: String,
)

/** An artist, with one of their albums to borrow a cover from. */
data class ArtistRow(
    val name: String,
    val albumCount: Int,
    val coverDirectory: String? = null,
    val coverFile: String? = null,
    /** Year of the earliest album (also what picks the cover album). */
    val firstYear: String? = null,
)

@Dao
interface LibraryDao {
    @Query(
        // MIN(year) makes SQLite take the other bare columns from the artist's earliest album.
        "SELECT artist AS name, COUNT(*) AS albumCount, MIN(year) AS firstYear, " +
            "directory AS coverDirectory, coverFile FROM albums " +
            "GROUP BY artist COLLATE NOCASE ORDER BY artist COLLATE NOCASE",
    )
    fun artists(): Flow<List<ArtistRow>>

    @Query("SELECT * FROM albums ORDER BY title COLLATE NOCASE, artist COLLATE NOCASE")
    fun albums(): Flow<List<AlbumEntity>>

    @Query("SELECT * FROM albums WHERE artist = :artist COLLATE NOCASE ORDER BY year, title COLLATE NOCASE")
    fun albumsBy(artist: String): Flow<List<AlbumEntity>>

    @Query("SELECT * FROM albums WHERE directory = :directory")
    fun album(directory: String): Flow<AlbumEntity?>

    @Query("SELECT * FROM songs WHERE directory = :directory ORDER BY disc, track, file")
    fun tracks(directory: String): Flow<List<SongEntity>>

    @Query("SELECT COUNT(*) FROM songs")
    fun songCount(): Flow<Int>

    @Query(
        "SELECT artist AS name, COUNT(*) AS albumCount, MIN(year) AS firstYear, " +
            "directory AS coverDirectory, coverFile FROM albums WHERE artistSearchText LIKE '%' || :term || '%' " +
            "GROUP BY artist COLLATE NOCASE ORDER BY artist COLLATE NOCASE LIMIT :limit",
    )
    suspend fun searchArtists(term: String, limit: Int): List<ArtistRow>

    @Query("SELECT * FROM albums WHERE searchText LIKE '%' || :term || '%' ORDER BY title COLLATE NOCASE LIMIT :limit")
    suspend fun searchAlbums(term: String, limit: Int): List<AlbumEntity>

    @Query("SELECT * FROM songs WHERE searchText LIKE '%' || :term || '%' ORDER BY title COLLATE NOCASE LIMIT :limit")
    suspend fun searchSongs(term: String, limit: Int): List<SongEntity>

    @Query("DELETE FROM songs")
    suspend fun clearSongs()

    @Query("DELETE FROM albums")
    suspend fun clearAlbums()

    @Insert
    suspend fun insertSongs(songs: List<SongEntity>)

    @Insert
    suspend fun insertAlbums(albums: List<AlbumEntity>)

    @Query("SELECT value FROM meta WHERE name = :name")
    suspend fun meta(name: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putMeta(meta: MetaEntity)
}

@Database(entities = [SongEntity::class, AlbumEntity::class, MetaEntity::class], version = 2, exportSchema = false)
abstract class LibraryDatabase : RoomDatabase() {
    abstract fun dao(): LibraryDao

    companion object {
        fun create(context: Context): LibraryDatabase =
            Room.databaseBuilder(context, LibraryDatabase::class.java, "library.db")
                // It's only a cache of what's on the server: rebuild rather than migrate.
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
