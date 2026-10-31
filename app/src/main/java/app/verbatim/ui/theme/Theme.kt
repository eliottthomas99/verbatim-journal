package app.verbatim.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// The brand palette is used instead of Android 12's "dynamic color" (which
// would repaint the app with the wallpaper's colors), so the app matches its icon.
private val LightColors = lightColorScheme(
    primary = Teal700,
    onPrimary = Color.White,
    primaryContainer = Teal100,
    onPrimaryContainer = Teal900,
    secondary = GoldDark,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFE6B8),
    onSecondaryContainer = Color(0xFF3A2600),
    tertiary = Teal500,
    background = Cream,
    onBackground = Ink,
    surface = Cream,
    onSurface = Ink,
    surfaceVariant = CreamDim,
    onSurfaceVariant = InkMuted,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White, // cards: lighter than the cream background
    surfaceContainer = CreamDim,
    surfaceContainerHigh = Color(0xFFEDE5D7),
    outline = Color(0xFF8A9591),
    outlineVariant = Color(0xFFDCD3C4),
    error = Recording,
)

private val DarkColors = darkColorScheme(
    primary = Teal200,
    onPrimary = Teal900,
    primaryContainer = Teal700,
    onPrimaryContainer = Teal100,
    secondary = Gold,
    onSecondary = Color(0xFF3A2600),
    secondaryContainer = Color(0xFF5C4100),
    onSecondaryContainer = Color(0xFFFFE6B8),
    tertiary = Teal200,
    background = Night,
    onBackground = Color(0xFFE6EEEB),
    surface = Night,
    onSurface = Color(0xFFE6EEEB),
    surfaceVariant = NightSurfaceHigh,
    onSurfaceVariant = Color(0xFFA9B8B3),
    surfaceContainerLowest = Color(0xFF0B1211),
    surfaceContainerLow = NightSurface, // cards: lighter than the night background
    surfaceContainer = NightSurface,
    surfaceContainerHigh = NightSurfaceHigh,
    outline = Color(0xFF6F7E7A),
    outlineVariant = Color(0xFF2F3D39),
    error = Color(0xFFFF8A80),
)

@Composable
fun VerbatimTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = Typography,
        content = content
    )
}
