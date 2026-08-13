package com.dhinasuthra.app.intelligence

import com.dhinasuthra.app.intelligence.RuleDomain.ANOMALY
import com.dhinasuthra.app.intelligence.RuleDomain.CONSISTENCY
import com.dhinasuthra.app.intelligence.RuleDomain.NARRATIVE
import com.dhinasuthra.app.intelligence.RuleDomain.PATTERN
import com.dhinasuthra.app.intelligence.RuleDomain.REMINDER
import com.dhinasuthra.app.intelligence.RuleDomain.ROUTINE
import com.dhinasuthra.app.intelligence.RuleDomain.TIMELINE
import com.dhinasuthra.app.intelligence.RuleKind.EVIDENCE
import com.dhinasuthra.app.intelligence.RuleKind.GUARD
import com.dhinasuthra.app.intelligence.RuleKind.NARRATIVE as NARRATIVE_KIND
import com.dhinasuthra.app.intelligence.RuleKind.STRUCTURAL

/**
 * Structural rules — how the day, the patterns, the routine and the narration
 * are allowed to behave. These are the rules that keep the product honest once
 * detection has done its work.
 */

object TimelineRules {

    val TWENTY_FOUR_HOURS = rule(
        "TML-01", TIMELINE, STRUCTURAL,
        "Every day reconciles to 24 hours",
        "THEN the episodes of a day tile it exactly: no overlaps, no holes, totalling 1440 minutes.",
        enforcedIn = "DayReconstruction.build"
    )
    val HONEST_UNKNOWN = rule(
        "TML-02", TIMELINE, STRUCTURAL,
        "Unexplained time is labelled, not invented",
        "IF no evidence covers a period THEN publish it as unclassified time — the app never manufactures a plausible-looking episode.",
        enforcedIn = "DayReconstruction.build"
    )
    val OVERLAP_BY_CONFIDENCE = rule(
        "TML-03", TIMELINE, STRUCTURAL,
        "Overlaps resolve by confidence",
        "IF two episodes claim the same minutes THEN the better-evidenced one keeps them and the other is truncated.",
        enforcedIn = "DayReconstruction.build"
    )
    val MERGE_IDENTICAL = rule(
        "TML-04", TIMELINE, STRUCTURAL,
        "Adjacent identical episodes merge",
        "IF neighbouring episodes share activity, location and place THEN merge them so the timeline reads as one stretch.",
        enforcedIn = "DayReconstruction.build"
    )
    val ABSORB_SLIVERS = rule(
        "TML-05", TIMELINE, STRUCTURAL,
        "Slivers are absorbed",
        "IF a gap is under 4 minutes THEN absorb it into its neighbour rather than showing sensor jitter as a period of life.",
        enforcedIn = "GapResolver.resolve"
    )
    val PATTERN_FILLS_GAP = rule(
        "TML-06", TIMELINE, EVIDENCE,
        "A gap can be explained by your own pattern",
        "IF a gap is flanked by the same location and matches a learned activity window THEN propose that activity for the gap.",
        0.4f, "GapResolver.resolve"
    )
    val GAP_NEEDS_CONFIDENCE = rule(
        "TML-07", TIMELINE, GUARD,
        "Gap resolution needs real confidence",
        "IF a gap proposal scores below 0.55 THEN leave the gap unclassified and offer it to the user instead.",
        enforcedIn = "GapResolver.resolve"
    )
    val CORRECTIONS_WIN = rule(
        "TML-08", TIMELINE, STRUCTURAL,
        "Your corrections outrank every inference",
        "IF you have corrected a period THEN that correction replaces whatever the engine concluded, permanently.",
        enforcedIn = "DayBuilder.build"
    )
    val CANDIDATE_BEFORE_TIMELINE = rule(
        "TML-09", TIMELINE, STRUCTURAL,
        "Signals become candidates before they become history",
        "THEN every raw transition passes through candidate → temporal buffer → context check → confirm, merge or discard.",
        enforcedIn = "LocationReconciler.reconcile"
    )
    val MIN_EPISODE_LENGTH = rule(
        "TML-10", TIMELINE, GUARD,
        "Very short episodes are not shown alone",
        "IF an episode is under 3 minutes THEN fold it into its neighbour rather than cluttering the day.",
        enforcedIn = "GapResolver.resolve"
    )
    val TODAY_STOPS_AT_NOW = rule(
        "TML-11", TIMELINE, STRUCTURAL,
        "Today stops at now",
        "THEN the current day is reconstructed only up to the present minute; the rest is 'not yet lived', not 'unknown'.",
        enforcedIn = "DayReconstruction.build"
    )
    val SPLIT_AT_MIDNIGHT = rule(
        "TML-12", TIMELINE, STRUCTURAL,
        "Episodes split at midnight",
        "IF an episode crosses the day boundary THEN split it so each day still totals 24 hours, while statistics keep the continuous time.",
        enforcedIn = "DayBuilder.build"
    )

    val PERSONAL_TIME = rule(
        "TML-13", TIMELINE, EVIDENCE,
        "Unexplained waking time at a place is personal time",
        "IF you are awake at home or another non-work place and nothing else explains the period THEN call it personal time rather than unclassified — but never at the office, where presence must decompose properly.",
        0.35f, "DayBuilder.baseEpisodes"
    )
    val UNCOVERED_MOVEMENT_IS_TRAVEL = rule(
        "TML-14", TIMELINE, EVIDENCE,
        "Time between places with movement is travel",
        "IF a period sits between two places and shows recognised locomotion THEN it is travel, not a hole in the day.",
        0.45f, "CommuteInterpreter.interpret"
    )

    val rules = listOf(
        TWENTY_FOUR_HOURS, HONEST_UNKNOWN, OVERLAP_BY_CONFIDENCE, MERGE_IDENTICAL, ABSORB_SLIVERS,
        PATTERN_FILLS_GAP, GAP_NEEDS_CONFIDENCE, CORRECTIONS_WIN, CANDIDATE_BEFORE_TIMELINE,
        MIN_EPISODE_LENGTH, TODAY_STOPS_AT_NOW, SPLIT_AT_MIDNIGHT, PERSONAL_TIME,
        UNCOVERED_MOVEMENT_IS_TRAVEL
    )
}

object PatternRules {

    val MIN_OBSERVATIONS = rule(
        "PAT-01", PATTERN, GUARD,
        "Three observations before a pattern exists",
        "IF an activity has fewer than 3 observations THEN it has no pattern and cannot be used to explain anything.",
        enforcedIn = "PatternEngine.build"
    )
    val MEDIAN_NOT_MEAN = rule(
        "PAT-02", PATTERN, STRUCTURAL,
        "Typical means median",
        "THEN typical times use the median so a single 3am night cannot move your normal bedtime.",
        enforcedIn = "PatternEngine.build"
    )
    val SPREAD_IS_REPORTED = rule(
        "PAT-03", PATTERN, STRUCTURAL,
        "Spread travels with the average",
        "THEN every typical value is published with its p10–p90 range, IQR and standard deviation — a number without spread is misleading.",
        enforcedIn = "PatternEngine.build"
    )
    val DAY_TYPES_SEPARATE = rule(
        "PAT-04", PATTERN, STRUCTURAL,
        "Weekdays and weekends learn separately",
        "THEN patterns are learned per day type, because a Sunday is not a slow Tuesday.",
        enforcedIn = "PatternEngine.build"
    )
    val MIDNIGHT_NORMALISATION = rule(
        "PAT-05", PATTERN, STRUCTURAL,
        "Times near midnight are normalised first",
        "IF an activity straddles midnight THEN shift onto a continuous axis before averaging, so 23:50 and 00:10 average to midnight.",
        enforcedIn = "RoutineStats.normalize"
    )
    val CONFIDENCE_FORMULA = rule(
        "PAT-06", PATTERN, STRUCTURAL,
        "Confidence grows with count, falls with spread",
        "THEN pattern confidence rises with the number of observations and falls as the interquartile range widens.",
        enforcedIn = "RoutineStats.confidence"
    )
    val LIFECYCLE = rule(
        "PAT-07", PATTERN, STRUCTURAL,
        "Patterns have a lifecycle",
        "THEN a pattern moves Learning → Emerging → Established as evidence accumulates, and the UI never shows a Learning pattern as fact.",
        enforcedIn = "PatternEngine.lifecycle"
    )
    val UNSTABLE_STATE = rule(
        "PAT-08", PATTERN, STRUCTURAL,
        "Disagreement makes a pattern unstable",
        "IF recent observations sit far from the historical median THEN mark the pattern unstable instead of pretending it still holds.",
        enforcedIn = "PatternEngine.lifecycle"
    )
    val STALE_STATE = rule(
        "PAT-09", PATTERN, STRUCTURAL,
        "Unobserved patterns go stale",
        "IF a pattern has not been observed for 21 days THEN mark it stale so life changes are not fought by old habits.",
        enforcedIn = "PatternEngine.lifecycle"
    )
    val RECENCY_WEIGHT = rule(
        "PAT-10", PATTERN, STRUCTURAL,
        "Recent days matter more",
        "THEN change detection compares the last 7 days with the preceding 21 rather than treating all history equally.",
        enforcedIn = "PatternEngine.changePoint"
    )
    val ONE_PER_DAY = rule(
        "PAT-11", PATTERN, GUARD,
        "One observation per activity per day",
        "THEN only the first occurrence of an activity each day feeds the start-time distribution, so a busy day cannot skew it.",
        enforcedIn = "PatternEngine.build"
    )
    val OUTLIERS_EXCLUDED_NOT_DELETED = rule(
        "PAT-12", PATTERN, STRUCTURAL,
        "Outliers are excluded, never deleted",
        "IF an observation lies beyond 1.5 IQR THEN exclude it from typical values but keep it visible in the data.",
        enforcedIn = "PatternEngine.build"
    )
    val PRIOR_IS_NOT_OBSERVATION = rule(
        "PAT-13", PATTERN, GUARD,
        "A time you told us is not a time we observed",
        "IF you state your usual time THEN store it as a decaying prior, labelled as yours, never counted as an observation.",
        enforcedIn = "RoutineLearningEngine.seedUserPrior"
    )
    val CHANGE_POINT = rule(
        "PAT-14", PATTERN, EVIDENCE,
        "A sustained shift is a change, not a mistake",
        "IF the recent median differs from the older median by more than 25 minutes across enough days THEN report a change point.",
        0.4f, "PatternEngine.changePoint"
    )

    val rules = listOf(
        MIN_OBSERVATIONS, MEDIAN_NOT_MEAN, SPREAD_IS_REPORTED, DAY_TYPES_SEPARATE,
        MIDNIGHT_NORMALISATION, CONFIDENCE_FORMULA, LIFECYCLE, UNSTABLE_STATE, STALE_STATE,
        RECENCY_WEIGHT, ONE_PER_DAY, OUTLIERS_EXCLUDED_NOT_DELETED, PRIOR_IS_NOT_OBSERVATION,
        CHANGE_POINT
    )
}

object RoutineRules {

    val SUGGEST_NEVER_IMPOSE = rule(
        "RTN-01", ROUTINE, GUARD,
        "Routines are offered, never imposed",
        "THEN the app proposes a routine and waits — it never activates a timetable on your behalf.",
        enforcedIn = "RoutineComposer.suggest"
    )
    val NEEDS_ESTABLISHED_PATTERN = rule(
        "RTN-02", ROUTINE, GUARD,
        "Only established patterns become routines",
        "IF a pattern is not yet Established THEN it cannot be offered as a routine entry.",
        enforcedIn = "RoutineComposer.suggest"
    )
    val TIMETABLE_SHAPE = rule(
        "RTN-03", ROUTINE, STRUCTURAL,
        "A routine is a timetable of targets",
        "THEN a saved routine holds entries of activity, target start, target end, expected location, tolerance and reminder policy.",
        enforcedIn = "Timetable"
    )
    val TOLERANCE_FROM_YOU = rule(
        "RTN-04", ROUTINE, STRUCTURAL,
        "Tolerance comes from your own spread",
        "THEN an entry's tolerance defaults to half your observed interquartile range, floored at 15 minutes.",
        enforcedIn = "RoutineComposer.suggest"
    )
    val ADHERENCE_PER_ENTRY = rule(
        "RTN-05", ROUTINE, STRUCTURAL,
        "Adherence is measured entry by entry",
        "THEN each entry is compared with what actually happened, and the deviation is shown in minutes before any score is computed.",
        enforcedIn = "TimetableAdherence.evaluate"
    )
    val UNPASSED_NOT_SCORED = rule(
        "RTN-06", ROUTINE, GUARD,
        "The future is not scored",
        "IF an entry's window has not passed THEN it is excluded from today's adherence rather than counted as a miss.",
        enforcedIn = "TimetableAdherence.evaluate"
    )
    val WEIGHTED_MEAN = rule(
        "RTN-07", ROUTINE, STRUCTURAL,
        "Adherence is a transparent weighted mean",
        "THEN the score is the confidence-weighted mean of per-entry scores, and the arithmetic is shown on request.",
        enforcedIn = "TimetableAdherence.evaluate"
    )
    val EARLY_IS_NOT_LATE = rule(
        "RTN-08", ROUTINE, STRUCTURAL,
        "Early is not the same as late",
        "THEN being early inside tolerance costs nothing, and being early outside it costs less than being late by the same amount.",
        enforcedIn = "TimetableAdherence.entryScore"
    )
    val DRIFT_OFFERS_UPDATE = rule(
        "RTN-09", ROUTINE, STRUCTURAL,
        "Drift updates the plan, it does not nag",
        "IF your behaviour has moved consistently away from a routine entry THEN offer to update the routine instead of repeating that you are late.",
        enforcedIn = "RoutineComposer.driftProposals"
    )
    val INACTIVE_IS_SILENT = rule(
        "RTN-10", ROUTINE, GUARD,
        "Inactive routines are silent",
        "IF a routine is deactivated THEN it is neither scored nor prompted, and it disappears from Today.",
        enforcedIn = "TimetableAdherence.evaluate"
    )
    val INTENTIONAL_DEVIATION = rule(
        "RTN-11", ROUTINE, GUARD,
        "An intentional deviation removes the day from scoring",
        "IF you mark a day or an activity as intentional THEN it is excluded from adherence and never counted against you.",
        enforcedIn = "TimetableAdherence.evaluate"
    )
    val DAY_TYPE_ROUTINES = rule(
        "RTN-12", ROUTINE, STRUCTURAL,
        "Weekday and weekend routines are separate",
        "THEN a routine declares the days it applies to, and only applies on those days.",
        enforcedIn = "Timetable.appliesTo"
    )

    val rules = listOf(
        SUGGEST_NEVER_IMPOSE, NEEDS_ESTABLISHED_PATTERN, TIMETABLE_SHAPE, TOLERANCE_FROM_YOU,
        ADHERENCE_PER_ENTRY, UNPASSED_NOT_SCORED, WEIGHTED_MEAN, EARLY_IS_NOT_LATE,
        DRIFT_OFFERS_UPDATE, INACTIVE_IS_SILENT, INTENTIONAL_DEVIATION, DAY_TYPE_ROUTINES
    )
}

object ReminderRules {

    val NEEDS_PATTERN = rule(
        "RMD-01", REMINDER, GUARD,
        "No pattern, no prompt",
        "IF the underlying pattern is not established THEN no reminder is scheduled, however convenient the clock time looks.",
        enforcedIn = "ReminderScheduler.planToday"
    )
    val ALREADY_HAPPENED = rule(
        "RMD-02", REMINDER, GUARD,
        "Never prompt for something you already did",
        "IF the activity has already been observed today THEN cancel its reminder.",
        enforcedIn = "ReminderScheduler.planToday"
    )
    val COOLDOWN = rule(
        "RMD-03", REMINDER, GUARD,
        "Respect the cooldown",
        "IF a reminder for this activity fired recently THEN stay quiet until the cooldown has passed.",
        enforcedIn = "ReminderScheduler.planToday"
    )
    val ESCALATION = rule(
        "RMD-04", REMINDER, STRUCTURAL,
        "Gentle first, significant only if it matters",
        "THEN prompts escalate from gentle to significant once, and never beyond.",
        enforcedIn = "ReminderScheduler.planToday"
    )
    val DISMISSAL_SUPPRESSES = rule(
        "RMD-05", REMINDER, GUARD,
        "Dismissal is an answer",
        "IF you dismiss the same reminder repeatedly THEN stop sending it.",
        enforcedIn = "ReminderScheduler.planToday"
    )
    val PAUSED_IS_SILENT = rule(
        "RMD-06", REMINDER, GUARD,
        "Paused means paused",
        "IF tracking is paused or off THEN no reminders are scheduled at all.",
        enforcedIn = "ReminderScheduler.planToday"
    )
    val QUIET_HOURS = rule(
        "RMD-07", REMINDER, GUARD,
        "Quiet hours are quiet",
        "IF the prompt would land inside your learned sleep window THEN suppress it.",
        enforcedIn = "ReminderScheduler.planToday"
    )
    val NAME_THE_REASON = rule(
        "RMD-08", REMINDER, STRUCTURAL,
        "Every prompt names its reason",
        "THEN a reminder always states the pattern behind it — 'your usual lunch is around 13:24' — never a bare instruction.",
        enforcedIn = "ReminderScheduler.planToday"
    )
    val CLOCK_ALONE_IS_NOT_A_REASON = rule(
        "RMD-09", REMINDER, GUARD,
        "A clock threshold alone is not a reason",
        "IF the only justification is that a time has passed THEN do not prompt; context and confidence must agree.",
        enforcedIn = "ReminderScheduler.planToday"
    )
    val DAILY_BUDGET = rule(
        "RMD-10", REMINDER, GUARD,
        "There is a daily budget",
        "THEN the app spends at most a few prompts a day; a quiet day is a success.",
        enforcedIn = "ReminderScheduler.planToday"
    )

    val rules = listOf(
        NEEDS_PATTERN, ALREADY_HAPPENED, COOLDOWN, ESCALATION, DISMISSAL_SUPPRESSES,
        PAUSED_IS_SILENT, QUIET_HOURS, NAME_THE_REASON, CLOCK_ALONE_IS_NOT_A_REASON, DAILY_BUDGET
    )
}

object AnomalyRules {

    val YOUR_OWN_BASELINE = rule(
        "ANM-01", ANOMALY, STRUCTURAL,
        "Measured against you, not against anyone else",
        "THEN every deviation is computed against your own history — there is no population norm anywhere in this app.",
        enforcedIn = "AnomalyEngine.evaluate"
    )
    val BEYOND_YOUR_SPREAD = rule(
        "ANM-02", ANOMALY, EVIDENCE,
        "Notable means outside your usual spread",
        "IF today's value sits beyond p90 plus tolerance or below p10 minus tolerance THEN it is notable.",
        0.5f, "AnomalyEngine.evaluate"
    )
    val ONE_DAY_IS_NOT_A_TREND = rule(
        "ANM-03", ANOMALY, GUARD,
        "One day is not a trend",
        "IF a deviation appears on a single day THEN describe it as a day, never as a trend.",
        enforcedIn = "AnomalyEngine.evaluate"
    )
    val DESCRIBE_DONT_JUDGE = rule(
        "ANM-04", ANOMALY, STRUCTURAL,
        "Describe, never judge",
        "THEN deviations are stated as facts with numbers; the app has no opinion about whether your day was good.",
        enforcedIn = "NarrativeEngine.compose"
    )
    val MISSING_DATA_IS_NOT_ANOMALY = rule(
        "ANM-05", ANOMALY, GUARD,
        "Missing data is not an anomaly",
        "IF the deviation is explained by absent evidence THEN say coverage was low instead of claiming behaviour changed.",
        enforcedIn = "AnomalyEngine.evaluate"
    )
    val LOW_COVERAGE_NOT_SCORED = rule(
        "ANM-06", ANOMALY, GUARD,
        "Thin days are not scored",
        "IF a day is less than half reconstructed THEN it is excluded from deviation analysis and rhythm scoring.",
        enforcedIn = "AnomalyEngine.evaluate"
    )

    val rules = listOf(
        YOUR_OWN_BASELINE, BEYOND_YOUR_SPREAD, ONE_DAY_IS_NOT_A_TREND, DESCRIBE_DONT_JUDGE,
        MISSING_DATA_IS_NOT_ANOMALY, LOW_COVERAGE_NOT_SCORED
    )
}

object ConsistencyRules {

    val DEFINITION = rule(
        "CNS-01", CONSISTENCY, STRUCTURAL,
        "Consistency is one minus your normalised spread",
        "THEN an activity's consistency is 1 − (interquartile range ÷ 180 minutes), clamped to 0–100%.",
        enforcedIn = "PatternEngine.consistency"
    )
    val MIN_OBSERVATIONS = rule(
        "CNS-02", CONSISTENCY, GUARD,
        "Five observations before consistency is shown",
        "IF fewer than 5 observations exist THEN consistency is withheld rather than estimated from noise.",
        enforcedIn = "PatternEngine.consistency"
    )
    val RHYTHM_SCORE = rule(
        "CNS-03", CONSISTENCY, STRUCTURAL,
        "The rhythm score is a weighted blend",
        "THEN today's rhythm score blends each pattern's match with your usual time, weighted by that pattern's confidence.",
        enforcedIn = "RhythmScorer.score"
    )
    val PREDICTABILITY = rule(
        "CNS-04", CONSISTENCY, STRUCTURAL,
        "Predictability uses temporal entropy",
        "THEN predictability is derived from the entropy of the day's activity distribution across the 24-hour grid.",
        enforcedIn = "StatisticsEngine.predictability"
    )
    val DAY_SIMILARITY = rule(
        "CNS-05", CONSISTENCY, STRUCTURAL,
        "Day similarity is minute-by-minute agreement",
        "THEN two days are compared by the share of minutes on which the same activity was happening.",
        enforcedIn = "StatisticsEngine.similarity"
    )
    val COMPARABLE_DAYS_ONLY = rule(
        "CNS-06", CONSISTENCY, GUARD,
        "Compare like with like",
        "IF two days are of different types THEN do not compare them without saying so.",
        enforcedIn = "StatisticsEngine.similarity"
    )

    val rules = listOf(
        DEFINITION, MIN_OBSERVATIONS, RHYTHM_SCORE, PREDICTABILITY, DAY_SIMILARITY,
        COMPARABLE_DAYS_ONLY
    )
}

object NarrativeRules {

    val SILENCE_IS_VALID = rule(
        "NAR-01", NARRATIVE, GUARD,
        "Silence beats a filler insight",
        "IF nothing meaningful can be said THEN say nothing — empty space is better than a manufactured observation.",
        enforcedIn = "NarrativeEngine.compose"
    )
    val CITE_EVIDENCE = rule(
        "NAR-02", NARRATIVE, NARRATIVE_KIND,
        "Every sentence carries its evidence",
        "THEN each insight stores the measurement and rules behind it, and can show them on tap.",
        enforcedIn = "NarrativeEngine.compose"
    )
    val HEDGE_WHEN_UNSURE = rule(
        "NAR-03", NARRATIVE, NARRATIVE_KIND,
        "Hedge in proportion to confidence",
        "IF confidence is below 0.8 THEN use 'appears', 'usually', 'around' — certainty is reserved for measured facts.",
        enforcedIn = "NarrativeEngine.hedge"
    )
    val NO_MOTIVATION = rule(
        "NAR-04", NARRATIVE, GUARD,
        "No motivational filler",
        "THEN no quotes, no streak-shaming, no encouragement the data does not support.",
        enforcedIn = "NarrativeEngine.compose"
    )
    val RANKED_AND_LIMITED = rule(
        "NAR-05", NARRATIVE, NARRATIVE_KIND,
        "Few insights, ranked by significance",
        "THEN insights are ranked by how far behaviour moved and how confident the pattern is, and the screen shows only the top few.",
        enforcedIn = "NarrativeEngine.compose"
    )
    val NO_FALSE_CERTAINTY = rule(
        "NAR-06", NARRATIVE, GUARD,
        "Never claim more than the confidence supports",
        "IF an inference is below the Likely band THEN it may not be stated as fact anywhere in the interface.",
        enforcedIn = "NarrativeEngine.compose"
    )
    val COMPARE_TO_YOURSELF = rule(
        "NAR-07", NARRATIVE, NARRATIVE_KIND,
        "Compare only to your own baseline",
        "THEN every comparison is against your own median for the same day type.",
        enforcedIn = "NarrativeEngine.compose"
    )
    val WORTH_SAYING = rule(
        "NAR-08", NARRATIVE, GUARD,
        "An insight must be interesting or useful",
        "IF a deviation is under the noticeable threshold THEN it is not published, even if it is technically true.",
        enforcedIn = "NarrativeEngine.compose"
    )
    val ENOUGH_HISTORY = rule(
        "NAR-09", NARRATIVE, GUARD,
        "Enough history before narration",
        "IF the pattern behind a sentence has fewer than 5 observations THEN the sentence is not published.",
        enforcedIn = "NarrativeEngine.compose"
    )
    val NO_REPEATS = rule(
        "NAR-10", NARRATIVE, GUARD,
        "Do not repeat yesterday's sentence",
        "IF the same insight was shown on the previous day THEN suppress it unless the measurement changed materially.",
        enforcedIn = "NarrativeEngine.compose"
    )

    val rules = listOf(
        SILENCE_IS_VALID, CITE_EVIDENCE, HEDGE_WHEN_UNSURE, NO_MOTIVATION, RANKED_AND_LIMITED,
        NO_FALSE_CERTAINTY, COMPARE_TO_YOURSELF, WORTH_SAYING, ENOUGH_HISTORY, NO_REPEATS
    )
}
