package com.dhinasuthra.app.activity

import android.content.Context
import com.dhinasuthra.app.core.DsLog
import com.dhinasuthra.app.core.TimeUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The activity-intelligence façade (plan §33).
 *
 * It owns three things and nothing else: the signal sources that feed the
 * engine, the repository that stores what they see, and the decision about
 * *when* to re-run detection. Everything interesting happens in the detectors,
 * which know nothing about Android, and in the repository, which knows nothing
 * about inference.
 *
 * Reprocessing is deliberately conservative. Days are recomputed when their
 * signals change, when the engine version moves on from what produced them, or
 * when the user corrects something (a correction changes what has been learned,
 * which changes what everything *after* it should look like). Days the user has
 * confirmed keep their answers regardless.
 */
class ActivityIntelligence(
    private val appContext: Context,
    val repository: ActivityRepository,
    private val sources: DeviceSignalSourceRegistry = DeviceSignalSourceRegistry.default(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {

    private val lock = Mutex()

    /** Start listening. Idempotent — safe to call from `onCreate` and `onResume`. */
    fun start() {
        sources.startAll(appContext) { signal ->
            scope.launch { runCatching { repository.record(signal) } }
        }
    }

    fun stop() = sources.stopAll(appContext)

    // -- reading ------------------------------------------------------------

    fun observeDay(epochDay: Long): Flow<List<StoredActivity>> =
        repository.observeEventsFor(epochDay)

    /** Provisional events from the last week, newest first — the confirmation queue. */
    fun observePending(): Flow<List<StoredActivity>> =
        repository.observePending(TimeUtils.epochDay() - 7)

    suspend fun evidenceFor(eventId: Long): List<EvidenceItem> = repository.evidenceFor(eventId)

    // -- recomputation ------------------------------------------------------

    /**
     * Recompute a day. Serialised, because two overlapping runs would each
     * delete the other's provisional rows.
     */
    suspend fun refresh(epochDay: Long = TimeUtils.epochDay()): List<StoredActivity> =
        lock.withLock {
            runCatching { repository.reprocess(epochDay) }
                .onFailure { DsLog.e("activity reprocess failed for day $epochDay", it) }
                .getOrDefault(emptyList())
        }

    /**
     * Bring recent history up to date with the current engine (plan §22, §23).
     *
     * Called after an upgrade: an engine that now understands charger unplugs
     * should get to apply that to the last fortnight, not only to tomorrow. It
     * touches provisional events only — [ActivityRepository.reprocess] cannot do
     * otherwise — so nothing the user confirmed under the old engine moves.
     */
    suspend fun refreshRecent(days: Int = 14) {
        val today = TimeUtils.epochDay()
        for (day in (today - days + 1)..today) refresh(day)
    }

    /**
     * Runs once per engine version, on the first launch after an update.
     *
     * Cheap when nothing changed (one key lookup), and the only thing that makes
     * "we improved wake detection" mean anything for the fortnight you already
     * lived through.
     */
    suspend fun refreshAfterEngineChange(days: Int = 14) {
        val seen = runCatching { repository.lastEngineVersion() }.getOrNull()
        if (seen == InferenceEngine.CURRENT) {
            refresh()
            return
        }
        DsLog.d("inference engine ${seen ?: "none"} → ${InferenceEngine.CURRENT}; reprocessing $days days")
        refreshRecent(days)
        runCatching { repository.markEngineVersion() }
    }

    // -- user actions (plan §20, §21) ---------------------------------------

    suspend fun confirm(eventId: Long) {
        repository.confirm(eventId)
        relearnAfterFeedback(eventId)
    }

    suspend fun correct(
        eventId: Long,
        activityCode: String? = null,
        start: Long? = null,
        end: Long? = null
    ) {
        repository.correct(eventId, activityCode = activityCode, start = start, end = end)
        relearnAfterFeedback(eventId)
    }

    suspend fun ignore(eventId: Long) = repository.ignore(eventId)

    suspend fun split(eventId: Long, at: Long) = repository.split(eventId, at)

    suspend fun merge(firstId: Long, secondId: Long) = repository.merge(firstId, secondId)

    /**
     * A correction is a teaching signal, so today's provisional events are
     * recomputed against what was just learned. Earlier days are left alone:
     * re-drawing last Tuesday because of something said about today would be
     * exactly the silent rewriting plan §22 rules out.
     */
    private suspend fun relearnAfterFeedback(eventId: Long) {
        runCatching { refresh(TimeUtils.epochDay()) }
            .onFailure { DsLog.w("could not refresh after feedback on $eventId") }
    }

    suspend fun learnedProfiles(): Map<String, LearnedProfile> = repository.learnedProfiles()
}
