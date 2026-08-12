package com.dhinasuthra.app.routine

import com.dhinasuthra.app.core.TimeUtils
import com.dhinasuthra.app.core.database.ContextEventEntity
import com.dhinasuthra.app.core.database.PlaceEntity
import com.dhinasuthra.app.core.database.RoutinePatternEntity
import com.dhinasuthra.app.core.model.ContextType
import com.dhinasuthra.app.core.model.DayType
import com.dhinasuthra.app.core.model.RoutineEventType
import com.dhinasuthra.app.core.time.TimeProvider

/**
 * Greeting Engine (experience spec §35–36): preferredName + device-local time +
 * current context + routine state → a greeting whose every secondary line comes
 * from actual local state. Low-confidence inference is never presented as
 * certainty (§2.11) — hence "probably", "usually", "around".
 */
class GreetingEngine(private val time: TimeProvider) {

    data class Greeting(val salutation: String, val contextLine: String?)

    fun compose(
        preferredName: String,
        openContext: ContextEventEntity?,
        currentPlace: PlaceEntity?,
        patterns: List<RoutinePatternEntity>
    ): Greeting {
        val hour = time.localTime().hour
        val base = when (hour) {
            in 5..11 -> "Good morning"
            in 12..16 -> "Good afternoon"
            in 17..20 -> "Good evening"
            else -> "Good night"
        }
        val salutation = if (preferredName.isBlank()) base else "$base, $preferredName"
        return Greeting(salutation, contextLine(openContext, currentPlace, patterns))
    }

    private fun contextLine(
        open: ContextEventEntity?,
        place: PlaceEntity?,
        patterns: List<RoutinePatternEntity>
    ): String? {
        val nowMin = time.minuteOfDay()
        val dayType = TimeUtils.dayType(time.epochDay())

        // Near sleep window? (§36 "close to your usual sleep window")
        patterns.firstOrNull { it.eventType == RoutineEventType.SLEEP && it.dayType == dayType }
            ?.let { sleep ->
                val sleepStart = RoutineStats.denormalize(sleep.medianMin)
                val delta = ((sleepStart - nowMin) + 1440) % 1440
                if (delta in 0..45) return "You're close to your usual sleep window."
            }

        if (open == null) return null
        val dwellMin = ((time.now().toEpochMilli() - open.startTime) / 60000L).toInt()
        return when (open.contextType) {
            ContextType.TRAVEL ->
                if (dwellMin >= 3) "You're on the move. Your journey started ${TimeUtils.formatDurationMin(dwellMin)} ago."
                else "You're on the move."
            ContextType.HOME -> homeLine(nowMin, dayType, patterns)
            ContextType.OFFICE ->
                if (dwellMin >= 30) "You're at ${place?.name ?: "Office"}. You've been here ${TimeUtils.formatDurationMin(dwellMin)}."
                else "You're at ${place?.name ?: "Office"}."
            ContextType.KNOWN_PLACE -> "You're at ${place?.name ?: "a familiar place"}."
            ContextType.UNKNOWN_STAY -> "You've paused somewhere new."
            ContextType.UNKNOWN -> null
        }
    }

    private fun homeLine(nowMin: Int, dayType: DayType, patterns: List<RoutinePatternEntity>): String {
        val leave = patterns.firstOrNull {
            it.eventType == RoutineEventType.LEAVE_HOME && it.dayType == dayType
        }
        if (leave != null) {
            val t = RoutineStats.denormalize(leave.medianMin)
            if (nowMin < t + 90) {
                return "You're at Home. Your day usually starts around ${TimeUtils.formatMinuteOfDay(t)}."
            }
        }
        return "You're at Home."
    }
}
