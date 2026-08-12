package com.dhinasuthra.app.simulate

import android.content.Context
import com.dhinasuthra.app.DhinaSuthraApp
import com.dhinasuthra.app.core.DsLog
import com.dhinasuthra.app.core.TimeUtils
import com.dhinasuthra.app.core.database.PlaceEntity
import com.dhinasuthra.app.core.model.ActivityKind
import com.dhinasuthra.app.core.model.DayType
import com.dhinasuthra.app.core.model.EventSource
import com.dhinasuthra.app.core.model.LatLon
import com.dhinasuthra.app.core.model.PlaceCategory
import java.time.Instant
import kotlin.random.Random

/**
 * Sensor simulator (product.md §53 Phase 1 / §58): generates a realistic
 * multi-week signal stream — geofence transitions, activity transitions, and
 * 15-minute screen/charging samples — and feeds it through the SAME
 * ContextEngine → rollup → learning → adherence pipeline as real sensing.
 *
 * There are no hard-coded dashboard numbers anywhere: everything visible in the
 * UI after simulation was inferred by the production engines.
 *
 * Debug builds only (BuildConfig.SIMULATOR_ENABLED).
 */
class DaySimulator(private val context: Context) {

    private val rng = Random(20260810)

    // Anjar, Gujarat area coordinates for plausible home/office separation.
    private val homeLoc = LatLon(23.1126, 70.0263)
    private val officeLoc = LatLon(23.1330, 70.0680)
    private val cafeLoc = LatLon(23.1335, 70.0672)     // near office → lunch excursion
    private val relativeLoc = LatLon(23.0980, 70.0150)

    suspend fun loadDays(days: Int = 21) {
        val app = DhinaSuthraApp.get(context)
        val c = app.container
        c.contextEngine.reset()

        // Seed confirmed Home/Office so geofence signals have targets — mirrors a
        // user who confirmed the app's hypothesis after the learning phase started.
        val placeDao = c.db.placeDao()
        val now = System.currentTimeMillis()
        val homeId = placeDao.upsert(
            PlaceEntity(name = "Home", category = PlaceCategory.HOME, lat = homeLoc.lat, lon = homeLoc.lon,
                radiusM = 150f, confidence = 0.9f, firstSeen = now, lastSeen = now,
                visitCount = 0, confirmed = true, visitDays = 0)
        )
        val officeId = placeDao.upsert(
            PlaceEntity(name = "Office", category = PlaceCategory.OFFICE, lat = officeLoc.lat, lon = officeLoc.lon,
                radiusM = 150f, confidence = 0.9f, firstSeen = now, lastSeen = now,
                visitCount = 0, confirmed = true, visitDays = 0)
        )

        val today = TimeUtils.epochDay()
        for (d in (today - days + 1)..today) {
            when {
                TimeUtils.dayType(d) == DayType.WEEKDAY -> simulateWorkday(d, homeId, officeId, d == today)
                else -> simulateWeekend(d, homeId, d == today)
            }
            c.analyticsEngine.dailyRollup(d, EventSource.SIMULATED)
        }
        c.reminderScheduler.planToday()
        c.settings.simulatedDataLoaded = true
        DsLog.d("simulation loaded: $days days")
    }

    private fun jitter(min: Int) = rng.nextInt(-min, min + 1)

    private suspend fun simulateWorkday(day: Long, homeId: Long, officeId: Long, isToday: Boolean) {
        val c = DhinaSuthraApp.get(context).container
        fun at(minute: Int): Instant = TimeUtils.instantAt(day, minute)
        val src = EventSource.SIMULATED

        val wake = 7 * 60 + 25 + jitter(14)
        val leaveHome = 8 * 60 + 40 + jitter(12)
        val arriveOffice = leaveHome + 40 + jitter(6)
        val lunchOut = 13 * 60 + 15 + jitter(15)
        val lunchBack = lunchOut + 35 + jitter(8)
        val leaveOffice = 19 * 60 + 30 + jitter(20)
        val arriveHome = leaveOffice + 42 + jitter(6)
        val sleep = 23 * 60 + 45 + jitter(18)

        val nowMin = TimeUtils.minuteOfDay(Instant.now())
        fun done(minute: Int) = !isToday || minute <= nowMin

        // Night → morning at home: quiet samples, then interaction from wake.
        var m = 0
        while (m < leaveHome && done(m)) {
            c.contextEngine.onSample(homeLoc, 20f, interactive = m >= wake && rng.nextFloat() < 0.7f,
                charging = m < wake, at = at(m), source = src)
            m += 15
        }
        if (done(leaveHome)) {
            c.contextEngine.onGeofence(homeId, enter = false, at = at(leaveHome), source = src)
            c.contextEngine.onActivity(ActivityKind.IN_VEHICLE, true, at(leaveHome + 3), src)
            var t = leaveHome + 10
            while (t < arriveOffice && done(t)) {
                c.contextEngine.onSample(between(homeLoc, officeLoc, (t - leaveHome) / (arriveOffice - leaveHome).toFloat()),
                    30f, interactive = false, charging = false, at = at(t), source = src)
                t += 10
            }
        }
        if (done(arriveOffice)) {
            c.contextEngine.onActivity(ActivityKind.IN_VEHICLE, false, at(arriveOffice - 2), src)
            c.contextEngine.onGeofence(officeId, enter = true, at = at(arriveOffice), source = src)
            c.contextEngine.onActivity(ActivityKind.STILL, true, at(arriveOffice + 5), src)
            var t = arriveOffice + 15
            while (t < lunchOut && done(t)) {
                c.contextEngine.onSample(officeLoc, 25f, rng.nextFloat() < 0.5f, false, at(t), src)
                t += 15
            }
        }
        if (done(lunchOut)) {
            c.contextEngine.onGeofence(officeId, enter = false, at = at(lunchOut), source = src)
            c.contextEngine.onActivity(ActivityKind.WALKING, true, at(lunchOut + 1), src)
            c.contextEngine.onSample(cafeLoc, 25f, false, false, at(lunchOut + 10), src)
        }
        if (done(lunchBack)) {
            c.contextEngine.onActivity(ActivityKind.WALKING, false, at(lunchBack - 2), src)
            c.contextEngine.onGeofence(officeId, enter = true, at = at(lunchBack), source = src)
            c.contextEngine.onActivity(ActivityKind.STILL, true, at(lunchBack + 4), src)
            var t = lunchBack + 15
            while (t < leaveOffice && done(t)) {
                c.contextEngine.onSample(officeLoc, 25f, rng.nextFloat() < 0.5f, false, at(t), src)
                t += 15
            }
        }
        if (done(leaveOffice)) {
            c.contextEngine.onGeofence(officeId, enter = false, at = at(leaveOffice), source = src)
            c.contextEngine.onActivity(ActivityKind.IN_VEHICLE, true, at(leaveOffice + 3), src)
        }
        if (done(arriveHome)) {
            c.contextEngine.onActivity(ActivityKind.IN_VEHICLE, false, at(arriveHome - 2), src)
            c.contextEngine.onGeofence(homeId, enter = true, at = at(arriveHome), source = src)
            var t = arriveHome + 15
            while (t < 1439 && done(t)) {
                c.contextEngine.onSample(homeLoc, 20f, interactive = t < sleep, charging = t >= sleep, at = at(t), source = src)
                t += 15
            }
        }
    }

    private suspend fun simulateWeekend(day: Long, homeId: Long, isToday: Boolean) {
        val c = DhinaSuthraApp.get(context).container
        fun at(minute: Int): Instant = TimeUtils.instantAt(day, minute)
        val src = EventSource.SIMULATED
        val wake = 8 * 60 + 30 + jitter(25)
        val outingStart = 11 * 60 + jitter(30)
        val outingEnd = outingStart + 150 + jitter(30)
        val sleep = 24 * 60 + 10 + jitter(20)
        val nowMin = TimeUtils.minuteOfDay(Instant.now())
        fun done(minute: Int) = !isToday || minute <= nowMin

        var m = 0
        while (m < outingStart && done(m)) {
            c.contextEngine.onSample(homeLoc, 20f, m >= wake && rng.nextFloat() < 0.6f, m < wake, at(m), src)
            m += 15
        }
        if (done(outingStart)) {
            c.contextEngine.onGeofence(homeId, false, at(outingStart), src)
            c.contextEngine.onActivity(ActivityKind.IN_VEHICLE, true, at(outingStart + 2), src)
            c.contextEngine.onActivity(ActivityKind.IN_VEHICLE, false, at(outingStart + 25), src)
            var t = outingStart + 30
            while (t < outingEnd && done(t)) {
                c.contextEngine.onSample(relativeLoc, 25f, rng.nextFloat() < 0.4f, false, at(t), src)
                t += 15
            }
        }
        if (done(outingEnd)) {
            c.contextEngine.onActivity(ActivityKind.IN_VEHICLE, true, at(outingEnd), src)
            c.contextEngine.onActivity(ActivityKind.IN_VEHICLE, false, at(outingEnd + 25), src)
            c.contextEngine.onGeofence(homeId, true, at(outingEnd + 27), src)
            val sleepMin = minOf(sleep, 1435)
            var t = outingEnd + 40
            while (t < 1439 && done(t)) {
                c.contextEngine.onSample(homeLoc, 20f, interactive = t < sleepMin, charging = t >= sleepMin, at = at(t), source = src)
                t += 15
            }
        }
    }

    private fun between(a: LatLon, b: LatLon, f: Float): LatLon =
        LatLon(a.lat + (b.lat - a.lat) * f, a.lon + (b.lon - a.lon) * f)
}
