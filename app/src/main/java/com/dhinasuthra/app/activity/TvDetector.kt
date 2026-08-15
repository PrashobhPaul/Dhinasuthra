package com.dhinasuthra.app.activity

/**
 * Television watching from external-device signals (plan §16).
 *
 * When the phone is used as a Google/Android TV remote, the interactions are a
 * strong hint — but plan §16 draws a line that most implementations wouldn't:
 *
 * > Distinguish `TV_ON` from `USER_WATCHING_TV`. A TV being ON does not prove
 * > the user is watching it.
 *
 * So opening the remote app once is *not* an evening in front of the telly. A
 * session needs repeated interaction spread over time, and it ends when the
 * interactions stop — not when the TV does. If the TV reports itself as on but
 * nobody has touched anything for [presenceTimeoutMin], that's counted as
 * evidence *against* the person still being there.
 */
class TvDetector(
    /** A lull longer than this ends the session. */
    private val sessionGapMin: Int = 45,
    /** No interaction for this long, while the TV is on, argues nobody is watching. */
    private val presenceTimeoutMin: Int = 60,
    /** One tap is not a session. */
    private val minInteractions: Int = 2,
    private val minSessionMin: Int = 10
) : ActivityDetector {

    override val id = "tv"

    private val remoteSignals = setOf(
        SignalTypes.TV_REMOTE_INTERACTION,
        SignalTypes.TV_NAVIGATION,
        SignalTypes.TV_PLAYBACK,
        SignalTypes.TV_VOLUME
    )

    override fun detect(window: SignalWindow, context: DetectionContext): List<ActivityCandidate> {
        val weights = context.weights
        val out = mutableListOf<ActivityCandidate>()
        out += mediaSessions(window, context)

        val interactions = window.signals.filter { it.type in remoteSignals }
        if (interactions.isEmpty()) return out

        val gapMs = sessionGapMin * 60_000L

        // Group interactions into sessions separated by long lulls.
        var group = mutableListOf(interactions.first())
        val groups = mutableListOf<List<Signal>>()
        for (s in interactions.drop(1)) {
            if (s.timestamp - group.last().timestamp > gapMs) {
                groups.add(group)
                group = mutableListOf(s)
            } else {
                group.add(s)
            }
        }
        groups.add(group)

        for (session in groups) {
            if (session.size < minInteractions) continue
            val start = session.first().timestamp
            val end = session.last().timestamp
            val minutes = ((end - start) / 60_000L).toInt()
            if (minutes < minSessionMin) continue

            val evidence = EvidenceBundle()
            evidence.add(
                start, SignalTypes.TV_REMOTE_INTERACTION, weights[WeightConfig.TV_REMOTE_USED],
                "you used the TV remote"
            )
            evidence.add(
                end, SignalTypes.TV_REMOTE_INTERACTION, weights[WeightConfig.TV_REPEATED_REMOTE],
                "you kept using it over $minutes minutes"
            )
            if (session.any { it.type == SignalTypes.TV_PLAYBACK }) {
                evidence.add(
                    start, SignalTypes.TV_PLAYBACK, weights[WeightConfig.TV_PLAYBACK],
                    "you started something playing"
                )
            }

            // Corroboration: at home, and not moving about.
            val home = context.anchor(DetectionContext.ANCHOR_HOME)
            val atHome = home != null && window.ofTypeBetween(
                setOf(SignalTypes.ARRIVED), start - 6 * 3_600_000L, end
            ).any { it.value == home.placeId?.toString() }
            if (atHome) {
                evidence.add(start, SignalTypes.ARRIVED, weights[WeightConfig.TV_AT_HOME], "you were at home")
            }
            val moved = window.ofTypeBetween(
                setOf(SignalTypes.WALKING, SignalTypes.RUNNING, SignalTypes.IN_VEHICLE), start, end
            )
            if (moved.isEmpty()) {
                evidence.add(start, SignalTypes.STILL, weights[WeightConfig.TV_STATIONARY], "you stayed put")
            }

            // Plan §16: the TV being on is not the user being there.
            val tvStillOn = window.ofTypeBetween(setOf(SignalTypes.TV_ON), end, end + presenceTimeoutMin * 60_000L)
            val laterInteraction = window.firstAfter(end + 1, remoteSignals)
            if (tvStillOn.isNotEmpty() && laterInteraction == null) {
                evidence.add(
                    end, SignalTypes.TV_ON, weights[WeightConfig.TV_NOT_PRESENT],
                    "the TV stayed on after you stopped touching anything"
                )
            }

            out.add(
                ActivityCandidate(
                    activityCode = ActivityCatalog.WATCHING_TV,
                    start = start,
                    end = end,
                    confidence = evidence.confidence(),
                    evidence = evidence.items,
                    status = ActivityStatus.INFERRED,
                    detectorId = id
                )
            )
        }
        return out
    }

    /**
     * A video app that held the screen for a while.
     *
     * Distinct from remote use — this is something playing, wherever it was
     * playing — but it lands on the timeline the same way, because from the
     * user's side both are "I was watching something". The evidence names the
     * app so they can tell at a glance which it was.
     */
    private fun mediaSessions(
        window: SignalWindow,
        context: DetectionContext
    ): List<ActivityCandidate> =
        window.ofType(SignalTypes.MEDIA_APP_ACTIVE).mapNotNull { signal ->
            val minutes = signal.minutes ?: return@mapNotNull null
            if (minutes < minSessionMin) return@mapNotNull null
            val end = signal.timestamp + minutes * 60_000L
            val app = signal.metadata

            val evidence = EvidenceBundle().add(
                signal.timestamp, SignalTypes.MEDIA_APP_ACTIVE,
                context.weights[WeightConfig.TV_MEDIA_APP],
                if (app != null) "you had $app open for $minutes minutes"
                else "something was playing for $minutes minutes"
            )
            val moved = window.ofTypeBetween(
                setOf(SignalTypes.WALKING, SignalTypes.RUNNING, SignalTypes.IN_VEHICLE),
                signal.timestamp, end
            )
            if (moved.isEmpty()) {
                evidence.add(
                    signal.timestamp, SignalTypes.STILL,
                    context.weights[WeightConfig.TV_STATIONARY], "you stayed put"
                )
            }

            ActivityCandidate(
                activityCode = ActivityCatalog.WATCHING_TV,
                start = signal.timestamp,
                end = end,
                confidence = evidence.confidence(),
                evidence = evidence.items,
                status = ActivityStatus.INFERRED,
                detectorId = id
            )
        }
}

/**
 * Calls as activities (plan §31: Calls, WhatsApp call, Teams meeting, Missed call).
 *
 * Two rules decide everything here.
 *
 * **Duration runs from answer to end, never from ring.** A call that rang for
 * twenty seconds and was answered lasted from the answer. A call that was never
 * answered lasted nothing at all, and plan §31 is explicit that a missed call
 * must not create a duration activity — so an unanswered ring produces no
 * candidate, not a zero-length one.
 *
 * **What the call was for depends on which app carried it.** A Teams call is an
 * office meeting; a WhatsApp or cellular call is a call. That mapping is a table
 * rather than a chain of `if`s, so supporting the next app is one line.
 */
class CallDetector(
    private val appActivity: Map<String, String> = DEFAULT_APP_ACTIVITY,
    /**
     * Zero, on purpose. A forty-second call to your mother happened, and an app
     * that quietly drops it is wrong about the day in a way the user will notice.
     * The only calls excluded are the ones that never connected.
     */
    private val minCallMin: Int = 0
) : ActivityDetector {

    override val id = "call"

    override fun detect(window: SignalWindow, context: DetectionContext): List<ActivityCandidate> {
        val out = mutableListOf<ActivityCandidate>()
        // Keyed by which app carried the call: a cellular call and a WhatsApp
        // call can overlap, and pairing them by arrival order alone would give
        // one of them the other's end time.
        val answered = HashMap<String, Signal>()

        for (signal in window.signals) {
            val carrier = signal.value ?: UNKNOWN_CARRIER
            when (signal.type) {
                SignalTypes.CALL_ANSWERED -> answered[carrier] = signal
                SignalTypes.CALL_MISSED -> answered.remove(carrier)  // never connected
                SignalTypes.CALL_ENDED -> {
                    val start = answered.remove(carrier) ?: continue
                    if (signal.timestamp <= start.timestamp) continue
                    val minutes = ((signal.timestamp - start.timestamp) / 60_000L).toInt()
                    if (minutes < minCallMin) continue

                    val app = start.value ?: signal.value
                    val code = appActivity[app] ?: ActivityCatalog.PHONE_CALL
                    val via = app?.let { CARRIER_LABELS[it] }

                    // The contact name, when Android had one cached. It never
                    // leaves the device — the app holds no INTERNET permission.
                    val who = start.metadata ?: signal.metadata
                    val detail = when {
                        who != null && via != null -> "a $via call with $who, ${lengthOf(minutes)}"
                        who != null -> "a call with $who, ${lengthOf(minutes)}"
                        via != null -> "a $via call you answered, ${lengthOf(minutes)}"
                        else -> "a call you answered, ${lengthOf(minutes)}"
                    }
                    val evidence = EvidenceBundle().add(
                        start.timestamp, SignalTypes.CALL_ANSWERED,
                        context.weights[WeightConfig.CALL_CONNECTED],
                        detail
                    )

                    out.add(
                        ActivityCandidate(
                            activityCode = code,
                            start = start.timestamp,
                            end = signal.timestamp,
                            confidence = evidence.confidence(),
                            evidence = evidence.items,
                            // Observed, not reasoned about: the call demonstrably happened.
                            status = ActivityStatus.SYSTEM_OBSERVED,
                            detectorId = id,
                            source = EventSources.DEVICE
                        )
                    )
                }
                else -> Unit
            }
        }
        return out
    }

    private fun lengthOf(minutes: Int): String =
        if (minutes < 1) "under a minute" else "$minutes minutes"

    companion object {
        private const val UNKNOWN_CARRIER = "UNKNOWN"

        const val CARRIER_CELLULAR = "CELLULAR"
        const val CARRIER_WHATSAPP = "WHATSAPP"
        const val CARRIER_TEAMS = "TEAMS"

        /** Which app means which activity. Extend here, not in the loop. */
        val DEFAULT_APP_ACTIVITY = mapOf(
            CARRIER_CELLULAR to ActivityCatalog.PHONE_CALL,
            CARRIER_WHATSAPP to ActivityCatalog.PHONE_CALL,
            CARRIER_TEAMS to ActivityCatalog.MEETING
        )

        val CARRIER_LABELS = mapOf(
            CARRIER_CELLULAR to "phone",
            CARRIER_WHATSAPP to "WhatsApp",
            CARRIER_TEAMS to "Microsoft Teams"
        )
    }
}
