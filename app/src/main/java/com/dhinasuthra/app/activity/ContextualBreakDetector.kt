package com.dhinasuthra.app.activity

/**
 * Contextual break classification (plan §11, §12).
 *
 * [BoundaryDetector] finds that you were away from your desk between 12:52 and
 * 13:42. This decides that it was probably lunch — and then, crucially, *asks*.
 * Plan §11 is emphatic: "Do not automatically make it confirmed."
 *
 * The windows below are starting points, not truths. Plan §12 says to "start
 * with broad contextual windows, then learn from confirmations", so a
 * [LearnedProfile] built from what the user has actually confirmed overrides the
 * default window and narrows it. Once someone has confirmed four teas at around
 * 10:42, an interruption at 10:40 is scored against *their* 10:42, not against a
 * generic hour-long band.
 *
 * Windows are constructor arguments so they can be made user-configurable
 * without touching the logic — plan §11: "The exact lunch window should
 * eventually become configurable and learnable per user."
 */
class ContextualBreakDetector(
    private val windows: List<BreakWindow> = DEFAULT_WINDOWS,
    private val boundaries: BoundaryDetector = BoundaryDetector()
) : ActivityDetector {

    override val id = "contextual-break"

    /**
     * A time of day that usually means something. [minDurationMin] keeps a
     * two-minute wander from being reported as lunch.
     */
    data class BreakWindow(
        val activityCode: String,
        val fromMin: Int,
        val toMin: Int,
        val minDurationMin: Int,
        val maxDurationMin: Int,
        /** What the user is asked, in their language. */
        val prompt: String
    ) {
        fun contains(minuteOfDay: Int) = minuteOfDay in fromMin..toMin
        fun plausible(durationMin: Int) = durationMin in minDurationMin..maxDurationMin
    }

    override fun detect(window: SignalWindow, context: DetectionContext): List<ActivityCandidate> =
        boundaries.interruptions(window, context).mapNotNull { classify(it, context) }

    /**
     * Give an interruption a name, if the context supports one.
     *
     * Returns null rather than guessing when nothing fits: an unnamed
     * interruption is a better answer than a wrong lunch, and the timeline is
     * perfectly capable of showing "stepped away for 20 minutes".
     */
    fun classify(
        interruption: BoundaryDetector.Interruption,
        context: DetectionContext
    ): ActivityCandidate? {
        val startMin = context.minuteOfDay(interruption.start)
        val duration = interruption.durationMin
        val weights = context.weights

        // The user's own pattern outranks the default windows entirely.
        val learned = context.profiles.values
            .filter { it.isEstablished }
            .maxByOrNull { it.affinity(startMin, duration, interruption.placeId) }
            ?.takeIf { it.affinity(startMin, duration, interruption.placeId) >= 0.5f }

        val candidateWindow = windows.firstOrNull { it.contains(startMin) && it.plausible(duration) }
        val code = learned?.activityCode ?: candidateWindow?.activityCode ?: return null

        val evidence = EvidenceBundle(interruption.evidence)
        if (candidateWindow != null && candidateWindow.activityCode == code) {
            evidence.add(
                interruption.start, SignalTypes.STILL, weights[WeightConfig.MEAL_IN_WINDOW],
                "it happened when people usually take a ${ActivityCatalog.labelFor(code).lowercase()}"
            )
        }
        if (learned != null) {
            evidence.add(
                interruption.start, SignalTypes.STILL, weights[WeightConfig.MEAL_MATCHES_LEARNED],
                "it matches your usual ${ActivityCatalog.labelFor(code).lowercase()} — ${learned.describe()}"
            )
            if (learned.matchesDuration(duration)) {
                evidence.add(
                    interruption.end, SignalTypes.STILL, weights[WeightConfig.MEAL_DURATION_TYPICAL],
                    "it lasted about as long as it usually does"
                )
            }
            if (learned.typicalPlaceId != null && learned.typicalPlaceId == interruption.placeId) {
                evidence.add(
                    interruption.start, SignalTypes.ARRIVED, weights[WeightConfig.MEAL_LOCATION_MATCHES],
                    "you were where you usually go"
                )
            }
        }

        return ActivityCandidate(
            activityCode = code,
            start = interruption.start,
            end = interruption.end,
            confidence = evidence.confidence(),
            evidence = evidence.items,
            // Provisional, always. Plan §11: detected, not decided.
            status = ActivityStatus.INFERRED,
            placeId = interruption.placeId,
            detectorId = id
        )
    }

    companion object {
        /**
         * The out-of-the-box windows from plan §11 and §12. Broad on purpose —
         * they exist to be replaced by what the user actually confirms.
         */
        val DEFAULT_WINDOWS = listOf(
            BreakWindow(
                activityCode = ActivityCatalog.LUNCH,
                fromMin = 12 * 60 + 45, toMin = 14 * 60 + 30,
                minDurationMin = 15, maxDurationMin = 120,
                prompt = "Lunch break?"
            ),
            BreakWindow(
                activityCode = ActivityCatalog.TEA_BREAK,
                fromMin = 10 * 60 + 30, toMin = 11 * 60 + 30,
                minDurationMin = 5, maxDurationMin = 40,
                prompt = "Tea break?"
            ),
            BreakWindow(
                activityCode = ActivityCatalog.TEA_BREAK,
                fromMin = 15 * 60 + 30, toMin = 17 * 60,
                minDurationMin = 5, maxDurationMin = 40,
                prompt = "Tea or coffee?"
            )
        )
    }
}
