package com.dhinasuthra.app.activity

import com.dhinasuthra.app.core.TimeUtils
import com.dhinasuthra.app.core.database.ActivityEventDao
import com.dhinasuthra.app.core.database.AppStateDao
import com.dhinasuthra.app.core.database.AppStateEntity
import com.dhinasuthra.app.core.database.ActivityEventEntity
import com.dhinasuthra.app.core.database.ActivityEvidenceDao
import com.dhinasuthra.app.core.database.ActivityEvidenceEntity
import com.dhinasuthra.app.core.database.DeviceSignalDao
import com.dhinasuthra.app.core.database.DeviceSignalEntity
import com.dhinasuthra.app.core.database.PlaceDao
import com.dhinasuthra.app.core.database.RawSensorEventDao
import com.dhinasuthra.app.core.model.PlaceCategory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Where detection meets storage (plan §19, §21, §22).
 *
 * Three rules govern everything in this file, and they are the reason it exists
 * as its own layer rather than being folded into the detectors:
 *
 * 1. **Raw observations are immutable.** Signals are appended, never edited.
 * 2. **Provisional inference may be recalculated; user truth may not.**
 *    [reprocess] deletes and rebuilds only `INFERRED` rows. A confirmed lunch
 *    survives every future engine, including ones that would have disagreed.
 * 3. **Corrections supersede, they don't erase.** [correct] writes a new row
 *    pointing back at the old one, and the original — with its evidence — stays
 *    exactly where it was (plan §21: "Do not erase the evidence").
 */
class ActivityRepository(
    private val eventDao: ActivityEventDao,
    private val evidenceDao: ActivityEvidenceDao,
    private val signalDao: DeviceSignalDao,
    private val rawDao: RawSensorEventDao,
    private val placeDao: PlaceDao,
    private val appStateDao: AppStateDao,
    private val registry: DetectorRegistry = DetectorRegistry.default(),
    private val weights: WeightConfig = WeightConfig(),
    private val now: () -> Long = System::currentTimeMillis
) {

    // -- raw signals --------------------------------------------------------

    /** Append one observation. Nothing here ever updates an existing row. */
    suspend fun record(signal: Signal) {
        signalDao.insert(
            DeviceSignalEntity(
                timestamp = signal.timestamp,
                signalType = signal.type,
                source = signal.source,
                value = signal.value,
                confidence = signal.confidence,
                metadata = signal.metadata
            )
        )
    }

    suspend fun recordAll(signals: List<Signal>) {
        if (signals.isEmpty()) return
        signalDao.insertAll(
            signals.map {
                DeviceSignalEntity(
                    timestamp = it.timestamp,
                    signalType = it.type,
                    source = it.source,
                    value = it.value,
                    confidence = it.confidence,
                    metadata = it.metadata
                )
            }
        )
    }

    suspend fun signalsFor(epochDay: Long, lookBackHours: Int = 6): SignalWindow {
        // Nights cross midnight, so a day's reasoning needs the tail of the one
        // before it. Six hours covers a wake window without dragging in yesterday.
        val from = TimeUtils.dayStart(epochDay).toEpochMilli() - lookBackHours * 3_600_000L
        val to = TimeUtils.dayEnd(epochDay).toEpochMilli()
        // Live signals *and* the sensor history the app already had. The bridge
        // is applied on read rather than written into `device_signals`, so raw
        // observations stay in exactly one place and stay immutable — and an
        // existing user's months of history are reasoned over from the first
        // launch after the update, not from the update forward.
        val live = signalDao.between(from, to).map { it.toSignal() }
        val bridged = SignalBridge.fromAll(rawDao.between(from, to))
        return SignalWindow(live + bridged)
    }

    // -- reading ------------------------------------------------------------

    suspend fun eventsFor(epochDay: Long): List<StoredActivity> =
        eventDao.forDay(epochDay).map { it.toStored() }

    fun observeEventsFor(epochDay: Long): Flow<List<StoredActivity>> =
        eventDao.observeForDay(epochDay).map { rows -> rows.map { it.toStored() } }

    /** The provisional events the timeline should offer to confirm. */
    fun observePending(sinceDay: Long, limit: Int = 20): Flow<List<StoredActivity>> =
        eventDao.observePending(sinceDay, limit).map { rows -> rows.map { it.toStored() } }

    suspend fun evidenceFor(eventId: Long): List<EvidenceItem> =
        evidenceDao.forEvent(eventId).map {
            EvidenceItem(it.timestamp, it.signalType, it.weight, it.detail)
        }

    // -- inference ----------------------------------------------------------

    /**
     * Recompute one day from its raw signals.
     *
     * Safe to call after any engine change, which is the whole point of plan
     * §22: version 2 can reach a better answer than version 1 did about a day in
     * March, because the raw signals from March are still there — but it can
     * only do so where the user hasn't already spoken.
     */
    suspend fun reprocess(epochDay: Long): List<StoredActivity> {
        val window = signalsFor(epochDay)
        if (window.isEmpty) return eventsFor(epochDay)

        val existing = eventDao.forDay(epochDay)
        val userTruth = existing.filter { ActivityStatus.of(it.status).isUserTruth }

        val context = DetectionContext(
            epochDay = epochDay,
            anchors = anchors(),
            profiles = learnedProfiles(),
            weights = weights,
            userTruth = userTruth.map { it.toCandidate() }
        )

        // Detectors are independent, so two of them describing the same gap is
        // expected rather than a bug — the resolver decides which one the
        // timeline shows, and user truth outranks all of them.
        val candidates = CandidateResolver.resolve(registry.run(window, context))
            .filterNot { candidate -> userTruth.any { it.overlaps(candidate) } }

        // Order matters: evidence first, because its delete is scoped by a
        // subquery over the events that are about to disappear.
        evidenceDao.clearProvisionalForDay(epochDay)
        eventDao.clearProvisionalForDay(epochDay)

        candidates.forEach { save(epochDay, it) }
        return eventsFor(epochDay)
    }

    private suspend fun save(epochDay: Long, candidate: ActivityCandidate): Long {
        val stamp = now()
        val id = eventDao.insert(
            ActivityEventEntity(
                epochDay = epochDay,
                activityType = candidate.activityCode,
                startTime = candidate.start,
                endTime = candidate.end,
                status = candidate.status.code,
                confidence = candidate.confidence,
                source = candidate.source,
                placeId = candidate.placeId,
                inferenceEngineVersion = InferenceEngine.CURRENT,
                createdAt = stamp,
                updatedAt = stamp,
                supersedesId = null,
                correctionReason = null
            )
        )
        if (candidate.evidence.isNotEmpty()) {
            evidenceDao.insertAll(
                candidate.evidence.map {
                    ActivityEvidenceEntity(
                        activityEventId = id,
                        signalType = it.signalType,
                        timestamp = it.timestamp,
                        weight = it.weight,
                        detail = it.detail
                    )
                }
            )
        }
        return id
    }

    // -- confirmation and correction (plan §20, §21) ------------------------

    /** The user said yes. From here on it is history, not a guess. */
    suspend fun confirm(eventId: Long) {
        val row = eventDao.byId(eventId) ?: return
        eventDao.update(
            row.copy(
                status = ActivityStatus.CONFIRMED.code,
                confidence = 1f,
                updatedAt = now(),
                correctionReason = CorrectionReasons.USER_CONFIRM
            )
        )
    }

    /**
     * The user changed something.
     *
     * The original row is *kept*, marked as superseded by the new one, so the
     * question "what did the app originally think, and what did I say instead?"
     * stays answerable — and so the correction can be used as a learning signal
     * with the thing it corrected still attached.
     */
    suspend fun correct(
        eventId: Long,
        activityCode: String? = null,
        start: Long? = null,
        end: Long? = null,
        placeId: Long? = null,
        reason: String = CorrectionReasons.USER_EDIT
    ): Long? {
        val original = eventDao.byId(eventId) ?: return null
        val stamp = now()
        val corrected = original.copy(
            id = 0,
            activityType = activityCode ?: original.activityType,
            startTime = start ?: original.startTime,
            endTime = end ?: original.endTime,
            status = ActivityStatus.CORRECTED.code,
            confidence = 1f,
            source = EventSources.USER,
            placeId = placeId ?: original.placeId,
            createdAt = stamp,
            updatedAt = stamp,
            supersedesId = original.id,
            correctionReason = reason
        )
        val newId = eventDao.insert(corrected)
        // The superseded original stays on disk; it simply stops being the one
        // the timeline draws.
        eventDao.update(original.copy(status = ActivityStatus.IGNORED.code, updatedAt = stamp))
        // Carry the evidence forward so the corrected event can still explain
        // where the engine's original suggestion came from.
        val evidence = evidenceDao.forEvent(original.id)
        if (evidence.isNotEmpty()) {
            evidenceDao.insertAll(evidence.map { it.copy(id = 0, activityEventId = newId) })
        }
        return newId
    }

    /** Not a thing that happened. Kept as a record of the engine being wrong. */
    suspend fun ignore(eventId: Long) {
        val row = eventDao.byId(eventId) ?: return
        eventDao.update(
            row.copy(
                status = ActivityStatus.IGNORED.code,
                updatedAt = now(),
                correctionReason = CorrectionReasons.USER_IGNORE
            )
        )
    }

    /** One activity becomes two at [at]. Both are user truth from the moment they exist. */
    suspend fun split(eventId: Long, at: Long): List<Long> {
        val row = eventDao.byId(eventId) ?: return emptyList()
        val end = row.endTime ?: return emptyList()
        if (at <= row.startTime || at >= end) return emptyList()
        val stamp = now()
        val first = eventDao.insert(
            row.copy(
                id = 0, endTime = at, status = ActivityStatus.CORRECTED.code,
                source = EventSources.USER, createdAt = stamp, updatedAt = stamp,
                supersedesId = row.id, correctionReason = CorrectionReasons.USER_SPLIT
            )
        )
        val second = eventDao.insert(
            row.copy(
                id = 0, startTime = at, status = ActivityStatus.CORRECTED.code,
                source = EventSources.USER, createdAt = stamp, updatedAt = stamp,
                supersedesId = row.id, correctionReason = CorrectionReasons.USER_SPLIT
            )
        )
        eventDao.update(row.copy(status = ActivityStatus.IGNORED.code, updatedAt = stamp))
        return listOf(first, second)
    }

    /** Two adjacent activities were really one. */
    suspend fun merge(firstId: Long, secondId: Long): Long? {
        val a = eventDao.byId(firstId) ?: return null
        val b = eventDao.byId(secondId) ?: return null
        val stamp = now()
        val merged = a.copy(
            id = 0,
            startTime = minOf(a.startTime, b.startTime),
            endTime = listOfNotNull(a.endTime, b.endTime).maxOrNull(),
            status = ActivityStatus.CORRECTED.code,
            source = EventSources.USER,
            createdAt = stamp, updatedAt = stamp,
            supersedesId = a.id,
            correctionReason = CorrectionReasons.USER_MERGE
        )
        val id = eventDao.insert(merged)
        eventDao.update(a.copy(status = ActivityStatus.IGNORED.code, updatedAt = stamp))
        eventDao.update(b.copy(status = ActivityStatus.IGNORED.code, updatedAt = stamp))
        return id
    }

    // -- learning (plan §13) ------------------------------------------------

    /**
     * What the user's confirmations have taught us, ready to feed back into the
     * next detection run.
     */
    suspend fun learnedProfiles(perActivity: Int = 60): Map<String, LearnedProfile> {
        val codes = listOf(
            ActivityCatalog.WAKE, ActivityCatalog.LUNCH, ActivityCatalog.TEA_BREAK,
            ActivityCatalog.WATCHING_TV, ActivityCatalog.BREAK, ActivityCatalog.MEETING
        )
        val occurrences = codes.flatMap { code ->
            eventDao.userTruthFor(code, perActivity).map { row ->
                ConfirmedOccurrence(
                    activityCode = row.activityType,
                    startMinuteOfDay = TimeUtils.minuteOfDay(java.time.Instant.ofEpochMilli(row.startTime)),
                    durationMin = row.endTime?.let { ((it - row.startTime) / 60_000L).toInt() } ?: 0,
                    placeId = row.placeId,
                    epochDay = row.epochDay
                )
            }
        }
        return LearningEngine.learn(occurrences)
    }

    private suspend fun anchors(): List<Anchor> {
        val home = placeDao.confirmedByCategory(PlaceCategory.HOME)
        val office = placeDao.confirmedByCategory(PlaceCategory.OFFICE)
        return listOfNotNull(
            home?.let { Anchor(DetectionContext.ANCHOR_HOME, it.id, it.name, it.radiusM) },
            // The desk anchor plan §9 asks for: for now the office place itself,
            // tightened to a desk-sized radius rather than the whole building.
            office?.let { Anchor(DetectionContext.ANCHOR_DESK, it.id, it.name, radiusM = 25f) }
        )
    }

    // -- engine versioning (plan §23) ---------------------------------------

    /** The engine version that last reprocessed history on this device. */
    suspend fun lastEngineVersion(): String? = appStateDao.get(KEY_ENGINE_VERSION)

    suspend fun markEngineVersion(version: String = InferenceEngine.CURRENT) {
        appStateDao.put(AppStateEntity(KEY_ENGINE_VERSION, version))
    }

    /** Raw signals age out on the same schedule as the rest of the sensor layer. */
    suspend fun prune(keepDays: Int = 60) {
        signalDao.pruneBefore(TimeUtils.dayStart(TimeUtils.epochDay() - keepDays).toEpochMilli())
    }

    companion object {
        const val KEY_ENGINE_VERSION = "activity.engine_version"
    }
}

// ---------------------------------------------------------------------------
// Row ↔ domain
// ---------------------------------------------------------------------------

/** An activity as it exists on disk, ready for the UI. */
data class StoredActivity(
    val id: Long,
    val epochDay: Long,
    val activityCode: String,
    val start: Long,
    val end: Long?,
    val status: ActivityStatus,
    val confidence: Float,
    val placeId: Long?,
    val engineVersion: String,
    val supersedesId: Long?
) {
    val label: String get() = ActivityCatalog.labelFor(activityCode)
    val icon: String get() = ActivityCatalog.iconFor(activityCode)
    val durationMin: Int get() = end?.let { ((it - start) / 60_000L).toInt() } ?: 0

    /** Only provisional events are worth asking about. */
    val awaitingAnswer: Boolean get() = status.isProvisional
}

private fun DeviceSignalEntity.toSignal() = Signal(
    timestamp = timestamp,
    type = signalType,
    source = source,
    value = value,
    confidence = confidence,
    metadata = metadata
)

private fun ActivityEventEntity.toStored() = StoredActivity(
    id = id,
    epochDay = epochDay,
    activityCode = activityType,
    start = startTime,
    end = endTime,
    status = ActivityStatus.of(status),
    confidence = confidence,
    placeId = placeId,
    engineVersion = inferenceEngineVersion,
    supersedesId = supersedesId
)

private fun ActivityEventEntity.toCandidate() = ActivityCandidate(
    activityCode = activityType,
    start = startTime,
    end = endTime,
    confidence = confidence,
    status = ActivityStatus.of(status),
    placeId = placeId,
    source = source
)

private fun ActivityEventEntity.overlaps(candidate: ActivityCandidate): Boolean {
    val myEnd = endTime ?: startTime
    val theirEnd = candidate.end ?: candidate.start
    return startTime < theirEnd && candidate.start < myEnd
}
