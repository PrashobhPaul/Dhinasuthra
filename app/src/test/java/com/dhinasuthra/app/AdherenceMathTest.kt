package com.dhinasuthra.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-math mirror of AdherenceEngine.matchScore semantics (product spec §28):
 * inside IQR → 1.0, linear falloff to window edges, 0 outside.
 * Kept as plain functions here so the scoring contract is pinned without Room.
 */
class AdherenceMathTest {

    private fun score(minute: Int, p10: Int, p25: Int, p75: Int, p90: Int): Float {
        val windowStart = p10 - 30
        val windowEnd = p90 + 30
        return when {
            minute in p25..p75 -> 1f
            minute < windowStart || minute > windowEnd -> 0f
            minute < p25 -> (minute - windowStart).toFloat() / (p25 - windowStart)
            else -> (windowEnd - minute).toFloat() / (windowEnd - p75)
        }.coerceIn(0f, 1f)
    }

    @Test fun `inside IQR is full credit`() = assertEquals(1f, score(510, 480, 500, 520, 540), 0.001f)
    @Test fun `outside widened window is zero`() = assertEquals(0f, score(700, 480, 500, 520, 540), 0.001f)
    @Test fun `between p90 and window end decays linearly`() {
        val s = score(560, 480, 500, 520, 540)   // window end 570, p75 520 → (570-560)/50
        assertEquals(0.2f, s, 0.001f)
        assertTrue(s in 0f..1f)
    }
}
