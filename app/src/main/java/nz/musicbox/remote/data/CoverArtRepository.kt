package nz.musicbox.remote.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import nz.musicbox.mpd.MpdClient
import nz.musicbox.mpd.MpdException
import nz.musicbox.mpd.coverArt
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Album covers, keyed by album folder.
 *
 * Each cover is fetched from MPD once, scaled down to [STORED_SIZE] px and kept as a JPEG in the
 * app's cache folder (which Android may clear when space is short). Decoded images are also held
 * in memory so scrolling lists don't flicker. Folders with no art are remembered for the session.
 */
class CoverArtRepository(private val client: MpdClient, context: Context) {
    private val dir = File(context.cacheDir, "covers").apply { mkdirs() }
    private val missing = ConcurrentHashMap.newKeySet<String>()
    private val fetchLocks = ConcurrentHashMap<String, Mutex>()

    private val memory = object : LruCache<String, ImageBitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    /** A cover already in memory, without any waiting. */
    fun peek(directory: String, sizePx: Int): ImageBitmap? = memory.get(memoryKey(directory, sizePx))

    /**
     * The cover for the album in [directory], at least [sizePx] across if the original allows.
     * [songFile] is any song in that folder: MPD looks up art by song.
     */
    suspend fun load(directory: String, songFile: String, sizePx: Int): ImageBitmap? {
        val key = memoryKey(directory, sizePx)
        memory.get(key)?.let { return it }
        if (directory in missing) return null
        val file = diskFile(directory)
        if (!file.exists() && !fetch(directory, songFile, file)) return null
        val image = withContext(Dispatchers.IO) { decode(file, sizePx) } ?: return null
        memory.put(key, image)
        return image
    }

    /** Forgets everything, e.g. after the library changed on the server (covers may have been replaced). */
    fun clear() {
        memory.evictAll()
        missing.clear()
        dir.listFiles()?.forEach { it.delete() }
    }

    private suspend fun fetch(directory: String, songFile: String, target: File): Boolean =
        fetchLocks.computeIfAbsent(directory) { Mutex() }.withLock {
            if (target.exists()) return@withLock true
            val bytes = try {
                client.coverArt(songFile)
            } catch (e: IOException) {
                Log.d(TAG, "Cover fetch failed for $directory: ${e.message}")
                return@withLock false // temporary; try again next time
            } catch (e: MpdException) {
                Log.w(TAG, "Cover refused for $directory: ${e.message}")
                null
            }
            if (bytes == null) {
                missing += directory
                return@withLock false
            }
            withContext(Dispatchers.Default) {
                val stored = store(bytes, target)
                if (!stored) missing += directory
                stored
            }
        }

    /** Scales [bytes] down to [STORED_SIZE] and writes them to [target]. */
    private fun store(bytes: ByteArray, target: File): Boolean {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds, STORED_SIZE) }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return false
        val scale = STORED_SIZE.toFloat() / maxOf(decoded.width, decoded.height)
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                decoded,
                (decoded.width * scale).toInt().coerceAtLeast(1),
                (decoded.height * scale).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            decoded
        }
        val temp = File(target.path + ".tmp")
        temp.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        if (!temp.renameTo(target)) return false
        trimDiskCache()
        return true
    }

    private fun decode(file: File, sizePx: Int): ImageBitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds, sizePx) }
        return BitmapFactory.decodeFile(file.path, options)?.asImageBitmap()
    }

    /** Largest power-of-two reduction that still leaves the image at least [target] px across. */
    private fun sampleSize(bounds: BitmapFactory.Options, target: Int): Int {
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= target) sample *= 2
        return sample
    }

    /** Keeps the folder under [MAX_DISK_BYTES] by deleting the least recently written covers. */
    private fun trimDiskCache() {
        val files = dir.listFiles() ?: return
        var total = files.sumOf { it.length() }
        if (total <= MAX_DISK_BYTES) return
        for (f in files.sortedBy { it.lastModified() }) {
            total -= f.length()
            f.delete()
            if (total <= MAX_DISK_BYTES * 9 / 10) break
        }
    }

    private fun diskFile(directory: String) = File(dir, sha1(directory) + ".jpg")

    // Two decoded sizes are enough: list thumbnails and big covers.
    private fun memoryKey(directory: String, sizePx: Int) = "$directory@" + if (sizePx <= THUMB_SIZE) "s" else "l"

    private fun sha1(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    private companion object {
        const val TAG = "CoverArt"
        const val STORED_SIZE = 800
        const val THUMB_SIZE = 200
        const val MAX_DISK_BYTES = 300L * 1024 * 1024
    }
}
