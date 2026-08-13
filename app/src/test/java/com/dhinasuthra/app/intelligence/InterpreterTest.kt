package com.dhinasuthra.app.intelligence

import com.dhinasuthra.app.core.model.ActivityKind
import com.dhinasuthra.app.core.model.DayType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arguments from the specification, turned into assertions:
 * alarm dismissal is not wakefulness, office presence is not work, and lunch is
 * never 13:00 just because the clock says so.
 */
class InterpreterTest {

    private val day = 20000L

    private fun evidence(
        presences: List<Presence> = emptyList(),
        samples: List<DeviceSample> = emptyList(),
        movements: List<MovementSpan> = emptyList(),
        evening: List<DeviceSample> = emptyList(),
        patterns: PatternIndex = PatternIndex.empty(),
        corrections: List<Correction> = emptyList(),
        lived: Int = 1440
    ) = DayEvidence(
        epochDay = day,
        dayType = DayType.WEEKDAY,
        livedUntilMin = lived,
        presences = presences,
        samples = samples,
        movements = movements,
        previousEveningSamples = evening,
        patterns = patterns,
        corrections = corrections
    )

    private fun samples(range: IntProgression, interactive: Boolean, charging: Boolean = false) =
        range.map { DeviceSample(it, interactive, charging) }

    // -- Sleep ---------------------------------------------------------------

    @Test
    fun `a stir at the alarm does not end the night`() {
        val evening = samples(1080..1395 step 15, interactive = true) +
            samples(1410..1439 step 15, interactive = false, charging = true)
        val morning = samples(0..450 step 15, interactive = false, charging = true) +
            listOf(DeviceSample(465, true, true)) +          // the alarm stir
            samples(480..495 step 15, interactive = false, charging = true) +
            samples(510..600 step 15, interactive = true)

        val e = evidence(
            presences = listOf(Presence(0, 700, LocationType.HOME, placeId = 1, placeName = "Home")),
            samples = morning,
            movements = listOf(MovementSpan(515, 545, ActivityKind.WALKING)),
            evening = evening
        )

        val night = SleepInterpreter.interpret(e)
        assertNotNull(night)
        night!!

        assertTrue("the 07:45 stir must not be the wake time", night.confirmedWakeMin > 465)
        assertEquals("the run resumes past the absorbed stir", 510, night.wakeIntentMin)
        assertTrue("wake is confirmed by later evidence", night.confirmedWakeMin >= 515)
        assertTrue(
            "the absorbed stir is recorded as a fired rule",
            night.ledger.ruleIds().contains(SleepRules.BRIEF_STIR_IGNORED.id)
        )

        val episodes = SleepInterpreter.episodes(e, night)
        val sleep = episodes.first { it.activity == ActivityType.SLEEP }
        assertEquals("sleep runs to the confirmed wake", night.confirmedWakeMin, sleep.endMin)
    }

    @Test
    fun `no samples means no sleep story`() {
        assertNull(SleepInterpreter.interpret(evidence()))
    }

    @Test
    fun `a short quiet run is not the night's sleep`() {
        val morning = samples(0..60 step 15, interactive = false) + samples(75..300 step 15, interactive = true)
        assertNull(SleepInterpreter.interpret(evidence(samples = morning)))
    }

    // -- Meals ---------------------------------------------------------------

    private fun officeDayWithMiddayGap(patterns: PatternIndex = PatternIndex.empty()) = evidence(
        presences = listOf(
            Presence(540, 790, LocationType.OFFICE, placeId = 2, placeName = "Office"),
            Presence(830, 1080, LocationType.OFFICE, placeId = 2, placeName = "Office")
        ),
        patterns = patterns
    )

    private fun lunchBand(observations: Int = 12) = PatternIndex.of(
        listOf(
            ActivityBand(
                activity = ActivityType.LUNCH, dayType = DayType.WEEKDAY,
                typicalStartMin = 800, p10 = 780, p25 = 790, p75 = 810, p90 = 820,
                typicalDurationMin = 40, observations = observations, confidence = 0.85f,
                lastObservedDay = 20000, lifecycle = PatternLifecycle.ESTABLISHED
            )
        )
    )

    @Test
    fun `a midday gap alone is not lunch`() {
        // MEA-01 — the clock is necessary but never sufficient.
        assertNull(MealInterpreter.lunchCandidate(officeDayWithMiddayGap()))
    }

    @Test
    fun `a midday gap that matches your learned lunch is lunch`() {
        val candidate = MealInterpreter.lunchCandidate(officeDayWithMiddayGap(lunchBand()))
        assertNotNull(candidate)
        candidate!!
        assertEquals(ActivityType.LUNCH, candidate.activity)
        assertEquals(790, candidate.startMin)
        assertEquals(830, candidate.endMin)
        assertTrue(candidate.ruleIds.contains(MealRules.LEARNED_START.id))
        assertTrue(candidate.ruleIds.contains(MealRules.LEARNED_DURATION.id))
        assertTrue(candidate.confidence >= MealInterpreter.ACCEPT_THRESHOLD)
    }

    @Test
    fun `a three hour midday absence is not a meal`() {
        val e = evidence(
            presences = listOf(
                Presence(540, 700, LocationType.OFFICE, placeId = 2, placeName = "Office"),
                Presence(880, 1080, LocationType.OFFICE, placeId = 2, placeName = "Office")
            ),
            patterns = lunchBand()
        )
        assertNull("MEA-11 caps a meal at two hours", MealInterpreter.lunchCandidate(e))
    }

    @Test
    fun `breakfast is not invented without a band`() {
        val e = evidence(presences = listOf(Presence(0, 600, LocationType.HOME, placeId = 1)))
        assertNull(MealInterpreter.breakfastCandidate(e))
    }

    // -- Work ----------------------------------------------------------------

    @Test
    fun `office presence decomposes and never equals work outright`() {
        // Nine hours at the office, with lunch taken inside the building.
        val e = evidence(
            presences = listOf(Presence(540, 1080, LocationType.OFFICE, placeId = 2, placeName = "Office")),
            patterns = lunchBand()
        )
        val lunch = EpisodeCandidate(
            startMin = 790, endMin = 830,
            activity = ActivityType.LUNCH, location = LocationType.OFFICE,
            confidence = 0.8f, priority = 25
        )
        val work = WorkInterpreter.decompose(e, listOf(lunch))

        val presenceMin = e.presences.sumOf { it.durationMin }
        val workMin = work.filter { it.activity == ActivityType.WORK }.sumOf { it.durationMin }

        assertEquals(540, presenceMin)
        assertEquals("work is presence minus what was not work", 500, workMin)
        assertTrue("presence must exceed classified work", workMin < presenceMin)
        assertEquals("the day is split around lunch, not treated as one block", 2, work.size)
        assertTrue(
            "the decomposition names the rule that forbids presence = work",
            work.first().ruleIds.contains(WorkRules.PRESENCE_IS_NOT_WORK.id)
        )
    }

    @Test
    fun `a short excursion between two office stays is a break, not a departure`() {
        val e = evidence(
            presences = listOf(
                Presence(540, 700, LocationType.OFFICE, placeId = 2, placeName = "Office"),
                Presence(715, 1080, LocationType.OFFICE, placeId = 2, placeName = "Office")
            )
        )
        val out = WorkInterpreter.decompose(e, emptyList())
        val breaks = out.filter { it.activity == ActivityType.TEA_BREAK }
        assertEquals(1, breaks.size)
        assertEquals(700, breaks.first().startMin)
        assertEquals(715, breaks.first().endMin)
    }

    @Test
    fun `weekend office presence is not assumed to be work`() {
        val e = DayEvidence(
            epochDay = day, dayType = DayType.WEEKEND, livedUntilMin = 1440,
            presences = listOf(Presence(600, 900, LocationType.OFFICE, placeId = 2)),
            samples = emptyList(), movements = emptyList()
        )
        assertTrue(WorkInterpreter.decompose(e, emptyList()).isEmpty())
    }

    // -- Commute -------------------------------------------------------------

    @Test
    fun `movement between two places becomes one journey`() {
        val e = evidence(
            presences = listOf(
                Presence(0, 520, LocationType.HOME, placeId = 1, placeName = "Home"),
                Presence(560, 1080, LocationType.OFFICE, placeId = 2, placeName = "Office")
            ),
            movements = listOf(
                MovementSpan(520, 535, ActivityKind.IN_VEHICLE),
                MovementSpan(536, 540, ActivityKind.STILL),
                MovementSpan(541, 558, ActivityKind.IN_VEHICLE)
            )
        )
        val journeys = CommuteInterpreter.interpret(e)
        assertEquals("a journey is one episode, not three", 1, journeys.size)
        assertEquals(520, journeys.first().startMin)
        assertEquals(560, journeys.first().endMin)
        assertEquals(LocationType.TRANSIT, journeys.first().location)
    }

    // -- Gap resolution ------------------------------------------------------

    @Test
    fun `a gap is left unclassified when nothing explains it`() {
        val e = evidence(presences = listOf(Presence(540, 1080, LocationType.OFFICE, placeId = 2)))
        val placed = listOf(
            EpisodeCandidate(540, 700, ActivityType.WORK, LocationType.OFFICE, confidence = 0.8f),
            EpisodeCandidate(800, 1080, ActivityType.WORK, LocationType.OFFICE, confidence = 0.8f)
        )
        assertTrue(GapResolver.resolve(e, placed).isEmpty())
    }

    @Test
    fun `a gap matching a learned window is proposed with its evidence`() {
        val e = evidence(
            presences = listOf(Presence(540, 1080, LocationType.OFFICE, placeId = 2, placeName = "Office")),
            patterns = lunchBand(observations = 9)
        )
        val placed = listOf(
            EpisodeCandidate(540, 780, ActivityType.WORK, LocationType.OFFICE, confidence = 0.8f),
            EpisodeCandidate(830, 1080, ActivityType.WORK, LocationType.OFFICE, confidence = 0.8f)
        )
        val resolved = GapResolver.resolve(e, placed)
        assertEquals(1, resolved.size)
        assertEquals(ActivityType.LUNCH, resolved.first().activity)
        assertTrue(resolved.first().ruleIds.contains(TimelineRules.PATTERN_FILLS_GAP.id))
    }

    // -- Corrections ---------------------------------------------------------

    @Test
    fun `a correction outranks every inference for that day`() {
        val e = evidence(
            presences = listOf(Presence(540, 1080, LocationType.OFFICE, placeId = 2, placeName = "Office")),
            corrections = listOf(
                Correction(
                    epochDay = day, startMin = 780, endMin = 840,
                    activity = ActivityType.MEETING, location = LocationType.OFFICE,
                    dayType = DayType.WEEKDAY, createdAt = 0L
                )
            )
        )
        val reconstruction = DayBuilder.build(e)
        val corrected = reconstruction.at(800)
        assertEquals(ActivityType.MEETING, corrected?.activity)
        assertEquals(EpisodeStatus.CORRECTED, corrected?.status)
        assertEquals(1f, corrected?.confidence ?: 0f, 0.001f)
    }

    @Test
    fun `a full build still tiles the whole day`() {
        val e = evidence(
            presences = listOf(
                Presence(0, 520, LocationType.HOME, placeId = 1, placeName = "Home"),
                Presence(560, 1080, LocationType.OFFICE, placeId = 2, placeName = "Office"),
                Presence(1120, 1440, LocationType.HOME, placeId = 1, placeName = "Home")
            ),
            movements = listOf(
                MovementSpan(520, 558, ActivityKind.IN_VEHICLE),
                MovementSpan(1080, 1118, ActivityKind.IN_VEHICLE)
            ),
            patterns = lunchBand()
        )
        val reconstruction = DayBuilder.build(e)
        assertEquals(1440, reconstruction.coveredMin)
        assertTrue(LensProjector.reconciles(reconstruction))
    }
}
