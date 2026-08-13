package com.dhinasuthra.app.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.dhinasuthra.app.intelligence.ActivityType
import com.dhinasuthra.app.intelligence.ConfidenceBand
import com.dhinasuthra.app.intelligence.LocationType
import com.dhinasuthra.app.intelligence.PatternLifecycle
import com.dhinasuthra.app.intelligence.RuleDomain

/**
 * The design tokens (spec §29, §30).
 *
 * One rule governs this file: **the same activity means the same thing
 * everywhere**. Sleep is the same violet on the Today ribbon, in the radial
 * clock, in the year landscape and in the legend of a box plot. Nothing in the
 * app is allowed to name a colour literally — it asks here.
 */
object DsTokens {

    // -- surfaces -----------------------------------------------------------

    val Void = Color(0xFF070B16)          // deepest background, behind everything
    val Base = Color(0xFF0B1120)          // page background
    val Raised = Color(0xFF121A2E)        // cards
    val Elevated = Color(0xFF18223C)      // sheets, popovers
    val Hairline = Color(0x1AFFFFFF)      // 10% white — the only border weight
    val HairlineStrong = Color(0x33FFFFFF)

    // -- ink ----------------------------------------------------------------

    val Ink = Color(0xFFF3F5FB)
    val InkSoft = Color(0xFFB9C2D6)
    val InkMuted = Color(0xFF7B87A3)
    val InkFaint = Color(0xFF4A556E)

    // -- accents ------------------------------------------------------------

    val Gold = Color(0xFFF5B33C)          // the thread — primary accent
    val GoldSoft = Color(0xFFFFD98A)
    val Cyan = Color(0xFF3BC9F0)
    val Violet = Color(0xFF8B7CF6)
    val Green = Color(0xFF34D399)
    val Rose = Color(0xFFFF5C7A)
    val Blue = Color(0xFF3B82F6)

    // -- semantic activity palette (§30) ------------------------------------

    private val activityColors = mapOf(
        ActivityType.SLEEP to Color(0xFF8B7CF6),
        ActivityType.WAKE_TRANSITION to Color(0xFFA5B4FC),
        ActivityType.MORNING_ROUTINE to Color(0xFF5EEAD4),
        ActivityType.BREAKFAST to Color(0xFF4ADE80),
        ActivityType.WORK to Color(0xFFF59E0B),
        ActivityType.MEETING to Color(0xFFFB923C),
        ActivityType.LUNCH to Color(0xFF34D399),
        ActivityType.TEA_BREAK to Color(0xFF6EE7B7),
        ActivityType.EXERCISE to Color(0xFFFF5C7A),
        ActivityType.COMMUTE to Color(0xFFFBBF24),
        ActivityType.DINNER to Color(0xFF22C55E),
        ActivityType.PERSONAL to Color(0xFF94A3B8),
        ActivityType.LEISURE to Color(0xFF818CF8),
        ActivityType.UNKNOWN to Color(0xFF39435C)
    )

    private val locationColors = mapOf(
        LocationType.HOME to Color(0xFF3BA9FF),
        LocationType.OFFICE to Color(0xFFF59E0B),
        LocationType.GYM to Color(0xFFFF5C7A),
        LocationType.RESTAURANT to Color(0xFF34D399),
        LocationType.FRIEND to Color(0xFFA78BFA),
        LocationType.OTHER_KNOWN to Color(0xFF38BDF8),
        LocationType.TRANSIT to Color(0xFFFBBF24),
        LocationType.UNKNOWN to Color(0xFF39435C)
    )

    fun colorFor(activity: ActivityType): Color = activityColors[activity] ?: InkMuted

    fun colorFor(location: LocationType): Color = locationColors[location] ?: InkMuted

    fun colorFor(band: ConfidenceBand): Color = when (band) {
        ConfidenceBand.CONFIDENT -> Green
        ConfidenceBand.LIKELY -> Cyan
        ConfidenceBand.POSSIBLE -> Gold
        ConfidenceBand.UNCERTAIN -> InkMuted
    }

    fun colorFor(lifecycle: PatternLifecycle): Color = when (lifecycle) {
        PatternLifecycle.ESTABLISHED -> Green
        PatternLifecycle.EMERGING -> Cyan
        PatternLifecycle.LEARNING -> InkMuted
        PatternLifecycle.UNSTABLE -> Gold
        PatternLifecycle.STALE -> Rose
    }

    fun colorFor(domain: RuleDomain): Color = when (domain) {
        RuleDomain.SLEEP -> Color(0xFF8B7CF6)
        RuleDomain.MEAL -> Color(0xFF34D399)
        RuleDomain.WORK -> Color(0xFFF59E0B)
        RuleDomain.COMMUTE -> Color(0xFFFBBF24)
        RuleDomain.LOCATION -> Color(0xFF3BA9FF)
        RuleDomain.TIMELINE -> Color(0xFF5EEAD4)
        RuleDomain.PATTERN -> Color(0xFF818CF8)
        RuleDomain.ROUTINE -> Color(0xFFFF9E45)
        RuleDomain.REMINDER -> Color(0xFFF5B33C)
        RuleDomain.ANOMALY -> Color(0xFFFF5C7A)
        RuleDomain.CONSISTENCY -> Color(0xFF3BC9F0)
        RuleDomain.NARRATIVE -> Color(0xFFA78BFA)
    }

    /** Gentle vertical wash used inside cards so surfaces read as glass, not paint. */
    fun glass(tint: Color = Cyan): Brush = Brush.verticalGradient(
        listOf(
            Color.White.copy(alpha = 0.055f),
            tint.copy(alpha = 0.028f),
            Color.White.copy(alpha = 0.012f)
        )
    )

    val CardBrush: Brush = Brush.verticalGradient(listOf(Raised, Color(0xFF0E1626)))
    val ThreadBrush: Brush = Brush.linearGradient(listOf(Violet, Blue, Cyan, Green, Gold))
    val GoldBrush: Brush = Brush.linearGradient(listOf(GoldSoft, Gold))

    fun activityBrush(activity: ActivityType): Brush {
        val c = colorFor(activity)
        return Brush.verticalGradient(listOf(c.copy(alpha = 0.95f), c.copy(alpha = 0.6f)))
    }

    // -- rhythm -------------------------------------------------------------

    /** A single ramp for "how close to your usual" — used by score rings and heat cells. */
    fun rhythmColor(score: Int): Color = when {
        score >= 85 -> Green
        score >= 70 -> Cyan
        score >= 50 -> Gold
        else -> Rose
    }

    // -- metrics ------------------------------------------------------------

    val ScreenPadding = 20.dp
    val CardPadding = 18.dp
    val CardCorner = 26.dp
    val ChipCorner = 14.dp
    val GapS = 8.dp
    val GapM = 14.dp
    val GapL = 22.dp
    val GapXL = 32.dp

    /** Space reserved under scrolling content so the bottom bar never covers the last card. */
    val BottomInset = 108.dp
}
