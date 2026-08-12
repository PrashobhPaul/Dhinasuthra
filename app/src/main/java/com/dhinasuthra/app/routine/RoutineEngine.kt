package com.dhinasuthra.app.routine

import com.dhinasuthra.app.context.SleepEstimator
import com.dhinasuthra.app.core.TimeUtils
import com.dhinasuthra.app.core.database.ContextEventDao
import com.dhinasuthra.app.core.database.RoutineEventDao
import com.dhinasuthra.app.core.database.RoutineEventEntity
import com.dhinasuthra.app.core.database.RoutinePatternDao
import com.dhinasuthra.app.core.database.RoutinePatternEntity
import com.dhinasuthra.app.core.model.ContextType
import com.dhinasuthra.app.core.model.DayType
import com.dhinasuthra.app.core.model.EventSource
import com.dhinasuthra.app.core.model.RoutineEventType
import java.time.Instant

/**
 * Routine Learning Engine — the heart of DhinaSuthra (product.md §11).
 * Derives semantic RoutineEvents from the day's ContextEvents, then learns
 * per-(event, dayType) robust time distributions with confidence.
 */
class RoutineLearningEngine(
    private val contextDao: ContextEventDao,
    private val routineDao: RoutineEventDao,
    private val patternDao: RoutinePatternDao,
    private val sleepEstimator: SleepEstimator
) {

    /** Re-derive inferred routine events for a day (idempotent; user events preserved). */
    suspend fun deriveEventsForDay(epochDay: Long, source: EventSource = EventSource.SENSOR) {
        routineDao.clearInferredForDay(epochDay)
        val from = TimeUtils.dayStart(epochDay).toEpochMilli()
        val to = TimeUtils.dayEnd(epochDay).toEpochMilli()
        val segs = contextDao.overlapping(from, to)
            .filter { it.endTime == null || it.endTime > from }
            .sortedBy { it.startTime }
        val events = mutableListOf<RoutineEventEntity>()

        fun add(type: RoutineEventType, ts: Long, dur: Int? = null, placeId: Long? = null, conf: Float = 0.8f) {
            if (ts in from until to) events += RoutineEventEntity(
                epochDay = epochDay, eventType = type, timestamp = ts,
                durationMin = dur, placeId = placeId, confidence = conf, source = source
            )
        }

        // Transition scan (architecture.md §23 examples).
        var officeEnteredAt: Long? = null
        var midOfficeExitAt: Long? = null
        for (i in segs.indices) {
            val cur = segs[i]
            val prev = segs.getOrNull(i - 1)
            when {
                prev?.contextType == ContextType.HOME && cur.contextType == ContextType.TRAVEL ->
                    add(RoutineEventType.LEAVE_HOME, cur.startTime, conf = minOf(prev.confidence, cur.confidence))
                cur.contextType == ContextType.OFFICE && prev?.contextType != ContextType.OFFICE -> {
                    if (officeEnteredAt == null) {
                        add(RoutineEventType.ARRIVE_OFFICE, cur.startTime, placeId = cur.placeId, conf = cur.confidence)
                    } else {
                        // Returned to office after a midday excursion → that excursion was LUNCH or a break.
                        val awayStart = midOfficeExitAt
                        if (awayStart != null) {
                            val awayMin = ((cur.startTime - awayStart) / 60000L).toInt()
                            val startMinOfDay = TimeUtils.minuteOfDay(Instant.ofEpochMilli(awayStart))
                            val type = if (startMinOfDay in (11 * 60)..(15 * 60) && awayMin in 15..120)
                                RoutineEventType.LUNCH else RoutineEventType.SHORT_BREAK
                            add(type, awayStart, dur = awayMin, conf = 0.7f)
                        }
                    }
                    officeEnteredAt = cur.startTime
                    midOfficeExitAt = null
                }
                prev?.contextType == ContextType.OFFICE && cur.contextType != ContextType.OFFICE ->
                    midOfficeExitAt = cur.startTime
                prev?.contextType == ContextType.TRAVEL && cur.contextType == ContextType.HOME ->
                    add(RoutineEventType.ARRIVE_HOME, cur.startTime, placeId = cur.placeId, conf = cur.confidence)
                cur.contextType == ContextType.KNOWN_PLACE && prev?.contextType != ContextType.KNOWN_PLACE &&
                    cur.endTime != null && cur.endTime - cur.startTime >= 30 * 60 * 1000L ->
                    add(
                        RoutineEventType.SOCIAL_VISIT, cur.startTime,
                        dur = ((cur.endTime - cur.startTime) / 60000L).toInt(),
                        placeId = cur.placeId, conf = 0.65f
                    )
            }
        }
        // Final office departure = last office→non-office transition of the day.
        val lastOfficeExit = run {
            var t: Long? = null
            for (i in 1 until segs.size) {
                if (segs[i - 1].contextType == ContextType.OFFICE && segs[i].contextType != ContextType.OFFICE) {
                    t = segs[i].startTime
                }
            }
            t
        }
        // Only a departure if the user did not come back afterwards.
        if (lastOfficeExit != null && segs.lastOrNull { it.contextType == ContextType.OFFICE }?.endTime != null &&
            segs.last { it.contextType == ContextType.OFFICE }.endTime!! <= lastOfficeExit
        ) {
            add(RoutineEventType.LEAVE_OFFICE, lastOfficeExit, conf = 0.85f)
        }

        // Sleep window that ended this morning (§17) → WAKE today, SLEEP recorded on start day.
        sleepEstimator.estimateForNightEnding(epochDay)?.let { w ->
            add(RoutineEventType.WAKE, w.wake.toEpochMilli(), conf = w.confidence)
            val sleepDay = TimeUtils.epochDay(w.sleepStart)
            if (sleepDay == epochDay) {
                add(RoutineEventType.SLEEP, w.sleepStart.toEpochMilli(), dur = w.durationMin, conf = w.confidence)
            } else {
                routineDao.insert(
                    RoutineEventEntity(
                        epochDay = sleepDay, eventType = RoutineEventType.SLEEP,
                        timestamp = w.sleepStart.toEpochMilli(), durationMin = w.durationMin,
                        confidence = w.confidence, source = source
                    )
                )
            }
        }

        events.forEach { routineDao.insert(it) }
    }

    /** Relearn all patterns from up to the last 90 days of routine events (§26/§27). */
    suspend fun relearnPatterns(today: Long) {
        val events = routineDao.betweenDays(today - 90, today)
        val grouped = events
            .filter { it.eventType != RoutineEventType.TRAVEL && it.eventType != RoutineEventType.OTHER }
            .groupBy { Pair(it.eventType, TimeUtils.dayType(it.epochDay)) }
        val now = System.currentTimeMillis()
        for ((key, list) in grouped) {
            val (type, dayType) = key
            // One observation per day per event (first occurrence) keeps distributions honest.
            val perDay = list.groupBy { it.epochDay }.map { (_, dayEvents) -> dayEvents.minByOrNull { it.timestamp }!! }
            if (perDay.size < 3) continue
            val minutes = perDay.map {
                RoutineStats.normalize(type, TimeUtils.minuteOfDay(Instant.ofEpochMilli(it.timestamp)))
            }
            var q = RoutineStats.quantiles(minutes)
            var conf = RoutineStats.confidence(perDay.size, q.iqr)

            // §49F — Bayesian-style weighted update: a user-defined routine template
            // acts as PRIOR_PSEUDO_OBS pseudo-observations whose influence decays as
            // real observations accumulate. Never abruptly replaced, never presented
            // as observed data.
            val existing = patternDao.byKey(type, dayType)
            val prior = existing?.priorMin
            if (prior != null) {
                val n = perDay.size
                val blendedMedian = ((q.median * n + prior * PRIOR_PSEUDO_OBS).toFloat() /
                    (n + PRIOR_PSEUDO_OBS)).toInt()
                val shift = blendedMedian - q.median
                q = q.copy(
                    median = blendedMedian,
                    p10 = q.p10 + shift, p25 = q.p25 + shift,
                    p75 = q.p75 + shift, p90 = q.p90 + shift
                )
                conf = maxOf(conf, 0.5f)
            }

            val durations = perDay.mapNotNull { it.durationMin }
            patternDao.upsert(
                RoutinePatternEntity(
                    eventType = type, dayType = dayType,
                    medianMin = q.median, p10 = q.p10, p25 = q.p25, p75 = q.p75, p90 = q.p90,
                    typicalDurationMin = if (durations.size >= 3) durations.sorted()[durations.size / 2] else null,
                    observationCount = perDay.size,
                    confidence = conf,
                    updatedAt = now,
                    priorMin = prior,
                    priorSource = existing?.priorSource
                )
            )
        }
    }

    /**
     * §49C routine template: seed a pattern from the user's stated usual time.
     * Stored with observationCount = 0 and priorSource — usable immediately for
     * Next Up and reminders, replaced gradually by observed evidence (§49D).
     */
    suspend fun seedUserPrior(type: RoutineEventType, dayType: DayType, minuteOfDay: Int) {
        val norm = RoutineStats.normalize(type, minuteOfDay)
        val existing = patternDao.byKey(type, dayType)
        if (existing != null && existing.observationCount >= 3) {
            // Real observations exist — record the prior for future blending only.
            patternDao.upsert(existing.copy(priorMin = norm, priorSource = PRIOR_USER_TEMPLATE))
            return
        }
        patternDao.upsert(
            RoutinePatternEntity(
                eventType = type, dayType = dayType,
                medianMin = norm, p10 = norm - 25, p25 = norm - 12, p75 = norm + 12, p90 = norm + 25,
                typicalDurationMin = null,
                observationCount = 0,
                confidence = 0.5f,
                updatedAt = System.currentTimeMillis(),
                priorMin = norm,
                priorSource = PRIOR_USER_TEMPLATE
            )
        )
    }

    companion object {
        const val PRIOR_PSEUDO_OBS = 5
        const val PRIOR_USER_TEMPLATE = "USER_TEMPLATE"
    }
}

/**
 * Routine Adherence Engine (product.md §14): expected vs actual → Routine Match.
 * Not a health score, not a productivity score — consistency with the user's own history.
 */
class AdherenceEngine {

    /**
     * @param patterns learned patterns for the day's type (confidence-filtered by caller)
     * @param actualMinutes map of eventType → normalized first-occurrence minute today
     * @param nowMinute normalized "now" so unpassed windows don't count against the day
     * @return 0..100 match, or null if nothing is scoreable yet
     */
    fun routineMatch(
        patterns: List<RoutinePatternEntity>,
        actualMinutes: Map<RoutineEventType, Int>,
        nowMinute: Int
    ): Int? {
        var weight = 0f
        var score = 0f
        for (p in patterns) {
            if (p.confidence < 0.45f || p.observationCount < 4) continue
            val windowLo = p.p10 - 30
            val windowHi = p.p90 + 30
            val actual = actualMinutes[p.eventType]
            when {
                actual != null -> {
                    weight += p.confidence
                    score += p.confidence * matchScore(actual, p)
                }
                nowMinute > windowHi -> {          // window passed, event missing
                    weight += p.confidence
                    // partial credit 0 — but only if not the very start of learning
                    score += 0f
                }
                else -> Unit                       // window not yet passed: skip
            }
            // windowLo intentionally unused for scoring asymmetry (early ≠ deviation)
            if (windowLo == Int.MIN_VALUE) return null
        }
        if (weight <= 0f) return null
        return ((score / weight) * 100).toInt().coerceIn(0, 100)
    }

    /** 1.0 inside [p25,p75], linear fall-off to 0 at ±(p90−p10)+45 outside median. */
    private fun matchScore(actual: Int, p: RoutinePatternEntity): Float {
        if (actual in p.p25..p.p75) return 1f
        val spread = ((p.p90 - p.p10) + 45).coerceAtLeast(45)
        val dist = when {
            actual < p.p25 -> p.p25 - actual
            else -> actual - p.p75
        }
        return (1f - dist.toFloat() / spread).coerceIn(0f, 1f)
    }
}
