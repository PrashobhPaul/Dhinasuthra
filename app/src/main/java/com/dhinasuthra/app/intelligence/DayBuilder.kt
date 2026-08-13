package com.dhinasuthra.app.intelligence

import com.dhinasuthra.app.core.TimeUtils

/**
 * The orchestrator: evidence in, one honest 24-hour day out.
 *
 * Order matters, and the order is the argument. Corrections outrank everything
 * (TML-08). Sleep is settled before the waking day is interpreted. Meals are
 * carved before work, because work is what is left of office presence once the
 * things that are not work have been removed (WRK-01/WRK-02).
 */
object DayBuilder {

    fun build(e: DayEvidence): DayReconstruction {
        val candidates = mutableListOf<EpisodeCandidate>()

        // 1 — TML-08: what the user told us is not up for debate.
        candidates += corrections(e)

        // 2 — sleep and the night boundary.
        val night = SleepInterpreter.interpret(e)
        candidates += SleepInterpreter.episodes(e, night)

        // 3 — meals, before work, so work cannot swallow them.
        candidates += MealInterpreter.interpret(e).filterNot { it.clashesWith(candidates) }

        // 4 — office presence decomposes into what is left.
        candidates += WorkInterpreter.decompose(e, candidates.toList()).filterNot { it.clashesWith(candidates) }

        // 5 — journeys between places.
        candidates += CommuteInterpreter.interpret(e).filterNot { it.clashesWith(candidates) }

        // 6 — TML-13: awake at a place with nothing else to say about it.
        candidates += baseEpisodes(e, candidates.toList())

        // 7 — spec §14: try to explain the remaining holes; admit the ones that resist.
        candidates += GapResolver.resolve(e, candidates.toList())

        val episodes = candidates
            .filter { it.durationMin > 0 }
            .sortedWith(compareByDescending<EpisodeCandidate> { it.priority }.thenByDescending { it.confidence })
            .mapIndexed { i, c -> c.toEpisode(e.epochDay, i) }

        return DayReconstruction.build(e.epochDay, episodes, e.livedUntilMin)
    }

    /** User corrections for this day become CORRECTED episodes with total authority. */
    private fun corrections(e: DayEvidence): List<EpisodeCandidate> =
        e.corrections
            .filter { it.epochDay == e.epochDay && it.durationMin > 0 }
            .map { c ->
                val ledger = EvidenceLedger().add(
                    TimelineRules.CORRECTIONS_WIN,
                    "you set ${TimeUtils.formatMinuteOfDay(c.startMin)}–${TimeUtils.formatMinuteOfDay(c.endMin)} to ${c.activity.label.lowercase()}",
                    0f
                )
                EpisodeCandidate.from(
                    c.startMin, c.endMin, c.activity, c.location, ledger,
                    status = EpisodeStatus.CORRECTED, priority = 100, confidenceOverride = 1f
                )
            }

    /**
     * TML-13 — waking time at a place that nothing else explains is personal time.
     * Never applied at the office: there, unexplained presence must stay unexplained
     * so that presence and work can never quietly become the same number (WRK-07).
     */
    private fun baseEpisodes(e: DayEvidence, placed: List<EpisodeCandidate>): List<EpisodeCandidate> {
        val out = mutableListOf<EpisodeCandidate>()
        for (p in e.presences) {
            if (p.location == LocationType.OFFICE || p.location == LocationType.UNKNOWN) continue
            if (p.location == LocationType.TRANSIT) continue
            val blocking = placed
                .filter { it.startMin < p.endMin && p.startMin < it.endMin }
                .sortedBy { it.startMin }
            var cursor = p.startMin
            val slices = mutableListOf<Pair<Int, Int>>()
            for (b in blocking) {
                if (b.startMin > cursor) slices += cursor to minOf(b.startMin, p.endMin)
                cursor = maxOf(cursor, b.endMin)
            }
            if (cursor < p.endMin) slices += cursor to p.endMin

            for ((from, to) in slices) {
                if (to - from < 5) continue
                val ledger = EvidenceLedger().add(
                    TimelineRules.PERSONAL_TIME,
                    "awake at ${p.placeName ?: p.location.label} with nothing else explaining ${TimeUtils.formatMinuteOfDay(from)}–${TimeUtils.formatMinuteOfDay(to)}"
                )
                p.evidence.forEach { detail ->
                    val id = detail.substringBefore(" ·")
                    RuleBook.byId(id)?.let { ledger.add(it, detail.substringAfter("· "), 0f) }
                }
                out += EpisodeCandidate.from(
                    from, to,
                    if (p.location == LocationType.GYM) ActivityType.EXERCISE else ActivityType.PERSONAL,
                    p.location, ledger, placeName = p.placeName, priority = 3
                )
            }
        }
        return out
    }

    private fun EpisodeCandidate.clashesWith(existing: List<EpisodeCandidate>): Boolean =
        existing.any { it.priority >= priority && it.startMin < endMin && startMin < it.endMin }
}
