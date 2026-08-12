package com.dhinasuthra.app.places

import com.dhinasuthra.app.context.ContextEngine
import com.dhinasuthra.app.core.DsLog
import com.dhinasuthra.app.core.TimeUtils
import com.dhinasuthra.app.core.database.ContextEventDao
import com.dhinasuthra.app.core.database.PlaceDao
import com.dhinasuthra.app.core.database.PlaceEntity
import com.dhinasuthra.app.core.model.ContextType
import com.dhinasuthra.app.core.model.LatLon
import com.dhinasuthra.app.core.model.PlaceCategory

/**
 * Automatic place learning (product.md §8, architecture.md §25).
 *
 * Stay-points come from UNKNOWN_STAY context segments ≥ MIN_STAY. Segments whose
 * centroids fall within CLUSTER_RADIUS of an existing candidate merge into it;
 * otherwise a new candidate is created. Promotion follows the visit rules:
 *   1 visit → unknown, 3 distinct days → recurring candidate (suggest labeling),
 *   user confirmation → known place (geofenced).
 *
 * Home/Office hypothesis: the candidate that accumulates the most night minutes
 * (00–05) is proposed as HOME; the weekday 10–17 leader as OFFICE. Both remain
 * unconfirmed until the user agrees (§8.4).
 */
class PlaceLearner(
    private val placeDao: PlaceDao,
    private val contextDao: ContextEventDao
) {
    companion object {
        private const val MIN_STAY_MS = 20 * 60 * 1000L
        private const val CLUSTER_RADIUS_M = 200.0
        const val SUGGEST_AFTER_DAYS = 3
    }

    /** Process a day's closed segments into place candidates. Idempotency: callers run per-day rollups. */
    suspend fun learnFromDay(epochDay: Long) {
        val from = TimeUtils.dayStart(epochDay).toEpochMilli()
        val to = TimeUtils.dayEnd(epochDay).toEpochMilli()
        val stays = contextDao.overlapping(from, to).filter {
            it.contextType == ContextType.UNKNOWN_STAY &&
                it.endTime != null && (it.endTime - it.startTime) >= MIN_STAY_MS &&
                it.centroidLat != null && it.centroidLon != null
        }
        if (stays.isEmpty()) return

        val existing = placeDao.all().toMutableList()
        val touchedToday = mutableSetOf<Long>()
        for (stay in stays) {
            val c = LatLon(stay.centroidLat!!, stay.centroidLon!!)
            val match = existing.minByOrNull { ContextEngine.haversineMeters(c, LatLon(it.lat, it.lon)) }
                ?.takeIf { ContextEngine.haversineMeters(c, LatLon(it.lat, it.lon)) <= CLUSTER_RADIUS_M }
            if (match != null) {
                val firstToday = touchedToday.add(match.id)
                val updated = match.copy(
                    lastSeen = stay.endTime ?: stay.startTime,
                    visitCount = match.visitCount + 1,
                    visitDays = match.visitDays + if (firstToday) 1 else 0,
                    confidence = (match.confidence + 0.05f).coerceAtMost(0.9f)
                )
                placeDao.update(updated)
                existing[existing.indexOfFirst { it.id == match.id }] = updated
            } else {
                val id = placeDao.upsert(
                    PlaceEntity(
                        name = "New place", category = PlaceCategory.UNLABELED,
                        lat = c.lat, lon = c.lon, radiusM = 150f, confidence = 0.3f,
                        firstSeen = stay.startTime, lastSeen = stay.endTime ?: stay.startTime,
                        visitCount = 1, confirmed = false, visitDays = 1
                    )
                )
                touchedToday.add(id)
                existing.add(placeDao.byId(id)!!)
                DsLog.d("place candidate created")
            }
        }
        hypothesizeHomeOffice(epochDay)
    }

    /** Attach HOME/OFFICE hypotheses from where night vs. workday hours accumulate. */
    private suspend fun hypothesizeHomeOffice(uptoDay: Long) {
        if (placeDao.confirmedByCategory(PlaceCategory.HOME) != null &&
            placeDao.confirmedByCategory(PlaceCategory.OFFICE) != null
        ) return

        val from = TimeUtils.dayStart(uptoDay - 20).toEpochMilli()
        val to = TimeUtils.dayEnd(uptoDay).toEpochMilli()
        val segments = contextDao.overlapping(from, to)
            .filter { it.centroidLat != null && it.endTime != null }
        if (segments.isEmpty()) return

        val places = placeDao.all().filter { !it.confirmed || it.category == PlaceCategory.UNLABELED }
        if (places.isEmpty()) return

        val nightMin = HashMap<Long, Long>()
        val workMin = HashMap<Long, Long>()
        for (seg in segments) {
            val c = LatLon(seg.centroidLat!!, seg.centroidLon!!)
            val place = places.minByOrNull { ContextEngine.haversineMeters(c, LatLon(it.lat, it.lon)) }
                ?.takeIf { ContextEngine.haversineMeters(c, LatLon(it.lat, it.lon)) <= CLUSTER_RADIUS_M }
                ?: continue
            val startMin = TimeUtils.minuteOfDay(java.time.Instant.ofEpochMilli(seg.startTime))
            val durMin = (seg.endTime!! - seg.startTime) / 60000L
            if (startMin < 5 * 60 || startMin >= 23 * 60) nightMin.merge(place.id, durMin, Long::plus)
            if (startMin in (10 * 60)..(17 * 60) &&
                TimeUtils.dayType(TimeUtils.epochDay(java.time.Instant.ofEpochMilli(seg.startTime))) ==
                com.dhinasuthra.app.core.model.DayType.WEEKDAY
            ) workMin.merge(place.id, durMin, Long::plus)
        }

        if (placeDao.confirmedByCategory(PlaceCategory.HOME) == null) {
            nightMin.maxByOrNull { it.value }?.takeIf { it.value >= 240 }?.let { (id, _) ->
                placeDao.byId(id)?.takeIf { it.category == PlaceCategory.UNLABELED }?.let {
                    placeDao.update(it.copy(category = PlaceCategory.HOME, name = "Home", confidence = 0.7f))
                }
            }
        }
        if (placeDao.confirmedByCategory(PlaceCategory.OFFICE) == null) {
            workMin.maxByOrNull { it.value }?.takeIf { it.value >= 240 }?.let { (id, _) ->
                placeDao.byId(id)?.takeIf { it.category == PlaceCategory.UNLABELED }?.let {
                    placeDao.update(it.copy(category = PlaceCategory.OFFICE, name = "Office", confidence = 0.7f))
                }
            }
        }
    }

    /** Candidates recurring enough to ask the user about (§8.2 / §25). */
    suspend fun labelingSuggestions(): List<PlaceEntity> =
        placeDao.all().filter { !it.confirmed && it.visitDays >= SUGGEST_AFTER_DAYS }
}
