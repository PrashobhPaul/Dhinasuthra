package com.dhinasuthra.app.intelligence

import com.dhinasuthra.app.core.TimeUtils
import com.dhinasuthra.app.core.database.AppStateDao
import com.dhinasuthra.app.core.database.AppStateEntity
import com.dhinasuthra.app.core.database.DhinaSuthraDatabase
import com.dhinasuthra.app.core.database.PlaceEntity
import com.dhinasuthra.app.core.model.ActivityKind
import com.dhinasuthra.app.core.model.ContextType
import com.dhinasuthra.app.core.model.DayType
import com.dhinasuthra.app.core.model.PlaceCategory
import com.dhinasuthra.app.core.model.SensorSignalType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant

/**
 * The bridge between stored signals and the intelligence layer.
 *
 * Everything the UI shows is derived here, on demand, from raw evidence — there
 * is no second copy of the truth to drift out of sync. Reconstructions are cached
 * in memory and invalidated whenever the underlying data changes.
 */
class TimeIntelligenceRepository(
    private val db: DhinaSuthraDatabase,
    private val timetableStore: TimetableStore,
    private val correctionStore: CorrectionStore
) {

    /** Bumped on every write so screens can recompose off derived data. */
    val revision = MutableStateFlow(0L)

    private val mutex = Mutex()
    private var cache: Snapshot? = null

    data class Snapshot(
        val today: DayReconstruction,
        val history: List<DayReconstruction>,
        val patterns: PatternIndex,
        val rhythm: RhythmScorer.RhythmScore?,
        val deviations: List<AnomalyEngine.Deviation>,
        val insights: List<Insight>,
        val timetables: List<Timetable>,
        val activeReport: TimetableAdherence.Report?,
        val nowMin: Int,
        val observedDays: Int,
        val builtForDay: Long,
        val builtAt: Long,
        val historyDays: Int
    ) {
        fun day(epochDay: Long): DayReconstruction? = history.firstOrNull { it.epochDay == epochDay }
    }

    companion object {
        /** How far back the analytics window reaches by default. */
        const val DEFAULT_HISTORY_DAYS = 120
        private const val CACHE_TTL_MS = 60_000L
    }

    fun invalidate() {
        cache = null
        revision.value = revision.value + 1
    }

    suspend fun snapshot(historyDays: Int = DEFAULT_HISTORY_DAYS, force: Boolean = false): Snapshot =
        mutex.withLock {
            val today = TimeUtils.epochDay()
            val cached = cache
            if (!force && cached != null && cached.builtForDay == today &&
                cached.historyDays >= historyDays &&
                System.currentTimeMillis() - cached.builtAt < CACHE_TTL_MS
            ) {
                return cached
            }
            val built = build(today, historyDays)
            cache = built
            built
        }

    /** A single day, reconstructed on its own (used by Timeline day navigation). */
    suspend fun day(epochDay: Long, patterns: PatternIndex): DayReconstruction =
        reconstruct(epochDay, epochDay, patterns).firstOrNull() ?: DayReconstruction.empty(epochDay)

    private suspend fun build(today: Long, historyDays: Int): Snapshot {
        val from = today - historyDays + 1
        val priors = priorBands(timetableStore.load())

        // Pass 1 — reconstruct with only user-stated priors, so patterns can be learned.
        val firstPass = reconstruct(from, today, PatternIndex.of(priors))
        // Pass 2 — reconstruct again, now informed by what pass 1 taught us.
        val patterns = PatternEngine.build(firstPass, today, priors)
        val days = reconstruct(from, today, patterns)
        val refinedPatterns = PatternEngine.build(days, today, priors)

        val todayDay = days.lastOrNull { it.epochDay == today } ?: DayReconstruction.empty(today, nowMin())
        val nowMin = nowMin()
        val rhythm = RhythmScorer.score(todayDay, refinedPatterns, nowMin)
        val deviations = AnomalyEngine.evaluate(todayDay, refinedPatterns, nowMin)
        val insights = NarrativeEngine.compose(todayDay, days, refinedPatterns, deviations, rhythm, nowMin)
        val timetables = timetableStore.load()
        val active = timetables.firstOrNull { it.appliesTo(today) }
        val report = active?.let {
            TimetableAdherence.evaluate(it, todayDay, nowMin, refinedPatterns)
        }

        return Snapshot(
            today = todayDay,
            history = days,
            patterns = refinedPatterns,
            rhythm = rhythm,
            deviations = deviations,
            insights = insights,
            timetables = timetables,
            activeReport = report,
            nowMin = nowMin,
            observedDays = days.count { it.knownMin > 60 },
            builtForDay = today,
            builtAt = System.currentTimeMillis(),
            historyDays = historyDays
        )
    }

    private fun nowMin(): Int = TimeUtils.minuteOfDay(Instant.now())

    // -----------------------------------------------------------------------
    // Evidence loading
    // -----------------------------------------------------------------------

    private suspend fun reconstruct(fromDay: Long, toDay: Long, patterns: PatternIndex): List<DayReconstruction> {
        val fromMs = TimeUtils.dayStart(fromDay - 1).toEpochMilli()   // -1 day for the sleep onset window
        val toMs = TimeUtils.dayEnd(toDay).toEpochMilli()
        val segments = db.contextEventDao().overlapping(fromMs, toMs)
        val raw = db.rawSensorEventDao().between(fromMs, toMs)
        val places = db.placeDao().all().associateBy { it.id }
        val corrections = correctionStore.load()
        val today = TimeUtils.epochDay()

        return (fromDay..toDay).map { day ->
            val evidence = evidenceFor(day, today, segments, raw, places, patterns, corrections)
            DayBuilder.build(evidence)
        }
    }

    private fun evidenceFor(
        day: Long,
        today: Long,
        segments: List<com.dhinasuthra.app.core.database.ContextEventEntity>,
        raw: List<com.dhinasuthra.app.core.database.RawSensorEventEntity>,
        places: Map<Long, PlaceEntity>,
        patterns: PatternIndex,
        corrections: List<Correction>
    ): DayEvidence {
        val dayStart = TimeUtils.dayStart(day).toEpochMilli()
        val dayEnd = TimeUtils.dayEnd(day).toEpochMilli()
        val livedUntil = if (day >= today) nowMin() else 1440

        fun minuteOf(ts: Long): Int = ((ts - dayStart) / 60000L).toInt()

        val stays = segments
            .filter { it.startTime < dayEnd && (it.endTime ?: System.currentTimeMillis()) > dayStart }
            .filter { it.contextType != ContextType.TRAVEL }
            .map { s ->
                val place = s.placeId?.let { places[it] }
                LocationReconciler.RawStay(
                    startMin = minuteOf(maxOf(s.startTime, dayStart)).coerceIn(0, 1440),
                    endMin = minuteOf(minOf(s.endTime ?: minOf(System.currentTimeMillis(), dayEnd), dayEnd)).coerceIn(0, 1440),
                    location = locationTypeOf(s.contextType, place?.category),
                    placeId = s.placeId,
                    placeName = place?.name,
                    confidence = s.confidence,
                    visitDays = place?.visitDays ?: 99
                )
            }
            .filter { it.endMin > it.startMin }

        val daySamples = raw
            .filter { it.type == SensorSignalType.SCREEN_SAMPLE && it.timestamp in dayStart until dayEnd }
            .map {
                DeviceSample(
                    minuteOfDay = minuteOf(it.timestamp).coerceIn(0, 1439),
                    interactive = it.interactive,
                    charging = it.charging,
                    lat = it.lat, lon = it.lon, accuracyM = it.accuracyM
                )
            }
            .sortedBy { it.minuteOfDay }

        val prevStart = TimeUtils.dayStart(day - 1).toEpochMilli()
        val previousEvening = raw
            .filter { it.type == SensorSignalType.SCREEN_SAMPLE && it.timestamp in prevStart until dayStart }
            .map {
                DeviceSample(
                    minuteOfDay = (((it.timestamp - prevStart) / 60000L).toInt()).coerceIn(0, 1439),
                    interactive = it.interactive,
                    charging = it.charging
                )
            }
            .sortedBy { it.minuteOfDay }

        val movements = movementSpans(
            raw.filter {
                (it.type == SensorSignalType.ACTIVITY_ENTER || it.type == SensorSignalType.ACTIVITY_EXIT) &&
                    it.timestamp in dayStart until dayEnd
            }.map { Triple(minuteOf(it.timestamp), it.activity ?: ActivityKind.UNKNOWN, it.type == SensorSignalType.ACTIVITY_ENTER) },
            livedUntil
        )

        val presences = LocationReconciler.reconcile(stays, movements, livedUntil)

        return DayEvidence(
            epochDay = day,
            dayType = TimeUtils.dayType(day),
            livedUntilMin = livedUntil,
            presences = presences,
            samples = daySamples,
            movements = movements,
            previousEveningSamples = previousEvening,
            patterns = patterns,
            corrections = corrections
        )
    }

    private fun movementSpans(events: List<Triple<Int, ActivityKind, Boolean>>, livedUntil: Int): List<MovementSpan> {
        val out = mutableListOf<MovementSpan>()
        var openKind: ActivityKind? = null
        var openAt = 0
        for ((minute, kind, enter) in events.sortedBy { it.first }) {
            if (enter) {
                openKind?.let { if (minute > openAt) out += MovementSpan(openAt, minute, it) }
                openKind = kind
                openAt = minute
            } else if (openKind == kind) {
                if (minute > openAt) out += MovementSpan(openAt, minute, kind)
                openKind = null
            }
        }
        openKind?.let { if (livedUntil > openAt) out += MovementSpan(openAt, livedUntil, it) }
        return out
    }

    private fun locationTypeOf(context: ContextType, category: PlaceCategory?): LocationType = when (context) {
        ContextType.HOME -> LocationType.HOME
        ContextType.OFFICE -> LocationType.OFFICE
        ContextType.TRAVEL -> LocationType.TRANSIT
        ContextType.KNOWN_PLACE -> when (category) {
            PlaceCategory.GYM -> LocationType.GYM
            PlaceCategory.RESTAURANT -> LocationType.RESTAURANT
            PlaceCategory.FRIEND, PlaceCategory.RELATIVE -> LocationType.FRIEND
            PlaceCategory.HOME -> LocationType.HOME
            PlaceCategory.OFFICE -> LocationType.OFFICE
            else -> LocationType.OTHER_KNOWN
        }
        ContextType.UNKNOWN_STAY, ContextType.UNKNOWN -> LocationType.UNKNOWN
    }

    /** PAT-13 — routine entries become priors, clearly flagged as stated rather than observed. */
    private fun priorBands(timetables: List<Timetable>): List<ActivityBand> {
        val out = mutableListOf<ActivityBand>()
        for (t in timetables) {
            val dayTypes = buildList {
                if (t.days.any { it in 1..5 }) add(DayType.WEEKDAY)
                if (t.days.any { it in 6..7 }) add(DayType.WEEKEND)
            }
            for (entry in t.entries) {
                for (dt in dayTypes) {
                    if (out.any { it.activity == entry.activity && it.dayType == dt }) continue
                    val start = PatternEngine.normalise(entry.activity, entry.targetStartMin)
                    val tol = entry.toleranceMin.coerceAtLeast(10)
                    out += ActivityBand(
                        activity = entry.activity,
                        dayType = dt,
                        typicalStartMin = start,
                        p10 = start - tol, p25 = start - tol / 2,
                        p75 = start + tol / 2, p90 = start + tol,
                        typicalDurationMin = entry.plannedDurationMin,
                        observations = 0,
                        confidence = 0.5f,
                        lastObservedDay = TimeUtils.epochDay(),
                        lifecycle = PatternLifecycle.EMERGING,
                        isPrior = true
                    )
                }
            }
        }
        return out
    }
}

/** Routines live in the existing key/value table — no schema change, no JSON dependency. */
class TimetableStore(private val dao: AppStateDao) {
    private var cached: List<Timetable>? = null

    suspend fun load(): List<Timetable> =
        cached ?: TimetableCodec.decode(dao.get(KEY)).also { cached = it }

    suspend fun save(timetables: List<Timetable>) {
        cached = timetables
        dao.put(AppStateEntity(KEY, TimetableCodec.encode(timetables)))
    }

    suspend fun upsert(timetable: Timetable) {
        val current = load().filterNot { it.id == timetable.id }
        save(current + timetable)
    }

    suspend fun delete(id: String) = save(load().filterNot { it.id == id })

    companion object {
        private const val KEY = "timetables_v1"
    }
}

/** User corrections (spec §42) — personal rule adaptation, stored as plain text. */
class CorrectionStore(private val dao: AppStateDao) {
    private var cached: List<Correction>? = null

    suspend fun load(): List<Correction> =
        cached ?: CorrectionCodec.decode(dao.get(KEY)).also { cached = it }

    suspend fun add(correction: Correction) {
        val kept = load()
            .filterNot {
                it.epochDay == correction.epochDay &&
                    it.startMin < correction.endMin && correction.startMin < it.endMin
            }
            .takeLast(MAX_STORED)
        val next = kept + correction
        cached = next
        dao.put(AppStateEntity(KEY, CorrectionCodec.encode(next)))
    }

    suspend fun clear() {
        cached = emptyList()
        dao.put(AppStateEntity(KEY, ""))
    }

    companion object {
        private const val KEY = "corrections_v1"
        private const val MAX_STORED = 400
    }
}

object CorrectionCodec {
    private const val HEADER = "DSC1"

    fun encode(items: List<Correction>): String = buildString {
        appendLine(HEADER)
        for (c in items) {
            appendLine(
                listOf(
                    c.epochDay, c.startMin, c.endMin, c.activity.name,
                    c.location.name, c.dayType.name, c.createdAt
                ).joinToString("|")
            )
        }
    }

    fun decode(raw: String?): List<Correction> {
        if (raw.isNullOrBlank()) return emptyList()
        val lines = raw.lines().filter { it.isNotBlank() }
        if (lines.firstOrNull() != HEADER) return emptyList()
        return lines.drop(1).mapNotNull { line ->
            val p = line.split("|")
            if (p.size < 7) return@mapNotNull null
            val activity = runCatching { ActivityType.valueOf(p[3]) }.getOrNull() ?: return@mapNotNull null
            val location = runCatching { LocationType.valueOf(p[4]) }.getOrNull() ?: return@mapNotNull null
            val dayType = runCatching { DayType.valueOf(p[5]) }.getOrNull() ?: return@mapNotNull null
            Correction(
                epochDay = p[0].toLongOrNull() ?: return@mapNotNull null,
                startMin = p[1].toIntOrNull() ?: return@mapNotNull null,
                endMin = p[2].toIntOrNull() ?: return@mapNotNull null,
                activity = activity,
                location = location,
                dayType = dayType,
                createdAt = p[6].toLongOrNull() ?: 0L
            )
        }
    }
}
