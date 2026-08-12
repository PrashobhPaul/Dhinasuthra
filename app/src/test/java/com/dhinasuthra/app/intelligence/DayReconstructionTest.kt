package com.dhinasuthra.app.intelligence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 24-hour invariant (TML-01) is the foundation every chart in the app stands
 * on, so it is tested directly rather than trusted.
 */
class DayReconstructionTest {

    private fun episode(
        from: Int, to: Int,
        activity: ActivityType = ActivityType.WORK,
        location: LocationType = LocationType.OFFICE,
        confidence: Float = 0.8f
    ) = TimeEpisode(
        id = "$from-$to-$activity", epochDay = 20000, startMin = from, endMin = to,
        activity = activity, location = location, confidence = confidence,
        status = EpisodeStatus.INFERRED
    )

    @Test
    fun `a day always tiles exactly 1440 minutes`() {
        val day = DayReconstruction.build(
            20000,
            listOf(
                episode(540, 720),
                episode(780, 1080),
                episode(0, 420, ActivityType.SLEEP, LocationType.HOME)
            )
        )
        assertEquals(1440, day.coveredMin)
        day.episodes.zipWithNext().forEach { (a, b) ->
            assertEquals("episodes must be contiguous", a.endMin, b.startMin)
        }
        assertEquals(0, day.episodes.first().startMin)
        assertEquals(1440, day.episodes.last().endMin)
    }

    @Test
    fun `gaps become honest unknown episodes rather than being hidden`() {
        val day = DayReconstruction.build(20000, listOf(episode(540, 720)))
        val unknown = day.episodes.filter { it.activity == ActivityType.UNKNOWN }
        assertTrue(unknown.isNotEmpty())
        assertEquals(1440 - 180, day.unknownMin)
        assertTrue(unknown.all { it.ruleIds.contains(TimelineRules.HONEST_UNKNOWN.id) })
    }

    @Test
    fun `overlaps are resolved in favour of the better evidenced episode`() {
        val day = DayReconstruction.build(
            20000,
            listOf(
                episode(540, 780, ActivityType.WORK, LocationType.OFFICE, confidence = 0.6f),
                episode(720, 780, ActivityType.LUNCH, LocationType.RESTAURANT, confidence = 0.9f)
            )
        )
        val lunch = day.episodes.first { it.activity == ActivityType.LUNCH }
        assertEquals(720, lunch.startMin)
        assertEquals(780, lunch.endMin)
        val work = day.episodes.first { it.activity == ActivityType.WORK }
        assertEquals("work was truncated, not overwritten", 720, work.endMin)
        assertEquals(1440, day.coveredMin)
    }

    @Test
    fun `identical neighbours merge into one readable stretch`() {
        val day = DayReconstruction.build(
            20000,
            listOf(episode(540, 660), episode(660, 780))
        )
        assertEquals(1, day.episodes.count { it.activity == ActivityType.WORK })
        val work = day.episodes.first { it.activity == ActivityType.WORK }
        assertEquals(540, work.startMin)
        assertEquals(780, work.endMin)
    }

    @Test
    fun `today stops at now instead of inventing unknown evening hours`() {
        val day = DayReconstruction.build(
            20000,
            listOf(episode(540, 720)),
            livedUntilMin = 780
        )
        assertEquals(780, day.coveredMin)
        assertEquals(1440 - 780, day.notYetLivedMin)
        assertEquals("only the lived gap counts as unknown", 540 + 60, day.unknownMin)
    }

    @Test
    fun `coverage is measured against the part of the day that happened`() {
        val day = DayReconstruction.build(
            20000,
            listOf(episode(0, 600)),
            livedUntilMin = 600
        )
        assertEquals(1f, day.coverageFraction, 0.001f)
    }

    @Test
    fun `lookup by minute returns the covering episode`() {
        val day = DayReconstruction.build(20000, listOf(episode(540, 720)))
        assertEquals(ActivityType.WORK, day.at(600)?.activity)
        assertNotNull(day.at(100))
        assertEquals(ActivityType.UNKNOWN, day.at(100)?.activity)
    }

    @Test
    fun `an empty day is still a complete day`() {
        val day = DayReconstruction.empty(20000)
        assertEquals(1440, day.coveredMin)
        assertEquals(1440, day.unknownMin)
        assertEquals(0f, day.coverageFraction, 0.001f)
    }
}
