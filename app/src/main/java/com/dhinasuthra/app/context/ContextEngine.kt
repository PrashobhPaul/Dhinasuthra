package com.dhinasuthra.app.context

import com.dhinasuthra.app.core.DsLog
import com.dhinasuthra.app.core.database.ContextEventDao
import com.dhinasuthra.app.core.database.ContextEventEntity
import com.dhinasuthra.app.core.database.PlaceDao
import com.dhinasuthra.app.core.database.RawSensorEventDao
import com.dhinasuthra.app.core.database.RawSensorEventEntity
import com.dhinasuthra.app.core.model.ActivityKind
import com.dhinasuthra.app.core.model.ContextType
import com.dhinasuthra.app.core.model.EventSource
import com.dhinasuthra.app.core.model.LatLon
import com.dhinasuthra.app.core.model.PlaceCategory
import com.dhinasuthra.app.core.model.SensorSignalType
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Context fusion engine (architecture.md §23): converts raw signals into a
 * debounced, confidence-scored stream of ContextEvents.
 *
 *   HOME → POSSIBLE_TRAVEL → TRAVEL → OFFICE → …
 *
 * Design notes:
 *  - Every entry point takes an explicit timestamp so the simulator and unit
 *    tests drive the *same* production pipeline (product.md §58).
 *  - Debouncing (§24): a segment shorter than MIN_SEGMENT that reverts to the
 *    previous type is merged back instead of producing flapping transitions.
 *  - No sensor is universally authoritative (§11.2): type decisions combine
 *    geofence membership, last activity, and location distance to known places.
 */
class ContextEngine(
    private val placeDao: PlaceDao,
    private val contextDao: ContextEventDao,
    private val rawDao: RawSensorEventDao
) {
    private val mutex = Mutex()

    // In-memory mirror of the open segment; reloaded lazily after process death.
    private var open: ContextEventEntity? = null
    private var loaded = false

    // Latest fused signals.
    private var insidePlaceIds: MutableSet<Long> = mutableSetOf()
    private var lastActivity: ActivityKind = ActivityKind.UNKNOWN
    private var lastActivityAt: Instant = Instant.EPOCH
    private var lastLatLon: LatLon? = null

    companion object {
        private const val MIN_SEGMENT_MS = 4 * 60 * 1000L        // §24 hysteresis
        private const val NEAR_PLACE_METERS = 180.0
        private const val TRAVEL_ACTIVITY_HOLD_MS = 20 * 60 * 1000L

        fun haversineMeters(a: LatLon, b: LatLon): Double {
            val r = 6371000.0
            val dLat = Math.toRadians(b.lat - a.lat)
            val dLon = Math.toRadians(b.lon - a.lon)
            val s = sin(dLat / 2) * sin(dLat / 2) +
                    cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) *
                    sin(dLon / 2) * sin(dLon / 2)
            return 2 * r * atan2(sqrt(s), sqrt(1 - s))
        }
    }

    /** Drop all in-memory state (used when clearing data / reloading simulation). */
    suspend fun reset() = mutex.withLock {
        open = null; loaded = false
        insidePlaceIds.clear()
        lastActivity = ActivityKind.UNKNOWN
        lastLatLon = null
    }

    // -----------------------------------------------------------------------
    // Signal entry points
    // -----------------------------------------------------------------------

    suspend fun onGeofence(placeId: Long, enter: Boolean, at: Instant, source: EventSource = EventSource.SENSOR) {
        rawDao.insert(
            RawSensorEventEntity(
                timestamp = at.toEpochMilli(),
                type = if (enter) SensorSignalType.GEOFENCE_ENTER else SensorSignalType.GEOFENCE_EXIT,
                placeId = placeId, source = source
            )
        )
        mutex.withLock {
            ensureLoaded()
            if (enter) insidePlaceIds.add(placeId) else insidePlaceIds.remove(placeId)
            reevaluate(at, source)
        }
    }

    suspend fun onActivity(kind: ActivityKind, enter: Boolean, at: Instant, source: EventSource = EventSource.SENSOR) {
        rawDao.insert(
            RawSensorEventEntity(
                timestamp = at.toEpochMilli(),
                type = if (enter) SensorSignalType.ACTIVITY_ENTER else SensorSignalType.ACTIVITY_EXIT,
                activity = kind, source = source
            )
        )
        mutex.withLock {
            ensureLoaded()
            if (enter) { lastActivity = kind; lastActivityAt = at }
            else if (lastActivity == kind) { lastActivity = ActivityKind.UNKNOWN; lastActivityAt = at }
            reevaluate(at, source)
        }
    }

    /** Periodic fused sample: last known location + screen + charging (§16.5/§16.6). */
    suspend fun onSample(
        loc: LatLon?, accuracyM: Float?, interactive: Boolean, charging: Boolean,
        at: Instant, source: EventSource = EventSource.SENSOR
    ) {
        rawDao.insert(
            RawSensorEventEntity(
                timestamp = at.toEpochMilli(), type = SensorSignalType.SCREEN_SAMPLE,
                lat = loc?.lat, lon = loc?.lon, accuracyM = accuracyM,
                interactive = interactive, charging = charging, source = source
            )
        )
        mutex.withLock {
            ensureLoaded()
            if (loc != null) {
                lastLatLon = loc
                // Location can substitute for a missed geofence transition.
                val places = placeDao.confirmed()
                val nowInside = places.filter {
                    haversineMeters(loc, LatLon(it.lat, it.lon)) <= maxOf(it.radiusM.toDouble(), NEAR_PLACE_METERS)
                }.map { it.id }.toSet()
                if (nowInside.isNotEmpty() || accuracyM == null || accuracyM < 250f) {
                    insidePlaceIds = nowInside.toMutableSet()
                }
            }
            reevaluate(at, source)
        }
    }

    // -----------------------------------------------------------------------
    // State machine
    // -----------------------------------------------------------------------

    private suspend fun ensureLoaded() {
        if (!loaded) {
            open = contextDao.openEvent()
            loaded = true
        }
    }

    private suspend fun reevaluate(at: Instant, source: EventSource) {
        val decided = decide(at)
        val cur = open
        if (cur == null) {
            openSegment(decided.first, decided.second, decided.third, at, source)
            return
        }
        val sameType = cur.contextType == decided.first && cur.placeId == decided.second
        if (sameType) {
            // Refresh confidence upward only (evidence accumulates).
            if (decided.third > cur.confidence) {
                val updated = cur.copy(confidence = decided.third)
                contextDao.update(updated); open = updated
            }
            return
        }
        // Debounce: a very short segment that flaps back is merged (delete-by-revert).
        val curLenMs = at.toEpochMilli() - cur.startTime
        if (curLenMs < MIN_SEGMENT_MS) {
            val updated = cur.copy(contextType = decided.first, placeId = decided.second, confidence = decided.third)
            contextDao.update(updated); open = updated
            return
        }
        // Close current, open new.
        val closed = cur.copy(endTime = at.toEpochMilli(), centroidLat = lastLatLon?.lat, centroidLon = lastLatLon?.lon)
        contextDao.update(closed)
        openSegment(decided.first, decided.second, decided.third, at, source)
        DsLog.d("context ${cur.contextType} -> ${decided.first}")
    }

    private suspend fun openSegment(type: ContextType, placeId: Long?, conf: Float, at: Instant, source: EventSource) {
        val e = ContextEventEntity(
            startTime = at.toEpochMilli(), endTime = null, contextType = type,
            placeId = placeId, activity = lastActivity, confidence = conf,
            centroidLat = lastLatLon?.lat, centroidLon = lastLatLon?.lon, source = source
        )
        val id = contextDao.insert(e)
        open = e.copy(id = id)
    }

    /** Fuse current signals into (type, placeId, confidence). */
    private suspend fun decide(at: Instant): Triple<ContextType, Long?, Float> {
        // 1. Inside a confirmed place → that place wins.
        val placeId = insidePlaceIds.firstOrNull()
        if (placeId != null) {
            val place = placeDao.byId(placeId)
            if (place != null) {
                val type = when (place.category) {
                    PlaceCategory.HOME -> ContextType.HOME
                    PlaceCategory.OFFICE -> ContextType.OFFICE
                    else -> ContextType.KNOWN_PLACE
                }
                var conf = 0.75f
                if (lastActivity == ActivityKind.STILL || lastActivity == ActivityKind.WALKING) conf += 0.15f
                return Triple(type, placeId, conf.coerceAtMost(0.97f))
            }
        }
        // 2. Sustained locomotion outside known places → TRAVEL.
        val moving = lastActivity in setOf(ActivityKind.IN_VEHICLE, ActivityKind.ON_BICYCLE, ActivityKind.RUNNING, ActivityKind.WALKING)
        val activityFresh = at.toEpochMilli() - lastActivityAt.toEpochMilli() < TRAVEL_ACTIVITY_HOLD_MS
        if (moving && activityFresh) {
            val conf = if (lastActivity == ActivityKind.IN_VEHICLE) 0.9f else 0.7f
            return Triple(ContextType.TRAVEL, null, conf)
        }
        // 3. Still at an unrecognized location → UNKNOWN_STAY (feeds place learning).
        if (lastLatLon != null) {
            return Triple(ContextType.UNKNOWN_STAY, null, 0.55f)
        }
        return Triple(ContextType.UNKNOWN, null, 0.3f)
    }
}
