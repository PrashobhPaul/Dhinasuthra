package com.dhinasuthra.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the §49F weighted-prior contract used by RoutineLearningEngine:
 * blended = (obsMedian*n + prior*K) / (n + K), K = 5 pseudo-observations.
 * Observed evidence progressively dominates; the template never flips the
 * model after a single unusual day.
 */
class PriorBlendTest {

    private fun blend(obsMedian: Int, n: Int, prior: Int, k: Int = 5): Int =
        ((obsMedian * n + prior * k).toFloat() / (n + k)).toInt()

    @Test
    fun `few observations stay close to the user template`() {
        val b = blend(obsMedian = 572, n = 3, prior = 555)   // user said 09:15, saw ~09:32
        assertTrue(b in 556..566)                             // pulled toward template
    }

    @Test
    fun `many observations dominate the template`() {
        val b = blend(obsMedian = 564, n = 40, prior = 555)
        assertTrue(kotlin.math.abs(b - 564) <= 2)
    }

    @Test
    fun `identical values are a fixed point`() {
        assertEquals(555, blend(555, 10, 555))
    }
}
