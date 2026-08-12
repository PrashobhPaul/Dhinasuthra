package com.dhinasuthra.app.intelligence

import com.dhinasuthra.app.core.TimeUtils
import com.dhinasuthra.app.core.model.ActivityKind

/**
 * Temporal reconciliation of place evidence (spec §9, §10).
 *
 * The problem this solves: a phone that reports "left home / arrived home / left
 * home / arrived home" while its owner simply parked the car and walked to the
 * front door. The fix is to refuse to write history directly from sensor
 * transitions. A crossing raises a *candidate*; only dwell, continuity and
 * context turn a candidate into a departure.
 */
object LocationReconciler {

    /** A stay as the sensing layer saw it, before any reconciliation. */
    data class RawStay(
        val startMin: Int,
        val endMin: Int,
        val location: LocationType,
        val placeId: Long?,
        val placeName: String?,
        val confidence: Float,
        val visitDays: Int = 99,
        val worstAccuracyM: Float? = null
    ) {
        val durationMin: Int get() = (endMin - startMin).coerceAtLeast(0)
    }

    /** A departure must be backed by this much time away before it is believed (LOC-02). */
    const val DEPARTURE_DWELL_MIN = 8

    /** Park-and-walk reconciliation window: an absence this short that ends on foot is an arrival (LOC-05). */
    const val PARK_WALK_WINDOW_MIN = 20

    /** Stays shorter than this are sensor jitter, not life (TML-05). */
    const val SLIVER_MIN = 4

    /** Dwell that turns an arrival from probable into solid (LOC-04). */
    const val ARRIVAL_DWELL_MIN = 10

    fun reconcile(
        raw: List<RawStay>,
        movements: List<MovementSpan>,
        livedUntilMin: Int = 1440
    ): List<Presence> {
        if (raw.isEmpty()) return emptyList()

        val ordered = raw
            .map { it.copy(startMin = it.startMin.coerceIn(0, livedUntilMin), endMin = it.endMin.coerceIn(0, livedUntilMin)) }
            .filter { it.durationMin > 0 }
            .sortedBy { it.startMin }
        if (ordered.isEmpty()) return emptyList()

        // Pass 1 — LOC-12: consecutive segments at the same place are one presence.
        val collapsed = mutableListOf<RawStay>()
        for (s in ordered) {
            val last = collapsed.lastOrNull()
            if (last != null && last.placeId == s.placeId && last.location == s.location && s.startMin <= last.endMin) {
                collapsed[collapsed.lastIndex] = last.copy(
                    endMin = maxOf(last.endMin, s.endMin),
                    confidence = maxOf(last.confidence, s.confidence)
                )
            } else {
                collapsed += s
            }
        }

        // Pass 2 — LOC-01/LOC-03/LOC-05: absorb excursions that never earned a departure.
        val presences = mutableListOf<Presence>()
        var i = 0
        while (i < collapsed.size) {
            val stay = collapsed[i]
            val ledger = EvidenceLedger()
            var start = stay.startMin
            var end = stay.endMin
            var confidence = stay.confidence

            if (stay.isKnown()) {
                ledger.add(
                    LocationRules.CROSSING_IS_NOT_DEPARTURE,
                    "arrival at ${stay.placeName ?: stay.location.label} treated as a candidate until dwell confirmed it",
                    0f
                )
            }

            // Look ahead: while the next same-place stay returns quickly, swallow the gap.
            var j = i + 1
            while (j < collapsed.size) {
                val next = collapsed[j]
                if (next.placeId != stay.placeId || next.location != stay.location) {
                    // Different place: only continue if it is a short away-window followed by
                    // a return to the same place.
                    val afterNext = collapsed.getOrNull(j + 1)
                    val awayLen = (afterNext?.startMin ?: next.endMin) - end
                    val returns = afterNext != null && afterNext.placeId == stay.placeId &&
                        afterNext.location == stay.location
                    if (!returns) break
                    val walkedBack = endsOnFoot(movements, next.startMin, afterNext.startMin)
                    val quickReturn = awayLen < DEPARTURE_DWELL_MIN
                    val parkAndWalk = awayLen <= PARK_WALK_WINDOW_MIN && walkedBack &&
                        startsVehicular(movements, end, next.startMin)
                    if (!quickReturn && !parkAndWalk) break

                    if (quickReturn) {
                        ledger.add(
                            LocationRules.QUICK_RETURN_MERGES,
                            "returned to ${stay.placeName ?: stay.location.label} after ${awayLen}m away — under the ${DEPARTURE_DWELL_MIN}m departure threshold, so no leave/arrive pair was written",
                            0f
                        )
                    } else {
                        ledger.add(
                            LocationRules.PARK_AND_WALK,
                            "vehicle stop then ${awayLen}m on foot ending back at ${stay.placeName ?: stay.location.label} — recorded as one continuous arrival",
                            0f
                        )
                        ledger.add(
                            CommuteRules.WALKING_NEAR_PLACE_IS_ARRIVAL,
                            "the walk finished inside the place, so it counts as arriving rather than travelling",
                            0f
                        )
                    }
                    ledger.add(
                        LocationRules.NO_DUPLICATE_ARRIVALS,
                        "suppressed a duplicate arrival at ${stay.placeName ?: stay.location.label}",
                        0f
                    )
                    end = maxOf(end, afterNext.endMin)
                    confidence = maxOf(confidence, afterNext.confidence)
                    j += 2
                    continue
                }
                // Same place again with a gap in between.
                val awayLen = next.startMin - end
                if (awayLen >= DEPARTURE_DWELL_MIN) break
                ledger.add(
                    LocationRules.QUICK_RETURN_MERGES,
                    "gap of ${awayLen}m at ${stay.placeName ?: stay.location.label} treated as sensor noise",
                    0f
                )
                end = maxOf(end, next.endMin)
                confidence = maxOf(confidence, next.confidence)
                j++
            }

            val duration = end - start
            if (stay.isKnown() && duration >= ARRIVAL_DWELL_MIN && isMostlyStill(movements, start, end)) {
                ledger.add(
                    LocationRules.DWELL_CONFIRMS_ARRIVAL,
                    "stationary for ${TimeUtils.formatDurationMin(duration)} at ${stay.placeName ?: stay.location.label}"
                )
            }
            if (stay.worstAccuracyM != null && stay.worstAccuracyM > 120f) {
                ledger.cap(
                    LocationRules.ACCURACY_WEIGHTING,
                    "best location accuracy during this stay was ${stay.worstAccuracyM.toInt()}m",
                    0.7f
                )
            }
            if (stay.isKnown() && stay.visitDays <= 1) {
                ledger.cap(
                    LocationRules.SINGLE_VISIT_EXPLAINS_NOTHING,
                    "this place has only been seen on one day, so it cannot justify an activity yet",
                    0.5f
                )
            }

            // The sensor's own confidence and the rule evidence reinforce each other,
            // but a guard's ceiling still wins over both (LOC-07, LOC-10).
            val combined = minOf(maxOf(confidence, ledger.confidence()), ledger.ceiling())
            presences += Presence(
                startMin = start,
                endMin = end,
                location = stay.location,
                placeId = stay.placeId,
                placeName = stay.placeName,
                confidence = combined.coerceIn(0f, 0.98f),
                ruleIds = ledger.ruleIds(),
                evidence = ledger.explanations()
            )
            i = if (j > i + 1) j else i + 1
        }

        // Pass 3 — TML-05: drop slivers wedged between two presences of the same place.
        val cleaned = mutableListOf<Presence>()
        for ((idx, p) in presences.withIndex()) {
            val prev = cleaned.lastOrNull()
            val next = presences.getOrNull(idx + 1)
            val sliver = p.durationMin < SLIVER_MIN &&
                prev != null && next != null &&
                prev.placeId == next.placeId && prev.location == next.location
            if (sliver) continue
            if (prev != null && prev.placeId == p.placeId && prev.location == p.location && p.startMin <= prev.endMin + SLIVER_MIN) {
                cleaned[cleaned.lastIndex] = prev.copy(
                    endMin = maxOf(prev.endMin, p.endMin),
                    confidence = maxOf(prev.confidence, p.confidence),
                    ruleIds = (prev.ruleIds + p.ruleIds).distinct(),
                    evidence = (prev.evidence + p.evidence).distinct()
                )
            } else {
                cleaned += p
            }
        }
        return cleaned
    }

    private fun RawStay.isKnown() = location != LocationType.UNKNOWN && location != LocationType.TRANSIT

    private fun endsOnFoot(movements: List<MovementSpan>, from: Int, to: Int): Boolean =
        movements.any { it.kind == ActivityKind.WALKING && it.endMin >= to - 3 && it.startMin < to && it.startMin >= from - 5 }

    private fun startsVehicular(movements: List<MovementSpan>, from: Int, to: Int): Boolean =
        movements.any { it.isVehicular && it.startMin <= to && it.endMin >= from }

    private fun isMostlyStill(movements: List<MovementSpan>, from: Int, to: Int): Boolean {
        val span = (to - from).coerceAtLeast(1)
        val moving = movements.filter { it.isLocomotion }
            .sumOf { (minOf(it.endMin, to) - maxOf(it.startMin, from)).coerceAtLeast(0) }
        return moving * 4 < span
    }
}
