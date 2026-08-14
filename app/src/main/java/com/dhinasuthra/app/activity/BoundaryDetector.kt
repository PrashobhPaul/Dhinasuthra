package com.dhinasuthra.app.activity

/**
 * The activity boundary engine (plan §8, §9, §10).
 *
 * One component decides when what you were doing stopped, whatever you were
 * doing and wherever you were. At a desk that produces an office break; on the
 * sofa it produces an interruption in an evening of television. The mechanism is
 * the same, which is the point of plan §8 — boundaries are not a per-activity
 * special case.
 *
 * The shape it looks for:
 *
 * ```
 * settled at an anchor → movement away → time elsewhere → return → settled again
 * ```
 *
 * Two guards keep it honest. Plan §9: "Do not treat every tiny movement as a
 * break" — movement inside the desk zone is still work, so only a departure
 * counts. And a departure that never comes back, or that lasts eleven seconds,
 * isn't a break either: it needs [minBreakMin] to earn a place on the timeline.
 *
 * Plan §10 asks that the middle be an `ACTIVITY_INTERRUPTION` rather than an
 * invented new activity, so that is what this emits. [ContextualBreakDetector]
 * is what later decides an interruption was probably lunch.
 */
class BoundaryDetector(
    /** Shorter than this is a stretch of the legs, not a break. */
    private val minBreakMin: Int = 5,
    /** Longer than this stops being a break and becomes the rest of your day. */
    private val maxBreakMin: Int = 150,
    /** Movement must settle for this long before a return counts as "back". */
    private val settleMin: Int = 3
) : ActivityDetector {

    override val id = "boundary"

    override fun detect(window: SignalWindow, context: DetectionContext): List<ActivityCandidate> =
        interruptions(window, context).map { it.toCandidate(id) }

    /**
     * Every away-and-back cycle in the window.
     *
     * Exposed separately from [detect] because the contextual classifier needs
     * the raw shape — with its start, end and place — before deciding what to
     * call it.
     */
    fun interruptions(window: SignalWindow, context: DetectionContext): List<Interruption> {
        val weights = context.weights
        val out = mutableListOf<Interruption>()

        var awaySince: Long? = null
        var awayEvidence = EvidenceBundle()
        var lastMovement: Long? = null

        fun close(returnedAt: Long, returnEvidence: EvidenceItem?) {
            val start = awaySince ?: return
            val minutes = ((returnedAt - start) / 60_000L).toInt()
            awaySince = null
            val gathered = awayEvidence
            awayEvidence = EvidenceBundle()
            if (minutes < minBreakMin || minutes > maxBreakMin) return

            returnEvidence?.let { gathered.add(it.timestamp, it.signalType, it.weight, it.detail) }
            gathered.add(
                returnedAt, SignalTypes.STILL, weights[WeightConfig.BREAK_DURATION_PLAUSIBLE],
                "you were away for $minutes minutes"
            )
            out.add(
                Interruption(
                    start = start,
                    end = returnedAt,
                    evidence = gathered.items,
                    confidence = gathered.confidence(),
                    placeId = null
                )
            )
        }

        for (signal in window.signals) {
            when (signal.type) {
                SignalTypes.DEPARTED -> {
                    if (awaySince == null) {
                        awaySince = signal.timestamp
                        awayEvidence.add(
                            signal.timestamp, signal.type, weights[WeightConfig.BREAK_LEFT_ANCHOR],
                            "you left where you'd been sitting"
                        )
                    }
                    lastMovement = signal.timestamp
                }

                SignalTypes.WALKING, SignalTypes.RUNNING -> {
                    if (awaySince == null) {
                        // Walking without a recorded departure still starts a
                        // break: not every place the app knows has a geofence.
                        awaySince = signal.timestamp
                        awayEvidence.add(
                            signal.timestamp, signal.type, weights[WeightConfig.BREAK_WALKING],
                            "you got up and walked"
                        )
                    } else {
                        awayEvidence.add(
                            signal.timestamp, signal.type, weights[WeightConfig.BREAK_WALKING],
                            "you were moving about"
                        )
                    }
                    lastMovement = signal.timestamp
                }

                SignalTypes.LOCATION_CHANGE -> {
                    if (awaySince == null) awaySince = signal.timestamp
                    awayEvidence.add(
                        signal.timestamp, signal.type, weights[WeightConfig.BREAK_LOCATION_CHANGE],
                        "you'd moved somewhere else"
                    )
                    lastMovement = signal.timestamp
                }

                SignalTypes.ARRIVED -> close(
                    signal.timestamp,
                    EvidenceItem(
                        signal.timestamp, signal.type, weights[WeightConfig.BREAK_RETURNED],
                        "you came back"
                    )
                )

                SignalTypes.STILL -> {
                    // Settled again, and far enough from the last movement that
                    // this is a genuine return rather than a pause mid-corridor.
                    val since = lastMovement
                    if (awaySince != null && since != null &&
                        signal.timestamp - since >= settleMin * 60_000L
                    ) {
                        close(
                            signal.timestamp,
                            EvidenceItem(
                                signal.timestamp, signal.type, weights[WeightConfig.BREAK_RETURNED],
                                "you settled back down"
                            )
                        )
                    }
                }

                else -> Unit
            }
        }
        return out
    }

    /** One away-and-back cycle, before anyone has decided what it was. */
    data class Interruption(
        val start: Long,
        val end: Long,
        val evidence: List<EvidenceItem>,
        val confidence: Float,
        val placeId: Long?
    ) {
        val durationMin: Int get() = ((end - start) / 60_000L).toInt()

        fun toCandidate(detectorId: String) = ActivityCandidate(
            activityCode = ActivityCatalog.INTERRUPTION,
            start = start,
            end = end,
            confidence = confidence,
            evidence = evidence,
            status = ActivityStatus.INFERRED,
            placeId = placeId,
            detectorId = detectorId
        )
    }
}
