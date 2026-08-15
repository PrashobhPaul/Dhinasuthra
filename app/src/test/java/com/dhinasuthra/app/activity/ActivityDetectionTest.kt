package com.dhinasuthra.app.activity

import com.dhinasuthra.app.core.TimeUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The acceptance criteria from plan §31, as tests.
 *
 * Each one is a scenario the plan says the next version must get right, written
 * out as the signals a phone would actually have recorded. If any of these goes
 * red, the release is not done — plan §34's definition of done is behavioural,
 * not "the new UI appears to work".
 */
class ActivityDetectionTest {

    private val day = 20_000L

    /** A timestamp at a given local time on the test day. */
    private fun at(hour: Int, minute: Int): Long =
        TimeUtils.instantAt(day, hour * 60 + minute).toEpochMilli()

    private fun context(
        profiles: Map<String, LearnedProfile> = emptyMap(),
        elapsedMin: Int = 1440
    ) = DetectionContext(
        epochDay = day,
        profiles = profiles,
        elapsedMin = elapsedMin,
        anchors = listOf(
            Anchor(DetectionContext.ANCHOR_HOME, 1L, "Home"),
            Anchor(DetectionContext.ANCHOR_DESK, 2L, "Office")
        )
    )

    private fun window(vararg signals: Signal) = SignalWindow(signals.toList())

    // -----------------------------------------------------------------------
    // Wake (plan §31 "Wake")
    // -----------------------------------------------------------------------

    @Test
    fun `wake is inferred at the first credible awakening, not at a much later movement`() {
        // 08:55 charger unplug, 08:57 motion, 09:10 screen on, 09:14 phone used.
        val w = window(
            Signal(at(8, 55), SignalTypes.CHARGER_DISCONNECTED),
            Signal(at(8, 57), SignalTypes.MOTION),
            Signal(at(9, 3), SignalTypes.MOTION),
            Signal(at(9, 10), SignalTypes.SCREEN_ON),
            Signal(at(9, 14), SignalTypes.USER_PRESENT),
            Signal(at(9, 19), SignalTypes.SCREEN_TIME, value = "5"),
            // The late movement the old logic would have waited for.
            Signal(at(10, 6), SignalTypes.WALKING)
        )

        val assessment = WakeDetector().assess(w, context())

        assertEquals(AwakeningState.WAKE_CONFIRMED, assessment.state)
        assertEquals("first stir should be the 08:57 motion", at(8, 57), assessment.firstStir)
        val wake = assessment.timelineWake!!
        assertTrue(
            "wake landed at ${TimeUtils.formatMinuteOfDay(TimeUtils.minuteOfDay(java.time.Instant.ofEpochMilli(wake)))}",
            wake <= at(9, 20)
        )
        assertTrue("wake must not wait for the 10:06 walk", wake < at(10, 0))
    }

    @Test
    fun `a single night-time motion with the screen off leaves you asleep`() {
        val w = window(Signal(at(3, 0), SignalTypes.MOTION))

        val assessment = WakeDetector().assess(w, context())

        assertEquals(AwakeningState.SLEEP, assessment.state)
        assertNull(assessment.likelyAwake)
        assertNull(assessment.confirmedAwake)
        assertTrue(WakeDetector().detect(w, context()).isEmpty())
    }

    @Test
    fun `charger unplug followed by motion is strong evidence on its own`() {
        val w = window(
            Signal(at(7, 30), SignalTypes.CHARGER_DISCONNECTED),
            Signal(at(7, 33), SignalTypes.MOTION),
            Signal(at(7, 36), SignalTypes.MOTION),
            Signal(at(7, 40), SignalTypes.WALKING)
        )

        val assessment = WakeDetector().assess(w, context())

        assertNotNull("unplug + repeated motion + walking should reach 'likely awake'", assessment.likelyAwake)
        assertTrue("expected a wake, got ${assessment.confidence}", assessment.confidence >= 0.35f)
        assertTrue(assessment.evidence.any { it.detail.contains("unplugged") })
    }

    @Test
    fun `the wake candidate keeps the earlier stir as an alternative to offer`() {
        val w = window(
            Signal(at(8, 57), SignalTypes.MOTION),
            Signal(at(9, 3), SignalTypes.MOTION),
            Signal(at(9, 14), SignalTypes.USER_PRESENT),
            Signal(at(9, 24), SignalTypes.SCREEN_TIME, value = "10")
        )

        val candidate = WakeDetector().detect(w, context()).single()

        assertEquals(at(8, 57), candidate.alternativeStart)
        assertEquals(ActivityStatus.INFERRED, candidate.status)
        assertTrue(candidate.reasons().isNotEmpty())
    }

    @Test
    fun `evidence is written in plain language, never in signal names or weights`() {
        val w = window(
            Signal(at(8, 55), SignalTypes.CHARGER_DISCONNECTED),
            Signal(at(8, 57), SignalTypes.MOTION),
            Signal(at(9, 14), SignalTypes.USER_PRESENT)
        )

        val reasons = WakeDetector().assess(w, context()).evidence.map { it.detail }

        assertTrue(reasons.isNotEmpty())
        reasons.forEach { line ->
            assertTrue("evidence leaked a signal name: $line", !line.contains("_"))
            assertTrue("evidence leaked a weight: $line", !line.contains("0."))
        }
    }

    // -----------------------------------------------------------------------
    // Boundaries and breaks (plan §31 "Office break")
    // -----------------------------------------------------------------------

    @Test
    fun `leaving the desk and coming back produces one break`() {
        val w = window(
            Signal(at(10, 40), SignalTypes.DEPARTED, value = "2"),
            Signal(at(10, 41), SignalTypes.WALKING),
            Signal(at(10, 55), SignalTypes.ARRIVED, value = "2")
        )

        val breaks = BoundaryDetector().interruptions(w, context())

        assertEquals(1, breaks.size)
        assertEquals(at(10, 40), breaks[0].start)
        assertEquals(15, breaks[0].durationMin)
    }

    @Test
    fun `a two-minute wander is not a break`() {
        val w = window(
            Signal(at(10, 40), SignalTypes.DEPARTED),
            Signal(at(10, 42), SignalTypes.ARRIVED)
        )

        assertTrue(BoundaryDetector().interruptions(w, context()).isEmpty())
    }

    @Test
    fun `an interruption is reported as stepping away, not as a new activity`() {
        val w = window(
            Signal(at(20, 42), SignalTypes.DEPARTED),
            Signal(at(20, 43), SignalTypes.WALKING),
            Signal(at(20, 49), SignalTypes.ARRIVED)
        )

        val candidate = BoundaryDetector().detect(w, context()).single()

        assertEquals(ActivityCatalog.INTERRUPTION, candidate.activityCode)
        assertEquals(ActivityStatus.INFERRED, candidate.status)
    }

    // -----------------------------------------------------------------------
    // Contextual classification (plan §31 "Lunch", "Tea")
    // -----------------------------------------------------------------------

    @Test
    fun `a midday interruption is offered as lunch, not asserted as lunch`() {
        val w = window(
            Signal(at(12, 52), SignalTypes.DEPARTED),
            Signal(at(12, 53), SignalTypes.WALKING),
            Signal(at(13, 0), SignalTypes.LOCATION_CHANGE),
            Signal(at(13, 42), SignalTypes.ARRIVED)
        )

        val candidate = ContextualBreakDetector().detect(w, context()).single()

        assertEquals(ActivityCatalog.LUNCH, candidate.activityCode)
        assertEquals(at(12, 52), candidate.start)
        assertEquals(at(13, 42), candidate.end)
        // Plan §11: "Do not automatically make it confirmed."
        assertEquals(ActivityStatus.INFERRED, candidate.status)
        assertTrue(candidate.confidence > 0.5f)
    }

    @Test
    fun `a mid-morning break falls in the tea window`() {
        val w = window(
            Signal(at(10, 42), SignalTypes.DEPARTED),
            Signal(at(10, 43), SignalTypes.WALKING),
            Signal(at(10, 56), SignalTypes.ARRIVED)
        )

        val candidate = ContextualBreakDetector().detect(w, context()).single()

        assertEquals(ActivityCatalog.TEA_BREAK, candidate.activityCode)
    }

    @Test
    fun `an interruption outside every window is left unnamed rather than guessed`() {
        val w = window(
            Signal(at(19, 10), SignalTypes.DEPARTED),
            Signal(at(19, 11), SignalTypes.WALKING),
            Signal(at(19, 25), SignalTypes.ARRIVED)
        )

        assertTrue(ContextualBreakDetector().detect(w, context()).isEmpty())
    }

    @Test
    fun `what the user has confirmed outranks the default windows`() {
        // Four confirmed teas at 09:50 — outside every out-of-the-box window.
        val profile = LearnedProfile(
            activityCode = ActivityCatalog.TEA_BREAK,
            typicalStartMin = 9 * 60 + 50,
            startSpreadMin = 20,
            typicalDurationMin = 12,
            durationSpreadMin = 8,
            typicalPlaceId = null,
            observations = 6
        )
        val w = window(
            Signal(at(9, 48), SignalTypes.DEPARTED),
            Signal(at(9, 49), SignalTypes.WALKING),
            Signal(at(10, 0), SignalTypes.ARRIVED)
        )

        val candidate = ContextualBreakDetector()
            .detect(w, context(mapOf(ActivityCatalog.TEA_BREAK to profile)))
            .single()

        assertEquals(ActivityCatalog.TEA_BREAK, candidate.activityCode)
        assertTrue(candidate.reasons().any { it.contains("your usual") })
    }

    // -----------------------------------------------------------------------
    // Television (plan §31 "TV")
    // -----------------------------------------------------------------------

    @Test
    fun `repeated remote use at home reads as watching television`() {
        val w = window(
            Signal(at(19, 42), SignalTypes.TV_REMOTE_INTERACTION, SignalSources.TV_REMOTE),
            Signal(at(19, 43), SignalTypes.TV_NAVIGATION, SignalSources.TV_REMOTE),
            Signal(at(19, 44), SignalTypes.TV_PLAYBACK, SignalSources.TV_REMOTE),
            Signal(at(19, 58), SignalTypes.TV_VOLUME, SignalSources.TV_REMOTE),
            Signal(at(20, 21), SignalTypes.TV_REMOTE_INTERACTION, SignalSources.TV_REMOTE)
        )

        val candidate = TvDetector().detect(w, context()).single()

        assertEquals(ActivityCatalog.WATCHING_TV, candidate.activityCode)
        assertEquals(at(19, 42), candidate.start)
        assertEquals(at(20, 21), candidate.end)
        assertTrue(candidate.confidence > 0.5f)
    }

    @Test
    fun `opening the remote once is not an evening of television`() {
        val w = window(Signal(at(19, 42), SignalTypes.TV_REMOTE_INTERACTION, SignalSources.TV_REMOTE))

        assertTrue(TvDetector().detect(w, context()).isEmpty())
    }

    @Test
    fun `a television left on with nobody touching it lowers confidence`() {
        val watched = window(
            Signal(at(19, 42), SignalTypes.TV_REMOTE_INTERACTION, SignalSources.TV_REMOTE),
            Signal(at(19, 50), SignalTypes.TV_NAVIGATION, SignalSources.TV_REMOTE),
            Signal(at(20, 0), SignalTypes.TV_VOLUME, SignalSources.TV_REMOTE)
        )
        val abandoned = window(
            Signal(at(19, 42), SignalTypes.TV_REMOTE_INTERACTION, SignalSources.TV_REMOTE),
            Signal(at(19, 50), SignalTypes.TV_NAVIGATION, SignalSources.TV_REMOTE),
            Signal(at(20, 0), SignalTypes.TV_VOLUME, SignalSources.TV_REMOTE),
            Signal(at(20, 30), SignalTypes.TV_ON, SignalSources.TV_REMOTE)
        )

        val a = TvDetector().detect(watched, context()).single()
        val b = TvDetector().detect(abandoned, context()).single()

        assertTrue(
            "TV_ON without interaction should not raise confidence (${a.confidence} → ${b.confidence})",
            b.confidence < a.confidence
        )
    }

    // -----------------------------------------------------------------------
    // Calls (plan §31 "Calls", "WhatsApp call", "Teams meeting", "Missed call")
    // -----------------------------------------------------------------------

    @Test
    fun `an answered cellular call becomes a call from answer to end`() {
        val w = window(
            Signal(at(10, 2), SignalTypes.CALL_RINGING, value = CallDetector.CARRIER_CELLULAR),
            Signal(at(10, 3), SignalTypes.CALL_ANSWERED, value = CallDetector.CARRIER_CELLULAR),
            Signal(at(10, 27), SignalTypes.CALL_ENDED, value = CallDetector.CARRIER_CELLULAR)
        )

        val candidate = CallDetector().detect(w, context()).single()

        assertEquals(ActivityCatalog.PHONE_CALL, candidate.activityCode)
        assertEquals("duration runs from the answer, not the ring", at(10, 3), candidate.start)
        assertEquals(24, candidate.durationMin)
        assertEquals(ActivityStatus.SYSTEM_OBSERVED, candidate.status)
    }

    @Test
    fun `a Teams call is an office meeting`() {
        val w = window(
            Signal(at(14, 0), SignalTypes.CALL_RINGING, value = CallDetector.CARRIER_TEAMS),
            Signal(at(14, 1), SignalTypes.CALL_ANSWERED, value = CallDetector.CARRIER_TEAMS),
            Signal(at(15, 2), SignalTypes.CALL_ENDED, value = CallDetector.CARRIER_TEAMS)
        )

        val candidate = CallDetector().detect(w, context()).single()

        assertEquals(ActivityCatalog.MEETING, candidate.activityCode)
        assertEquals(61, candidate.durationMin)
        assertTrue(candidate.reasons().any { it.contains("Teams") })
    }

    @Test
    fun `a WhatsApp call is a call, and says so`() {
        val w = window(
            Signal(at(18, 11), SignalTypes.CALL_ANSWERED, value = CallDetector.CARRIER_WHATSAPP),
            Signal(at(18, 35), SignalTypes.CALL_ENDED, value = CallDetector.CARRIER_WHATSAPP)
        )

        val candidate = CallDetector().detect(w, context()).single()

        assertEquals(ActivityCatalog.PHONE_CALL, candidate.activityCode)
        assertEquals(24, candidate.durationMin)
        assertTrue(candidate.reasons().any { it.contains("WhatsApp") })
    }

    @Test
    fun `a missed call creates no activity at all`() {
        val w = window(
            Signal(at(10, 2), SignalTypes.CALL_RINGING, value = CallDetector.CARRIER_CELLULAR),
            Signal(at(10, 2), SignalTypes.CALL_MISSED, value = CallDetector.CARRIER_CELLULAR),
            Signal(at(10, 3), SignalTypes.CALL_ENDED, value = CallDetector.CARRIER_CELLULAR)
        )

        assertTrue(CallDetector().detect(w, context()).isEmpty())
    }

    // -----------------------------------------------------------------------
    // Scoring and registry
    // -----------------------------------------------------------------------

    @Test
    fun `weak evidence combines without either piece being enough alone`() {
        val single = EvidenceBundle().add(1L, SignalTypes.MOTION, 0.2f, "a").confidence()
        val both = EvidenceBundle()
            .add(1L, SignalTypes.MOTION, 0.2f, "a")
            .add(2L, SignalTypes.WALKING, 0.2f, "b")
            .confidence()

        assertTrue(both > single)
        assertTrue("noisy-OR must not simply add", both < 0.4f)
        assertTrue("confidence must never reach certainty", both < 1f)
    }

    @Test
    fun `contradictory evidence pulls confidence back down`() {
        val supported = EvidenceBundle().add(1L, SignalTypes.MOTION, 0.5f, "for").confidence()
        val disputed = EvidenceBundle()
            .add(1L, SignalTypes.MOTION, 0.5f, "for")
            .add(2L, SignalTypes.SCREEN_OFF, -0.3f, "against")
            .confidence()

        assertTrue(disputed < supported)
    }

    @Test
    fun `weights are overridable without touching a detector`() {
        val defaults = WeightConfig()
        val tuned = defaults.with(WeightConfig.WAKE_SINGLE_MOTION to 0.9f)

        assertEquals(0.05f, defaults[WeightConfig.WAKE_SINGLE_MOTION], 0.0001f)
        assertEquals(0.9f, tuned[WeightConfig.WAKE_SINGLE_MOTION], 0.0001f)
        assertEquals(
            "an untouched weight must keep its default",
            defaults[WeightConfig.WAKE_WALKING], tuned[WeightConfig.WAKE_WALKING], 0.0001f
        )
        assertEquals("an unknown key is zero, not a crash", 0f, defaults["nonsense.key"], 0.0001f)
    }

    @Test
    fun `a detector that throws does not take the others down with it`() {
        val broken = object : ActivityDetector {
            override val id = "broken"
            override fun detect(window: SignalWindow, context: DetectionContext) =
                error("this detector is having a bad day")
        }
        val registry = DetectorRegistry(listOf(broken)) + CallDetector()
        val w = window(
            Signal(at(18, 11), SignalTypes.CALL_ANSWERED, value = CallDetector.CARRIER_WHATSAPP),
            Signal(at(18, 35), SignalTypes.CALL_ENDED, value = CallDetector.CARRIER_WHATSAPP)
        )

        assertEquals(1, registry.run(w, context()).size)
    }

    @Test
    fun `when two detectors describe the same gap, the one that names it wins`() {
        // Exactly what happens on a real lunch: the boundary engine reports
        // stepping away, the contextual classifier reports lunch, both over the
        // same minutes. The timeline must show one of them.
        val w = window(
            Signal(at(12, 52), SignalTypes.DEPARTED),
            Signal(at(12, 53), SignalTypes.WALKING),
            Signal(at(13, 42), SignalTypes.ARRIVED)
        )
        val raw = DetectorRegistry(listOf(BoundaryDetector(), ContextualBreakDetector()))
            .run(w, context())
        assertEquals("both detectors should fire", 2, raw.size)

        val resolved = CandidateResolver.resolve(raw)

        assertEquals(1, resolved.size)
        assertEquals(ActivityCatalog.LUNCH, resolved.single().activityCode)
    }

    @Test
    fun `a wake marker survives alongside activities that start later`() {
        val wake = ActivityCandidate(ActivityCatalog.WAKE, at(7, 0), at(7, 0), 0.8f)
        val lunch = ActivityCandidate(ActivityCatalog.LUNCH, at(12, 52), at(13, 42), 0.8f)

        val resolved = CandidateResolver.resolve(listOf(wake, lunch))

        assertEquals(2, resolved.size)
        assertEquals(ActivityCatalog.WAKE, resolved.first().activityCode)
    }

    @Test
    fun `an observed call outranks an inferred break over the same minutes`() {
        val call = ActivityCandidate(
            ActivityCatalog.PHONE_CALL, at(10, 40), at(10, 55), 0.9f,
            status = ActivityStatus.SYSTEM_OBSERVED
        )
        val guess = ActivityCandidate(ActivityCatalog.TEA_BREAK, at(10, 42), at(10, 56), 0.95f)

        val resolved = CandidateResolver.resolve(listOf(guess, call))

        assertEquals(1, resolved.size)
        assertEquals(ActivityCatalog.PHONE_CALL, resolved.single().activityCode)
    }

    // -----------------------------------------------------------------------
    // Asking unprompted — the app must not sit silent through a whole afternoon
    // -----------------------------------------------------------------------

    @Test
    fun `a day with no sensor signals at all still asks about lunch`() {
        // Exactly the reported case: a phone that observed nothing, and an app
        // that consequently never wondered whether its owner had eaten.
        val asked = MealPromptDetector().detect(SignalWindow.EMPTY, context(elapsedMin = 15 * 60))

        val lunch = asked.single { it.activityCode == ActivityCatalog.LUNCH }
        assertEquals(ActivityStatus.INFERRED, lunch.status)
        assertTrue("a prompt must stay tentative", lunch.confidence < 0.5f)
        assertTrue(lunch.reasons().isNotEmpty())
    }

    @Test
    fun `it does not ask about lunch at half past twelve`() {
        val asked = MealPromptDetector().detect(SignalWindow.EMPTY, context(elapsedMin = 12 * 60 + 30))

        assertTrue(asked.none { it.activityCode == ActivityCatalog.LUNCH })
    }

    @Test
    fun `by mid-afternoon it has asked about breakfast and lunch but not dinner`() {
        val asked = MealPromptDetector().detect(SignalWindow.EMPTY, context(elapsedMin = 15 * 60))
            .map { it.activityCode }

        assertTrue(asked.contains(ActivityCatalog.BREAKFAST))
        assertTrue(asked.contains(ActivityCatalog.LUNCH))
        assertTrue("dinner hasn't happened yet", !asked.contains(ActivityCatalog.DINNER))
    }

    @Test
    fun `an observed lunch beats the standing prompt for the same window`() {
        val observed = ActivityCandidate(
            ActivityCatalog.LUNCH, at(12, 52), at(13, 42), 0.86f, detectorId = "contextual-break"
        )
        val prompt = MealPromptDetector()
            .detect(SignalWindow.EMPTY, context(elapsedMin = 15 * 60))
            .single { it.activityCode == ActivityCatalog.LUNCH }

        val resolved = CandidateResolver.resolve(listOf(prompt, observed))
            .filter { it.activityCode == ActivityCatalog.LUNCH }

        assertEquals(1, resolved.size)
        assertEquals("contextual-break", resolved.single().detectorId)
    }

    @Test
    fun `once you have confirmed your lunch time it asks about that time instead`() {
        val profile = LearnedProfile(
            activityCode = ActivityCatalog.LUNCH,
            typicalStartMin = 13 * 60 + 20, startSpreadMin = 15,
            typicalDurationMin = 35, durationSpreadMin = 10,
            typicalPlaceId = null, observations = 6
        )
        val prompt = MealPromptDetector()
            .detect(SignalWindow.EMPTY, context(mapOf(ActivityCatalog.LUNCH to profile), 15 * 60))
            .single { it.activityCode == ActivityCatalog.LUNCH }

        assertEquals(13 * 60 + 20, TimeUtils.minuteOfDay(java.time.Instant.ofEpochMilli(prompt.start)))
        assertEquals(35, prompt.durationMin)
        assertTrue(prompt.reasons().any { it.contains("usually") })
    }

    @Test
    fun `a short call still lands on the timeline`() {
        val w = window(
            Signal(at(11, 0), SignalTypes.CALL_ANSWERED, value = CallDetector.CARRIER_CELLULAR, metadata = "Amma"),
            Signal(at(11, 0) + 40_000L, SignalTypes.CALL_ENDED, value = CallDetector.CARRIER_CELLULAR)
        )

        val candidate = CallDetector().detect(w, context()).single()

        assertEquals(ActivityCatalog.PHONE_CALL, candidate.activityCode)
        assertTrue("the person you called is what makes it legible", candidate.reasons().any { it.contains("Amma") })
    }

    // -----------------------------------------------------------------------
    // Viewing time and app calls
    // -----------------------------------------------------------------------

    @Test
    fun `a long stretch in a video app becomes watching time, named`() {
        val w = window(
            Signal(at(13, 0), SignalTypes.MEDIA_APP_ACTIVE, SignalSources.USAGE_STATS,
                value = "38", metadata = "Netflix")
        )

        val candidate = TvDetector().detect(w, context()).single()

        assertEquals(ActivityCatalog.WATCHING_TV, candidate.activityCode)
        assertEquals(at(13, 0), candidate.start)
        assertEquals(38, candidate.durationMin)
        assertTrue(candidate.reasons().any { it.contains("Netflix") })
    }

    @Test
    fun `a two-minute glance at a video app is not an evening's viewing`() {
        val w = window(
            Signal(at(13, 0), SignalTypes.MEDIA_APP_ACTIVE, SignalSources.USAGE_STATS, value = "2")
        )

        assertTrue(TvDetector().detect(w, context()).isEmpty())
    }

    @Test
    fun `a remote app session read from usage access reads as watching TV`() {
        // Exactly the two signals UsageStatsSource emits for one foreground run.
        val w = window(
            Signal(at(20, 0), SignalTypes.TV_REMOTE_INTERACTION, SignalSources.USAGE_STATS,
                value = "com.google.android.apps.googletv", metadata = "Google TV"),
            Signal(at(20, 42), SignalTypes.TV_NAVIGATION, SignalSources.USAGE_STATS,
                value = "com.google.android.apps.googletv", metadata = "Google TV")
        )

        val candidate = TvDetector().detect(w, context()).single()

        assertEquals(ActivityCatalog.WATCHING_TV, candidate.activityCode)
        assertEquals(42, candidate.durationMin)
    }

    @Test
    fun `overlapping calls on different apps keep their own start and end`() {
        // A WhatsApp call taken during a cellular call: pairing by arrival order
        // alone would hand one of them the other's end time.
        val w = window(
            Signal(at(10, 0), SignalTypes.CALL_ANSWERED, value = CallDetector.CARRIER_CELLULAR),
            Signal(at(10, 5), SignalTypes.CALL_ANSWERED, value = CallDetector.CARRIER_WHATSAPP),
            Signal(at(10, 12), SignalTypes.CALL_ENDED, value = CallDetector.CARRIER_WHATSAPP),
            Signal(at(10, 30), SignalTypes.CALL_ENDED, value = CallDetector.CARRIER_CELLULAR)
        )

        val calls = CallDetector().detect(w, context()).sortedBy { it.start }

        assertEquals(2, calls.size)
        assertEquals(at(10, 0), calls[0].start)
        assertEquals(30, calls[0].durationMin)
        assertEquals(at(10, 5), calls[1].start)
        assertEquals(7, calls[1].durationMin)
    }

    @Test
    fun `a Teams call from a notification is an office meeting`() {
        val w = window(
            Signal(at(14, 1), SignalTypes.CALL_ANSWERED, SignalSources.NOTIFICATION,
                value = CallDetector.CARRIER_TEAMS),
            Signal(at(15, 2), SignalTypes.CALL_ENDED, SignalSources.NOTIFICATION,
                value = CallDetector.CARRIER_TEAMS)
        )

        val candidate = CallDetector().detect(w, context()).single()

        assertEquals(ActivityCatalog.MEETING, candidate.activityCode)
        assertEquals(61, candidate.durationMin)
    }

    @Test
    fun `a WhatsApp call that only ever rang creates nothing`() {
        val w = window(
            Signal(at(18, 10), SignalTypes.CALL_MISSED, SignalSources.NOTIFICATION,
                value = CallDetector.CARRIER_WHATSAPP)
        )

        assertTrue(CallDetector().detect(w, context()).isEmpty())
    }

    @Test
    fun `the call apps watched map to the right kind of activity`() {
        val mapped = CallApps.WATCHED
        assertEquals(CallDetector.CARRIER_WHATSAPP, mapped[CallApps.WHATSAPP])
        assertEquals(CallDetector.CARRIER_TEAMS, mapped[CallApps.TEAMS])
        assertEquals(
            ActivityCatalog.MEETING,
            CallDetector.DEFAULT_APP_ACTIVITY[CallDetector.CARRIER_TEAMS]
        )
        assertEquals(
            ActivityCatalog.PHONE_CALL,
            CallDetector.DEFAULT_APP_ACTIVITY[CallDetector.CARRIER_WHATSAPP]
        )
        assertNull("anything not a call app must be invisible", CallApps.carrierFor("com.android.chrome"))
        assertNull(CallApps.carrierFor(null))
    }

    @Test
    fun `only remote and video packages are recognised at all`() {
        val apps = AppSignalMap.DEFAULT
        assertEquals(AppSignalMap.Role.TV_REMOTE, apps.roleOf("com.google.android.apps.googletv"))
        assertEquals(AppSignalMap.Role.MEDIA, apps.roleOf("com.netflix.mediaclient"))
        assertNull("a banking app must not be readable", apps.roleOf("com.example.bank"))
        assertNull("a browser must not be readable", apps.roleOf("com.android.chrome"))
        assertNull("music is not television", apps.roleOf("com.spotify.music"))
    }

    @Test
    fun `a wake marker does not suppress the breakfast question`() {
        val wake = ActivityCandidate(ActivityCatalog.WAKE, at(8, 0), at(8, 0), 0.8f)
        val breakfast = ActivityCandidate(ActivityCatalog.BREAKFAST, at(7, 50), at(8, 20), 0.3f)

        val resolved = CandidateResolver.resolve(listOf(wake, breakfast)).map { it.activityCode }

        assertTrue(resolved.contains(ActivityCatalog.WAKE))
        assertTrue(resolved.contains(ActivityCatalog.BREAKFAST))
    }

    @Test
    fun `an unknown activity code degrades to something readable`() {
        assertEquals("Lunch", ActivityCatalog.labelFor(ActivityCatalog.LUNCH))
        // Written by a future release this build has never heard of.
        assertEquals("Kitchen visit", ActivityCatalog.labelFor("KITCHEN_VISIT"))
        assertNotNull(ActivityCatalog.iconFor("KITCHEN_VISIT"))
    }

    @Test
    fun `an unknown status is treated as provisional rather than as user truth`() {
        val status = ActivityStatus.of("SOMETHING_NEW")
        assertEquals(ActivityStatus.INFERRED, status)
        assertTrue(status.isProvisional)
        assertTrue(!status.isUserTruth)
    }

    // -----------------------------------------------------------------------
    // Learning (plan §13, §31 "Tea")
    // -----------------------------------------------------------------------

    @Test
    fun `repeated confirmations teach the app when your tea break is`() {
        val confirmations = listOf(640, 645, 638, 651, 642, 647).mapIndexed { i, min ->
            ConfirmedOccurrence(ActivityCatalog.TEA_BREAK, min, 12, 2L, day - i)
        }

        val profile = LearningEngine.learn(confirmations)[ActivityCatalog.TEA_BREAK]!!

        assertTrue(profile.isEstablished)
        assertTrue("median should land near 10:43", profile.typicalStartMin in 640..647)
        assertEquals(12, profile.typicalDurationMin)
        assertEquals(2L, profile.typicalPlaceId)
        assertTrue(profile.matchesStart(644))
        assertTrue(!profile.matchesStart(900))
    }

    @Test
    fun `three observations are a hint, not an established pattern`() {
        val profile = LearningEngine.learn(
            (0..2).map { ConfirmedOccurrence(ActivityCatalog.LUNCH, 780, 40, null, day - it) }
        )[ActivityCatalog.LUNCH]!!

        assertTrue(!profile.isEstablished)
        assertEquals(0f, profile.affinity(780, 40, null), 0.0001f)
    }

    @Test
    fun `one outlying weekend lunch does not drag the weekday estimate`() {
        val rows = listOf(780, 785, 778, 790, 782, 1020).mapIndexed { i, min ->
            ConfirmedOccurrence(ActivityCatalog.LUNCH, min, 42, null, day - i)
        }

        val profile = LearningEngine.learn(rows)[ActivityCatalog.LUNCH]!!

        assertTrue("median must resist the 17:00 outlier", profile.typicalStartMin < 800)
    }

    // -----------------------------------------------------------------------
    // Bridging existing history (plan §22)
    // -----------------------------------------------------------------------

    @Test
    fun `screen samples become edges only when the level actually changed`() {
        val rows = listOf(
            rawScreen(at(7, 0), interactive = false),
            rawScreen(at(7, 15), interactive = true),
            rawScreen(at(7, 30), interactive = true),
            rawScreen(at(7, 45), interactive = false)
        )

        val signals = SignalBridge.fromAll(rows)

        assertEquals(1, signals.count { it.type == SignalTypes.SCREEN_ON })
        assertEquals(1, signals.count { it.type == SignalTypes.SCREEN_OFF })
        assertEquals(at(7, 15), signals.first { it.type == SignalTypes.SCREEN_ON }.timestamp)
    }

    @Test
    fun `an existing user's old sensor rows are enough to detect a wake`() {
        // Nothing here was recorded with wake detection in mind — it is the
        // fifteen-minute sampling the app has been doing since version one.
        val rows = listOf(
            rawCharging(at(7, 45), charging = true),
            rawCharging(at(8, 0), charging = true),
            rawScreen(at(8, 0), interactive = false),
            rawCharging(at(8, 15), charging = false),
            rawScreen(at(8, 15), interactive = true),
            rawScreen(at(8, 30), interactive = true),
            rawActivity(at(8, 20), com.dhinasuthra.app.core.model.ActivityKind.WALKING)
        )

        val assessment = WakeDetector().assess(SignalWindow(SignalBridge.fromAll(rows)), context())

        assertTrue(
            "expected a wake from bridged legacy signals, got ${assessment.state}",
            assessment.state != AwakeningState.SLEEP
        )
        assertNotNull(assessment.timelineWake)
    }

    private fun rawScreen(ts: Long, interactive: Boolean) =
        com.dhinasuthra.app.core.database.RawSensorEventEntity(
            timestamp = ts,
            type = com.dhinasuthra.app.core.model.SensorSignalType.SCREEN_SAMPLE,
            interactive = interactive
        )

    private fun rawCharging(ts: Long, charging: Boolean) =
        com.dhinasuthra.app.core.database.RawSensorEventEntity(
            timestamp = ts,
            type = com.dhinasuthra.app.core.model.SensorSignalType.CHARGING_SAMPLE,
            charging = charging
        )

    private fun rawActivity(ts: Long, kind: com.dhinasuthra.app.core.model.ActivityKind) =
        com.dhinasuthra.app.core.database.RawSensorEventEntity(
            timestamp = ts,
            type = com.dhinasuthra.app.core.model.SensorSignalType.ACTIVITY_ENTER,
            activity = kind
        )
}
