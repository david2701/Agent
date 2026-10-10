package app.kairo.android.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.kairo.android.R

val Geist = FontFamily(
    Font(R.font.geist_regular, FontWeight.Normal),
    Font(R.font.geist_medium, FontWeight.Medium),
    Font(R.font.geist_semibold, FontWeight.SemiBold),
    Font(R.font.geist_bold, FontWeight.Bold),
)

val GeistMono = FontFamily(
    Font(R.font.geistmono_regular, FontWeight.Normal),
    Font(R.font.geistmono_medium, FontWeight.Medium),
)

private fun style(size: Int, line: Int, weight: FontWeight, tracking: Double = 0.0) = TextStyle(
    fontFamily = Geist,
    fontSize = size.sp,
    lineHeight = line.sp,
    fontWeight = weight,
    letterSpacing = tracking.em,
)

val KairoTypography = Typography(
    displayLarge = style(52, 56, FontWeight.SemiBold, -0.03),
    displayMedium = style(42, 48, FontWeight.SemiBold, -0.03),
    displaySmall = style(34, 40, FontWeight.SemiBold, -0.025),
    headlineLarge = style(30, 36, FontWeight.SemiBold, -0.02),
    headlineMedium = style(26, 32, FontWeight.SemiBold, -0.02),
    headlineSmall = style(22, 28, FontWeight.SemiBold, -0.015),
    titleLarge = style(20, 26, FontWeight.SemiBold, -0.01),
    titleMedium = style(16, 22, FontWeight.Medium, -0.005),
    titleSmall = style(14, 20, FontWeight.Medium),
    bodyLarge = style(16, 24, FontWeight.Normal),
    bodyMedium = style(14, 21, FontWeight.Normal),
    bodySmall = style(12, 17, FontWeight.Normal),
    labelLarge = style(14, 20, FontWeight.Medium),
    labelMedium = style(12, 16, FontWeight.Medium, 0.01),
    labelSmall = style(11, 14, FontWeight.Medium, 0.02),
)

/** Times, dates and numbers. */
val MonoStyle = TextStyle(fontFamily = GeistMono, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp)
