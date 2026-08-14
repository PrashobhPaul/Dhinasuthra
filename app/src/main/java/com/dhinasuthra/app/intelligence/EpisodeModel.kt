package com.dhinasuthra.app.intelligence

import com.dhinasuthra.app.core.TimeUtils
import java.time.Instant

/**
 * The canonical 24-hour time model (spec §7, §8).
 *
 * A day is a set of non-overlapping [TimeEpisode]s that reconcile to exactly 1440
 * minutes. Unknown time is represented honestly as an episode, never hidden.
 *
 * The hard rule from spec §6 lives here: an episode carries an [ActivityType] AND a
 * [LocationType], and the two are only ever combined by the explicit
 * "activity at location" lens. Neither enum contains a member of the other's
 * vocabulary — there is no HOME activity and no SLEEP location, by construction.
 */

/** What the person was doing. Never contains a place. */
enum class ActivityType(val label: String, val icon: String) {
    SLEEP("Sleep", "🌙"),
    WAKE_TRANSITION("Waking up", "🌤"),
    MORNING_ROUTINE("Morning routine", "🪥"),
    BREAKFAST("Breakfast", "🍳"),
    WORK("Work", "💼"),
    MEETING("Meeting", "🗣"),
    LUNCH("Lunch", "🍱"),
    TEA_BREAK("Break", "☕"),
    EXERCISE("Exercise", "🏃"),
    COMMUTE("Commute", "🚗"),
    DINNER("Dinner", "🍽"),
    PERSONAL("Personal", "🏡"),
    LEISURE("Leisure", "🎧"),
    WATCHING_TV("Watching TV", "📺"),
    PHONE_CALL("Call", "📞"),
    /** A short step away from what you were doing, before it resumes (plan §10). */
    INTERRUPTION("Stepped away", "🚶"),
    UNKNOWN("Unclassified", "❔");

    val isMeal: Boolean get() = this == BREAKFAST || this == LUNCH || this == DINNER
    val isRest: Boolean get() = this == SLEEP
}

/** Where the person was. Never contains an activity. */
enum class LocationType(val label: String, val icon: String) {
    HOME("Home", "🏠"),
    OFFICE("Office", "🏢"),
    GYM("Gym", "💪"),
    RESTAURANT("Restaurant", "🍴"),
    FRIEND("Friend or family", "👥"),
    OTHER_KNOWN("Known place", "📍"),
    TRANSIT("Transit", "🛣"),
    UNKNOWN("Unknown", "❔")
}

/** Trust level of an episode (spec §8). This distinction is what makes the app honest. */
enum class EpisodeStatus(val label: String, val short: String) {
    /** Directly supported by sensor evidence. */
    OBSERVED("Observed", "OBS"),

    /** Reconstructed by rules from surrounding evidence and learned patterns. */
    INFERRED("Inferred", "INF"),

    /** The user said yes. */
    CONFIRMED("Confirmed", "CNF"),

    /** The user replaced what the engine decided. */
    CORRECTED("Corrected", "COR")
}

/**
 * One slice of the day.
 *
 * [startMin]/[endMin] are minutes from the start of [epochDay]; [endMin] may exceed
 * 1440 only inside intermediate calculations — [DayReconstruction] always clips to
 * the day boundary so every day reconciles to 1440 minutes exactly.
 */
data class TimeEpisode(
    val id: String,
    val epochDay: Long,
    val startMin: Int,
    val endMin: Int,
    val activity: ActivityType,
    val location: LocationType,
    val placeName: String? = null,
    val confidence: Float,
    val status: EpisodeStatus,
    /** Ids of the rules that produced this episode, e.g. ["MEA-02", "MEA-05"]. */
    val ruleIds: List<String> = emptyList(),
    /** Human sentences, one per fired rule — the "why" shown on tap. */
    val evidence: List<String> = emptyList()
) {
    val durationMin: Int get() = (endMin - startMin).coerceAtLeast(0)

    val band: ConfidenceBand get() = ConfidenceBand.of(confidence)

    val startInstant: Instant get() = TimeUtils.instantAt(epochDay, startMin)
    val endInstant: Instant get() = TimeUtils.instantAt(epochDay, endMin)

    fun startLabel(): String = TimeUtils.formatMinuteOfDay(startMin)
    fun endLabel(): String = TimeUtils.formatMinuteOfDay(endMin)
    fun rangeLabel(): String = "${startLabel()}–${endLabel()}"
    fun durationLabel(): String = TimeUtils.formatDurationMin(durationMin)

    fun overlaps(otherStart: Int, otherEnd: Int): Boolean =
        startMin < otherEnd && otherStart < endMin

    /** Same activity and location, and adjacent in time — a merge candidate. */
    fun mergeableWith(other: TimeEpisode): Boolean =
        activity == other.activity && location == other.location &&
            placeName == other.placeName && endMin == other.startMin
}

/**
 * A full day. Construction guarantees the 24-hour invariant, so downstream
 * analytics can trust that totals sum to the day without re-checking.
 */
class DayReconstruction private constructor(
    val epochDay: Long,
    val episodes: List<TimeEpisode>,
    /** Minutes at the end of a partial (today) day that simply haven't happened yet. */
    val notYetLivedMin: Int
) {

    val coveredMin: Int get() = episodes.sumOf { it.durationMin }
    val unknownMin: Int get() = episodes.filter { it.activity == ActivityType.UNKNOWN }.sumOf { it.durationMin }
    val knownMin: Int get() = coveredMin - unknownMin

    /** Coverage as a fraction of the part of the day that has actually happened. */
    val coverageFraction: Float
        get() {
            val lived = (1440 - notYetLivedMin).coerceAtLeast(1)
            return (knownMin.toFloat() / lived).coerceIn(0f, 1f)
        }

    fun at(minuteOfDay: Int): TimeEpisode? =
        episodes.firstOrNull { minuteOfDay >= it.startMin && minuteOfDay < it.endMin }

    fun of(activity: ActivityType): List<TimeEpisode> = episodes.filter { it.activity == activity }

    fun firstStartOf(activity: ActivityType): Int? =
        episodes.firstOrNull { it.activity == activity }?.startMin

    fun totalOf(activity: ActivityType): Int =
        episodes.filter { it.activity == activity }.sumOf { it.durationMin }

    fun totalAt(location: LocationType): Int =
        episodes.filter { it.location == location }.sumOf { it.durationMin }

    companion object {
        /**
         * Normalise a raw episode list into a valid day:
         *  1. sort and clip to [0, 1440]
         *  2. resolve overlaps in favour of the higher-confidence episode
         *  3. fill every remaining gap with an honest UNKNOWN episode (spec §14/§44)
         *  4. merge identical neighbours so the timeline reads naturally
         *
         * [livedUntilMin] lets today stop at "now" instead of inventing 9 unknown
         * evening hours — the rest of the day is reported as not yet lived.
         */
        fun build(
            epochDay: Long,
            raw: List<TimeEpisode>,
            livedUntilMin: Int = 1440
        ): DayReconstruction {
            val lived = livedUntilMin.coerceIn(0, 1440)
            val clipped = raw.asSequence()
                .map { it.copy(startMin = it.startMin.coerceIn(0, lived), endMin = it.endMin.coerceIn(0, lived)) }
                .filter { it.durationMin > 0 }
                .sortedWith(compareBy({ it.startMin }, { -it.confidence }))
                .toList()

            // 2 — overlap resolution: the earlier episode keeps its ground unless the
            // later one is meaningfully more confident, in which case it truncates it.
            val placed = mutableListOf<TimeEpisode>()
            for (e in clipped) {
                val prev = placed.lastOrNull()
                if (prev == null || e.startMin >= prev.endMin) {
                    placed += e
                    continue
                }
                if (e.confidence > prev.confidence + 0.1f) {
                    val shortened = prev.copy(endMin = e.startMin)
                    placed.removeAt(placed.lastIndex)
                    if (shortened.durationMin > 0) placed += shortened
                    placed += e
                } else {
                    val pushed = e.copy(startMin = prev.endMin)
                    if (pushed.durationMin > 0) placed += pushed
                }
            }

            // 3 — gap filling.
            val complete = mutableListOf<TimeEpisode>()
            var cursor = 0
            for (e in placed) {
                if (e.startMin > cursor) {
                    complete += unknownEpisode(epochDay, cursor, e.startMin)
                }
                complete += e
                cursor = maxOf(cursor, e.endMin)
            }
            if (cursor < lived) complete += unknownEpisode(epochDay, cursor, lived)

            // 4 — merge identical neighbours.
            val merged = mutableListOf<TimeEpisode>()
            for (e in complete) {
                val last = merged.lastOrNull()
                if (last != null && last.mergeableWith(e)) {
                    merged[merged.lastIndex] = last.copy(
                        endMin = e.endMin,
                        confidence = minOf(last.confidence, e.confidence),
                        ruleIds = (last.ruleIds + e.ruleIds).distinct(),
                        evidence = (last.evidence + e.evidence).distinct()
                    )
                } else {
                    merged += e
                }
            }

            return DayReconstruction(epochDay, merged, 1440 - lived)
        }

        fun empty(epochDay: Long, livedUntilMin: Int = 1440): DayReconstruction =
            build(epochDay, emptyList(), livedUntilMin)

        private fun unknownEpisode(epochDay: Long, from: Int, to: Int) = TimeEpisode(
            id = "unk-$epochDay-$from",
            epochDay = epochDay,
            startMin = from,
            endMin = to,
            activity = ActivityType.UNKNOWN,
            location = LocationType.UNKNOWN,
            confidence = 0f,
            status = EpisodeStatus.INFERRED,
            ruleIds = listOf(TimelineRules.HONEST_UNKNOWN.id),
            evidence = listOf("no evidence covered this period, so it is reported as unclassified rather than guessed")
        )
    }
}
