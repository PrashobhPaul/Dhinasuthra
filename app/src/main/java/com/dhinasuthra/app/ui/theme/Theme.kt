package com.dhinasuthra.app.ui.theme

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

/**
 * Brand surface of the design system. [DsTokens] holds the semantic palette;
 * this object stays as the shorthand the whole app already speaks, now pointing
 * at the V2 tokens so every screen moves together.
 */
object Ds {
    val Navy = DsTokens.Base
    val NavyRaised = DsTokens.Raised
    val NavyCard = DsTokens.Elevated
    val Purple = DsTokens.Violet
    val Blue = DsTokens.Blue
    val Active = DsTokens.Cyan
    val Positive = DsTokens.Green
    val Amber = DsTokens.Gold
    val Warm = Color(0xFFFF9E45)
    val Cream = DsTokens.Ink
    val Muted = DsTokens.InkSoft
    val ThreadStart = DsTokens.Violet

    /** The Sūtra gradient — thread of the day: violet → blue → cyan → green → gold. */
    val ThreadColors = listOf(ThreadStart, Blue, Active, Positive, Amber, Warm)
    val ThreadBrush = Brush.linearGradient(ThreadColors)
    val SunsetBrush = Brush.linearGradient(listOf(Amber, Warm))
    val CardBrush = DsTokens.CardBrush
}

private val ColorScheme = darkColorScheme(
    primary = Ds.Amber,
    onPrimary = DsTokens.Void,
    secondary = Ds.Active,
    onSecondary = DsTokens.Void,
    tertiary = Ds.Purple,
    background = DsTokens.Base,
    onBackground = DsTokens.Ink,
    surface = DsTokens.Raised,
    onSurface = DsTokens.Ink,
    surfaceVariant = DsTokens.Elevated,
    onSurfaceVariant = DsTokens.InkSoft,
    outline = DsTokens.HairlineStrong,
    error = Color(0xFFFF6B81)
)

/**
 * Typography (spec §31): a serif display voice for the product's own words, a
 * clean sans for reading, and a wide, confident numeric voice for time — the one
 * thing this app is actually about.
 */
private val DsTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold,
        fontSize = 56.sp, lineHeight = 58.sp, letterSpacing = (-2).sp, color = DsTokens.Ink
    ),
    displayMedium = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold,
        fontSize = 40.sp, lineHeight = 44.sp, letterSpacing = (-1.4).sp, color = DsTokens.Ink
    ),
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold,
        fontSize = 30.sp, lineHeight = 34.sp, letterSpacing = (-0.8).sp, color = DsTokens.Ink
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold,
        fontSize = 25.sp, lineHeight = 30.sp, letterSpacing = (-0.2).sp, color = DsTokens.Ink
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp, lineHeight = 26.sp, color = DsTokens.Ink
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 24.sp, color = DsTokens.Ink
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 21.sp,
        letterSpacing = 0.1.sp, color = DsTokens.Ink
    ),
    titleSmall = TextStyle(
        fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 19.sp, color = DsTokens.Ink
    ),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, color = DsTokens.InkSoft),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp, color = DsTokens.InkSoft),
    bodySmall = TextStyle(fontSize = 12.5.sp, lineHeight = 18.sp, color = DsTokens.InkMuted),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = DsTokens.Ink),
    labelMedium = TextStyle(
        fontWeight = FontWeight.Medium, fontSize = 12.sp, letterSpacing = 0.5.sp, color = DsTokens.InkMuted
    ),
    labelSmall = TextStyle(
        fontWeight = FontWeight.Medium, fontSize = 10.5.sp, letterSpacing = 0.9.sp, color = DsTokens.InkMuted
    )
)

@Composable
fun DhinaSuthraTheme(content: @Composable () -> Unit) {
    // The identity is deliberately a dark instrument panel (spec §29); no light variant.
    MaterialTheme(colorScheme = ColorScheme, typography = DsTypography, content = content)
}
