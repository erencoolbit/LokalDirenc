package org.lokaldirenc.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Anlamı olan renkler: uyarı, dikkat ve başarı. Her iki modda da okunur kontrast taşır. */
@Immutable
internal class SemanticColors(val warn: Color, val danger: Color, val caution: Color, val ok: Color)

private val LightSemantic = SemanticColors(warn = Color(0xFFB3261E), danger = Color(0xFFB3261E), caution = Color(0xFF8A5A00), ok = Color(0xFF1B6E4F))
private val DarkSemantic = SemanticColors(warn = Color(0xFFFFB4AB), danger = Color(0xFFC62828), caution = Color(0xFFF2C14E), ok = Color(0xFF7FD8B0))

private val LightScheme: ColorScheme = lightColorScheme(
    primary = Color(0xFF14523E),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCFE9DC),
    onPrimaryContainer = Color(0xFF07291E),
    secondary = Color(0xFF7A5B00),
    onSecondary = Color.White,
    background = Color(0xFFF8F9F6),
    onBackground = Color(0xFF191C1A),
    surface = Color(0xFFF8F9F6),
    onSurface = Color(0xFF191C1A),
    surfaceVariant = Color(0xFFE6EAE5),
    onSurfaceVariant = Color(0xFF414943),
    surfaceContainerHigh = Color(0xFFECEFEA),
    surfaceContainerHighest = Color(0xFFE6EAE5),
    surfaceContainerLow = Color(0xFFF1F4EF),
    outline = Color(0xFF727971),
    outlineVariant = Color(0xFFC1C9C1),
)

private val DarkScheme: ColorScheme = darkColorScheme(
    primary = Color(0xFF8FD3B6),
    onPrimary = Color(0xFF00382A),
    primaryContainer = Color(0xFF14523E),
    onPrimaryContainer = Color(0xFFCFE9DC),
    secondary = Color(0xFFF2C14E),
    onSecondary = Color(0xFF3F2E00),
    background = Color(0xFF111412),
    onBackground = Color(0xFFE1E3DF),
    surface = Color(0xFF111412),
    onSurface = Color(0xFFE1E3DF),
    surfaceVariant = Color(0xFF242925),
    onSurfaceVariant = Color(0xFFC1C9C1),
    surfaceContainerHigh = Color(0xFF242925),
    surfaceContainerHighest = Color(0xFF2E332F),
    surfaceContainerLow = Color(0xFF191D1A),
    outline = Color(0xFF8B938B),
    outlineVariant = Color(0xFF414943),
)

private val LocalSemantic = staticCompositionLocalOf { LightSemantic }

internal val Warn: Color @Composable get() = LocalSemantic.current.warn

/** Dolu tehlike düğmesi: iki modda da beyaz yazılı koyu kırmızı. */
internal val Danger: Color @Composable get() = LocalSemantic.current.danger
internal val OnDanger: Color = Color.White
internal val Caution: Color @Composable get() = LocalSemantic.current.caution
internal val Ok: Color @Composable get() = LocalSemantic.current.ok

/** Kullanıcının seçimi: sistem, aydınlık veya karanlık. */
@Composable
internal fun isDarkTheme(choice: String): Boolean = when (choice) {
    "light" -> false
    "dark" -> true
    else -> isSystemInDarkTheme()
}

@Composable
internal fun LokalTheme(dark: Boolean, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalSemantic provides if (dark) DarkSemantic else LightSemantic) {
        MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme, content = content)
    }
}
