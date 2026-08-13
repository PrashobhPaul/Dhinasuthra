package com.dhinasuthra.app.intelligence

import com.dhinasuthra.app.core.TimeUtils
import com.dhinasuthra.app.core.model.DayType
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Rhythm, deviation and narration (spec §2, §20, §24, §51).
 *
 * This is the layer that talks. It is allowed to sound human; it is not allowed
 * to be vague. Every sentence it produces carries the measurement behind it and
 * the rules that let it be said at all — and if it has nothing worth saying, it
 * says nothing (NAR-01).
 */

// ---------------------------------------------------------------------------
// Rhythm score (CNS-03)
// ---------------------------------------------------------------------------

object RhythmScorer {

    data class Component(
        val activity: ActivityType,
        val expectedMin: Int,
        val actualMin: Int?,
        val score: Float,
        val weight: Float,
        val note: String
    )

    data class RhythmScore(
        val value: Int,
        val components: List<Component>,
        val ruleIds: List<String>,
        val explanation: String
    ) {
        val band: String
            get() = when {
                value >= 85 -> "Closely following your rhythm"
                value >= 70 -> "Broadly on your usual shape"
                value >= 50 -> "Drifting from your usual"
                else -> "A different kind of day"
            }
    }

    /** ANM-06 — a day that is mostly unreconstructed is not scored at all. */
    const val MIN_COVERAGE = 0.5f

    fun score(day: DayReconstruction, patterns: PatternIndex, nowMin: Int): RhythmScore? {
        if (day.coverageFraction < MIN_COVERAGE) return null
        val dayType = TimeUtils.dayType(day.epochDay)
        val components = mutableListOf<Component>()

        for (band in patterns.forDayType(dayType)) {
            if (band.isPrior || band.observations < 4) continue
            if (band.lifecycle == PatternLifecycle.LEARNING || band.lifecycle == PatternLifecycle.STALE) continue
            val actual = day.firstStartOf(band.activity)?.let { PatternEngine.normalise(band.activity, it) }
            when {
                actual != null -> components += Component(
                    activity = band.activity,
                    expectedMin = band.typicalStartMin,
                    actualMin = actual,
                    score = matchScore(actual, band),
                    weight = band.confidence,
                    note = "usually ${TimeUtils.formatMinuteOfDay(PatternEngine.denormalise(band.typicalStartMin))}, today ${TimeUtils.formatMinuteOfDay(PatternEngine.denormalise(actual))}"
                )
                // RTN-06 — the future is not a miss.
                nowMin > band.p90 + 30 -> components += Component(
                    activity = band.activity,
                    expectedMin = band.typicalStartMin,
                    actualMin = null,
                    score = 0f,
                    weight = band.confidence,
                    note = "expected around ${TimeUtils.formatMinuteOfDay(PatternEngine.denormalise(band.typicalStartMin))}, not seen"
                )
                else -> Unit
            }
        }

        val weight = components.sumOf { it.weight.toDouble() }.toFloat()
        if (weight <= 0f || components.isEmpty()) return null
        val value = (components.sumOf { (it.score * it.weight).toDouble() } / weight * 100).roundToInt().coerceIn(0, 100)
        return RhythmScore(
            value = value,
            components = components.sortedBy { it.expectedMin },
            ruleIds = listOf(ConsistencyRules.RHYTHM_SCORE.id, RoutineRules.UNPASSED_NOT_SCORED.id),
            explanation = "Weighted across ${components.size} learned pattern${if (components.size == 1) "" else "s"}, " +
                "each weighted by how confident that pattern is. Windows that haven't come round yet are not counted."
        )
    }

    /** RTN-08 — inside your usual quartiles is a full match; early costs less than late. */
    fun matchScore(actual: Int, band: ActivityBand): Float {
        if (actual in band.p25..band.p75) return 1f
        val spread = ((band.p90 - band.p10) + 45).coerceAtLeast(45)
        return if (actual < band.p25) {
            (1f - (band.p25 - actual) * 0.6f / spread).coerceIn(0f, 1f)
        } else {
            (1f - (actual - band.p75).toFloat() / spread).coerceIn(0f, 1f)
        }
    }
}

// ---------------------------------------------------------------------------
// Deviations (spec §24)
// ---------------------------------------------------------------------------

object AnomalyEngine {

    enum class Kind { STARTED_LATER, STARTED_EARLIER, LASTED_LONGER, LASTED_SHORTER, DID_NOT_HAPPEN }

    data class Deviation(
        val activity: ActivityType,
        val kind: Kind,
        val deltaMin: Int,
        val expectedMin: Int,
        val actualMin: Int?,
        val band: ActivityBand,
        val significance: Float,
        val ruleIds: List<String>,
        val evidence: List<String>
    )

    /** NAR-08 — under this, a difference is true but not worth a sentence. */
    const val NOTICEABLE_START_MIN = 15
    const val NOTICEABLE_DURATION_MIN = 30

    fun evaluate(day: DayReconstruction, patterns: PatternIndex, nowMin: Int): List<Deviation> {
        if (day.coverageFraction < RhythmScorer.MIN_COVERAGE) return emptyList()   // ANM-06
        val dayType = TimeUtils.dayType(day.epochDay)
        val out = mutableListOf<Deviation>()

        for (band in patterns.forDayType(dayType)) {
            if (band.observations < 5) continue          // NAR-09
            if (band.lifecycle == PatternLifecycle.STALE || band.lifecycle == PatternLifecycle.LEARNING) continue

            val actualStart = day.firstStartOf(band.activity)?.let { PatternEngine.normalise(band.activity, it) }
            if (actualStart == null) {
                if (nowMin > band.p90 + 45) {
                    out += Deviation(
                        activity = band.activity,
                        kind = Kind.DID_NOT_HAPPEN,
                        deltaMin = 0,
                        expectedMin = band.typicalStartMin,
                        actualMin = null,
                        band = band,
                        significance = band.confidence * 0.6f,
                        ruleIds = listOf(AnomalyRules.YOUR_OWN_BASELINE.id, AnomalyRules.MISSING_DATA_IS_NOT_ANOMALY.id),
                        evidence = listOf(
                            "${AnomalyRules.YOUR_OWN_BASELINE.id} · expected around ${TimeUtils.formatMinuteOfDay(PatternEngine.denormalise(band.typicalStartMin))} from ${band.observations} past days",
                            "${AnomalyRules.MISSING_DATA_IS_NOT_ANOMALY.id} · ${(day.coverageFraction * 100).roundToInt()}% of the day was reconstructed, so this is a genuine absence rather than a blind spot"
                        )
                    )
                }
                continue
            }

            val delta = actualStart - band.typicalStartMin
            if (abs(delta) >= NOTICEABLE_START_MIN && StatisticsEngine.isBeyondUsual(actualStart, band, tolerance = 5)) {
                out += Deviation(
                    activity = band.activity,
                    kind = if (delta > 0) Kind.STARTED_LATER else Kind.STARTED_EARLIER,
                    deltaMin = abs(delta),
                    expectedMin = band.typicalStartMin,
                    actualMin = actualStart,
                    band = band,
                    significance = significance(abs(delta), band),
                    ruleIds = listOf(AnomalyRules.YOUR_OWN_BASELINE.id, AnomalyRules.BEYOND_YOUR_SPREAD.id),
                    evidence = listOf(
                        "${AnomalyRules.BEYOND_YOUR_SPREAD.id} · ${TimeUtils.formatMinuteOfDay(PatternEngine.denormalise(actualStart))} is outside your usual ${TimeUtils.formatMinuteOfDay(PatternEngine.denormalise(band.p10))}–${TimeUtils.formatMinuteOfDay(PatternEngine.denormalise(band.p90))} window",
                        "${AnomalyRules.YOUR_OWN_BASELINE.id} · compared with your own median across ${band.observations} ${dayType.name.lowercase()} observations"
                    )
                )
            }

            val typicalDuration = band.typicalDurationMin
            val actualDuration = day.totalOf(band.activity)
            if (typicalDuration != null && actualDuration > 0) {
                val dd = actualDuration - typicalDuration
                if (abs(dd) >= NOTICEABLE_DURATION_MIN) {
                    out += Deviation(
                        activity = band.activity,
                        kind = if (dd > 0) Kind.LASTED_LONGER else Kind.LASTED_SHORTER,
                        deltaMin = abs(dd),
                        expectedMin = typicalDuration,
                        actualMin = actualDuration,
                        band = band,
                        significance = (abs(dd).toFloat() / (typicalDuration + 60)).coerceIn(0f, 1f) * band.confidence,
                        ruleIds = listOf(AnomalyRules.YOUR_OWN_BASELINE.id, AnomalyRules.ONE_DAY_IS_NOT_A_TREND.id),
                        evidence = listOf(
                            "${AnomalyRules.YOUR_OWN_BASELINE.id} · ${TimeUtils.formatDurationMin(actualDuration)} today against a usual ${TimeUtils.formatDurationMin(typicalDuration)}",
                            "${AnomalyRules.ONE_DAY_IS_NOT_A_TREND.id} · stated as one day, not as a trend"
                        )
                    )
                }
            }
        }
        return out.sortedByDescending { it.significance }
    }

    private fun significance(deltaMin: Int, band: ActivityBand): Float {
        val spread = (band.spread + 30).coerceAtLeast(30)
        return ((deltaMin.toFloat() / spread).coerceIn(0f, 1.5f) / 1.5f) * band.confidence
    }
}

// ---------------------------------------------------------------------------
// Narration (spec §2, §51)
// ---------------------------------------------------------------------------

enum class InsightKind(val label: String) {
    RHYTHM("Your rhythm"),
    DEVIATION("What changed today"),
    STRENGTH("Your strongest pattern"),
    CHANGE("A shift over time"),
    DISCOVERY("New pattern"),
    COVERAGE("Honest gaps"),
    ROUTINE("Routine opportunity")
}

data class Insight(
    val id: String,
    val kind: InsightKind,
    val headline: String,
    val detail: String,
    val significance: Float,
    val ruleIds: List<String>,
    val evidence: List<String>
)

object NarrativeEngine {

    const val MAX_INSIGHTS = 6

    /** NAR-03 — the confidence decides the verb. */
    fun hedge(confidence: Float, statement: String): String = when {
        confidence >= ConfidenceBand.CONFIDENT.floor -> statement
        confidence >= ConfidenceBand.LIKELY.floor -> "It looks like $statement"
        else -> "It appears $statement"
    }

    fun compose(
        today: DayReconstruction,
        history: List<DayReconstruction>,
        patterns: PatternIndex,
        deviations: List<AnomalyEngine.Deviation>,
        rhythm: RhythmScorer.RhythmScore?,
        nowMin: Int
    ): List<Insight> {
        val out = mutableListOf<Insight>()
        val dayName = TimeUtils.localDate(today.epochDay).dayOfWeek
            .getDisplayName(TextStyle.FULL, Locale.getDefault())
        val dayType = TimeUtils.dayType(today.epochDay)

        // 1 — today's rhythm.
        if (rhythm != null) {
            out += Insight(
                id = "rhythm",
                kind = InsightKind.RHYTHM,
                headline = rhythm.band,
                detail = "Your $dayName is scoring ${rhythm.value} out of 100 against your own usual shape, " +
                    "across ${rhythm.components.size} learned pattern${if (rhythm.components.size == 1) "" else "s"}.",
                significance = 0.9f,
                ruleIds = rhythm.ruleIds,
                evidence = rhythm.components.map { "${it.activity.label}: ${it.note}" }
            )
        }

        // 2 — today's deviations (NAR-05: ranked, NAR-08: only if worth saying).
        for (d in deviations.take(3)) {
            val text = when (d.kind) {
                AnomalyEngine.Kind.STARTED_LATER ->
                    "${d.activity.label} started ${TimeUtils.formatDurationMin(d.deltaMin)} later than your usual ${TimeUtils.formatMinuteOfDay(PatternEngine.denormalise(d.expectedMin))}."
                AnomalyEngine.Kind.STARTED_EARLIER ->
                    "${d.activity.label} started ${TimeUtils.formatDurationMin(d.deltaMin)} earlier than your usual ${TimeUtils.formatMinuteOfDay(PatternEngine.denormalise(d.expectedMin))}."
                AnomalyEngine.Kind.LASTED_LONGER ->
                    "${d.activity.label} ran ${TimeUtils.formatDurationMin(d.deltaMin)} longer than your normal $dayName."
                AnomalyEngine.Kind.LASTED_SHORTER ->
                    "${d.activity.label} was ${TimeUtils.formatDurationMin(d.deltaMin)} shorter than your normal $dayName."
                AnomalyEngine.Kind.DID_NOT_HAPPEN ->
                    "${d.activity.label} hasn't appeared today, though it usually has by now."
            }
            out += Insight(
                id = "dev-${d.activity.name}-${d.kind.name}",
                kind = InsightKind.DEVIATION,
                headline = text,
                detail = "Measured against ${d.band.observations} of your own ${dayType.name.lowercase()} observations.",
                significance = 0.5f + d.significance * 0.4f,
                ruleIds = d.ruleIds,
                evidence = d.evidence
            )
        }

        // 3 — the strongest pattern.
        patterns.forDayType(dayType)
            .filter { !it.isPrior && it.observations >= 5 && it.consistency != null }
            .maxByOrNull { it.consistency ?: 0 }
            ?.let { best ->
                out += Insight(
                    id = "strength-${best.activity.name}",
                    kind = InsightKind.STRENGTH,
                    headline = "${best.activity.label} is your most consistent ${dayType.name.lowercase()} activity.",
                    detail = "${best.consistency}% consistent — usually ${TimeUtils.formatMinuteOfDay(PatternEngine.denormalise(best.typicalStartMin))}, " +
                        "within ±${(best.iqr / 2)} minutes, across ${best.observations} days.",
                    significance = 0.6f,
                    ruleIds = listOf(ConsistencyRules.DEFINITION.id, ConsistencyRules.MIN_OBSERVATIONS.id),
                    evidence = listOf(
                        "${ConsistencyRules.DEFINITION.id} · consistency = 1 − (interquartile range ${best.iqr}m ÷ 180m)",
                        "${PatternRules.SPREAD_IS_REPORTED.id} · range ${TimeUtils.formatMinuteOfDay(PatternEngine.denormalise(best.p10))}–${TimeUtils.formatMinuteOfDay(PatternEngine.denormalise(best.p90))}"
                    )
                )
            }

        // 4 — sustained change (PAT-14).
        for (activity in listOf(ActivityType.SLEEP, ActivityType.WORK, ActivityType.LUNCH, ActivityType.COMMUTE)) {
            val change = PatternEngine.changePoint(history, activity, dayType, today.epochDay) ?: continue
            out += Insight(
                id = "change-${activity.name}",
                kind = InsightKind.CHANGE,
                headline = "Your ${activity.label.lowercase()} time has moved ${TimeUtils.formatDurationMin(abs(change.deltaMin))} " +
                    if (change.laterThanBefore) "later." else "earlier.",
                detail = "Over the last week it has settled around ${TimeUtils.formatMinuteOfDay(PatternEngine.denormalise(change.recentMedianMin))}, " +
                    "against ${TimeUtils.formatMinuteOfDay(PatternEngine.denormalise(change.previousMedianMin))} in the three weeks before.",
                significance = 0.7f,
                ruleIds = listOf(PatternRules.CHANGE_POINT.id, PatternRules.RECENCY_WEIGHT.id),
                evidence = listOf(
                    "${PatternRules.RECENCY_WEIGHT.id} · last 7 days compared with the preceding 21",
                    "${PatternRules.CHANGE_POINT.id} · median moved ${abs(change.deltaMin)} minutes across ${change.recentDays} recent days"
                )
            )
            break
        }

        // 5 — a pattern that has just become established.
        patterns.forDayType(dayType)
            .filter { !it.isPrior && it.lifecycle == PatternLifecycle.ESTABLISHED && it.observations in 8..14 }
            .minByOrNull { it.typicalStartMin }
            ?.let { fresh ->
                out += Insight(
                    id = "discovery-${fresh.activity.name}",
                    kind = InsightKind.DISCOVERY,
                    headline = "You've repeated your ${fresh.activity.label.lowercase()} timing for ${fresh.observations} days.",
                    detail = "That is enough for DhinaSuthra to treat it as established — you can save it as part of a routine.",
                    significance = 0.65f,
                    ruleIds = listOf(PatternRules.LIFECYCLE.id, RoutineRules.NEEDS_ESTABLISHED_PATTERN.id),
                    evidence = listOf(
                        "${PatternRules.LIFECYCLE.id} · ${fresh.observations} observations with an interquartile range of ${fresh.iqr} minutes",
                        "${RoutineRules.SUGGEST_NEVER_IMPOSE.id} · offered as a suggestion; nothing is activated for you"
                    )
                )
            }

        // 6 — honesty about what could not be reconstructed (§44).
        val unknown = today.unknownMin
        if (unknown >= 45) {
            out += Insight(
                id = "coverage",
                kind = InsightKind.COVERAGE,
                headline = "There is ${TimeUtils.formatDurationMin(unknown)} of today DhinaSuthra couldn't classify.",
                detail = "You can fill it in from the timeline, or leave it — unknown is a valid answer.",
                significance = 0.45f + (unknown / 1440f),
                ruleIds = listOf(TimelineRules.HONEST_UNKNOWN.id, TimelineRules.GAP_NEEDS_CONFIDENCE.id),
                evidence = listOf(
                    "${TimelineRules.GAP_NEEDS_CONFIDENCE.id} · no explanation reached the confidence bar for these periods",
                    "${TimelineRules.HONEST_UNKNOWN.id} · ${(today.coverageFraction * 100).roundToInt()}% of the lived day is accounted for"
                )
            )
        }

        // NAR-05 — rank and cut. NAR-01 — an empty list is a legitimate outcome.
        return out.sortedByDescending { it.significance }.take(MAX_INSIGHTS)
    }

    /** The one-line summary above Today, composed from the same evidence. */
    fun todayLine(rhythm: RhythmScorer.RhythmScore?, day: DayReconstruction, dayName: String): String = when {
        rhythm == null && day.coveredMin < 60 -> "Your $dayName is just beginning."
        rhythm == null -> "DhinaSuthra is still learning the shape of your $dayName."
        rhythm.value >= 85 -> "Your $dayName is following your usual rhythm closely."
        rhythm.value >= 70 -> "Your $dayName is broadly on its usual shape."
        rhythm.value >= 50 -> "Your $dayName is drifting from your usual pattern."
        else -> "Your $dayName looks quite unlike your usual."
    }
}
