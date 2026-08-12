package com.dhinasuthra.app.intelligence

import com.dhinasuthra.app.core.TimeUtils
import com.dhinasuthra.app.core.model.DayType
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Routine as an object you can hold (spec §5, §18, §20).
 *
 * A routine is the point where DhinaSuthra stops observing and starts being
 * useful: an observed pattern, promoted by you into a timetable of targets, and
 * then measured against what actually happens.
 */

data class TimetableEntry(
    val activity: ActivityType,
    val targetStartMin: Int,
    val targetEndMin: Int? = null,
    val expectedLocation: LocationType = LocationType.UNKNOWN,
    /** RTN-04 — defaults to half your own observed spread, floored at 15 minutes. */
    val toleranceMin: Int = 20,
    val reminderEnabled: Boolean = true
) {
    val plannedDurationMin: Int? get() = targetEndMin?.let { (it - targetStartMin).coerceAtLeast(0) }
    fun startLabel(): String = TimeUtils.formatMinuteOfDay(targetStartMin)
}

data class Timetable(
    val id: String,
    val name: String,
    /** ISO day numbers: 1 = Monday … 7 = Sunday. */
    val days: Set<Int>,
    val active: Boolean,
    val entries: List<TimetableEntry>,
    val createdAt: Long
) {
    fun appliesTo(epochDay: Long): Boolean =
        active && TimeUtils.localDate(epochDay).dayOfWeek.value in days

    val sortedEntries: List<TimetableEntry> get() = entries.sortedBy { it.targetStartMin }

    val dayLabel: String
        get() = when {
            days.size == 7 -> "Every day"
            days == setOf(1, 2, 3, 4, 5) -> "Weekdays"
            days == setOf(6, 7) -> "Weekends"
            else -> days.sorted().joinToString(" ") { DAY_NAMES[it - 1] }
        }

    companion object {
        val DAY_NAMES = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
        val WEEKDAYS = setOf(1, 2, 3, 4, 5)
        val WEEKENDS = setOf(6, 7)
    }
}

/**
 * A tiny line-based codec so routines can live in the existing key/value store
 * with no schema migration and no JSON dependency — and, more usefully, so the
 * whole format is unit-testable in plain Kotlin.
 */
object TimetableCodec {

    private const val HEADER = "DSTT1"
    private const val SEP = "|"

    fun encode(timetables: List<Timetable>): String = buildString {
        appendLine(HEADER)
        for (t in timetables) {
            appendLine(
                listOf(
                    "T", t.id, escape(t.name), t.days.sorted().joinToString(","),
                    t.active.toString(), t.createdAt.toString()
                ).joinToString(SEP)
            )
            for (e in t.entries) {
                appendLine(
                    listOf(
                        "E", e.activity.name, e.targetStartMin.toString(),
                        (e.targetEndMin ?: -1).toString(), e.expectedLocation.name,
                        e.toleranceMin.toString(), e.reminderEnabled.toString()
                    ).joinToString(SEP)
                )
            }
        }
    }

    fun decode(raw: String?): List<Timetable> {
        if (raw.isNullOrBlank()) return emptyList()
        val lines = raw.lines().filter { it.isNotBlank() }
        if (lines.firstOrNull() != HEADER) return emptyList()
        val out = mutableListOf<Timetable>()
        var current: Timetable? = null
        val entries = mutableListOf<TimetableEntry>()

        fun flush() {
            current?.let { out += it.copy(entries = entries.toList()) }
            entries.clear()
        }

        for (line in lines.drop(1)) {
            val parts = line.split(SEP)
            when (parts.firstOrNull()) {
                "T" -> {
                    if (parts.size < 6) continue
                    flush()
                    current = Timetable(
                        id = parts[1],
                        name = unescape(parts[2]),
                        days = parts[3].split(",").mapNotNull { it.toIntOrNull() }.toSet(),
                        active = parts[4].toBooleanStrictOrNull() ?: true,
                        entries = emptyList(),
                        createdAt = parts[5].toLongOrNull() ?: 0L
                    )
                }
                "E" -> {
                    if (parts.size < 7 || current == null) continue
                    val activity = runCatching { ActivityType.valueOf(parts[1]) }.getOrNull() ?: continue
                    val location = runCatching { LocationType.valueOf(parts[4]) }.getOrNull() ?: LocationType.UNKNOWN
                    entries += TimetableEntry(
                        activity = activity,
                        targetStartMin = parts[2].toIntOrNull() ?: continue,
                        targetEndMin = parts[3].toIntOrNull()?.takeIf { it >= 0 },
                        expectedLocation = location,
                        toleranceMin = parts[5].toIntOrNull() ?: 20,
                        reminderEnabled = parts[6].toBooleanStrictOrNull() ?: true
                    )
                }
            }
        }
        flush()
        return out
    }

    private fun escape(s: String) = s.replace(SEP, "¦").replace("\n", " ")
    private fun unescape(s: String) = s.replace("¦", SEP)
}

/**
 * Planned vs observed (spec §20). Every number here is reproducible by hand:
 * the report carries the arithmetic, not just the verdict.
 */
object TimetableAdherence {

    data class EntryResult(
        val entry: TimetableEntry,
        val actualStartMin: Int?,
        val deviationMin: Int?,
        val score: Float?,
        val scored: Boolean,
        val note: String
    ) {
        val late: Boolean get() = (deviationMin ?: 0) > 0
    }

    data class Report(
        val timetable: Timetable,
        val epochDay: Long,
        val results: List<EntryResult>,
        val adherence: Int?,
        val ruleIds: List<String>,
        val explanation: String
    ) {
        val scoredCount: Int get() = results.count { it.scored }
        val onTimeCount: Int get() = results.count { (it.score ?: 0f) >= 0.9f }
    }

    fun evaluate(
        timetable: Timetable,
        day: DayReconstruction,
        nowMin: Int,
        patterns: PatternIndex,
        intentionalActivities: Set<ActivityType> = emptySet()
    ): Report {
        val dayType = TimeUtils.dayType(day.epochDay)
        val results = timetable.sortedEntries.map { entry ->
            val actual = day.firstStartOf(entry.activity)
            val confidence = patterns.band(entry.activity, dayType)?.confidence ?: 0.6f
            when {
                // RTN-11 — you said it was deliberate; it is not scored.
                entry.activity in intentionalActivities -> EntryResult(
                    entry, actual, actual?.let { it - entry.targetStartMin }, null, false,
                    "marked intentional — excluded from the score"
                )
                actual != null -> {
                    val deviation = actual - entry.targetStartMin
                    EntryResult(
                        entry, actual, deviation, entryScore(deviation, entry.toleranceMin), true,
                        if (abs(deviation) <= entry.toleranceMin) "within your ${entry.toleranceMin}m tolerance"
                        else if (deviation > 0) "${TimeUtils.formatDurationMin(deviation)} later than planned"
                        else "${TimeUtils.formatDurationMin(-deviation)} earlier than planned"
                    )
                }
                // RTN-06 — the window hasn't come round yet.
                nowMin <= entry.targetStartMin + entry.toleranceMin -> EntryResult(
                    entry, null, null, null, false, "still ahead of you today"
                )
                else -> EntryResult(
                    entry, null, null, 0f, true, "the window passed without this appearing"
                )
            }
        }

        val scored = results.filter { it.scored }
        val adherence = if (scored.isEmpty()) null
        else (scored.sumOf { (it.score ?: 0f).toDouble() } / scored.size * 100).roundToInt().coerceIn(0, 100)

        return Report(
            timetable = timetable,
            epochDay = day.epochDay,
            results = results,
            adherence = if (timetable.active) adherence else null,
            ruleIds = listOf(
                RoutineRules.ADHERENCE_PER_ENTRY.id,
                RoutineRules.UNPASSED_NOT_SCORED.id,
                RoutineRules.WEIGHTED_MEAN.id,
                RoutineRules.EARLY_IS_NOT_LATE.id
            ),
            explanation = "Mean of ${scored.size} scored entr${if (scored.size == 1) "y" else "ies"}. " +
                "Inside tolerance scores 1.0; outside it falls off linearly over the next two hours, and being early costs 40% less than being late."
        )
    }

    /** RTN-08 — inside tolerance is perfect, and early is treated more kindly than late. */
    fun entryScore(deviationMin: Int, toleranceMin: Int): Float {
        val over = abs(deviationMin) - toleranceMin
        if (over <= 0) return 1f
        val penaltyWindow = 120f
        val weighted = if (deviationMin < 0) over * 0.6f else over.toFloat()
        return (1f - weighted / penaltyWindow).coerceIn(0f, 1f)
    }
}

/** Turning observation into an offer (spec §17). */
object RoutineComposer {

    /** RTN-02 — only established patterns are offered. */
    fun suggest(patterns: PatternIndex, dayType: DayType, name: String): Timetable? {
        val bands = patterns.forDayType(dayType)
            .filter { !it.isPrior && it.lifecycle == PatternLifecycle.ESTABLISHED }
            .filter { it.activity != ActivityType.UNKNOWN && it.activity != ActivityType.PERSONAL }
        if (bands.size < 3) return null
        return Timetable(
            id = "auto-${dayType.name.lowercase()}",
            name = name,
            days = if (dayType == DayType.WEEKDAY) Timetable.WEEKDAYS else Timetable.WEEKENDS,
            active = false,          // RTN-01: nothing is switched on for you
            entries = bands.map { entryFor(it) },
            createdAt = System.currentTimeMillis()
        )
    }

    fun entryFor(band: ActivityBand): TimetableEntry = TimetableEntry(
        activity = band.activity,
        targetStartMin = PatternEngine.denormalise(band.typicalStartMin),
        targetEndMin = band.typicalDurationMin?.let { PatternEngine.denormalise(band.typicalStartMin) + it },
        expectedLocation = LocationType.UNKNOWN,
        toleranceMin = maxOf(15, band.iqr / 2),      // RTN-04
        reminderEnabled = false
    )

    data class DriftProposal(
        val entry: TimetableEntry,
        val observedStartMin: Int,
        val deltaMin: Int,
        val observations: Int
    )

    /** RTN-09 — when life moves, offer to move the plan instead of repeating "you're late". */
    fun driftProposals(timetable: Timetable, patterns: PatternIndex, dayType: DayType): List<DriftProposal> =
        timetable.entries.mapNotNull { entry ->
            val band = patterns.band(entry.activity, dayType) ?: return@mapNotNull null
            if (band.isPrior || band.observations < 6) return@mapNotNull null
            if (band.lifecycle == PatternLifecycle.STALE) return@mapNotNull null
            val observed = PatternEngine.denormalise(band.typicalStartMin)
            val delta = observed - entry.targetStartMin
            if (abs(delta) < maxOf(entry.toleranceMin, 20)) return@mapNotNull null
            DriftProposal(entry, observed, delta, band.observations)
        }
}
