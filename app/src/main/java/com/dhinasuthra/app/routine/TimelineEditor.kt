package com.dhinasuthra.app.routine

import com.dhinasuthra.app.core.TimeUtils
import com.dhinasuthra.app.core.database.RoutineEventDao
import com.dhinasuthra.app.core.database.RoutineEventEntity
import com.dhinasuthra.app.core.model.EventSource
import com.dhinasuthra.app.core.model.RoutineEventType

/**
 * Backdated data correction (experience spec §49A): manual events enter the SAME
 * event model as automatic data (§49A.2), carry MANUAL provenance via
 * EventSource.USER (§49A.3), are treated as user-confirmed (§49A.4), and every
 * edit triggers recalculation of the affected day + learned patterns (§49A.5/49A.6).
 */
class TimelineEditor(
    private val routineDao: RoutineEventDao,
    private val onDayChanged: suspend (epochDay: Long) -> Unit
) {

    sealed class Result {
        data object Ok : Result()
        data class Invalid(val reason: String) : Result()
    }

    /** Add a manual event for any historical or current day (§49A.9). */
    suspend fun addEvent(
        epochDay: Long,
        type: RoutineEventType,
        minuteOfDay: Int,
        durationMin: Int?
    ): Result {
        if (epochDay > TimeUtils.epochDay()) return Result.Invalid("Future days can't be edited yet.")
        if (minuteOfDay !in 0..1439) return Result.Invalid("Time must be within the day.")
        if (durationMin != null && durationMin <= 0) return Result.Invalid("Duration must be positive.")
        // §49K: impossible-timeline guard — the same event type twice within its window.
        val existing = routineDao.forDay(epochDay)
        val ts = TimeUtils.instantAt(epochDay, minuteOfDay).toEpochMilli()
        val clash = existing.any {
            it.eventType == type && kotlin.math.abs(it.timestamp - ts) < 20 * 60 * 1000L
        }
        if (clash) return Result.Invalid("A ${type.name.lowercase().replace('_', ' ')} already exists near that time.")
        routineDao.insert(
            RoutineEventEntity(
                epochDay = epochDay, eventType = type, timestamp = ts,
                durationMin = durationMin, placeId = null,
                confidence = 1f,                    // user-confirmed (§49A.4)
                source = EventSource.USER
            )
        )
        onDayChanged(epochDay)
        return Result.Ok
    }

    /** Remove an event. Manual events are removed outright; inferred events are
     *  suppressed by re-derivation only if the user removes their source data —
     *  for M1, deleting an inferred event removes it until the next rollup
     *  re-derives, so we convert the ask into a user-visible constraint. */
    suspend fun deleteEvent(event: RoutineEventEntity): Result {
        if (event.source != EventSource.USER) {
            return Result.Invalid("Automatic events are re-derived from sensing. Edit or pause tracking instead.")
        }
        routineDao.deleteById(event.id)
        onDayChanged(event.epochDay)
        return Result.Ok
    }
}
