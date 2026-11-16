package nz.musicbox.remote.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import nz.musicbox.remote.R
import nz.musicbox.remote.data.CoverArtRepository

/** Provided once in the app shell, so any screen can show covers. */
val LocalCovers = staticCompositionLocalOf<CoverArtRepository?> { null }

/**
 * An album cover, loaded in the background. Shows a placeholder icon until it arrives,
 * or for good if the album has no art.
 *
 * @param directory album folder (the cache key)
 * @param songFile any song in that folder (MPD looks art up by song)
 * @param size the size the image is drawn at, used to pick a sharp enough version
 * @param modifier defaults to a square of [size]
 */
@Composable
fun CoverImage(
    directory: String?,
    songFile: String?,
    size: Dp,
    modifier: Modifier = Modifier.size(size),
    shape: Shape = RoundedCornerShape(if (size > 64.dp) 12.dp else 6.dp),
    @DrawableRes placeholder: Int = R.drawable.ic_album,
) {
    val covers = LocalCovers.current
    val px = with(LocalDensity.current) { size.roundToPx() }
    val image by produceState<ImageBitmap?>(
        initialValue = directory?.let { covers?.peek(it, px) },
        directory,
        songFile,
        px,
    ) {
        if (covers != null && directory != null && songFile != null) {
            value = covers.load(directory, songFile, px)
        }
    }

    Surface(modifier = modifier, shape = shape, color = MaterialTheme.colorScheme.surfaceVariant) {
        val bitmap = image
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painterResource(placeholder),
                    contentDescription = null,
                    modifier = Modifier.size(size * 0.45f),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            }
        }
    }
}
