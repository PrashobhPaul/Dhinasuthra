package com.dhinasuthra.app.intelligence

import com.dhinasuthra.app.core.TimeUtils
import com.dhinasuthra.app.core.model.ActivityKind
import com.dhinasuthra.app.core.model.DayType
import kotlin.math.abs

/**
 * The interpreters: pure functions from evidence to activity, one per domain.
 *
 * Each one returns [EpisodeCandidate]s carrying the ledger that produced them, so
 * nothing reaches the screen without an answer to "why do you think that?".
 */

data class EpisodeCandidate(
    val startMin: Int,
    val endMin: Int,
    val activity: ActivityType,
    val location: LocationType,
    val placeName: String? = null,
    val confidence: Float,
    val status: EpisodeStatus = EpisodeStatus.INFERRED,
    val ruleIds: List<String> = emptyList(),
    val evidence: List<String> = emptyList(),
    /** Higher priority wins an overlap regardless of confidence (corrections, sleep). */
    val priority: Int = 0
) {
    val durationMin: Int get() = (endMin - startMin).coerceAtLeast(0)

    fun toEpisode(epochDay: Long, index: Int) = TimeEpisode(
        id = "$epochDay-$index-${activity.name}",
        epochDay = epochDay,
        startMin = startMin,
        endMin = endMin,
        activity = activity,
        location = location,
        placeName = placeName,
        confidence = confidence,
        status = status,
        ruleIds = ruleIds,
        evidence = evidence
    )

    companion object {
        fun from(
            startMin: Int,
            endMin: Int,
            activity: ActivityType,
            location: LocationType,
            ledger: EvidenceLedger,
            placeName: String? = null,
            status: EpisodeStatus = EpisodeStatus.INFERRED,
            priority: Int = 0,
            confidenceOverride: Float? = null
        ) = EpisodeCandidate(
            startMin = startMin,
            endMin = endMin,
            activity = activity,
            location = location,
            placeName = placeName,
            confidence = confidenceOverride ?: ledger.confidence(),
            status = status,
            ruleIds = ledger.ruleIds(),
            evidence = ledger.explanations(),
            priority = priority
        )
    }
}

// ---------------------------------------------------------------------------
// Sleep (spec §11) — the alarm is intent, not wakefulness
// ---------------------------------------------------------------------------

object SleepInterpreter {

    /** Confidence the accumulated wake evidence must reach before sleep is closed. */
    const val WAKE_CONFIRM_THRESHOLD = 0.6f

    /** Minimum quiet run for a night's sleep. */
    const val MIN_NIGHT_MIN = 180

    /** Below this, a quiet run is not the night's sleep at all (SLP-13). */
    const val MIN_ANY_SLEEP_MIN = 90

    const val MAX_NIGHT_MIN = 11 * 60

    data class Night(
        val onsetMin: Int,          // may be negative: minutes before this day's midnight
        val wakeIntentMin: Int,
        val confirmedWakeMin: Int,
        val confidence: Float,
        val ledger: EvidenceLedger
    ) {
        val durationMin: Int get() = (confirmedWakeMin - onsetMin).coerceAtLeast(0)
    }

    fun interpret(e: DayEvidence): Night? {
        val axis = buildAxis(e)
        if (axis.size < 4) return null      // SLP-15: no evidence, no story

        val run = findQuietWindow(axis) ?: return null
        if (run.lengthMin < MIN_NIGHT_MIN) return null

        val ledger = EvidenceLedger()
        ledger.add(
            SleepRules.QUIET_WINDOW,
            "device untouched for ${TimeUtils.formatDurationMin(run.lengthMin)} from ${label(run.startMin)}"
        )
        if (run.absorbedInteractions > 0) {
            ledger.add(
                SleepRules.BRIEF_STIR_IGNORED,
                "absorbed ${run.absorbedInteractions} brief night-time interaction(s) instead of ending the night",
                0f
            )
        }
        if (run.charging) {
            ledger.add(SleepRules.CHARGING_OVERNIGHT, "the phone was charging during the quiet window")
        }
        if (run.lengthMin in MIN_NIGHT_MIN..MAX_NIGHT_MIN) {
            ledger.add(
                SleepRules.PLAUSIBLE_LENGTH,
                "${TimeUtils.formatDurationMin(run.lengthMin)} is within the plausible 3–11 hour range"
            )
        }
        if (locationStable(e, run.startMin, run.endMin)) {
            ledger.add(SleepRules.LOCATION_STABLE, "you stayed in one place across the whole window")
        }
        val sleepBand = e.patterns.usableBand(ActivityType.SLEEP, e.dayType)
        if (sleepBand != null) {
            val normalisedOnset = if (run.startMin < 0) run.startMin + 1440 else run.startMin
            if (sleepBand.contains(normalisedOnset, tolerance = 30)) {
                ledger.add(
                    SleepRules.ONSET_MATCHES_PATTERN,
                    "onset ${label(run.startMin)} sits inside your usual ${TimeUtils.formatMinuteOfDay(sleepBand.p10)}–${TimeUtils.formatMinuteOfDay(sleepBand.p90)} window"
                )
            }
        }

        val wakeIntent = run.endMin
        val confirmed = confirmWake(e, wakeIntent, ledger)
        ledger.add(
            SleepRules.SLEEP_EXTENDS_TO_CONFIRMED_WAKE,
            "sleep recorded to ${label(confirmed)}, the confirmed wake — not to the first stir at ${label(wakeIntent)}",
            0f
        )

        return Night(
            onsetMin = run.startMin,
            wakeIntentMin = wakeIntent,
            confirmedWakeMin = confirmed,
            confidence = ledger.confidence(),
            ledger = ledger
        )
    }

    /** Episodes for the day: the part of the night after midnight, plus the waking-up bridge. */
    fun episodes(e: DayEvidence, night: Night?): List<EpisodeCandidate> {
        if (night == null) return emptyList()
        val out = mutableListOf<EpisodeCandidate>()
        val sleepStart = maxOf(0, night.onsetMin)
        // SLP-11 — sleep runs to the confirmed wake, not to the first stir.
        val sleepEnd = night.confirmedWakeMin.coerceAtMost(e.livedUntilMin)
        val sleepLocation = e.presenceAt(maxOf(sleepStart, 1))?.location ?: LocationType.HOME

        if (sleepEnd > sleepStart) {
            val ledger = night.ledger
            if (night.confirmedWakeMin > night.wakeIntentMin) {
                ledger.add(
                    SleepRules.WAKE_TRANSITION_EPISODE,
                    "first stirred at ${TimeUtils.formatMinuteOfDay(night.wakeIntentMin)}, actually up ${night.confirmedWakeMin - night.wakeIntentMin} minutes later",
                    0f
                )
            }
            out += EpisodeCandidate.from(
                sleepStart, sleepEnd, ActivityType.SLEEP, sleepLocation, ledger,
                priority = 40, confidenceOverride = night.confidence
            )
        }
        findDaytimeRest(e)?.let { out += it }
        return out.filter { it.durationMin > 0 }
    }

    // -- internals ----------------------------------------------------------

    private data class QuietRun(
        val startMin: Int,
        val endMin: Int,
        val charging: Boolean,
        val absorbedInteractions: Int
    ) {
        val lengthMin: Int get() = endMin - startMin
    }

    /** Previous evening (as negative minutes) + this morning, on one continuous axis. */
    private fun buildAxis(e: DayEvidence): List<DeviceSample> {
        val evening = e.previousEveningSamples
            .filter { it.minuteOfDay >= 18 * 60 }
            .map { it.copy(minuteOfDay = it.minuteOfDay - 1440) }
        val morning = e.samples.filter { it.minuteOfDay <= 13 * 60 }
        return (evening + morning).filter { it.interactive != null }.sortedBy { it.minuteOfDay }
    }

    private fun findQuietWindow(axis: List<DeviceSample>): QuietRun? {
        var best: QuietRun? = null
        var start: Int? = null
        var last = 0
        var charging = false
        var absorbed = 0

        fun close(endAt: Int) {
            val s = start ?: return
            val run = QuietRun(s, endAt, charging, absorbed)
            if (run.lengthMin >= MIN_ANY_SLEEP_MIN && (best == null || run.lengthMin > best!!.lengthMin)) {
                best = run
            }
            start = null; charging = false; absorbed = 0
        }

        for ((i, s) in axis.withIndex()) {
            if (s.interactive == false) {
                if (start == null) { start = s.minuteOfDay; charging = false; absorbed = 0 }
                if (s.charging == true) charging = true
                last = s.minuteOfDay
            } else {
                val open = start
                if (open != null) {
                    // SLP-10 — a single isolated stir inside a long quiet run is absorbed.
                    val next = axis.getOrNull(i + 1)
                    val isolated = next?.interactive == false && (s.minuteOfDay - open) >= 60 && absorbed == 0
                    if (isolated) {
                        absorbed++
                    } else {
                        close(s.minuteOfDay)
                    }
                }
            }
        }
        close(last)
        return best
    }

    /**
     * Walk forward from the wake candidate, accumulating evidence, and confirm at the
     * first minute where it crosses [WAKE_CONFIRM_THRESHOLD] (SLP-07/08/09/12).
     */
    private fun confirmWake(e: DayEvidence, wakeIntentMin: Int, ledger: EvidenceLedger): Int {
        data class Signal(val minute: Int, val rule: Rule, val detail: String)

        val signals = mutableListOf<Signal>()

        e.movements
            .firstOrNull { it.isLocomotion && it.startMin >= wakeIntentMin - 5 && it.startMin <= wakeIntentMin + 240 }
            ?.let {
                signals += Signal(
                    it.startMin, SleepRules.MOVEMENT_CONFIRMS_WAKE,
                    "movement (${it.kind.name.lowercase().replace('_', ' ')}) detected at ${label(it.startMin)}"
                )
            }

        val after = e.samples.filter { it.minuteOfDay >= wakeIntentMin }.sortedBy { it.minuteOfDay }
        for (i in 0 until (after.size - 1)) {
            if (after[i].interactive == true && after[i + 1].interactive == true) {
                signals += Signal(
                    after[i + 1].minuteOfDay, SleepRules.INTERACTION_CONFIRMS_WAKE,
                    "the phone was used in two consecutive samples by ${label(after[i + 1].minuteOfDay)}"
                )
                break
            }
        }

        val sleepPlace = e.presenceAt(maxOf(wakeIntentMin - 30, 0))?.placeId
        e.presences.firstOrNull { it.startMin > wakeIntentMin && it.placeId != sleepPlace }?.let {
            signals += Signal(
                it.startMin, SleepRules.DEPARTURE_CONFIRMS_WAKE,
                "you had left for ${it.placeName ?: it.location.label} by ${label(it.startMin)}"
            )
        }

        if (signals.isEmpty()) {
            ledger.cap(
                SleepRules.WAKE_INTENT_ONLY,
                "nothing after ${label(wakeIntentMin)} confirmed waking, so the wake time is a best estimate",
                0.55f
            )
            return wakeIntentMin
        }

        var inverse = 1f
        val sorted = signals.sortedBy { it.minute }
        for (s in sorted) {
            ledger.add(s.rule, s.detail)
            inverse *= (1f - s.rule.weight)
            if (1f - inverse >= WAKE_CONFIRM_THRESHOLD) {
                val band = e.patterns.usableBand(ActivityType.WAKE_TRANSITION, e.dayType)
                if (band != null && band.contains(s.minute, tolerance = 30)) {
                    ledger.add(
                        SleepRules.WAKE_MATCHES_PATTERN,
                        "${label(s.minute)} matches your usual wake time of ${TimeUtils.formatMinuteOfDay(band.typicalStartMin)}"
                    )
                }
                return s.minute
            }
        }
        // Evidence never reached the bar — take the last signal, but say so.
        ledger.cap(
            SleepRules.WAKE_INTENT_ONLY,
            "wake evidence stayed below the confirmation threshold; the time shown is the strongest available signal",
            0.6f
        )
        return sorted.last().minute
    }

    /** SLP-16 — an afternoon rest is its own thing, never merged into the night. */
    fun findDaytimeRest(e: DayEvidence): EpisodeCandidate? {
        val window = e.samples.filter { it.minuteOfDay in (12 * 60)..(18 * 60) && it.interactive != null }
        if (window.size < 4) return null
        var start: Int? = null
        var last = 0
        var best: Pair<Int, Int>? = null
        for (s in window) {
            if (s.interactive == false) {
                if (start == null) start = s.minuteOfDay
                last = s.minuteOfDay
            } else {
                val open = start
                if (open != null && last - open >= 45) {
                    if (best == null || (last - open) > (best!!.second - best!!.first)) best = open to last
                }
                start = null
            }
        }
        val open = start
        if (open != null && last - open >= 45 && (best == null || (last - open) > (best!!.second - best!!.first))) {
            best = open to last
        }
        val (from, to) = best ?: return null
        val presence = e.presenceAt(from) ?: return null
        if (presence.location != LocationType.HOME) return null
        if (e.locomotionMinutes(from, to) > 5) return null

        val ledger = EvidenceLedger().add(
            SleepRules.DAYTIME_REST,
            "${TimeUtils.formatDurationMin(to - from)} of stationary quiet at home in the afternoon — counted separately from the night"
        )
        return EpisodeCandidate.from(
            from, to, ActivityType.SLEEP, presence.location, ledger,
            placeName = presence.placeName, priority = 20
        )
    }

    private fun label(minute: Int): String = TimeUtils.formatMinuteOfDay(((minute % 1440) + 1440) % 1440)

    private fun locationStable(e: DayEvidence, fromMin: Int, toMin: Int): Boolean {
        val from = maxOf(fromMin, 0)
        val covering = e.presences.filter { it.endMin > from && it.startMin < toMin }
        return covering.isNotEmpty() && covering.map { it.placeId }.distinct().size == 1
    }
}

// ---------------------------------------------------------------------------
// Meals (spec §12) — lunch is learned, never assumed
// ---------------------------------------------------------------------------

object MealInterpreter {

    const val ACCEPT_THRESHOLD = 0.55f
    const val MIDDAY_FROM = 11 * 60
    const val MIDDAY_TO = 15 * 60 + 30
    const val MAX_MEAL_MIN = 120
    const val MIN_MEAL_MIN = 15

    fun interpret(e: DayEvidence): List<EpisodeCandidate> =
        listOfNotNull(lunchCandidate(e)) + listOfNotNull(breakfastCandidate(e), dinnerCandidate(e))

    /** Windows where the person was away from, or between, their steady places. */
    private fun gaps(e: DayEvidence): List<Triple<Int, Int, Pair<Presence?, Presence?>>> {
        val out = mutableListOf<Triple<Int, Int, Pair<Presence?, Presence?>>>()
        val ps = e.presences.sortedBy { it.startMin }
        for (i in 0 until ps.size - 1) {
            val gapStart = ps[i].endMin
            val gapEnd = ps[i + 1].startMin
            if (gapEnd > gapStart) out += Triple(gapStart, gapEnd, ps[i] to ps[i + 1])
        }
        // A stay at an eating place is itself a candidate window.
        for (p in ps) {
            if (p.location == LocationType.RESTAURANT) out += Triple(p.startMin, p.endMin, p to p)
        }
        return out
    }

    fun lunchCandidate(e: DayEvidence): EpisodeCandidate? {
        val band = e.patterns.usableBand(ActivityType.LUNCH, e.dayType)
        var best: EpisodeCandidate? = null

        for ((start, end, neighbours) in gaps(e)) {
            val duration = end - start
            if (duration < MIN_MEAL_MIN) continue
            if (duration > MAX_MEAL_MIN) continue          // MEA-11
            if (start !in MIDDAY_FROM..MIDDAY_TO) continue

            val ledger = EvidenceLedger()
            var substantive = 0
            ledger.add(
                MealRules.MIDDAY_BAND,
                "${TimeUtils.formatMinuteOfDay(start)}–${TimeUtils.formatMinuteOfDay(end)} sits in the midday band"
            )

            if (band != null) {
                if (abs(start - band.typicalStartMin) <= 25) {
                    substantive++
                    ledger.add(
                        MealRules.LEARNED_START,
                        "started ${abs(start - band.typicalStartMin)}m from your usual ${TimeUtils.formatMinuteOfDay(band.typicalStartMin)}"
                    )
                }
                val typical = band.typicalDurationMin
                if (typical != null && abs(duration - typical) <= 15) {
                    substantive++
                    ledger.add(
                        MealRules.LEARNED_DURATION,
                        "${duration}m is close to your usual ${typical}m lunch"
                    )
                }
                if (band.observations >= 3) {
                    substantive++
                    ledger.add(
                        MealRules.WEEKDAY_REPEAT,
                        "a similar window has been observed on ${band.observations} comparable days"
                    )
                }
            }

            val (before, after) = neighbours
            val atEatingPlace = e.presences.any {
                it.location == LocationType.RESTAURANT && it.startMin < end && it.endMin > start
            }
            if (atEatingPlace) {
                substantive++
                ledger.add(MealRules.EATING_PLACE, "the window was spent at a place you have marked as somewhere to eat")
            }
            if (before != null && after != null &&
                before.location == LocationType.OFFICE && after.location == LocationType.OFFICE &&
                before.placeId == after.placeId
            ) {
                substantive++
                ledger.add(MealRules.SURROUNDED_BY_WORK, "you left the office and came back to the same office")
            }
            if (walkedOutAndBack(e, start, end)) {
                substantive++
                ledger.add(MealRules.WALK_BOUNDARY, "walking was detected at both ends of the window")
            }
            correctionPrior(e, ActivityType.LUNCH, start, end)?.let {
                substantive++
                ledger.add(MealRules.USER_CORRECTION_PRIOR, it)
            }

            if (substantive == 0) {
                // MEA-01 — the midday clock alone never makes lunch.
                continue
            }
            val confidence = ledger.confidence()
            if (confidence < ACCEPT_THRESHOLD) continue
            val location = e.presenceAt(start + duration / 2)?.location ?: LocationType.UNKNOWN
            val candidate = EpisodeCandidate.from(
                start, end, ActivityType.LUNCH, location, ledger,
                placeName = e.presenceAt(start + duration / 2)?.placeName, priority = 25
            )
            if (best == null || candidate.confidence > best!!.confidence) best = candidate   // MEA-12
        }
        return best
    }

    fun breakfastCandidate(e: DayEvidence): EpisodeCandidate? =
        homeMealCandidate(e, ActivityType.BREAKFAST, MealRules.BREAKFAST_WINDOW, 15, 60)

    fun dinnerCandidate(e: DayEvidence): EpisodeCandidate? =
        homeMealCandidate(e, ActivityType.DINNER, MealRules.DINNER_WINDOW, 20, 90)

    private fun homeMealCandidate(
        e: DayEvidence,
        activity: ActivityType,
        windowRule: Rule,
        minMin: Int,
        maxMin: Int
    ): EpisodeCandidate? {
        // MEA-09 / MEA-10: without a band there is no meal — only personal time.
        val band = e.patterns.usableBand(activity, e.dayType) ?: return null
        val start = band.typicalStartMin
        val duration = (band.typicalDurationMin ?: 30).coerceIn(minMin, maxMin)
        val end = (start + duration).coerceAtMost(e.livedUntilMin)
        if (end <= start) return null

        val presence = e.presenceAt(start + (end - start) / 2) ?: return null
        if (presence.location != LocationType.HOME) return null
        if (e.locomotionMinutes(start, end) > 10) return null

        val ledger = EvidenceLedger().add(
            windowRule,
            if (band.isPrior)
                "you told DhinaSuthra this is your usual ${activity.label.lowercase()} time (${TimeUtils.formatMinuteOfDay(start)}), and you were home for it"
            else
                "you were home across your usual ${activity.label.lowercase()} window (${TimeUtils.formatMinuteOfDay(band.p25)}–${TimeUtils.formatMinuteOfDay(band.p75)}, ${band.observations} observations)"
        )
        correctionPrior(e, activity, start, end)?.let { ledger.add(MealRules.USER_CORRECTION_PRIOR, it) }
        if (band.isPrior) {
            ledger.cap(
                PatternRules.PRIOR_IS_NOT_OBSERVATION,
                "this comes from the routine you stated, not from repeated observation",
                0.6f
            )
        }
        if (ledger.confidence() < 0.4f) return null
        return EpisodeCandidate.from(
            start, end, activity, LocationType.HOME, ledger,
            placeName = presence.placeName, priority = 22
        )
    }

    private fun walkedOutAndBack(e: DayEvidence, start: Int, end: Int): Boolean {
        val outbound = e.movements.any { it.kind == ActivityKind.WALKING && it.startMin in (start - 5)..(start + 10) }
        val inbound = e.movements.any { it.kind == ActivityKind.WALKING && it.endMin in (end - 10)..(end + 5) }
        return outbound && inbound
    }

    private fun correctionPrior(e: DayEvidence, activity: ActivityType, start: Int, end: Int): String? {
        val mid = (start + end) / 2
        val match = e.corrections.filter {
            it.activity == activity && it.dayType == e.dayType && abs(it.midMin - mid) <= 45
        }
        if (match.isEmpty()) return null
        return "you have labelled this window as ${activity.label.lowercase()} ${match.size} time(s) before"
    }
}

// ---------------------------------------------------------------------------
// Work (spec §13) — office presence decomposes; it never equals work
// ---------------------------------------------------------------------------

object WorkInterpreter {

    const val BREAK_MIN = 5
    const val BREAK_MAX = 25
    const val MEETING_MIN_BLOCK = 30

    /**
     * @param claimed windows already explained by meals or other higher-priority rules
     */
    fun decompose(e: DayEvidence, claimed: List<EpisodeCandidate>): List<EpisodeCandidate> {
        val out = mutableListOf<EpisodeCandidate>()
        val officePresences = e.presences.filter { it.location == LocationType.OFFICE }

        if (officePresences.isEmpty()) {
            remoteWork(e, claimed)?.let { out += it }
            return out
        }

        if (e.dayType == DayType.WEEKEND && e.patterns.usableBand(ActivityType.WORK, DayType.WEEKEND) == null) {
            // WRK-11 — weekend presence stays honest rather than being called work.
            return out
        }

        // WRK-04 — short absences between two office presences are breaks, not departures.
        for (i in 0 until officePresences.size - 1) {
            val gapStart = officePresences[i].endMin
            val gapEnd = officePresences[i + 1].startMin
            val len = gapEnd - gapStart
            if (len !in BREAK_MIN..BREAK_MAX) continue
            if (claimed.any { it.startMin < gapEnd && gapStart < it.endMin }) continue
            val ledger = EvidenceLedger().add(
                WorkRules.SHORT_EXCURSION_IS_BREAK,
                "left and returned to the office within ${len}m"
            )
            out += EpisodeCandidate.from(
                gapStart, gapEnd, ActivityType.TEA_BREAK, LocationType.OFFICE, ledger,
                placeName = officePresences[i].placeName, priority = 18
            )
        }

        val breaks = out.toList()
        val arrivalBand = e.patterns.usableBand(ActivityType.WORK, e.dayType)

        for (p in officePresences) {
            val blocked = (claimed + breaks)
                .filter { it.startMin < p.endMin && p.startMin < it.endMin }
                .sortedBy { it.startMin }
            var cursor = p.startMin
            val slices = mutableListOf<Pair<Int, Int>>()
            for (b in blocked) {
                if (b.startMin > cursor) slices += cursor to minOf(b.startMin, p.endMin)
                cursor = maxOf(cursor, b.endMin)
            }
            if (cursor < p.endMin) slices += cursor to p.endMin

            for ((from, to) in slices) {
                if (to - from < 5) continue
                val ledger = EvidenceLedger()
                ledger.add(
                    WorkRules.PRESENCE_IS_NOT_WORK,
                    "of ${TimeUtils.formatDurationMin(p.durationMin)} present at ${p.placeName ?: "the office"}, this ${TimeUtils.formatDurationMin(to - from)} is being classified separately",
                    0f
                )
                ledger.add(
                    WorkRules.STATIONARY_IS_WORK,
                    "at ${p.placeName ?: "the office"} with no meal or break covering ${TimeUtils.formatMinuteOfDay(from)}–${TimeUtils.formatMinuteOfDay(to)}"
                )
                if (arrivalBand != null && from == p.startMin && arrivalBand.contains(from, tolerance = 40)) {
                    ledger.add(
                        WorkRules.ARRIVAL_STARTS_WORK,
                        "arrival at ${TimeUtils.formatMinuteOfDay(from)} matches your usual start of ${TimeUtils.formatMinuteOfDay(arrivalBand.typicalStartMin)}"
                    )
                }
                if (from > p.startMin) {
                    ledger.add(WorkRules.RESUME_AFTER_BREAK, "work resumed after a break rather than opening a gap")
                }

                val untouched = e.samplesBetween(from, to).let { s ->
                    s.size >= 2 && s.none { it.interactive == true }
                }
                val meeting = untouched && (to - from) >= MEETING_MIN_BLOCK && e.locomotionMinutes(from, to) == 0
                if (meeting) {
                    val mLedger = EvidenceLedger()
                        .add(
                            WorkRules.UNTOUCHED_STILL_BLOCK,
                            "${TimeUtils.formatDurationMin(to - from)} stationary at the office with the phone untouched"
                        )
                        .cap(
                            WorkRules.UNTOUCHED_STILL_BLOCK,
                            "DhinaSuthra cannot see calendars, so a meeting is never more than possible",
                            ConfidenceBand.LIKELY.floor - 0.02f
                        )
                    out += EpisodeCandidate.from(
                        from, to, ActivityType.MEETING, LocationType.OFFICE, mLedger,
                        placeName = p.placeName, priority = 15
                    )
                } else {
                    out += EpisodeCandidate.from(
                        from, to, ActivityType.WORK, LocationType.OFFICE, ledger,
                        placeName = p.placeName, priority = 12
                    )
                }
            }
        }

        // WRK-09 — the final office departure closes the working day; earlier ones that
        // were returned from have already been reclassified as breaks above.
        val lastDeparture = officePresences.last().endMin
        return out.map { candidate ->
            if (candidate.activity == ActivityType.WORK && candidate.endMin == lastDeparture) {
                candidate.copy(
                    ruleIds = candidate.ruleIds + WorkRules.LAST_DEPARTURE_ENDS_WORK.id,
                    evidence = candidate.evidence +
                        "${WorkRules.LAST_DEPARTURE_ENDS_WORK.id} · the working day closed at ${TimeUtils.formatMinuteOfDay(lastDeparture)}, your final departure from the office"
                )
            } else candidate
        }
    }

    /** WRK-10 — a weekday spent at home inside the learned working band is still work. */
    fun remoteWork(e: DayEvidence, claimed: List<EpisodeCandidate>): EpisodeCandidate? {
        if (e.dayType != DayType.WEEKDAY) return null
        val band = e.patterns.usableBand(ActivityType.WORK, DayType.WEEKDAY) ?: return null
        val from = band.p25
        val to = (band.p75 + (band.typicalDurationMin ?: 240)).coerceAtMost(e.livedUntilMin)
        if (to - from < 90) return null
        val presence = e.presenceAt((from + to) / 2) ?: return null
        if (presence.location != LocationType.HOME) return null
        if (claimed.any { it.startMin < to && from < it.endMin && it.activity != ActivityType.PERSONAL }) return null
        if (e.locomotionMinutes(from, to) > 20) return null

        val ledger = EvidenceLedger().add(
            WorkRules.REMOTE_WORK,
            "no office presence on a weekday, but you were home and stationary across your usual working hours"
        ).cap(
            WorkRules.REMOTE_WORK,
            "working from home is inferred from absence of travel, so it stays a suggestion",
            0.65f
        )
        return EpisodeCandidate.from(
            from, to, ActivityType.WORK, LocationType.HOME, ledger,
            placeName = presence.placeName, priority = 10
        )
    }
}

// ---------------------------------------------------------------------------
// Commute (spec §10) — a journey is one thing
// ---------------------------------------------------------------------------

object CommuteInterpreter {

    const val MIN_JOURNEY_MIN = 4

    fun interpret(e: DayEvidence): List<EpisodeCandidate> {
        val out = mutableListOf<EpisodeCandidate>()
        val ps = e.presences.sortedBy { it.startMin }
        if (ps.isEmpty()) return out

        for (i in 0 until ps.size - 1) {
            val from = ps[i]
            val to = ps[i + 1]
            val start = from.endMin
            val end = to.startMin
            if (end - start < MIN_JOURNEY_MIN) continue
            val locomotion = e.locomotionMinutes(start, end)
            if (locomotion == 0) continue

            val ledger = EvidenceLedger()
            ledger.add(
                CommuteRules.LOCOMOTION_IS_COMMUTE,
                "${TimeUtils.formatDurationMin(locomotion)} of recognised movement between ${from.placeName ?: from.location.label} and ${to.placeName ?: to.location.label}"
            )
            ledger.add(
                CommuteRules.JOURNEY_IS_ONE_EPISODE,
                "recorded as a single journey rather than a series of boundary crossings",
                0f
            )
            ledger.add(
                TimelineRules.UNCOVERED_MOVEMENT_IS_TRAVEL,
                "the period between two places is travel, not a hole in the day"
            )
            if (hasBriefStops(e, start, end)) {
                ledger.add(
                    CommuteRules.BRIEF_STOPS_DONT_SPLIT,
                    "brief stops inside the journey were kept inside it",
                    0f
                )
            }

            val band = when {
                from.location == LocationType.HOME && to.location == LocationType.OFFICE ->
                    e.patterns.usableBand(ActivityType.COMMUTE, e.dayType)?.also { b ->
                        if (b.contains(start, 45)) ledger.add(
                            CommuteRules.MORNING_COMMUTE,
                            "home to office at ${TimeUtils.formatMinuteOfDay(start)}, inside your usual commute band"
                        )
                    }
                from.location == LocationType.OFFICE && to.location == LocationType.HOME ->
                    e.patterns.usableBand(ActivityType.COMMUTE, e.dayType)?.also { b ->
                        if (start > 15 * 60 && b.observations >= 3) ledger.add(
                            CommuteRules.EVENING_COMMUTE,
                            "office to home at ${TimeUtils.formatMinuteOfDay(start)}"
                        )
                    }
                else -> null
            }
            val typical = band?.typicalDurationMin
            if (typical != null && abs((end - start) - typical) <= 10) {
                ledger.add(
                    CommuteRules.TYPICAL_JOURNEY_LENGTH,
                    "${end - start}m matches your usual ${typical}m for this journey"
                )
            }

            out += EpisodeCandidate.from(
                start, end, ActivityType.COMMUTE, LocationType.TRANSIT, ledger, priority = 16
            )
        }
        return out
    }

    private fun hasBriefStops(e: DayEvidence, from: Int, to: Int): Boolean {
        val still = e.movements.filter { it.kind == ActivityKind.STILL && it.startMin >= from && it.endMin <= to }
        return still.any { it.durationMin in 1..5 }
    }
}

// ---------------------------------------------------------------------------
// Gap resolution (spec §14) — explain what can be explained, admit the rest
// ---------------------------------------------------------------------------

object GapResolver {

    const val MIN_GAP_MIN = 12
    const val ACCEPT_THRESHOLD = 0.55f

    /**
     * Look at every unexplained stretch and try to account for it with the person's
     * own learned patterns. Anything that cannot be explained honestly stays unknown.
     */
    fun resolve(e: DayEvidence, placed: List<EpisodeCandidate>): List<EpisodeCandidate> {
        val out = mutableListOf<EpisodeCandidate>()
        val sorted = placed.sortedBy { it.startMin }
        var cursor = 0
        val bounds = mutableListOf<Pair<Int, Int>>()
        for (p in sorted) {
            if (p.startMin > cursor) bounds += cursor to p.startMin
            cursor = maxOf(cursor, p.endMin)
        }
        if (cursor < e.livedUntilMin) bounds += cursor to e.livedUntilMin

        for ((from, to) in bounds) {
            if (to - from < MIN_GAP_MIN) continue
            val before = sorted.lastOrNull { it.endMin <= from }
            val after = sorted.firstOrNull { it.startMin >= to }
            val sameSide = before != null && after != null && before.location == after.location

            val mid = (from + to) / 2
            val band = e.patterns.forDayType(e.dayType)
                .filter { it.activity != ActivityType.UNKNOWN && it.activity != ActivityType.SLEEP }
                .firstOrNull { it.contains(mid, tolerance = 20) && (it.isPrior || it.observations >= 3) }
                ?: continue

            val ledger = EvidenceLedger()
            ledger.add(
                TimelineRules.PATTERN_FILLS_GAP,
                "${TimeUtils.formatMinuteOfDay(from)}–${TimeUtils.formatMinuteOfDay(to)} matches your usual ${band.activity.label.lowercase()} window (${TimeUtils.formatMinuteOfDay(band.p10)}–${TimeUtils.formatMinuteOfDay(band.p90)})"
            )
            if (sameSide) {
                ledger.add(
                    LocationRules.ONE_PRESENCE_PER_STAY,
                    "you were at ${before!!.placeName ?: before.location.label} on both sides of the gap",
                    0.2f
                )
            }
            if (band.observations >= 5) {
                ledger.add(
                    PatternRules.CHANGE_POINT,
                    "the pattern behind this has ${band.observations} observations",
                    0.15f
                )
            }
            val confidence = ledger.confidence()
            if (confidence < ACCEPT_THRESHOLD) {
                // TML-07 — not confident enough; leave it unclassified for the user.
                continue
            }
            val location = before?.location ?: after?.location ?: LocationType.UNKNOWN
            out += EpisodeCandidate.from(
                from, to, band.activity, location, ledger,
                placeName = before?.placeName, priority = 5
            )
        }
        return out
    }
}
