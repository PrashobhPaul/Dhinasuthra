package com.dhinasuthra.app.analytics

import com.dhinasuthra.app.core.TimeUtils
import com.dhinasuthra.app.core.database.ContextEventDao
import com.dhinasuthra.app.core.database.DailySummaryDao
import com.dhinasuthra.app.core.database.DailySummaryEntity
import com.dhinasuthra.app.core.database.RawSensorEventDao
import com.dhinasuthra.app.core.database.RoutineEventDao
import com.dhinasuthra.app.core.database.RoutinePatternDao
import com.dhinasuthra.app.core.model.ContextType
import com.dhinasuthra.app.core.model.EventSource
import com.dhinasuthra.app.core.model.RoutineEventType
import com.dhinasuthra.app.places.PlaceLearner
import com.dhinasuthra.app.routine.AdherenceEngine
import com.dhinasuthra.app.routine.RoutineLearningEngine
import com.dhinasuthra.app.routine.RoutineStats
import java.time.Instant

/**
 * Analytics + rollup orchestration (product.md §20–§24).
 * dailyRollup() is idempotent and safe to run repeatedly for "today so far".
 */
class AnalyticsEngine(
    private val contextDao: ContextEventDao,
    private val routineDao: RoutineEventDao,
    private val patternDao: RoutinePatternDao,
    private val summaryDao: DailySummaryDao,
    private val rawDao: RawSensorEventDao,
    private val placeLearner: PlaceLearner,
    private val learningEngine: RoutineLearningEngine,
    private val adherence: AdherenceEngine
) {

    suspend fun dailyRollup(epochDay: Long, source: EventSource = EventSource.SENSOR) {
        placeLearner.learnFromDay(epochDay)
        learningEngine.deriveEventsForDay(epochDay, source)
        learningEngine.relearnPatterns(epochDay)
        writeSummary(epochDay)
    }

    suspend fun writeSummary(epochDay: Long) {
        val from = TimeUtils.dayStart(epochDay).toEpochMilli()
        val toBound = minOf(TimeUtils.dayEnd(epochDay).toEpochMilli(), System.currentTimeMillis())
        val segs = contextDao.overlapping(from, toBound)

        var home = 0L; var work = 0L; var travel = 0L; var social = 0L; var other = 0L; var unknown = 0L
        for (s in segs) {
            val st = maxOf(s.startTime, from)
            val en = minOf(s.endTime ?: toBound, toBound)
            if (en <= st) continue
            val min = (en - st) / 60000L
            when (s.contextType) {
                ContextType.HOME -> home += min
                ContextType.OFFICE -> work += min
                ContextType.TRAVEL -> travel += min
                ContextType.KNOWN_PLACE -> social += min
                ContextType.UNKNOWN_STAY -> other += min
                ContextType.UNKNOWN -> unknown += min
            }
        }

        val dayEvents = routineDao.forDay(epochDay)
        val sleepEvent = dayEvents.firstOrNull { it.eventType == RoutineEventType.SLEEP }
        val wakeEvent = dayEvents.firstOrNull { it.eventType == RoutineEventType.WAKE }
        // Sleep minutes attributed to the wake day: last night's window duration.
        val prevNightSleep = routineDao.forDay(epochDay - 1)
            .firstOrNull { it.eventType == RoutineEventType.SLEEP }?.durationMin
            ?: sleepEvent?.durationMin ?: 0

        val dayType = TimeUtils.dayType(epochDay)
        val patterns = patternDao.all().filter { it.dayType == dayType }
        val actuals = dayEvents
            .groupBy { it.eventType }
            .mapValues { (type, evs) ->
                RoutineStats.normalize(
                    type,
                    TimeUtils.minuteOfDay(Instant.ofEpochMilli(evs.minOf { it.timestamp }))
                )
            }
        val nowMin =
            if (epochDay < TimeUtils.epochDay()) 26 * 60
            else TimeUtils.minuteOfDay(Instant.now())
        val match = adherence.routineMatch(patterns, actuals, nowMin)

        summaryDao.upsert(
            DailySummaryEntity(
                epochDay = epochDay,
                homeMin = home.toInt(), workMin = work.toInt(), travelMin = travel.toInt(),
                sleepMin = prevNightSleep, socialMin = social.toInt(),
                otherMin = other.toInt(), unknownMin = unknown.toInt(),
                routineMatch = match,
                wakeMinOfDay = wakeEvent?.let { TimeUtils.minuteOfDay(Instant.ofEpochMilli(it.timestamp)) },
                sleepStartMinOfDay = sleepEvent?.let { TimeUtils.minuteOfDay(Instant.ofEpochMilli(it.timestamp)) },
                computedAt = System.currentTimeMillis()
            )
        )
    }

    /** Retention (architecture.md §20): raw 60 days, closed context 180 days. */
    suspend fun prune(today: Long) {
        rawDao.pruneBefore(TimeUtils.dayStart(today - 60).toEpochMilli())
        contextDao.pruneBefore(TimeUtils.dayStart(today - 180).toEpochMilli())
    }
}
