package com.dhinasuthra.app.intelligence

import com.dhinasuthra.app.core.TimeUtils
import com.dhinasuthra.app.core.model.DayType
import com.dhinasuthra.app.routine.RoutineStats
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * The statistics behind Time Lab (spec §26).
 *
 * Nothing here is clever. It is deliberately ordinary, well-known statistics —
 * medians, quartiles, Tukey fences, Shannon entropy, least squares — computed on
 * device, on your own data, with every formula written down next to it.
 */
object StatisticsEngine {

    // -- distributions ------------------------------------------------------

    data class Bin(val fromMin: Int, val toMin: Int, val count: Int) {
        val label: String get() = TimeUtils.formatMinuteOfDay(fromMin)
    }

    fun histogram(values: List<Int>, binMinutes: Int = 15): List<Bin> {
        if (values.isEmpty()) return emptyList()
        val lo = (values.min() / binMinutes) * binMinutes
        val hi = ((values.max() / binMinutes) + 1) * binMinutes
        val bins = mutableListOf<Bin>()
        var cursor = lo
        while (cursor < hi) {
            val next = cursor + binMinutes
            bins += Bin(cursor, next, values.count { it >= cursor && it < next })
            cursor = next
        }
        return bins
    }

    data class BoxStats(
        val min: Int,
        val p25: Int,
        val median: Int,
        val p75: Int,
        val max: Int,
        val outliers: List<Int>,
        val mean: Float,
        val standardDeviation: Float,
        val count: Int
    ) {
        val iqr: Int get() = p75 - p25
        val range: Int get() = max - min
    }

    fun box(values: List<Int>): BoxStats? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val q1 = RoutineStats.percentile(sorted, 25.0)
        val q3 = RoutineStats.percentile(sorted, 75.0)
        val iqr = q3 - q1
        val lo = q1 - (1.5 * iqr).roundToInt()
        val hi = q3 + (1.5 * iqr).roundToInt()
        val inliers = sorted.filter { it in lo..hi }.ifEmpty { sorted }
        return BoxStats(
            min = inliers.first(),
            p25 = q1,
            median = RoutineStats.percentile(sorted, 50.0),
            p75 = q3,
            max = inliers.last(),
            outliers = sorted.filter { it !in lo..hi },
            mean = sorted.average().toFloat(),
            standardDeviation = PatternEngine.standardDeviation(sorted),
            count = sorted.size
        )
    }

    fun percentileBands(values: List<Int>): List<Pair<String, Int>> {
        if (values.isEmpty()) return emptyList()
        val s = values.sorted()
        return listOf(
            "P10" to RoutineStats.percentile(s, 10.0),
            "P25" to RoutineStats.percentile(s, 25.0),
            "P50" to RoutineStats.percentile(s, 50.0),
            "P75" to RoutineStats.percentile(s, 75.0),
            "P90" to RoutineStats.percentile(s, 90.0)
        )
    }

    // -- day shape ----------------------------------------------------------

    /** The day at 15-minute resolution: 96 slots, one dominant activity each. */
    fun fingerprint(day: DayReconstruction, slotMinutes: Int = 15): List<ActivityType> {
        val slots = 1440 / slotMinutes
        return (0 until slots).map { i ->
            val from = i * slotMinutes
            val to = from + slotMinutes
            day.episodes
                .filter { it.startMin < to && from < it.endMin }
                .maxByOrNull { minOf(it.endMin, to) - maxOf(it.startMin, from) }
                ?.activity ?: ActivityType.UNKNOWN
        }
    }

    /** CNS-05 — the share of minutes on which two days were doing the same thing. */
    fun similarity(a: DayReconstruction, b: DayReconstruction, slotMinutes: Int = 15): Float {
        val fa = fingerprint(a, slotMinutes)
        val fb = fingerprint(b, slotMinutes)
        val comparable = fa.indices.count { fa[it] != ActivityType.UNKNOWN || fb[it] != ActivityType.UNKNOWN }
        if (comparable == 0) return 0f
        val agree = fa.indices.count { fa[it] == fb[it] && fa[it] != ActivityType.UNKNOWN }
        return agree.toFloat() / comparable
    }

    /** The most representative day of a set — the one most like all the others. */
    fun mostTypicalDay(days: List<DayReconstruction>): DayReconstruction? {
        if (days.size < 2) return days.firstOrNull()
        return days.maxByOrNull { candidate ->
            days.filter { it.epochDay != candidate.epochDay }
                .map { similarity(candidate, it) }
                .average()
        }
    }

    /**
     * CNS-04 — predictability from temporal entropy.
     *
     * For each 30-minute slot of the day we take the distribution of activities
     * observed across the given days and compute Shannon entropy H. Predictability
     * is 1 − mean(H)/log(k): a person who does the same thing at the same time
     * every day scores 100%; a person whose 3pm is a coin flip scores near zero.
     */
    fun predictability(days: List<DayReconstruction>, slotMinutes: Int = 30): Float? {
        if (days.size < 3) return null
        val slots = 1440 / slotMinutes
        val fingerprints = days.map { fingerprint(it, slotMinutes) }
        var total = 0.0
        var counted = 0
        for (slot in 0 until slots) {
            val values = fingerprints.mapNotNull { it.getOrNull(slot) }.filter { it != ActivityType.UNKNOWN }
            if (values.size < 3) continue
            val counts = values.groupingBy { it }.eachCount()
            if (counts.size <= 1) { total += 0.0; counted++; continue }
            var h = 0.0
            for ((_, c) in counts) {
                val p = c.toDouble() / values.size
                h -= p * ln(p)
            }
            total += h / ln(counts.size.toDouble())
            counted++
        }
        if (counted == 0) return null
        return (1.0 - total / counted).coerceIn(0.0, 1.0).toFloat()
    }

    // -- heatmaps -----------------------------------------------------------

    data class HeatCell(
        val row: Int,
        val hour: Int,
        val activity: ActivityType,
        val minutes: Int,
        val epochDay: Long
    )

    /** Day × hour grid — the 7×24 week and the 30×24 month use the same builder. */
    fun heatmap(days: List<DayReconstruction>): List<HeatCell> {
        val cells = mutableListOf<HeatCell>()
        days.sortedBy { it.epochDay }.forEachIndexed { row, day ->
            for (hour in 0 until 24) {
                val from = hour * 60
                val to = from + 60
                val overlapping = day.episodes.filter { it.startMin < to && from < it.endMin }
                val dominant = overlapping.maxByOrNull { minOf(it.endMin, to) - maxOf(it.startMin, from) }
                val known = overlapping.filter { it.activity != ActivityType.UNKNOWN }
                    .sumOf { minOf(it.endMin, to) - maxOf(it.startMin, from) }
                cells += HeatCell(
                    row = row,
                    hour = hour,
                    activity = dominant?.activity ?: ActivityType.UNKNOWN,
                    minutes = known.coerceIn(0, 60),
                    epochDay = day.epochDay
                )
            }
        }
        return cells
    }

    /** Calendar view: one value per day, for month and year heatmaps. */
    data class DayValue(val epochDay: Long, val value: Float, val label: String)

    fun calendarValues(
        days: List<DayReconstruction>,
        selector: (DayReconstruction) -> Float,
        formatter: (Float) -> String
    ): List<DayValue> = days.sortedBy { it.epochDay }
        .map { DayValue(it.epochDay, selector(it), formatter(selector(it))) }

    // -- trends -------------------------------------------------------------

    data class Trend(val slopePerDay: Float, val intercept: Float, val points: Int) {
        /** Change over [days] days, in the units of the input series. */
        fun changeOver(days: Int): Float = slopePerDay * days
    }

    /** Ordinary least squares on (index, value). */
    fun trend(values: List<Float>): Trend? {
        if (values.size < 3) return null
        val n = values.size
        val meanX = (n - 1) / 2.0
        val meanY = values.average()
        var num = 0.0
        var den = 0.0
        for (i in values.indices) {
            num += (i - meanX) * (values[i] - meanY)
            den += (i - meanX) * (i - meanX)
        }
        if (den == 0.0) return null
        val slope = num / den
        return Trend(slope.toFloat(), (meanY - slope * meanX).toFloat(), n)
    }

    /** Pearson correlation between two aligned series — used for "does X move with Y?". */
    fun correlation(a: List<Float>, b: List<Float>): Float? {
        if (a.size != b.size || a.size < 3) return null
        val ma = a.average()
        val mb = b.average()
        var num = 0.0
        var da = 0.0
        var db = 0.0
        for (i in a.indices) {
            num += (a[i] - ma) * (b[i] - mb)
            da += (a[i] - ma) * (a[i] - ma)
            db += (b[i] - mb) * (b[i] - mb)
        }
        if (da == 0.0 || db == 0.0) return null
        return (num / kotlin.math.sqrt(da * db)).toFloat()
    }

    // -- comparisons --------------------------------------------------------

    data class DayTypeComparison(
        val weekdayMedian: Int,
        val weekendMedian: Int,
        val activity: ActivityType
    ) {
        val deltaMin: Int get() = weekendMedian - weekdayMedian
    }

    fun compareDayTypes(days: List<DayReconstruction>, activity: ActivityType): DayTypeComparison? {
        val weekday = days.filter { TimeUtils.dayType(it.epochDay) == DayType.WEEKDAY }
            .map { it.totalOf(activity) }.filter { it > 0 }
        val weekend = days.filter { TimeUtils.dayType(it.epochDay) == DayType.WEEKEND }
            .map { it.totalOf(activity) }.filter { it > 0 }
        if (weekday.size < 2 || weekend.size < 2) return null
        return DayTypeComparison(
            PatternEngine.median(weekday),
            PatternEngine.median(weekend),
            activity
        )
    }

    /** Days whose shape is unlike the person's own normal (ANM-02). */
    fun unusualDays(days: List<DayReconstruction>, threshold: Float = 0.45f): List<Pair<Long, Float>> {
        if (days.size < 5) return emptyList()
        return days.map { day ->
            val others = days.filter {
                it.epochDay != day.epochDay && TimeUtils.dayType(it.epochDay) == TimeUtils.dayType(day.epochDay)
            }
            if (others.isEmpty()) day.epochDay to 1f
            else day.epochDay to others.map { similarity(day, it) }.average().toFloat()
        }.filter { it.second < threshold }.sortedBy { it.second }
    }

    fun deviationMinutes(actual: Int, band: ActivityBand): Int = actual - band.typicalStartMin

    fun isBeyondUsual(actual: Int, band: ActivityBand, tolerance: Int = 15): Boolean =
        actual > band.p90 + tolerance || actual < band.p10 - tolerance

    fun absDeviation(actual: Int, band: ActivityBand): Int = abs(actual - band.typicalStartMin)
}
