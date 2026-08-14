package com.dhinasuthra.app.activity

import com.dhinasuthra.app.intelligence.ActivityType

/**
 * The vocabulary of the activity-intelligence layer (plan §3, §4, §19, §23).
 *
 * Everything here is deliberately **open**. Signal types and activity codes are
 * strings, not enums, and the database stores them as strings. That is not
 * laziness — plan §34 asks that "the architecture supports additional future
 * sensor/device/activity signals without redesigning the database again". A new
 * signal source in a future release adds a constant here and a row in a
 * registry; it does not add a migration, and it does not break the parsing of
 * data written by an older build that has never heard of it.
 *
 * The reverse also holds: an *older* build reading rows written by a newer one
 * degrades to "unknown signal, weight 0" rather than crashing.
 */

// ---------------------------------------------------------------------------
// Lifecycle
// ---------------------------------------------------------------------------

/**
 * Where an activity sits between "the engine thinks" and "the user has said so"
 * (plan §19).
 *
 * ```
 * RAW OBSERVATION → INFERRED → CONFIRMED / CORRECTED → trusted history
 * ```
 *
 * The distinction is the whole point: [INFERRED] events may be recalculated by
 * a future engine, [CONFIRMED] and [CORRECTED] ones never are.
 */
enum class ActivityStatus(val code: String, val label: String) {
    /** The engine's current best guess. Provisional; recalculable. */
    INFERRED("INFERRED", "Likely"),

    /** The user said yes. Trusted history. */
    CONFIRMED("CONFIRMED", "Confirmed"),

    /** The user replaced what the engine decided. Trusted history. */
    CORRECTED("CORRECTED", "Corrected"),

    /** Directly observed rather than reasoned about — a call that actually connected. */
    SYSTEM_OBSERVED("SYSTEM_OBSERVED", "Detected"),

    /** The user told us this wasn't a thing. Kept, not deleted (plan §21). */
    IGNORED("IGNORED", "Dismissed");

    /** Trusted history is never silently rewritten (plan §22). */
    val isUserTruth: Boolean get() = this == CONFIRMED || this == CORRECTED || this == IGNORED

    /** Provisional events are the only ones a future engine may recalculate. */
    val isProvisional: Boolean get() = this == INFERRED

    companion object {
        fun of(code: String?): ActivityStatus =
            entries.firstOrNull { it.code == code } ?: INFERRED
    }
}

/** How an event came to exist. Free-form so future sources need no migration. */
object EventSources {
    const val INFERENCE = "INFERENCE"
    const val USER = "USER"
    const val DEVICE = "DEVICE"
    const val SIMULATOR = "SIMULATOR"
}

/** Why a user's edit happened, kept alongside the original (plan §21). */
object CorrectionReasons {
    const val USER_EDIT = "USER_EDIT"
    const val USER_CONFIRM = "USER_CONFIRM"
    const val USER_IGNORE = "USER_IGNORE"
    const val USER_SPLIT = "USER_SPLIT"
    const val USER_MERGE = "USER_MERGE"
    const val REPROCESSED = "REPROCESSED"
}

// ---------------------------------------------------------------------------
// Signals
// ---------------------------------------------------------------------------

/**
 * Raw signal vocabulary (plan §3). Strings, on purpose — see the file header.
 *
 * A signal is a fact about the device: the screen went on, the charger came out,
 * the TV remote was used. It carries no interpretation. Interpretation lives in
 * the detectors, which can change release to release while these stay stable.
 */
object SignalTypes {
    // phone
    const val SCREEN_ON = "SCREEN_ON"
    const val SCREEN_OFF = "SCREEN_OFF"
    const val SCREEN_ACTIVE = "SCREEN_ACTIVE"      // unlocked and in use
    const val SCREEN_TIME = "SCREEN_TIME"          // a sustained run of use; value = minutes
    const val USER_PRESENT = "USER_PRESENT"        // unlocked
    const val APP_OPENED = "APP_OPENED"
    const val APP_ACTIVE = "APP_ACTIVE"

    // power
    const val CHARGER_CONNECTED = "CHARGER_CONNECTED"
    const val CHARGER_DISCONNECTED = "CHARGER_DISCONNECTED"

    // movement
    const val MOTION = "MOTION"
    const val WALKING = "WALKING"
    const val RUNNING = "RUNNING"
    const val IN_VEHICLE = "IN_VEHICLE"
    const val STILL = "STILL"
    const val LOCATION_SAMPLE = "LOCATION_SAMPLE"
    const val LOCATION_CHANGE = "LOCATION_CHANGE"
    const val ARRIVED = "ARRIVED"
    const val DEPARTED = "DEPARTED"

    // external devices (plan §14, §16)
    const val TV_REMOTE_INTERACTION = "TV_REMOTE_INTERACTION"
    const val TV_NAVIGATION = "TV_NAVIGATION"
    const val TV_PLAYBACK = "TV_PLAYBACK"
    const val TV_VOLUME = "TV_VOLUME"
    const val TV_ON = "TV_ON"
    const val BLUETOOTH_CONNECTED = "BLUETOOTH_CONNECTED"
    const val BLUETOOTH_DISCONNECTED = "BLUETOOTH_DISCONNECTED"

    // communication (plan §31)
    const val CALL_RINGING = "CALL_RINGING"
    const val CALL_ANSWERED = "CALL_ANSWERED"
    const val CALL_ENDED = "CALL_ENDED"
    const val CALL_MISSED = "CALL_MISSED"

    /** Movement-ish signals, used by boundary detection. */
    val MOVEMENT = setOf(MOTION, WALKING, RUNNING, IN_VEHICLE, LOCATION_CHANGE, DEPARTED, ARRIVED)

    /** Signals that mean the person is demonstrably interacting with the phone. */
    val INTERACTION = setOf(SCREEN_ACTIVE, SCREEN_TIME, USER_PRESENT, APP_OPENED, APP_ACTIVE)
}

/** Where a signal came from. Also open-ended. */
object SignalSources {
    const val PHONE = "PHONE"
    const val ACTIVITY_RECOGNITION = "ACTIVITY_RECOGNITION"
    const val LOCATION = "LOCATION"
    const val TV_REMOTE = "TV_REMOTE"
    const val TELEPHONY = "TELEPHONY"
    const val NOTIFICATION = "NOTIFICATION"
    const val BLUETOOTH = "BLUETOOTH"
    const val SIMULATOR = "SIMULATOR"
}

/**
 * One immutable fact about the device at a moment in time (plan §3: "Raw signals
 * should generally not be rewritten when a semantic interpretation changes").
 */
data class Signal(
    val timestamp: Long,
    val type: String,
    val source: String = SignalSources.PHONE,
    /** Type-specific payload — minutes of screen time, an app id, a remote key. */
    val value: String? = null,
    /** How sure we are the signal itself happened, not what it means. */
    val confidence: Float = 1f,
    val metadata: String? = null
) {
    val isMovement: Boolean get() = type in SignalTypes.MOVEMENT
    val isInteraction: Boolean get() = type in SignalTypes.INTERACTION
    val minutes: Int? get() = value?.toIntOrNull()
}

// ---------------------------------------------------------------------------
// Activity catalogue
// ---------------------------------------------------------------------------

/**
 * Maps the stored activity code to something a person can read.
 *
 * Codes are strings so a future release can persist an activity this build has
 * never heard of; [labelFor] degrades to a readable fallback instead of failing.
 * Where a code corresponds to an existing [ActivityType], [toActivityType] hands
 * back the enum so the timeline, colours and lenses keep working unchanged.
 */
object ActivityCatalog {

    const val SLEEP = "SLEEP"
    const val WAKE = "WAKE"
    const val WORK = "WORK"
    const val MEETING = "MEETING"
    const val LUNCH = "LUNCH"
    const val TEA_BREAK = "TEA_BREAK"
    const val BREAK = "BREAK"
    const val INTERRUPTION = "INTERRUPTION"
    const val WATCHING_TV = "WATCHING_TV"
    const val PHONE_CALL = "PHONE_CALL"
    const val COMMUTE = "COMMUTE"
    const val EXERCISE = "EXERCISE"
    const val PERSONAL = "PERSONAL"
    const val UNKNOWN = "UNKNOWN"

    private val toEnum = mapOf(
        SLEEP to ActivityType.SLEEP,
        WAKE to ActivityType.WAKE_TRANSITION,
        WORK to ActivityType.WORK,
        MEETING to ActivityType.MEETING,
        LUNCH to ActivityType.LUNCH,
        TEA_BREAK to ActivityType.TEA_BREAK,
        BREAK to ActivityType.TEA_BREAK,
        INTERRUPTION to ActivityType.INTERRUPTION,
        WATCHING_TV to ActivityType.WATCHING_TV,
        PHONE_CALL to ActivityType.PHONE_CALL,
        COMMUTE to ActivityType.COMMUTE,
        EXERCISE to ActivityType.EXERCISE,
        PERSONAL to ActivityType.PERSONAL
    )

    fun toActivityType(code: String): ActivityType = toEnum[code] ?: ActivityType.UNKNOWN

    fun codeFor(type: ActivityType): String =
        toEnum.entries.firstOrNull { it.value == type }?.key ?: type.name

    fun labelFor(code: String): String =
        toEnum[code]?.label ?: code.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }

    fun iconFor(code: String): String = toEnum[code]?.icon ?: "❔"
}

// ---------------------------------------------------------------------------
// Engine version
// ---------------------------------------------------------------------------

/**
 * Stamped on every event the engine generates (plan §23) so a future release can
 * answer "which version decided this?" and reprocess selectively.
 *
 * Bump [CURRENT] whenever a change would produce a different answer from the
 * same raw signals.
 */
object InferenceEngine {
    const val CURRENT = "2.0.0"

    /** True when [version] predates [CURRENT] and its output is worth recomputing. */
    fun isStale(version: String?): Boolean = version != null && version != CURRENT
}
