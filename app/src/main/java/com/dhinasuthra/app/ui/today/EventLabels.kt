package com.dhinasuthra.app.ui.today

import androidx.compose.ui.graphics.Color
import com.dhinasuthra.app.core.model.RoutineEventType
import com.dhinasuthra.app.intelligence.ActivityType
import com.dhinasuthra.app.ui.theme.DsTokens

/**
 * Labels for the legacy [RoutineEventType] vocabulary, which the reminder rules and
 * settings still speak. Colours resolve through the V2 activity palette so a
 * reminder for lunch is the same green as lunch everywhere else in the app.
 */

fun eventTitle(t: RoutineEventType): String = when (t) {
    RoutineEventType.WAKE -> "Waking up"
    RoutineEventType.START_DAY -> "Starting the day"
    RoutineEventType.LEAVE_HOME -> "Leaving home"
    RoutineEventType.TRAVEL -> "Travel"
    RoutineEventType.ARRIVE_OFFICE -> "Arriving at the office"
    RoutineEventType.WORK -> "Work"
    RoutineEventType.SHORT_BREAK -> "Break"
    RoutineEventType.LUNCH -> "Lunch"
    RoutineEventType.LEAVE_OFFICE -> "Leaving the office"
    RoutineEventType.ARRIVE_HOME -> "Arriving home"
    RoutineEventType.SOCIAL_VISIT -> "Visit"
    RoutineEventType.SLEEP -> "Sleep"
    RoutineEventType.OTHER -> "Moment"
}

fun eventSubtitle(t: RoutineEventType): String = when (t) {
    RoutineEventType.WAKE -> "At home"
    RoutineEventType.LEAVE_HOME -> "Your day started"
    RoutineEventType.ARRIVE_OFFICE -> "Work mode"
    RoutineEventType.LUNCH -> "Away from the desk"
    RoutineEventType.SHORT_BREAK -> "A brief pause"
    RoutineEventType.LEAVE_OFFICE -> "Day wrapping up"
    RoutineEventType.ARRIVE_HOME -> "Back home"
    RoutineEventType.SOCIAL_VISIT -> "A known place"
    RoutineEventType.SLEEP -> "Estimated sleep window"
    else -> "Part of your thread"
}

fun activityFor(t: RoutineEventType): ActivityType = when (t) {
    RoutineEventType.WAKE, RoutineEventType.START_DAY -> ActivityType.WAKE_TRANSITION
    RoutineEventType.LEAVE_HOME, RoutineEventType.TRAVEL -> ActivityType.COMMUTE
    RoutineEventType.ARRIVE_OFFICE, RoutineEventType.WORK -> ActivityType.WORK
    RoutineEventType.SHORT_BREAK -> ActivityType.TEA_BREAK
    RoutineEventType.LUNCH -> ActivityType.LUNCH
    RoutineEventType.LEAVE_OFFICE, RoutineEventType.ARRIVE_HOME -> ActivityType.COMMUTE
    RoutineEventType.SOCIAL_VISIT -> ActivityType.LEISURE
    RoutineEventType.SLEEP -> ActivityType.SLEEP
    RoutineEventType.OTHER -> ActivityType.UNKNOWN
}

fun eventColor(t: RoutineEventType): Color = DsTokens.colorFor(activityFor(t))
