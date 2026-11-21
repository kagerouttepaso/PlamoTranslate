package app.plamotranslate.desktop

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val NightBird = darkColorScheme(
    primary = Color(0xFFFF8A65), onPrimary = Color(0xFF3B1408),
    primaryContainer = Color(0xFF6A2F42), onPrimaryContainer = Color(0xFFFFDCD0),
    secondary = Color(0xFF7FE0C8), onSecondary = Color(0xFF003829),
    secondaryContainer = Color(0xFF185867), onSecondaryContainer = Color(0xFFC6F6EA),
    tertiary = Color(0xFFA9B8FF), onTertiary = Color(0xFF15226A),
    tertiaryContainer = Color(0xFF2F3B7E), onTertiaryContainer = Color(0xFFDDE1FF),
    background = Color(0xFF141A30), onBackground = Color(0xFFE8EAF6),
    surface = Color(0xFF141A30), onSurface = Color(0xFFE8EAF6),
    surfaceVariant = Color(0xFF2C3458), onSurfaceVariant = Color(0xFFB9BEDA),
    surfaceContainerLowest = Color(0xFF0F1426), surfaceContainerLow = Color(0xFF181F38),
    surfaceContainer = Color(0xFF1B2240), surfaceContainerHigh = Color(0xFF20284A),
    surfaceContainerHighest = Color(0xFF2C3458), outline = Color(0xFF5A6390), outlineVariant = Color(0xFF2C3458),
)

private val PaperChirp = lightColorScheme(
    primary = Color(0xFFC23B22), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDAD2), onPrimaryContainer = Color(0xFF5C1305),
    secondary = Color(0xFF3E6B5A), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFC9E8DA), onSecondaryContainer = Color(0xFF0F2C22),
    tertiary = Color(0xFF4A5A8C), onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFDDE2F7), onTertiaryContainer = Color(0xFF17213F),
    background = Color(0xFFFBF7F0), onBackground = Color(0xFF2A2622),
    surface = Color(0xFFFBF7F0), onSurface = Color(0xFF2A2622),
    surfaceVariant = Color(0xFFEFE6D8), onSurfaceVariant = Color(0xFF6B6156),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF7F1E7),
    surfaceContainer = Color(0xFFF4ECDF), surfaceContainerHigh = Color(0xFFEFE6D8),
    surfaceContainerHighest = Color(0xFFE8DDCB), outline = Color(0xFF9C9083), outlineVariant = Color(0xFFE5DCCB),
)

@Composable
internal fun DesktopTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) NightBird else PaperChirp, content = content)
}
