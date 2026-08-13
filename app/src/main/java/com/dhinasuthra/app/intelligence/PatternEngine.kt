package com.dhinasuthra.app.intelligence

import com.dhinasuthra.app.core.TimeUtils
import com.dhinasuthra.app.core.model.DayType
import com.dhinasuthra.app.routine.RoutineStats
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Pattern formation (spec §16, §37, §38).
 *
 * A pattern is not an average. It is a median, a spread, a count, a lifecycle and
 * a date of last sighting — because "you usually eat at 13:24" is only useful
 * alongside "give or take 7 minutes, seen 27 times, most recently yesterday".
 */
object PatternEngine {

    const val MIN_OBSERVATIONS = 3
    const val STALE_AFTER_DAYS = 21
    const val ESTABLISHED_OBSERVATIONS = 8
    const val ESTABLISHED_MAX_IQR = 75
    const val UNSTABLE_DELTA_MIN = 45
    const val CHANGE_POINT_DELTA_MIN = 25

    /** One day's contribution to a pattern (PAT-11: at most one per activity per day). */
    data class Observation(
        val epochDay: Long,
        val activity: ActivityType,
        val dayType: DayType,
        val startMin: Int,
        val totalMin: Int
    )

    fun observations(days: List<DayReconstruction>): List<Observation> = days.flatMap { day ->
        val dayType = TimeUtils.dayType(day.epochDay)
        day.episodes
            .filter { it.activity != ActivityType.UNKNOWN }
            .groupBy { it.activity }
            .map { (activity, list) ->
                val first = list.minByOrNull { it.startMin }!!
                Observation(
                    epochDay = day.epochDay,
                    activity = activity,
                    dayType = dayType,
                    startMin = normalise(activity, first.startMin),
                    totalMin = list.sumOf { it.durationMin }
                )
            }
    }

    /** PAT-05 — activities that live near midnight are shifted onto a continuous axis. */
    fun normalise(activity: ActivityType, minute: Int): Int =
        if (activity == ActivityType.SLEEP && minute < 12 * 60) minute + 1440 else minute

    fun denormalise(minute: Int): Int = ((minute % 1440) + 1440) % 1440

    fun build(days: List<DayReconstruction>, today: Long, priors: List<ActivityBand> = emptyList()): PatternIndex {
        val bands = observations(days)
            .groupBy { it.activity to it.dayType }
            .mapNotNull { (key, obs) -> band(key.first, key.second, obs, today) }
            .toMutableList()

        // PAT-13 — user-stated times fill only the gaps observation has not reached.
        for (p in priors) {
            if (bands.none { it.activity == p.activity && it.dayType == p.dayType }) bands += p
        }
        return PatternIndex.of(bands)
    }

    fun band(
        activity: ActivityType,
        dayType: DayType,
        observations: List<Observation>,
        today: Long
    ): ActivityBand? {
        if (observations.size < MIN_OBSERVATIONS) return null   // PAT-01
        val starts = observations.map { it.startMin }
        val kept = withoutOutliers(starts)                      // PAT-12
        val q = RoutineStats.quantiles(kept)
        val durations = withoutOutliers(observations.map { it.totalMin })
        val lastSeen = observations.maxOf { it.epochDay }
        val confidence = RoutineStats.confidence(observations.size, q.iqr)

        val recent = observations.filter { it.epochDay > today - 7 }.map { it.startMin }
        val older = observations.filter { it.epochDay <= today - 7 }.map { it.startMin }

        return ActivityBand(
            activity = activity,
            dayType = dayType,
            typicalStartMin = q.median,
            p10 = q.p10, p25 = q.p25, p75 = q.p75, p90 = q.p90,
            typicalDurationMin = if (durations.size >= 3) RoutineStats.percentile(durations.sorted(), 50.0) else null,
            durationP25 = if (durations.size >= 3) RoutineStats.percentile(durations.sorted(), 25.0) else null,
            durationP75 = if (durations.size >= 3) RoutineStats.percentile(durations.sorted(), 75.0) else null,
            observations = observations.size,
            confidence = confidence,
            lastObservedDay = lastSeen,
            lifecycle = lifecycle(observations.size, q.iqr, lastSeen, today, recent, older)
        )
    }

    /** PAT-07 / PAT-08 / PAT-09 — where a pattern is in its life. */
    fun lifecycle(
        count: Int,
        iqr: Int,
        lastObservedDay: Long,
        today: Long,
        recentStarts: List<Int>,
        olderStarts: List<Int>
    ): PatternLifecycle {
        if (count < MIN_OBSERVATIONS) return PatternLifecycle.LEARNING
        if (today - lastObservedDay > STALE_AFTER_DAYS) return PatternLifecycle.STALE
        if (recentStarts.size >= 3 && olderStarts.size >= 3) {
            val delta = abs(median(recentStarts) - median(olderStarts))
            if (delta >= UNSTABLE_DELTA_MIN) return PatternLifecycle.UNSTABLE
        }
        if (count >= ESTABLISHED_OBSERVATIONS && iqr <= ESTABLISHED_MAX_IQR) return PatternLifecycle.ESTABLISHED
        return PatternLifecycle.EMERGING
    }

    /** CNS-01 / CNS-02 */
    fun consistency(band: ActivityBand): Int? = band.consistency

    data class ChangePoint(
        val activity: ActivityType,
        val dayType: DayType,
        val previousMedianMin: Int,
        val recentMedianMin: Int,
        val recentDays: Int
    ) {
        val deltaMin: Int get() = recentMedianMin - previousMedianMin
        val laterThanBefore: Boolean get() = deltaMin > 0
    }

    /**
     * PAT-10 / PAT-14 — compare the last 7 days with the 21 before them.
     * A sustained shift is a change of life, not a run of mistakes.
     */
    fun changePoint(
        days: List<DayReconstruction>,
        activity: ActivityType,
        dayType: DayType,
        today: Long
    ): ChangePoint? {
        val obs = observations(days).filter { it.activity == activity && it.dayType == dayType }
        val recent = obs.filter { it.epochDay > today - 7 }.map { it.startMin }
        val previous = obs.filter { it.epochDay in (today - 28)..(today - 7) }.map { it.startMin }
        if (recent.size < 3 || previous.size < 4) return null
        val rMed = median(recent)
        val pMed = median(previous)
        if (abs(rMed - pMed) < CHANGE_POINT_DELTA_MIN) return null
        return ChangePoint(activity, dayType, pMed, rMed, recent.size)
    }

    /** PAT-12 — Tukey fences: excluded from typical values, still present in the data. */
    fun withoutOutliers(values: List<Int>): List<Int> {
        if (values.size < 5) return values
        val sorted = values.sorted()
        val q1 = RoutineStats.percentile(sorted, 25.0)
        val q3 = RoutineStats.percentile(sorted, 75.0)
        val iqr = q3 - q1
        if (iqr == 0) return values
        val lo = q1 - (1.5 * iqr).roundToInt()
        val hi = q3 + (1.5 * iqr).roundToInt()
        val kept = values.filter { it in lo..hi }
        return if (kept.size >= MIN_OBSERVATIONS) kept else values
    }

    fun median(values: List<Int>): Int {
        if (values.isEmpty()) return 0
        return RoutineStats.percentile(values.sorted(), 50.0)
    }

    fun standardDeviation(values: List<Int>): Float {
        if (values.size < 2) return 0f
        val mean = values.average()
        val variance = values.sumOf { (it - mean) * (it - mean) } / (values.size - 1)
        return sqrt(variance).toFloat()
    }
}
