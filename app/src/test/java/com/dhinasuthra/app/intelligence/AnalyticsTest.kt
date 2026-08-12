package com.dhinasuthra.app.intelligence

import com.dhinasuthra.app.core.model.DayType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lenses, patterns, statistics and routines — the arithmetic the screens display.
 */
class AnalyticsTest {

    private companion object {
        /** A fixed "today" so the fixtures never depend on when the suite runs. */
        const val BASE = 20000L
    }

    private fun day(
        epochDay: Long,
        vararg spans: Triple<IntRange, ActivityType, LocationType>
    ): DayReconstruction = DayReconstruction.build(
        epochDay,
        spans.mapIndexed { i, (range, activity, location) ->
            TimeEpisode(
                id = "$epochDay-$i", epochDay = epochDay,
                startMin = range.first, endMin = range.last,
                activity = activity, location = location,
                confidence = 0.8f, status = EpisodeStatus.INFERRED
            )
        }
    )

    /** Patterns are learned per day type, so the fixtures must be real weekdays. */
    private fun weekdays(count: Int, endingAt: Long = BASE): List<Long> {
        val out = mutableListOf<Long>()
        var d = endingAt
        while (out.size < count) {
            if (com.dhinasuthra.app.core.TimeUtils.dayType(d) == DayType.WEEKDAY) out += d
            d -= 1
        }
        return out.sorted()
    }

    private fun aWeekday(): Long = weekdays(1).first()

    private fun typicalWorkday(epochDay: Long, lunchStart: Int = 790) = day(
        epochDay,
        Triple(0..450, ActivityType.SLEEP, LocationType.HOME),
        Triple(450..520, ActivityType.PERSONAL, LocationType.HOME),
        Triple(520..560, ActivityType.COMMUTE, LocationType.TRANSIT),
        Triple(560..lunchStart, ActivityType.WORK, LocationType.OFFICE),
        Triple(lunchStart..(lunchStart + 40), ActivityType.LUNCH, LocationType.OFFICE),
        Triple((lunchStart + 40)..1080, ActivityType.WORK, LocationType.OFFICE),
        Triple(1080..1120, ActivityType.COMMUTE, LocationType.TRANSIT),
        Triple(1120..1440, ActivityType.PERSONAL, LocationType.HOME)
    )

    // -- Lenses --------------------------------------------------------------

    @Test
    fun `activity and location lenses each total the same day`() {
        val d = typicalWorkday(BASE)
        val byActivity = LensProjector.activityLens(d).sumOf { it.minutes }
        val byLocation = LensProjector.locationLens(d).sumOf { it.minutes }

        assertEquals(1440, byActivity)
        assertEquals(1440, byLocation)
        assertTrue(LensProjector.reconciles(d))
    }

    @Test
    fun `the activity lens contains no place and the location lens no activity`() {
        val d = typicalWorkday(BASE)
        val activityLabels = LensProjector.activityLens(d).map { it.label }
        val locationLabels = LensProjector.locationLens(d).map { it.label }

        assertTrue(activityLabels.none { it == "Home" || it == "Office" || it == "Transit" })
        assertTrue(locationLabels.none { it == "Sleep" || it == "Work" || it == "Lunch" })
    }

    @Test
    fun `activity at location is the only place the two combine`() {
        val groups = LensProjector.activityAtLocation(typicalWorkday(BASE))
        val office = groups.first { it.location == LocationType.OFFICE }
        assertTrue(office.activities.any { it.activity == ActivityType.WORK })
        assertTrue(office.activities.any { it.activity == ActivityType.LUNCH })
        assertEquals(office.totalMinutes, office.activities.sumOf { it.minutes })
    }

    @Test
    fun `presence and activity are reported as different numbers`() {
        val pv = LensProjector.presenceVsActivity(
            listOf(typicalWorkday(BASE)), LocationType.OFFICE, ActivityType.WORK
        )
        assertTrue(pv.presenceMinutes > pv.activityMinutes)
        assertEquals(520, pv.presenceMinutes)
        assertEquals(480, pv.activityMinutes)
    }

    // -- Patterns ------------------------------------------------------------

    private fun history(lunchStarts: List<Int>): List<DayReconstruction> {
        val days = weekdays(lunchStarts.size)
        return lunchStarts.mapIndexed { i, start -> typicalWorkday(days[i], lunchStart = start) }
    }

    @Test
    fun `a pattern needs three observations before it exists`() {
        val index = PatternEngine.build(history(listOf(790, 795)), BASE)
        assertNull(index.band(ActivityType.LUNCH, DayType.WEEKDAY))
    }

    @Test
    fun `typical time is a median, so one late day cannot move it`() {
        val steady = List(9) { 790 } + listOf(1000)   // one wildly late lunch
        val index = PatternEngine.build(history(steady), BASE)
        val band = index.band(ActivityType.LUNCH, DayType.WEEKDAY)
        assertNotNull(band)
        assertEquals(790, band!!.typicalStartMin)
    }

    @Test
    fun `outliers are excluded from the typical value but the day still exists`() {
        val values = listOf(780, 785, 790, 795, 800, 1300)
        val kept = PatternEngine.withoutOutliers(values)
        assertTrue(kept.none { it == 1300 })
        assertEquals(5, kept.size)
    }

    @Test
    fun `a repeated tight pattern becomes established`() {
        val index = PatternEngine.build(history(List(12) { 790 + (it % 3) * 4 }), BASE)
        val band = index.band(ActivityType.LUNCH, DayType.WEEKDAY)!!
        assertEquals(PatternLifecycle.ESTABLISHED, band.lifecycle)
        assertNotNull(band.consistency)
        assertTrue(band.consistency!! > 80)
    }

    @Test
    fun `a pattern nobody has repeated recently goes stale`() {
        val old = history(List(8) { 790 })
        val index = PatternEngine.build(old, BASE + PatternEngine.STALE_AFTER_DAYS + 5)
        val band = index.band(ActivityType.LUNCH, DayType.WEEKDAY)!!
        assertEquals(PatternLifecycle.STALE, band.lifecycle)
        assertNull(
            "a stale band may not drive inference",
            index.usableBand(ActivityType.LUNCH, DayType.WEEKDAY)
        )
    }

    @Test
    fun `a sustained shift is reported as a change point`() {
        val days = (0 until 28)
            .map { BASE - 27 + it }
            .filter { com.dhinasuthra.app.core.TimeUtils.dayType(it) == DayType.WEEKDAY }
            .map { epochDay ->
                // The last week moved an hour later; the three before it did not.
                typicalWorkday(epochDay, lunchStart = if (epochDay > BASE - 7) 850 else 790)
            }
        val change = PatternEngine.changePoint(days, ActivityType.LUNCH, DayType.WEEKDAY, BASE)
        assertNotNull(change)
        assertTrue(change!!.laterThanBefore)
        assertEquals(60, change.deltaMin)
    }

    // -- Statistics ----------------------------------------------------------

    @Test
    fun `two identical days are perfectly similar and different days are not`() {
        val a = typicalWorkday(BASE)
        val b = typicalWorkday(BASE - 1)
        assertEquals(1f, StatisticsEngine.similarity(a, b), 0.001f)

        val lazyDay = day(
            BASE - 2,
            Triple(0..540, ActivityType.SLEEP, LocationType.HOME),
            Triple(540..1440, ActivityType.PERSONAL, LocationType.HOME)
        )
        assertTrue(StatisticsEngine.similarity(a, lazyDay) < 0.7f)
    }

    @Test
    fun `a perfectly repeated life is perfectly predictable`() {
        val days = (0 until 6).map { typicalWorkday(BASE - it) }
        val predictability = StatisticsEngine.predictability(days)
        assertNotNull(predictability)
        assertEquals(1f, predictability!!, 0.02f)
    }

    @Test
    fun `a box plot separates the middle half from the tails`() {
        val box = StatisticsEngine.box(listOf(780, 785, 790, 795, 800, 1300))!!
        assertTrue(box.p25 <= box.median)
        assertTrue(box.median <= box.p75)
        assertTrue(box.outliers.contains(1300))
        assertEquals(6, box.count)
    }

    @Test
    fun `a rising series has a positive slope`() {
        val trend = StatisticsEngine.trend(listOf(1f, 2f, 3f, 4f, 5f))!!
        assertEquals(1f, trend.slopePerDay, 0.001f)
        assertEquals(4f, trend.changeOver(4), 0.001f)
    }

    // -- Rhythm and routine --------------------------------------------------

    @Test
    fun `the rhythm score rewards keeping to your own usual times`() {
        val patterns = PatternEngine.build(history(List(10) { 790 }), BASE)
        val today = aWeekday()
        val onTime = RhythmScorer.score(typicalWorkday(today, lunchStart = 792), patterns, 1440)
        val late = RhythmScorer.score(typicalWorkday(today, lunchStart = 1000), patterns, 1440)

        assertNotNull(onTime)
        assertNotNull(late)
        assertTrue(onTime!!.value > late!!.value)
        assertTrue(onTime.value >= 90)
    }

    @Test
    fun `a barely reconstructed day is not scored at all`() {
        val thin = DayReconstruction.build(aWeekday(), emptyList())
        val patterns = PatternEngine.build(history(List(10) { 790 }), BASE)
        assertNull(RhythmScorer.score(thin, patterns, 1440))
    }

    @Test
    fun `being early costs less than being equally late`() {
        val early = TimetableAdherence.entryScore(-60, 20)
        val late = TimetableAdherence.entryScore(60, 20)
        assertTrue(early > late)
        assertEquals(1f, TimetableAdherence.entryScore(10, 20), 0.001f)
    }

    @Test
    fun `an entry whose window has not passed is not counted as a miss`() {
        val timetable = Timetable(
            id = "t", name = "Weekday", days = Timetable.WEEKDAYS, active = true,
            entries = listOf(
                TimetableEntry(ActivityType.LUNCH, targetStartMin = 790, toleranceMin = 20),
                TimetableEntry(ActivityType.DINNER, targetStartMin = 1200, toleranceMin = 20)
            ),
            createdAt = 0L
        )
        val report = TimetableAdherence.evaluate(
            timetable, typicalWorkday(aWeekday()), nowMin = 900, patterns = PatternIndex.empty()
        )
        assertEquals("only lunch is scoreable at 15:00", 1, report.scoredCount)
        assertEquals(100, report.adherence)
    }

    @Test
    fun `an inactive routine is neither scored nor shown`() {
        val timetable = Timetable(
            id = "t", name = "Weekday", days = Timetable.WEEKDAYS, active = false,
            entries = listOf(TimetableEntry(ActivityType.LUNCH, targetStartMin = 790)),
            createdAt = 0L
        )
        val report = TimetableAdherence.evaluate(
            timetable, typicalWorkday(aWeekday()), nowMin = 1440, patterns = PatternIndex.empty()
        )
        assertNull(report.adherence)
    }

    @Test
    fun `routines are only offered once patterns are established`() {
        val thin = PatternEngine.build(history(List(4) { 790 }), BASE)
        assertNull(RoutineComposer.suggest(thin, DayType.WEEKDAY, "Weekday"))
    }

    // -- Codecs --------------------------------------------------------------

    @Test
    fun `timetables survive a storage round trip`() {
        val original = listOf(
            Timetable(
                id = "weekday", name = "My weekday | rhythm", days = Timetable.WEEKDAYS,
                active = true,
                entries = listOf(
                    TimetableEntry(ActivityType.WAKE_TRANSITION, 450, 480, LocationType.HOME, 15, true),
                    TimetableEntry(ActivityType.LUNCH, 790, 830, LocationType.OFFICE, 25, false)
                ),
                createdAt = 1234L
            )
        )
        val decoded = TimetableCodec.decode(TimetableCodec.encode(original))
        assertEquals(1, decoded.size)
        assertEquals("My weekday | rhythm", decoded.first().name)
        assertEquals(2, decoded.first().entries.size)
        assertEquals(ActivityType.LUNCH, decoded.first().entries[1].activity)
        assertEquals(25, decoded.first().entries[1].toleranceMin)
        assertEquals(Timetable.WEEKDAYS, decoded.first().days)
    }

    @Test
    fun `corrections survive a storage round trip`() {
        val original = listOf(
            Correction(20000, 780, 840, ActivityType.MEETING, LocationType.OFFICE, DayType.WEEKDAY, 99L)
        )
        val decoded = CorrectionCodec.decode(CorrectionCodec.encode(original))
        assertEquals(original, decoded)
    }

    @Test
    fun `garbage in storage decodes to nothing rather than crashing`() {
        assertTrue(TimetableCodec.decode("not a timetable").isEmpty())
        assertTrue(TimetableCodec.decode(null).isEmpty())
        assertTrue(CorrectionCodec.decode("DSC1\nrubbish").isEmpty())
    }

    @Test
    fun `a timetable only applies on the days it declares`() {
        val weekend = Timetable(
            id = "w", name = "Weekend", days = Timetable.WEEKENDS, active = true,
            entries = emptyList(), createdAt = 0L
        )
        val someMonday = com.dhinasuthra.app.core.TimeUtils.localDate(20000).toEpochDay()
        val appliesSomewhere = (someMonday until someMonday + 7).count { weekend.appliesTo(it) }
        assertEquals(2, appliesSomewhere)
    }
}
