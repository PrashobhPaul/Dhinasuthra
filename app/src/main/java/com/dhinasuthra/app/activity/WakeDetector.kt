package com.dhinasuthra.app.activity

/**
 * Wake-up detection (plan §6, §7).
 *
 * The old behaviour was too conservative: sleep ran on until a *late* movement
 * event, so a person who woke at 09:00, read their phone in bed for twenty
 * minutes and only got up at 10:06 was recorded as having slept until 10:06.
 * Plan §6 replaces that with an awakening model:
 *
 * ```
 * SLEEP → FIRST_WAKE_EVIDENCE → AWAKENING → WAKE_CONFIRMED → AWAKE
 * ```
 *
 * The distinction that makes it work is between a *stir* and *waking up*. A
 * single roll-over at 03:00 with the screen never coming on is a stir and must
 * leave you asleep (plan §31, "False wake"). The same motion at 08:57 followed
 * by the charger coming out and five minutes of phone use is not.
 *
 * The state machine keeps all three moments — first stir, likely awake,
 * confirmed awake — because the confirmation prompt in plan §7 offers the user
 * a choice between two of them. The timeline draws one boundary; the evidence
 * remembers the rest.
 */

/** Where the night has got to. Exposed so the UI and tests can talk about it. */
enum class AwakeningState {
    SLEEP,
    FIRST_WAKE_EVIDENCE,
    AWAKENING,
    WAKE_CONFIRMED,
    AWAKE
}

data class WakeAssessment(
    val state: AwakeningState,
    /** The earliest movement that turned out to be part of waking up. */
    val firstStir: Long?,
    /** Where the evidence first crossed "probably awake". */
    val likelyAwake: Long?,
    /** Where it became unambiguous. */
    val confirmedAwake: Long?,
    val confidence: Float,
    val evidence: List<EvidenceItem>
) {
    /** The single boundary the timeline draws (plan §6: "The UI can choose one"). */
    val timelineWake: Long? get() = likelyAwake ?: confirmedAwake ?: firstStir
}

class WakeDetector(
    /** Where the morning wake window opens and closes, in minutes of the day. */
    private val windowStartMin: Int = 4 * 60,
    private val windowEndMin: Int = 12 * 60,
    /** Sustained phone use above this is decisive on its own. */
    private val sustainedUseMin: Int = 5,
    /** Below this, phone use is a glance at the clock. */
    private val briefUseMin: Int = 3,
    /** How long after unplugging a movement still counts as "and then they got up". */
    private val unplugToMotionMs: Long = 20 * 60_000L,
    /** The confidence at which the machine stops hedging. */
    private val confirmThreshold: Float = 0.60f,
    private val likelyThreshold: Float = 0.35f,
    /** Below this there is a stir on the record, but no awakening. */
    private val stirThreshold: Float = 0.10f
) : ActivityDetector {

    override val id = "wake"

    override fun detect(window: SignalWindow, context: DetectionContext): List<ActivityCandidate> {
        val assessment = assess(window, context)
        val wake = assessment.timelineWake ?: return emptyList()
        if (assessment.state == AwakeningState.SLEEP) return emptyList()

        return listOf(
            ActivityCandidate(
                activityCode = ActivityCatalog.WAKE,
                start = wake,
                end = wake,
                confidence = assessment.confidence,
                evidence = assessment.evidence,
                status = ActivityStatus.INFERRED,
                detectorId = id,
                // Plan §7 wants the prompt to offer both times rather than make
                // the user pick one out of the air.
                alternativeStart = assessment.firstStir.takeIf { it != wake }
            )
        )
    }

    /**
     * Walk the night's signals once and decide how far the awakening got.
     *
     * Evidence accumulates; state is a function of the accumulated confidence,
     * not of any single event. That is what stops one motion sample ending a
     * night's sleep, and what lets three weak signals do it together.
     */
    fun assess(window: SignalWindow, context: DetectionContext): WakeAssessment {
        val weights = context.weights
        val evidence = EvidenceBundle()

        val inWindow = window.signals.filter {
            val m = context.minuteOfDay(it.timestamp)
            m in windowStartMin..windowEndMin
        }
        if (inWindow.isEmpty()) {
            return WakeAssessment(AwakeningState.SLEEP, null, null, null, 0f, emptyList())
        }

        val windowFrom = inWindow.first().timestamp
        val windowTo = inWindow.last().timestamp

        var state = AwakeningState.SLEEP
        var firstStir: Long? = null
        var likelyAwake: Long? = null
        var confirmedAwake: Long? = null
        var motionCount = 0
        var lastUnplug: Long? = null
        var creditedUnplug = false

        /** Advance the machine to whatever the confidence now justifies. */
        fun reconsider(at: Long) {
            val c = evidence.confidence()
            if (state == AwakeningState.SLEEP && !evidence.isEmpty) {
                state = AwakeningState.FIRST_WAKE_EVIDENCE
            }
            if (c >= likelyThreshold && likelyAwake == null) {
                likelyAwake = at
                state = AwakeningState.AWAKENING
            }
            if (c >= confirmThreshold && confirmedAwake == null) {
                confirmedAwake = at
                state = AwakeningState.WAKE_CONFIRMED
            }
        }

        for (signal in inWindow) {
            val at = signal.timestamp
            when (signal.type) {
                SignalTypes.CHARGER_DISCONNECTED -> lastUnplug = at

                SignalTypes.MOTION -> {
                    motionCount++
                    if (firstStir == null) firstStir = at
                    // Plan §6: unplugging and *then* moving is strong; either
                    // alone is not. The pair is credited once, not per sample.
                    val unplug = lastUnplug
                    if (unplug != null && !creditedUnplug && at - unplug <= unplugToMotionMs) {
                        creditedUnplug = true
                        evidence.add(
                            at, SignalTypes.CHARGER_DISCONNECTED,
                            weights[WeightConfig.WAKE_UNPLUG_AND_MOTION],
                            "you unplugged your phone and then moved"
                        )
                    }
                    when (motionCount) {
                        1 -> evidence.add(
                            at, SignalTypes.MOTION, weights[WeightConfig.WAKE_SINGLE_MOTION],
                            "you stirred"
                        )
                        // Repeated movement is a different fact from one twitch.
                        2, 3 -> evidence.add(
                            at, SignalTypes.MOTION, weights[WeightConfig.WAKE_REPEATED_MOTION],
                            "you moved more than once"
                        )
                        else -> Unit
                    }
                }

                SignalTypes.WALKING, SignalTypes.RUNNING -> {
                    if (firstStir == null) firstStir = at
                    evidence.add(
                        at, signal.type, weights[WeightConfig.WAKE_WALKING],
                        "you were up and walking"
                    )
                }

                SignalTypes.LOCATION_CHANGE, SignalTypes.DEPARTED -> {
                    if (firstStir == null) firstStir = at
                    evidence.add(
                        at, signal.type, weights[WeightConfig.WAKE_LOCATION_MOVEMENT],
                        "you had moved somewhere else"
                    )
                }

                SignalTypes.USER_PRESENT, SignalTypes.APP_OPENED, SignalTypes.SCREEN_ACTIVE -> {
                    evidence.add(
                        at, signal.type, weights[WeightConfig.WAKE_PHONE_INTERACTION],
                        "you picked up your phone and used it"
                    )
                }

                else -> Unit
            }
            reconsider(at)
        }

        // Sustained use is scored once, from the longest run, rather than one
        // increment per sample — otherwise a chatty sensor outvotes the truth.
        window.longestScreenRunMinutes(windowFrom, windowTo)?.let { (start, minutes) ->
            if (minutes >= sustainedUseMin) {
                evidence.add(
                    start, SignalTypes.SCREEN_TIME, weights[WeightConfig.WAKE_SCREEN_SUSTAINED],
                    "your phone was in use for $minutes minutes"
                )
                if (firstStir == null) firstStir = start
                reconsider(start)
                // The strongest single signal there is: it should be able to
                // confirm on its own, even arriving after the loop above.
                if (confirmedAwake == null && evidence.confidence() >= confirmThreshold) {
                    confirmedAwake = start
                    state = AwakeningState.WAKE_CONFIRMED
                }
                if (likelyAwake == null || start < likelyAwake!!) likelyAwake = start
            } else if (minutes in briefUseMin until sustainedUseMin) {
                evidence.add(
                    start, SignalTypes.SCREEN_TIME, weights[WeightConfig.WAKE_PHONE_INTERACTION],
                    "your phone was in use for $minutes minutes"
                )
                reconsider(start)
            }
        }

        // Plan §31's "false wake": movement with the screen never coming on is a
        // stir. Recording it as contradictory evidence is what keeps 03:00
        // roll-overs out of the timeline, rather than a special case elsewhere.
        val everInteracted = inWindow.any { it.isInteraction } ||
            window.ofTypeBetween(setOf(SignalTypes.SCREEN_ON), windowFrom, windowTo).isNotEmpty()
        // Only when motion is *all* there is. Someone who unplugged their phone
        // and walked out of the room is up, whether or not they looked at it.
        val onlyWeakMotion = !creditedUnplug && inWindow.none {
            it.type == SignalTypes.WALKING || it.type == SignalTypes.RUNNING ||
                it.type == SignalTypes.LOCATION_CHANGE || it.type == SignalTypes.DEPARTED
        }
        if (!everInteracted && onlyWeakMotion) {
            evidence.add(
                windowFrom, SignalTypes.SCREEN_OFF, weights[WeightConfig.WAKE_SCREEN_STAYED_OFF],
                "your screen stayed off"
            )
        }

        // A wake that lands where this person usually wakes is likelier than one
        // that doesn't — but only once we've actually learned where that is.
        val profile = context.profile(ActivityCatalog.WAKE)
        val provisional = likelyAwake ?: confirmedAwake
        if (profile != null && profile.isEstablished && provisional != null &&
            profile.matchesStart(context.minuteOfDay(provisional))
        ) {
            evidence.add(
                provisional, SignalTypes.MOTION, weights[WeightConfig.WAKE_IN_USUAL_WINDOW],
                "it matches when you usually wake"
            )
        }

        val confidence = evidence.confidence()
        // Re-derive the final state from the final confidence: the contradiction
        // above can legitimately pull a borderline wake back to a stir.
        val finalState = when {
            confidence >= confirmThreshold -> AwakeningState.WAKE_CONFIRMED
            confidence >= likelyThreshold -> AwakeningState.AWAKENING
            confidence >= stirThreshold -> AwakeningState.FIRST_WAKE_EVIDENCE
            else -> AwakeningState.SLEEP
        }

        return WakeAssessment(
            state = finalState,
            firstStir = firstStir,
            likelyAwake = if (finalState.ordinal >= AwakeningState.AWAKENING.ordinal) likelyAwake else null,
            confirmedAwake = if (finalState == AwakeningState.WAKE_CONFIRMED) confirmedAwake else null,
            confidence = confidence,
            evidence = evidence.items
        )
    }
}
