package com.dhinasuthra.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Brand color system — product.md §39 verbatim. */
object Ds {
    val Navy = Color(0xFF0D1324)
    val NavyRaised = Color(0xFF141C33)
    val NavyCard = Color(0xFF18213C)
    val Purple = Color(0xFF3B2F88)
    val Blue = Color(0xFF2563EB)
    val Active = Color(0xFF2FA7FF)
    val Positive = Color(0xFF22C55E)
    val Amber = Color(0xFFF59E0B)
    val Warm = Color(0xFFFF9E45)
    val Cream = Color(0xFFFFF4E6)
    val Muted = Color(0xFFA9B2C3)
    val ThreadStart = Color(0xFF8B7CF6)

    /** The Sūtra gradient — thread of the day: purple → blue → cyan → gold. */
    val ThreadColors = listOf(ThreadStart, Blue, Active, Positive, Amber, Warm)
    val ThreadBrush = Brush.linearGradient(ThreadColors)
    val SunsetBrush = Brush.linearGradient(listOf(Amber, Warm))
    val CardBrush = Brush.verticalGradient(listOf(NavyCard, NavyRaised))
}

private val ColorScheme = darkColorScheme(
    primary = Ds.Amber,
    onPrimary = Ds.Navy,
    secondary = Ds.Active,
    onSecondary = Ds.Navy,
    tertiary = Ds.Purple,
    background = Ds.Navy,
    onBackground = Ds.Cream,
    surface = Ds.NavyRaised,
    onSurface = Ds.Cream,
    surfaceVariant = Ds.NavyCard,
    onSurfaceVariant = Ds.Muted,
    outline = Ds.Muted.copy(alpha = 0.3f),
    error = Color(0xFFEF6C6C)
)

private val DsTypography = Typography(
    displayLarge = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold, fontSize = 40.sp, color = Ds.Cream),
    headlineMedium = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold, fontSize = 26.sp, color = Ds.Cream),
    headlineSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 21.sp, color = Ds.Cream),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, letterSpacing = 0.1.sp, color = Ds.Cream),
    titleSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, color = Ds.Cream),
    bodyLarge = TextStyle(fontSize = 16.sp, color = Ds.Cream),
    bodyMedium = TextStyle(fontSize = 14.sp, color = Ds.Cream),
    bodySmall = TextStyle(fontSize = 12.sp, color = Ds.Muted),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Ds.Cream),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, letterSpacing = 0.4.sp, color = Ds.Muted),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 10.sp, letterSpacing = 0.6.sp, color = Ds.Muted)
)

@Composable
fun DhinaSuthraTheme(content: @Composable () -> Unit) {
    // Brand identity is intentionally dark (product.md §37); dark theme always.
    isSystemInDarkTheme()
    MaterialTheme(colorScheme = ColorScheme, typography = DsTypography, content = content)
}
