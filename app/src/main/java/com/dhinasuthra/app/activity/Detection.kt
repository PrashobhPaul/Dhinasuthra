package com.dhinasuthra.app.activity

import com.dhinasuthra.app.core.TimeUtils
import java.time.Instant

/**
 * The evidence and detection substrate (plan §8, §17, §18).
 *
 * Plan §17 asks for "an evidence/scoring layer rather than scattered if/else
 * rules", with weights that are configurable. That is what this file is. A
 * detector's whole job is to look at a window of signals and emit
 * [ActivityCandidate]s, each carrying the [EvidenceItem]s that justify it. It
 * never writes to the database, never touches Android, and never decides
 * whether the user should be asked — so every detector is a pure function that
 * a unit test can pin down exactly (plan §32.9).
 *
 * New detectors are added by registering them, not by editing a chain:
 *
 * ```kotlin
 * DetectorRegistry.default().plus(MyNewDetector())
 * ```
 */

// ---------------------------------------------------------------------------
// Evidence
// ---------------------------------------------------------------------------

/**
 * One reason. [detail] is written for the user, not for a log — it appears
 * verbatim under "why" in the timeline, so it says "your phone was in use for
 * five minutes", never "SCREEN_TIME w=0.40".
 */
data class EvidenceItem(
    val timestamp: Long,
    val signalType: String,
    val weight: Float,
    val detail: String
)

/**
 * Combines evidence into a confidence.
 *
 * Independent evidence combines the noisy-OR way — `1 − Π(1 − wᵢ)` — so two
 * weak signals reinforce each other without either one alone being enough, and
 * nothing can ever reach certainty. Contradictions subtract directly, because a
 * signal that says "this didn't happen" should be able to pull a conclusion
 * back down rather than merely fail to push it up.
 */
class EvidenceBundle(items: List<EvidenceItem> = emptyList()) {

    private val _items = items.toMutableList()

    val items: List<EvidenceItem> get() = _items.sortedBy { it.timestamp }

    val isEmpty: Boolean get() = _items.isEmpty()

    fun add(timestamp: Long, signalType: String, weight: Float, detail: String): EvidenceBundle {
        _items.add(EvidenceItem(timestamp, signalType, weight, detail))
        return this
    }

    fun addAll(other: EvidenceBundle): EvidenceBundle {
        _items.addAll(other._items)
        return this
    }

    fun confidence(): Float {
        if (_items.isEmpty()) return 0f
        var support = 1f
        var against = 0f
        for (item in _items) {
            if (item.weight >= 0f) support *= (1f - item.weight.coerceIn(0f, 0.99f))
            else against += -item.weight
        }
        return ((1f - support) - against).coerceIn(0f, 0.99f)
    }

    /** The strongest few reasons, for a UI that has room for three lines, not ten. */
    fun topReasons(limit: Int = 4): List<String> =
        _items.filter { it.weight > 0f }
            .sortedByDescending { it.weight }
            .map { it.detail }
            .distinct()
            .take(limit)
}

// ---------------------------------------------------------------------------
// Weights
// ---------------------------------------------------------------------------

/**
 * Every number the detectors use, in one place (plan §17: "The exact weights
 * should be configurable").
 *
 * Overrides arrive as a plain map, so a future settings screen, a remote-free
 * config file or a per-user learned adjustment can all supply them without any
 * of the detectors changing. An unknown key is ignored rather than fatal.
 */
class WeightConfig(private val overrides: Map<String, Float> = emptyMap()) {

    operator fun get(key: String): Float = overrides[key] ?: DEFAULTS[key] ?: 0f

    fun with(vararg pairs: Pair<String, Float>) = WeightConfig(overrides + pairs.toMap())

    fun asMap(): Map<String, Float> = DEFAULTS + overrides

    companion object {
        // -- wake (plan §17's worked example, verbatim) ----------------------
        const val WAKE_SCREEN_SUSTAINED = "wake.screen_active_5min"
        const val WAKE_PHONE_INTERACTION = "wake.phone_interaction"
        const val WAKE_UNPLUG_AND_MOTION = "wake.charger_unplug_with_motion"
        const val WAKE_WALKING = "wake.walking"
        const val WAKE_SINGLE_MOTION = "wake.single_motion"
        const val WAKE_REPEATED_MOTION = "wake.repeated_motion"
        const val WAKE_LOCATION_MOVEMENT = "wake.location_movement"
        const val WAKE_IN_USUAL_WINDOW = "wake.within_usual_window"
        const val WAKE_SCREEN_STAYED_OFF = "wake.screen_stayed_off"

        // -- boundaries ------------------------------------------------------
        const val BREAK_LEFT_ANCHOR = "break.left_anchor"
        const val BREAK_WALKING = "break.walking"
        const val BREAK_LOCATION_CHANGE = "break.location_change"
        const val BREAK_RETURNED = "break.returned_to_anchor"
        const val BREAK_DURATION_PLAUSIBLE = "break.duration_plausible"

        // -- contextual classification ---------------------------------------
        const val MEAL_IN_WINDOW = "meal.within_window"
        const val MEAL_MATCHES_LEARNED = "meal.matches_your_pattern"
        const val MEAL_DURATION_TYPICAL = "meal.duration_typical"
        const val MEAL_LOCATION_MATCHES = "meal.location_matches"

        // -- television -------------------------------------------------------
        const val TV_REMOTE_USED = "tv.remote_interaction"
        const val TV_REPEATED_REMOTE = "tv.repeated_remote_interaction"
        const val TV_AT_HOME = "tv.at_home"
        const val TV_STATIONARY = "tv.stationary"
        const val TV_PLAYBACK = "tv.playback_control"
        const val TV_NOT_PRESENT = "tv.no_interaction_since"

        // -- calls -------------------------------------------------------------
        const val CALL_CONNECTED = "call.connected"

        val DEFAULTS: Map<String, Float> = mapOf(
            WAKE_SCREEN_SUSTAINED to 0.40f,
            WAKE_PHONE_INTERACTION to 0.30f,
            WAKE_UNPLUG_AND_MOTION to 0.20f,
            WAKE_WALKING to 0.20f,
            WAKE_SINGLE_MOTION to 0.05f,
            WAKE_REPEATED_MOTION to 0.15f,
            WAKE_LOCATION_MOVEMENT to 0.18f,
            WAKE_IN_USUAL_WINDOW to 0.12f,
            WAKE_SCREEN_STAYED_OFF to -0.25f,

            BREAK_LEFT_ANCHOR to 0.35f,
            BREAK_WALKING to 0.25f,
            BREAK_LOCATION_CHANGE to 0.30f,
            BREAK_RETURNED to 0.30f,
            BREAK_DURATION_PLAUSIBLE to 0.20f,

            MEAL_IN_WINDOW to 0.30f,
            MEAL_MATCHES_LEARNED to 0.35f,
            MEAL_DURATION_TYPICAL to 0.20f,
            MEAL_LOCATION_MATCHES to 0.25f,

            TV_REMOTE_USED to 0.35f,
            TV_REPEATED_REMOTE to 0.30f,
            TV_AT_HOME to 0.20f,
            TV_STATIONARY to 0.15f,
            TV_PLAYBACK to 0.25f,
            TV_NOT_PRESENT to -0.40f,

            CALL_CONNECTED to 0.95f
        )
    }
}

// ---------------------------------------------------------------------------
// Signals in, candidates out
// ---------------------------------------------------------------------------

/** A time-ordered slice of raw signals, with the lookups detectors keep needing. */
class SignalWindow(signals: List<Signal>) {

    val signals: List<Signal> = signals.sortedBy { it.timestamp }

    val isEmpty: Boolean get() = signals.isEmpty()

    fun between(from: Long, to: Long): List<Signal> =
        signals.filter { it.timestamp in from..to }

    fun ofType(vararg types: String): List<Signal> {
        val set = types.toSet()
        return signals.filter { it.type in set }
    }

    fun ofTypeBetween(types: Set<String>, from: Long, to: Long): List<Signal> =
        signals.filter { it.type in types && it.timestamp in from..to }

    fun firstAfter(from: Long, types: Set<String>): Signal? =
        signals.firstOrNull { it.timestamp >= from && it.type in types }

    fun lastBefore(to: Long, types: Set<String>): Signal? =
        signals.lastOrNull { it.timestamp <= to && it.type in types }

    /**
     * The longest uninterrupted stretch of phone use starting at or after
     * [from], in minutes. Sustained use is the single strongest wake signal
     * (plan §6), so it gets its own accessor rather than being re-derived.
     */
    fun longestScreenRunMinutes(from: Long, to: Long): Pair<Long, Int>? {
        val relevant = signals.filter {
            it.timestamp in from..to &&
                (it.type == SignalTypes.SCREEN_TIME || it.type == SignalTypes.SCREEN_ACTIVE ||
                    it.type == SignalTypes.SCREEN_ON || it.type == SignalTypes.SCREEN_OFF)
        }
        var bestStart: Long? = null
        var bestMinutes = 0
        var runStart: Long? = null
        for (s in relevant) {
            when (s.type) {
                SignalTypes.SCREEN_TIME -> {
                    // An explicit measurement wins over anything reconstructed.
                    val minutes = s.minutes ?: 0
                    if (minutes > bestMinutes) {
                        bestMinutes = minutes
                        bestStart = s.timestamp - minutes * 60_000L
                    }
                }
                SignalTypes.SCREEN_ON, SignalTypes.SCREEN_ACTIVE -> if (runStart == null) runStart = s.timestamp
                SignalTypes.SCREEN_OFF -> {
                    val start = runStart
                    if (start != null) {
                        val minutes = ((s.timestamp - start) / 60_000L).toInt()
                        if (minutes > bestMinutes) {
                            bestMinutes = minutes
                            bestStart = start
                        }
                        runStart = null
                    }
                }
            }
        }
        // A run still open at the end of the window is closed at the last screen
        // signal, not at the window edge. Otherwise a single SCREEN_ON at 09:10
        // followed by a walk at 10:06 would be reported to the user as "your
        // phone was in use for 56 minutes", which nobody observed.
        runStart?.let { start ->
            val closesAt = minOf(to, relevant.lastOrNull()?.timestamp ?: to)
            val minutes = ((closesAt - start) / 60_000L).toInt()
            if (minutes > bestMinutes) {
                bestMinutes = minutes
                bestStart = start
            }
        }
        return bestStart?.let { it to bestMinutes }
    }

    companion object {
        val EMPTY = SignalWindow(emptyList())
    }
}

/** Known fixed points in a person's day — where "away from here" means something. */
data class Anchor(
    val id: String,
    val placeId: Long?,
    val label: String,
    /** How far you can drift and still count as "here", in metres. */
    val radiusM: Float = 25f
)

/**
 * Everything a detector may consult besides the signals themselves: the day
 * being reconstructed, the anchors, what has already been learned about this
 * person, and the weights.
 */
data class DetectionContext(
    val epochDay: Long,
    /** Timestamp → minute of the local day. Injectable so tests need no time zone. */
    val minuteOfDayOf: (Long) -> Int = { TimeUtils.minuteOfDay(Instant.ofEpochMilli(it)) },
    val anchors: List<Anchor> = emptyList(),
    val profiles: Map<String, LearnedProfile> = emptyMap(),
    val weights: WeightConfig = WeightConfig(),
    /** Confirmed activities already on the timeline for this day; never overwritten. */
    val userTruth: List<ActivityCandidate> = emptyList()
) {
    fun minuteOfDay(timestamp: Long): Int = minuteOfDayOf(timestamp)

    fun anchor(id: String): Anchor? = anchors.firstOrNull { it.id == id }

    fun profile(activityCode: String): LearnedProfile? = profiles[activityCode]

    companion object {
        const val ANCHOR_HOME = "HOME"
        const val ANCHOR_DESK = "DESK"
    }
}

/**
 * What a detector believes happened. Provisional by default — plan §11 is
 * explicit that a detected lunch must not "automatically make it confirmed".
 */
data class ActivityCandidate(
    val activityCode: String,
    val start: Long,
    val end: Long?,
    val confidence: Float,
    val evidence: List<EvidenceItem> = emptyList(),
    val status: ActivityStatus = ActivityStatus.INFERRED,
    val placeId: Long? = null,
    val detectorId: String = "",
    /**
     * An earlier, weaker boundary the engine also believes in — the first stir
     * before the confirmed wake. Kept so the confirmation prompt can offer both
     * times rather than making the user type one (plan §7).
     */
    val alternativeStart: Long? = null,
    val source: String = EventSources.INFERENCE
) {
    val durationMin: Int
        get() = end?.let { ((it - start) / 60_000L).toInt() } ?: 0

    fun reasons(limit: Int = 4): List<String> =
        EvidenceBundle(evidence).topReasons(limit)
}

/** A detector turns signals into candidates. That is its entire contract. */
interface ActivityDetector {
    val id: String
    fun detect(window: SignalWindow, context: DetectionContext): List<ActivityCandidate>
}

/**
 * The set of detectors that run, in order.
 *
 * Deliberately a list rather than a hardcoded pipeline: adding TV detection, or
 * a wearable, or whatever the next round of feedback asks for, means adding an
 * entry — not rewriting an orchestrator that already works.
 */
class DetectorRegistry(val detectors: List<ActivityDetector>) {

    operator fun plus(detector: ActivityDetector) = DetectorRegistry(detectors + detector)

    fun without(id: String) = DetectorRegistry(detectors.filterNot { it.id == id })

    /**
     * Runs every detector. One detector throwing must not cost the user the
     * output of the other six, so failures are contained per detector.
     */
    fun run(window: SignalWindow, context: DetectionContext): List<ActivityCandidate> =
        detectors.flatMap { d ->
            runCatching { d.detect(window, context) }.getOrDefault(emptyList())
        }.sortedBy { it.start }

    companion object {
        /** The detectors this release ships with. */
        fun default(): DetectorRegistry = DetectorRegistry(
            listOf(
                WakeDetector(),
                BoundaryDetector(),
                ContextualBreakDetector(),
                TvDetector(),
                CallDetector()
            )
        )
    }
}

/**
 * Decides which candidate wins when two detectors describe the same stretch of
 * time.
 *
 * They will, by design. [BoundaryDetector] finds that you were away from your
 * desk from 12:52 to 13:42; [ContextualBreakDetector] looks at the same gap and
 * says it was lunch. Both are correct, but the timeline should show one of them,
 * and it should be the one that says more.
 *
 * Keeping this here rather than inside the detectors is what lets them stay
 * independent and separately testable — a new detector doesn't have to know what
 * the others might also have found.
 */
object CandidateResolver {

    /**
     * A generic "you stepped away" loses to anything that can name the activity.
     * Beyond that, an observed fact beats an inference, and a stronger
     * conclusion beats a weaker one.
     */
    private fun rank(candidate: ActivityCandidate): Int = when {
        candidate.status == ActivityStatus.SYSTEM_OBSERVED -> 3
        candidate.activityCode == ActivityCatalog.INTERRUPTION -> 0
        else -> 2
    }

    fun resolve(candidates: List<ActivityCandidate>): List<ActivityCandidate> {
        val accepted = mutableListOf<ActivityCandidate>()
        candidates
            .sortedWith(compareByDescending<ActivityCandidate> { rank(it) }.thenByDescending { it.confidence })
            .forEach { candidate ->
                if (accepted.none { it.overlaps(candidate) }) accepted.add(candidate)
            }
        return accepted.sortedBy { it.start }
    }

    /**
     * Zero-length markers (a wake is a moment, not a span) only collide with
     * something that genuinely contains them.
     */
    private fun ActivityCandidate.overlaps(other: ActivityCandidate): Boolean {
        val myEnd = end ?: start
        val theirEnd = other.end ?: other.start
        if (start == myEnd) return other.start < start && start < theirEnd
        if (other.start == theirEnd) return start < other.start && other.start < myEnd
        return start < theirEnd && other.start < myEnd
    }
}
