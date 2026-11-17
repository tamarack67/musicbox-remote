package nz.musicbox.remote.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

/** App options other than the server address. */
class AppPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("app", Context.MODE_PRIVATE)

    private val _volumeSlider = MutableStateFlow(prefs.getBoolean(KEY_VOLUME_SLIDER, false))

    /** Whether Now playing shows a volume slider. Off by default: the amplifier sets the volume. */
    val volumeSlider: StateFlow<Boolean> = _volumeSlider.asStateFlow()

    fun setVolumeSlider(on: Boolean) {
        prefs.edit { putBoolean(KEY_VOLUME_SLIDER, on) }
        _volumeSlider.value = on
    }

    /** True the first time it's called, so a permission is only asked for once. */
    fun firstTimeAsking(permission: String): Boolean {
        val key = "asked:$permission"
        if (prefs.getBoolean(key, false)) return false
        prefs.edit { putBoolean(key, true) }
        return true
    }

    private companion object {
        const val KEY_VOLUME_SLIDER = "volume_slider"
    }
}

/**
 * The slider's range. The DAC's volume is logarithmic: below about 60 on MPD's scale it's
 * inaudible, and 100 is normal line level. So the slider (0 to 1) covers 60 to 100 only.
 */
object VolumeRange {
    const val MIN = 60
    const val MAX = 100

    fun toSlider(mpdVolume: Int): Float = ((mpdVolume - MIN).toFloat() / (MAX - MIN)).coerceIn(0f, 1f)

    fun toMpd(slider: Float): Int = (MIN + slider.coerceIn(0f, 1f) * (MAX - MIN)).roundToInt()
}
