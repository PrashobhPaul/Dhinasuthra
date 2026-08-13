package com.dhinasuthra.app.intelligence

import com.dhinasuthra.app.core.model.ActivityKind
import com.dhinasuthra.app.core.model.DayType

/**
 * The pure, Android-free input to the intelligence layer.
 *
 * Everything below this line is ordinary Kotlin: no Room, no Context, no clock
 * access. That is what makes an "AI-like" product testable — every conclusion the
 * app reaches can be reproduced in a unit test from a literal [DayEvidence].
 */

/** A reconciled stay at one place (already free of geofence flutter). */
data class Presence(
    val startMin: Int,
    val endMin: Int,
    val location: LocationType,
    val placeId: Long? = null,
    val placeName: String? = null,
    val confidence: Float = 0.7f,
    val ruleIds: List<String> = emptyList(),
    val evidence: List<String> = emptyList()
) {
    val durationMin: Int get() = (endMin - startMin).coerceAtLeast(0)
    val isKnownPlace: Boolean get() = location != LocationType.UNKNOWN && location != LocationType.TRANSIT
}

/** One periodic device sample: screen state, charging, and where we were. */
data class DeviceSample(
    val minuteOfDay: Int,
    val interactive: Boolean?,
    val charging: Boolean?,
    val lat: Double? = null,
    val lon: Double? = null,
    val accuracyM: Float? = null
)

/** A span during which the recognised movement state was [kind]. */
data class MovementSpan(val startMin: Int, val endMin: Int, val kind: ActivityKind) {
    val durationMin: Int get() = (endMin - startMin).coerceAtLeast(0)
    val isLocomotion: Boolean
        get() = kind == ActivityKind.IN_VEHICLE || kind == ActivityKind.ON_BICYCLE ||
            kind == ActivityKind.RUNNING || kind == ActivityKind.WALKING
    val isVehicular: Boolean get() = kind == ActivityKind.IN_VEHICLE || kind == ActivityKind.ON_BICYCLE
}

/** A user correction, replayed as personal evidence on later days (spec §42). */
data class Correction(
    val epochDay: Long,
    val startMin: Int,
    val endMin: Int,
    val activity: ActivityType,
    val location: LocationType,
    val dayType: DayType,
    val createdAt: Long
) {
    val durationMin: Int get() = (endMin - startMin).coerceAtLeast(0)
    val midMin: Int get() = (startMin + endMin) / 2
}

/** Everything the engine knows about one day, before it reasons about it. */
data class DayEvidence(
    val epochDay: Long,
    val dayType: DayType,
    /** Minutes of the day that have actually happened (1440 for a past day). */
    val livedUntilMin: Int,
    val presences: List<Presence>,
    val samples: List<DeviceSample>,
    val movements: List<MovementSpan>,
    /** Samples from the previous evening, needed to find a sleep onset before midnight. */
    val previousEveningSamples: List<DeviceSample> = emptyList(),
    val patterns: PatternIndex = PatternIndex.empty(),
    val corrections: List<Correction> = emptyList()
) {
    fun presenceAt(minute: Int): Presence? =
        presences.firstOrNull { minute >= it.startMin && minute < it.endMin }

    fun movementAt(minute: Int): MovementSpan? =
        movements.firstOrNull { minute >= it.startMin && minute < it.endMin }

    fun samplesBetween(from: Int, to: Int): List<DeviceSample> =
        samples.filter { it.minuteOfDay in from until to }

    fun interactionCount(from: Int, to: Int): Int =
        samplesBetween(from, to).count { it.interactive == true }

    fun locomotionMinutes(from: Int, to: Int): Int =
        movements.filter { it.isLocomotion }
            .sumOf { (minOf(it.endMin, to) - maxOf(it.startMin, from)).coerceAtLeast(0) }
}

/**
 * A learned band for one activity: the shape of your normal.
 * `null` bands mean "not learned yet" and are treated as absence of evidence,
 * never as a default (MEA-01).
 */
data class ActivityBand(
    val activity: ActivityType,
    val dayType: DayType,
    val typicalStartMin: Int,
    val p10: Int,
    val p25: Int,
    val p75: Int,
    val p90: Int,
    val typicalDurationMin: Int?,
    val durationP25: Int? = null,
    val durationP75: Int? = null,
    val observations: Int,
    val confidence: Float,
    val lastObservedDay: Long = 0L,
    val lifecycle: PatternLifecycle = PatternLifecycle.LEARNING,
    /**
     * True when this band comes from a time the user stated (a routine entry),
     * not from observation. PAT-13: usable, but never presented as observed.
     */
    val isPrior: Boolean = false
) {
    val iqr: Int get() = p75 - p25
    val spread: Int get() = p90 - p10

    /** Consistency (CNS-01): 1 − IQR/180, withheld under 5 observations (CNS-02). */
    val consistency: Int?
        get() = if (observations < 5) null
        else ((1f - (iqr / 180f)).coerceIn(0f, 1f) * 100).toInt()

    fun contains(minute: Int, tolerance: Int = 0): Boolean =
        minute >= p10 - tolerance && minute <= p90 + tolerance

    fun deviationFromTypical(minute: Int): Int = minute - typicalStartMin
}

enum class PatternLifecycle(val label: String, val description: String) {
    LEARNING("Learning", "Too few observations to say anything yet."),
    EMERGING("Emerging", "A shape is appearing, but it is not settled."),
    ESTABLISHED("Established", "Repeated often enough and tightly enough to rely on."),
    UNSTABLE("Unstable", "Recent days disagree with the history."),
    STALE("Stale", "Not observed recently — probably no longer true.")
}

/** Lookup of learned bands, keyed by activity and day type. */
class PatternIndex(private val bands: Map<Pair<ActivityType, DayType>, ActivityBand>) {

    fun band(activity: ActivityType, dayType: DayType): ActivityBand? = bands[activity to dayType]

    /**
     * Bands that may drive inference: three or more observations (PAT-01), or a
     * time the user stated themselves (PAT-13). Learning and stale bands are
     * excluded — they describe too little, or describe a life you no longer live.
     */
    fun usableBand(activity: ActivityType, dayType: DayType): ActivityBand? =
        band(activity, dayType)?.takeIf {
            (it.isPrior || it.observations >= 3) &&
                it.lifecycle != PatternLifecycle.LEARNING &&
                it.lifecycle != PatternLifecycle.STALE
        }

    fun all(): List<ActivityBand> = bands.values.sortedBy { it.typicalStartMin }

    fun forDayType(dayType: DayType): List<ActivityBand> =
        bands.values.filter { it.dayType == dayType }.sortedBy { it.typicalStartMin }

    val isEmpty: Boolean get() = bands.isEmpty()

    companion object {
        fun empty() = PatternIndex(emptyMap())
        fun of(bands: List<ActivityBand>) = PatternIndex(bands.associateBy { it.activity to it.dayType })
    }
}
