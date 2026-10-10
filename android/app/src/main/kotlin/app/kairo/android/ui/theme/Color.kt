package app.kairo.android.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// Tokens from design/DESIGN.md. A single accent: amber.
object KairoPalette {
    val Ink = Color(0xFF0E1013)
    val Bone = Color(0xFFECE8E1)
    val Amber = Color(0xFFF0A23B)
    val AmberLight = Color(0xFFE0901F)
    val AmberStrongDark = Color(0xFFF5B65E)
    val AmberStrongLight = Color(0xFF8F5300)
    val OnAmber = Color(0xFF1A1206)
}

val KairoDarkScheme: ColorScheme = darkColorScheme(
    primary = KairoPalette.Amber,
    onPrimary = KairoPalette.OnAmber,
    primaryContainer = KairoPalette.Amber,
    onPrimaryContainer = KairoPalette.OnAmber,
    inversePrimary = KairoPalette.AmberStrongLight,
    secondary = Color(0xFFC9CDD3),
    onSecondary = Color(0xFF15181C),
    secondaryContainer = Color(0xFF23282E),
    onSecondaryContainer = Color(0xFFECEDEE),
    tertiary = Color(0xFFC9CDD3),
    onTertiary = Color(0xFF15181C),
    tertiaryContainer = Color(0xFF23282E),
    onTertiaryContainer = Color(0xFFECEDEE),
    background = KairoPalette.Ink,
    onBackground = Color(0xFFECEDEE),
    surface = KairoPalette.Ink,
    onSurface = Color(0xFFECEDEE),
    surfaceVariant = Color(0xFF1C2025),
    onSurfaceVariant = Color(0xFF9BA1A9),
    surfaceTint = KairoPalette.Amber,
    inverseSurface = Color(0xFFECEDEE),
    inverseOnSurface = Color(0xFF15181C),
    error = Color(0xFFF0705F),
    onError = Color(0xFF1A0A08),
    errorContainer = Color(0xFF3B1A15),
    onErrorContainer = Color(0xFFF7B4AA),
    outline = Color(0xFF3A4048),
    outlineVariant = Color(0xFF262B31),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF23282E),
    surfaceDim = Color(0xFF0B0D10),
    surfaceContainerLowest = Color(0xFF0B0D10),
    surfaceContainerLow = Color(0xFF121519),
    surfaceContainer = Color(0xFF15181C),
    surfaceContainerHigh = Color(0xFF1C2025),
    surfaceContainerHighest = Color(0xFF23282E),
)

val KairoLightScheme: ColorScheme = lightColorScheme(
    primary = KairoPalette.AmberStrongLight,
    onPrimary = Color(0xFFFFFBF5),
    primaryContainer = KairoPalette.Amber,
    onPrimaryContainer = KairoPalette.OnAmber,
    inversePrimary = KairoPalette.Amber,
    secondary = Color(0xFF3D434B),
    onSecondary = Color(0xFFFCFCFD),
    secondaryContainer = Color(0xFFE4E7EA),
    onSecondaryContainer = Color(0xFF14161A),
    tertiary = Color(0xFF3D434B),
    onTertiary = Color(0xFFFCFCFD),
    tertiaryContainer = Color(0xFFE4E7EA),
    onTertiaryContainer = Color(0xFF14161A),
    background = Color(0xFFF3F4F5),
    onBackground = Color(0xFF14161A),
    surface = Color(0xFFF3F4F5),
    onSurface = Color(0xFF14161A),
    surfaceVariant = Color(0xFFEEF0F2),
    onSurfaceVariant = Color(0xFF5B616A),
    surfaceTint = KairoPalette.AmberLight,
    inverseSurface = Color(0xFF15181C),
    inverseOnSurface = Color(0xFFECEDEE),
    error = Color(0xFFC2412F),
    onError = Color(0xFFFFFBFA),
    errorContainer = Color(0xFFFBE4E0),
    onErrorContainer = Color(0xFF5C1A10),
    outline = Color(0xFFC3C8CE),
    outlineVariant = Color(0xFFDFE2E6),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFCFCFD),
    surfaceDim = Color(0xFFE4E7EA),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFCFCFD),
    surfaceContainer = Color(0xFFF7F8F9),
    surfaceContainerHigh = Color(0xFFEEF0F2),
    surfaceContainerHighest = Color(0xFFE6E9EC),
)

/** Semantic colors that Material does not cover. */
@Immutable
data class KairoExtraColors(
    val success: Color,
    val accentFill: Color,
    val onAccentFill: Color,
    val faint: Color,
    val orbRing: Color,
)

val DarkExtra = KairoExtraColors(Color(0xFF5BC48A), KairoPalette.Amber, KairoPalette.OnAmber, Color(0xFF6B717A), KairoPalette.Bone)
val LightExtra = KairoExtraColors(Color(0xFF2E8A57), KairoPalette.Amber, KairoPalette.OnAmber, Color(0xFF858B94), Color(0xFF14161A))

val LocalKairoColors = staticCompositionLocalOf { DarkExtra }
