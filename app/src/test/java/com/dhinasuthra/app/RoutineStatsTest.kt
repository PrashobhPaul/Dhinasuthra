package com.dhinasuthra.app

import com.dhinasuthra.app.core.model.RoutineEventType
import com.dhinasuthra.app.routine.RoutineStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutineStatsTest {

    @Test
    fun `percentile interpolates within sorted values`() {
        val sorted = listOf(10, 20, 30, 40, 50)
        assertEquals(10, RoutineStats.percentile(sorted, 0.0))
        assertEquals(30, RoutineStats.percentile(sorted, 50.0))
        assertEquals(50, RoutineStats.percentile(sorted, 100.0))
    }

    @Test
    fun `quantiles produce ordered spread`() {
        val q = RoutineStats.quantiles(listOf(500, 505, 510, 515, 520, 900))
        assertTrue(q.p10 <= q.p25)
        assertTrue(q.p25 <= q.median)
        assertTrue(q.median <= q.p75)
        assertTrue(q.p75 <= q.p90)
    }

    @Test
    fun `sleep normalization moves early-morning times past midnight`() {
        // 00:30 sleep onset → 24:30 in normalized space (product spec: robust stats §27)
        val n = RoutineStats.normalize(RoutineEventType.SLEEP, 30)
        assertEquals(1470, n)
        assertEquals(30, RoutineStats.denormalize(n))
    }

    @Test
    fun `late-evening sleep stays un-shifted`() {
        val n = RoutineStats.normalize(RoutineEventType.SLEEP, 23 * 60 + 45)
        assertEquals(23 * 60 + 45, n)
    }

    @Test
    fun `confidence grows with observations and shrinks with spread`() {
        val tight = RoutineStats.confidence(20, 10)
        val loose = RoutineStats.confidence(20, 120)
        val few = RoutineStats.confidence(3, 10)
        assertTrue(tight > loose)
        assertTrue(tight > few)
        assertTrue(tight in 0f..1f)
    }

    @Test
    fun `gentle tolerance floors at 15 minutes`() {
        val q = RoutineStats.quantiles(listOf(600, 600, 600, 600, 600))
        assertTrue(RoutineStats.gentleToleranceMin(q) >= 15)
    }
}
