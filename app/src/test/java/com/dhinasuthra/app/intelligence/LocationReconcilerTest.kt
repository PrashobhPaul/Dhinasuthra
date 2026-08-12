package com.dhinasuthra.app.intelligence

import com.dhinasuthra.app.core.model.ActivityKind
import com.dhinasuthra.app.intelligence.LocationReconciler.RawStay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec §10's complaint, in test form: parking the car and walking to the front
 * door must not produce "left home, arrived home, left home, arrived home".
 */
class LocationReconcilerTest {

    private fun home(from: Int, to: Int) =
        RawStay(from, to, LocationType.HOME, placeId = 1, placeName = "Home", confidence = 0.8f)

    private fun office(from: Int, to: Int) =
        RawStay(from, to, LocationType.OFFICE, placeId = 2, placeName = "Office", confidence = 0.8f)

    private fun nowhere(from: Int, to: Int) =
        RawStay(from, to, LocationType.UNKNOWN, placeId = null, placeName = null, confidence = 0.4f)

    @Test
    fun `parking then walking home is one continuous arrival`() {
        val raw = listOf(home(1140, 1150), nowhere(1150, 1158), home(1158, 1320))
        val movements = listOf(
            MovementSpan(1145, 1152, ActivityKind.IN_VEHICLE),
            MovementSpan(1152, 1158, ActivityKind.WALKING)
        )

        val presences = LocationReconciler.reconcile(raw, movements)

        assertEquals("one arrival, not three events", 1, presences.size)
        assertEquals(1140, presences.first().startMin)
        assertEquals(1320, presences.first().endMin)
        assertTrue(presences.first().ruleIds.contains(LocationRules.PARK_AND_WALK.id))
        assertTrue(presences.first().ruleIds.contains(LocationRules.NO_DUPLICATE_ARRIVALS.id))
    }

    @Test
    fun `a boundary flutter of a few minutes is treated as noise`() {
        val raw = listOf(office(540, 600), office(603, 700))
        val presences = LocationReconciler.reconcile(raw, emptyList())

        assertEquals(1, presences.size)
        assertEquals(540, presences.first().startMin)
        assertEquals(700, presences.first().endMin)
        assertTrue(presences.first().ruleIds.contains(LocationRules.QUICK_RETURN_MERGES.id))
    }

    @Test
    fun `a genuine departure with real dwell away is kept`() {
        val raw = listOf(office(540, 700), home(760, 900))
        val presences = LocationReconciler.reconcile(raw, emptyList())

        assertEquals(2, presences.size)
        assertEquals(LocationType.OFFICE, presences[0].location)
        assertEquals(LocationType.HOME, presences[1].location)
    }

    @Test
    fun `a crossing on its own is recorded as a candidate, never as a departure`() {
        val presences = LocationReconciler.reconcile(listOf(home(0, 500)), emptyList())
        assertEquals(1, presences.size)
        assertTrue(presences.first().ruleIds.contains(LocationRules.CROSSING_IS_NOT_DEPARTURE.id))
    }

    @Test
    fun `stationary dwell strengthens an arrival`() {
        val quiet = LocationReconciler.reconcile(listOf(home(0, 500)), emptyList())
        assertTrue(quiet.first().ruleIds.contains(LocationRules.DWELL_CONFIRMS_ARRIVAL.id))

        val restless = LocationReconciler.reconcile(
            listOf(home(0, 60)),
            listOf(MovementSpan(0, 60, ActivityKind.WALKING))
        )
        assertTrue(
            "constant movement should not confirm a settled arrival",
            !restless.first().ruleIds.contains(LocationRules.DWELL_CONFIRMS_ARRIVAL.id)
        )
    }

    @Test
    fun `a place seen on a single day cannot be certain`() {
        val oneVisit = RawStay(
            600, 800, LocationType.OTHER_KNOWN, placeId = 9, placeName = "Somewhere",
            confidence = 0.95f, visitDays = 1
        )
        val presences = LocationReconciler.reconcile(listOf(oneVisit), emptyList())
        assertTrue(
            "the guard must cap the sensor's own optimism",
            presences.first().confidence <= 0.5f
        )
        assertTrue(presences.first().ruleIds.contains(LocationRules.SINGLE_VISIT_EXPLAINS_NOTHING.id))
    }

    @Test
    fun `consecutive segments at one place collapse into a single presence`() {
        val raw = listOf(office(540, 700), office(700, 900), office(900, 1080))
        val presences = LocationReconciler.reconcile(raw, emptyList())
        assertEquals(1, presences.size)
        assertEquals(1080, presences.first().endMin)
    }
}
