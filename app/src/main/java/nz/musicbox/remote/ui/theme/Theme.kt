package nz.musicbox.remote.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// The app's own palette: warm amber, like the glow of a valve amp, on warm greys.
// Follows the phone's light or dark setting.

private val Dark = darkColorScheme(
    primary = Color(0xFFFFB95C),
    onPrimary = Color(0xFF462A00),
    primaryContainer = Color(0xFF643F00),
    onPrimaryContainer = Color(0xFFFFDDB6),
    inversePrimary = Color(0xFF835414),
    secondary = Color(0xFFDDC2A1),
    onSecondary = Color(0xFF3E2D16),
    secondaryContainer = Color(0xFF56432B),
    onSecondaryContainer = Color(0xFFFADEBC),
    tertiary = Color(0xFFBBCF9E),
    onTertiary = Color(0xFF273514),
    tertiaryContainer = Color(0xFF3D4C29),
    onTertiaryContainer = Color(0xFFD7EBB8),
    background = Color(0xFF17130E),
    onBackground = Color(0xFFECE1D7),
    surface = Color(0xFF17130E),
    onSurface = Color(0xFFECE1D7),
    surfaceVariant = Color(0xFF51453A),
    onSurfaceVariant = Color(0xFFD5C4B5),
    surfaceTint = Color(0xFFFFB95C),
    inverseSurface = Color(0xFFECE1D7),
    inverseOnSurface = Color(0xFF352F2A),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF9E8E81),
    outlineVariant = Color(0xFF51453A),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF3E3832),
    surfaceDim = Color(0xFF17130E),
    surfaceContainerLowest = Color(0xFF110D09),
    surfaceContainerLow = Color(0xFF1F1B16),
    surfaceContainer = Color(0xFF231F1A),
    surfaceContainerHigh = Color(0xFF2E2924),
    surfaceContainerHighest = Color(0xFF39332E),
)

private val Light = lightColorScheme(
    primary = Color(0xFF835414),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDDB6),
    onPrimaryContainer = Color(0xFF2A1700),
    inversePrimary = Color(0xFFFFB95C),
    secondary = Color(0xFF6F5B40),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFADEBC),
    onSecondaryContainer = Color(0xFF271904),
    tertiary = Color(0xFF536440),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFD7EBB8),
    onTertiaryContainer = Color(0xFF121F03),
    background = Color(0xFFFFF8F4),
    onBackground = Color(0xFF211A14),
    surface = Color(0xFFFFF8F4),
    onSurface = Color(0xFF211A14),
    surfaceVariant = Color(0xFFF1E0D0),
    onSurfaceVariant = Color(0xFF51453A),
    surfaceTint = Color(0xFF835414),
    inverseSurface = Color(0xFF362F29),
    inverseOnSurface = Color(0xFFF9EFE6),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    outline = Color(0xFF837568),
    outlineVariant = Color(0xFFD5C4B5),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFFF8F4),
    surfaceDim = Color(0xFFE2D8CF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFCF2E9),
    surfaceContainer = Color(0xFFF6ECE3),
    surfaceContainerHigh = Color(0xFFF0E6DD),
    surfaceContainerHighest = Color(0xFFEBE0D8),
)

@Composable
fun MusicBoxTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
