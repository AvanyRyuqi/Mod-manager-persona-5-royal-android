package app.compendium.android.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Red = Color(0xFFDC1E2E)

private val scheme = darkColorScheme(
    primary = Red,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF5A0F17),
    onPrimaryContainer = Color(0xFFFFDAD9),
    secondary = Color(0xFFB9C0D4),
    background = Color(0xFF11141B),
    onBackground = Color(0xFFE6E8EE),
    surface = Color(0xFF11141B),
    onSurface = Color(0xFFE6E8EE),
    surfaceVariant = Color(0xFF1C2233),
    onSurfaceVariant = Color(0xFFA9B0C3),
    surfaceContainer = Color(0xFF181D2A),
    surfaceContainerHigh = Color(0xFF1C2233),
    error = Color(0xFFFFB4AB),
)

val OkGreen = Color(0xFF5CD18A)
val WarnAmber = Color(0xFFE6B450)

@Composable
fun CompendiumTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}
