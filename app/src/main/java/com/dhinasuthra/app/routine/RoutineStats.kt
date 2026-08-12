package com.dhinasuthra.app.routine

import com.dhinasuthra.app.core.model.Quantiles
import com.dhinasuthra.app.core.model.RoutineEventType
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pure statistics for routine learning (product.md §11.2: no simple averages —
 * median + percentile distributions + observation count + confidence).
 *
 * Time values are minutes-of-day. Events that straddle midnight (SLEEP, WAKE for
 * night-shift users) are normalized onto a continuous axis before aggregation so
 * that 23:50 and 00:10 average to midnight rather than midday.
 */
object RoutineStats {

    /** Events whose natural anchor can sit near midnight. */
    private val CROSS_MIDNIGHT = setOf(RoutineEventType.SLEEP)

    /**
     * Normalize a minute-of-day for a given event type onto a continuous axis.
     * For SLEEP we work in the 12:00 → 36:00 window: minutes before noon are
     * shifted by +1440 so 00:20 becomes 1460 and sorts after 23:50 (1430).
     */
    fun normalize(eventType: RoutineEventType, minuteOfDay: Int): Int =
        if (eventType in CROSS_MIDNIGHT && minuteOfDay < 12 * 60) minuteOfDay + 1440
        else minuteOfDay

    /** Inverse of [normalize] for display. */
    fun denormalize(minute: Int): Int = ((minute % 1440) + 1440) % 1440

    /** Linear-interpolated percentile of a sorted list. */
    fun percentile(sorted: List<Int>, p: Double): Int {
        require(sorted.isNotEmpty())
        if (sorted.size == 1) return sorted[0]
        val rank = p / 100.0 * (sorted.size - 1)
        val lo = rank.toInt()
        val hi = min(lo + 1, sorted.size - 1)
        val frac = rank - lo
        return (sorted[lo] * (1 - frac) + sorted[hi] * frac).roundToInt()
    }

    fun quantiles(values: List<Int>): Quantiles {
        val s = values.sorted()
        return Quantiles(
            median = percentile(s, 50.0),
            p10 = percentile(s, 10.0),
            p25 = percentile(s, 25.0),
            p75 = percentile(s, 75.0),
            p90 = percentile(s, 90.0)
        )
    }

    /**
     * Confidence in a learned pattern (product.md §35): grows with observation
     * count, shrinks with spread. count=5 narrow ⇒ ~0.5; count≥20 narrow ⇒ ~0.95.
     */
    fun confidence(observationCount: Int, iqrMinutes: Int): Float {
        if (observationCount < 3) return 0f
        val countFactor = min(1.0, observationCount / 20.0)
        val spreadFactor = exp(-max(0, iqrMinutes) / 180.0)   // IQR 0 ⇒ 1.0, 90m ⇒ 0.61, 180m ⇒ 0.37
        return (0.35 + 0.65 * countFactor * spreadFactor).toFloat().coerceIn(0f, 1f)
            .let { if (observationCount < 5) it * 0.7f else it }
    }

    /**
     * Tolerance beyond p90 before a gentle reminder fires (product.md §15):
     * derived from the user's own historical variation, floored at 15 minutes.
     */
    fun gentleToleranceMin(q: Quantiles): Int = max(15, q.iqr / 2)

    /** Additional lag before a significant-deviation reminder. */
    fun significantLagMin(): Int = 45
}
