package app.kairo.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.dp

val KairoShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun KairoTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalKairoColors provides if (dark) DarkExtra else LightExtra) {
        MaterialExpressiveTheme(
            colorScheme = if (dark) KairoDarkScheme else KairoLightScheme,
            motionScheme = MotionScheme.expressive(),
            shapes = KairoShapes,
            typography = KairoTypography,
            content = content,
        )
    }
}

object Kairo {
    val colors: KairoExtraColors
        @Composable get() = LocalKairoColors.current
    val scheme
        @Composable get() = MaterialTheme.colorScheme
}
