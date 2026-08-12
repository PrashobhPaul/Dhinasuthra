package com.dhinasuthra.app.core.model

/**
 * Core domain contracts for DhinaSuthra.
 *
 * These are the production contracts (execution directive: no throwaway architecture).
 * Every layer — sensing, context, routine, reminders, analytics, UI — speaks these types.
 */

/** Semantic category of a learned place (product.md §8.1). */
enum class PlaceCategory {
    HOME, OFFICE, CHURCH, FRIEND, RELATIVE, RESTAURANT, GYM, SCHOOL, OTHER, UNLABELED
}

/** What the context engine believes is happening right now (architecture.md §23). */
enum class ContextType {
    HOME, OFFICE, KNOWN_PLACE, TRAVEL, UNKNOWN_STAY, UNKNOWN
}

/** Movement state derived from Activity Recognition (product.md §7.2). */
enum class ActivityKind {
    STILL, WALKING, RUNNING, ON_BICYCLE, IN_VEHICLE, UNKNOWN
}

/** Routine events inferred from context transitions (product.md §10.3). */
enum class RoutineEventType {
    WAKE, START_DAY, LEAVE_HOME, TRAVEL, ARRIVE_OFFICE, WORK,
    SHORT_BREAK, LUNCH, LEAVE_OFFICE, ARRIVE_HOME, SOCIAL_VISIT, SLEEP, OTHER
}

/** Weekday/weekend routine differentiation (product.md §11.3). Clusters expand in M2. */
enum class DayType { WEEKDAY, WEEKEND }

/** Raw signal types recorded by the sensor layer. */
enum class SensorSignalType {
    LOCATION, GEOFENCE_ENTER, GEOFENCE_EXIT, ACTIVITY_ENTER, ACTIVITY_EXIT,
    SCREEN_SAMPLE, CHARGING_SAMPLE, BOOT, TIMEZONE_CHANGE
}

/** Where a record came from — real device sensing, user correction, or the simulator. */
enum class EventSource { SENSOR, USER, SIMULATED }

/** Reminder escalation levels (product.md §16). */
enum class ReminderSeverity { GENTLE, REMINDER, SIGNIFICANT }

/** How the user responded to a reminder (product.md §19). */
enum class ReminderResponse { NONE, SNOOZED, DISMISSED, INTENTIONAL, DONE }

/** Notification privacy on the lock screen (architecture.md §75). */
enum class NotificationPrivacy { FULL, MINIMAL }

data class LatLon(val lat: Double, val lon: Double)

/** Robust time-of-day statistics for a routine event (product.md §11.2). */
data class Quantiles(
    val median: Int,
    val p10: Int,
    val p25: Int,
    val p75: Int,
    val p90: Int
) {
    val iqr: Int get() = p75 - p25
}

/** Every meaningful inference carries confidence (product.md §35). */
data class Inference<T>(val value: T, val confidence: Float)
