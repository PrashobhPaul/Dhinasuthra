package com.dhinasuthra.app.activity

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Confirmation-driven learning (plan §13, §30 Phase 7).
 *
 * Plan §13's point is that confirmation "is not just UI. It is a personalization
 * signal." So every time the user says *yes, that was lunch* — or corrects the
 * time — the answer is folded back into a [LearnedProfile] that the detectors
 * consult on the next run. The app's guesses get closer to this particular
 * person's life, which is the only benchmark that matters.
 *
 * The statistics here are deliberately robust rather than clever: medians and
 * the interquartile range, not means and standard deviations, because one
 * Saturday where lunch happened at four o'clock should not drag the weekday
 * estimate with it.
 */

/** What has been learned about one kind of activity, from confirmed events only. */
data class LearnedProfile(
    val activityCode: String,
    /** Minute-of-day the activity usually starts. */
    val typicalStartMin: Int,
    /** Half-width of the usual window, from the observed spread. */
    val startSpreadMin: Int,
    val typicalDurationMin: Int,
    val durationSpreadMin: Int,
    /** The place it usually happens, when there is a clear favourite. */
    val typicalPlaceId: Long?,
    /** How many confirmations this rests on. Below [MIN_OBSERVATIONS] it is a hint, not a rule. */
    val observations: Int
) {
    val isEstablished: Boolean get() = observations >= MIN_OBSERVATIONS

    /** The window this activity usually falls in, as minutes of the day. */
    val window: IntRange
        get() = (typicalStartMin - startSpreadMin)..(typicalStartMin + startSpreadMin)

    fun matchesStart(minuteOfDay: Int): Boolean = minuteOfDay in window

    fun matchesDuration(durationMin: Int): Boolean =
        abs(durationMin - typicalDurationMin) <= durationSpreadMin.coerceAtLeast(5)

    /** 0..1 — how well a candidate fits what this person actually does. */
    fun affinity(minuteOfDay: Int, durationMin: Int, placeId: Long?): Float {
        if (!isEstablished) return 0f
        var score = 0f
        if (matchesStart(minuteOfDay)) score += 0.5f
        if (matchesDuration(durationMin)) score += 0.3f
        if (typicalPlaceId != null && typicalPlaceId == placeId) score += 0.2f
        return score
    }

    /** "Typical time 10:42 · about 12 min" — the sentence shown when we suggest it. */
    fun describe(): String {
        val h = typicalStartMin / 60
        val m = typicalStartMin % 60
        return "usually around %02d:%02d, about %d min".format(h, m, typicalDurationMin)
    }

    companion object {
        const val MIN_OBSERVATIONS = 4
    }
}

/** One confirmed occurrence, reduced to the four things worth learning from. */
data class ConfirmedOccurrence(
    val activityCode: String,
    val startMinuteOfDay: Int,
    val durationMin: Int,
    val placeId: Long?,
    val epochDay: Long
)

object LearningEngine {

    /**
     * Build profiles from the user's confirmed and corrected history.
     *
     * Only user truth goes in. Feeding the engine's own guesses back to itself
     * would let one bad inference harden into a "pattern" and then justify more
     * of the same — the app would get confidently wronger over time.
     */
    fun learn(occurrences: List<ConfirmedOccurrence>): Map<String, LearnedProfile> =
        occurrences.groupBy { it.activityCode }
            .mapValues { (code, rows) -> profileOf(code, rows) }

    private fun profileOf(code: String, rows: List<ConfirmedOccurrence>): LearnedProfile {
        val starts = rows.map { it.startMinuteOfDay }.sorted()
        val durations = rows.map { it.durationMin }.filter { it > 0 }.sorted()
        val place = rows.mapNotNull { it.placeId }
            .groupingBy { it }.eachCount()
            .maxByOrNull { it.value }
            ?.takeIf { it.value * 2 >= rows.size }   // a clear favourite, not a coincidence
            ?.key

        return LearnedProfile(
            activityCode = code,
            typicalStartMin = median(starts),
            startSpreadMin = spread(starts, floor = 15, ceiling = 75),
            typicalDurationMin = if (durations.isEmpty()) 0 else median(durations),
            durationSpreadMin = spread(durations, floor = 8, ceiling = 45),
            typicalPlaceId = place,
            observations = rows.size
        )
    }

    private fun median(sorted: List<Int>): Int = when {
        sorted.isEmpty() -> 0
        sorted.size % 2 == 1 -> sorted[sorted.size / 2]
        else -> ((sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0).roundToInt()
    }

    /**
     * Half the interquartile range, clamped. The clamp matters: with three
     * observations the IQR can be zero, and a window of zero width would reject
     * every future occurrence including the correct one.
     */
    private fun spread(sorted: List<Int>, floor: Int, ceiling: Int): Int {
        if (sorted.size < 3) return floor
        val q1 = sorted[sorted.size / 4]
        val q3 = sorted[(sorted.size * 3) / 4]
        return ((q3 - q1) / 2).coerceIn(floor, ceiling)
    }
}
