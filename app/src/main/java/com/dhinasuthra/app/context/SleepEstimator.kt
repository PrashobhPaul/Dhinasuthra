package com.dhinasuthra.app.context

import com.dhinasuthra.app.core.TimeUtils
import com.dhinasuthra.app.core.database.RawSensorEventDao
import com.dhinasuthra.app.core.model.SensorSignalType
import java.time.Instant

/**
 * Estimated sleep window (architecture.md §17) — explicitly NOT medical sleep
 * tracking. Fuses: last meaningful screen interaction in the evening window,
 * first meaningful interaction the next morning, charging state as a weak
 * auxiliary signal. Bounded by 15-minute sampling resolution in M1.
 */
class SleepEstimator(private val rawDao: RawSensorEventDao) {

    data class SleepWindow(
        val sleepStart: Instant,
        val wake: Instant,
        val confidence: Float
    ) {
        val durationMin: Int get() = ((wake.toEpochMilli() - sleepStart.toEpochMilli()) / 60000L).toInt()
    }

    /**
     * Estimate the sleep window that *ended* on [epochDay] (i.e. last night).
     * Searches 18:00 (day-1) → 13:00 (day) for the inactivity gap.
     */
    suspend fun estimateForNightEnding(epochDay: Long): SleepWindow? {
        val from = TimeUtils.dayStart(epochDay - 1).plusSeconds(18 * 3600)
        val to = TimeUtils.dayStart(epochDay).plusSeconds(13 * 3600)
        val samples = rawDao.between(from.toEpochMilli(), to.toEpochMilli())
            .filter { it.type == SensorSignalType.SCREEN_SAMPLE && it.interactive != null }
        if (samples.size < 4) return null

        // Longest run of non-interactive samples.
        var bestStart = -1L; var bestEnd = -1L; var bestLen = 0L
        var runStart = -1L; var lastTs = -1L; var chargingSeen = false; var runCharging = false
        for (s in samples) {
            if (s.interactive == false) {
                if (runStart < 0) { runStart = s.timestamp; runCharging = false }
                if (s.charging == true) runCharging = true
                lastTs = s.timestamp
            } else {
                if (runStart >= 0 && lastTs - runStart > bestLen) {
                    bestLen = lastTs - runStart; bestStart = runStart; bestEnd = s.timestamp
                    chargingSeen = runCharging
                }
                runStart = -1
            }
        }
        if (runStart >= 0 && lastTs - runStart > bestLen) {
            bestLen = lastTs - runStart; bestStart = runStart; bestEnd = lastTs; chargingSeen = runCharging
        }
        if (bestStart < 0 || bestLen < 3 * 3600 * 1000L) return null   // require ≥3h of quiet

        var conf = 0.6f
        if (chargingSeen) conf += 0.15f                                 // charging overnight (§7.5)
        if (bestLen in (5 * 3600 * 1000L)..(11 * 3600 * 1000L)) conf += 0.1f
        return SleepWindow(
            sleepStart = Instant.ofEpochMilli(bestStart),
            wake = Instant.ofEpochMilli(bestEnd),
            confidence = conf.coerceAtMost(0.9f)
        )
    }
}
