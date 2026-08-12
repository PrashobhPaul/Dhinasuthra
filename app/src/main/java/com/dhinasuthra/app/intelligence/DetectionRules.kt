package com.dhinasuthra.app.intelligence

import com.dhinasuthra.app.intelligence.RuleDomain.COMMUTE
import com.dhinasuthra.app.intelligence.RuleDomain.LOCATION
import com.dhinasuthra.app.intelligence.RuleDomain.MEAL
import com.dhinasuthra.app.intelligence.RuleDomain.SLEEP
import com.dhinasuthra.app.intelligence.RuleDomain.WORK
import com.dhinasuthra.app.intelligence.RuleKind.EVIDENCE
import com.dhinasuthra.app.intelligence.RuleKind.GUARD
import com.dhinasuthra.app.intelligence.RuleKind.STRUCTURAL

/**
 * Detection rules — how raw evidence becomes an activity.
 *
 * These are the rules the spec argues about directly (§9–§14): alarm dismissal is
 * not wakefulness, office presence is not work, lunch is not 13:00, and a geofence
 * crossing is not a departure.
 */

object SleepRules {

    val WAKE_INTENT_ONLY = rule(
        "SLP-01", SLEEP, GUARD,
        "Alarm dismissal is intent, not wakefulness",
        "IF an alarm is dismissed THEN raise a wake-intent candidate and keep the sleep episode open — dismissal alone never ends sleep.",
        enforcedIn = "SleepInterpreter.confirmWake"
    )
    val QUIET_WINDOW = rule(
        "SLP-02", SLEEP, EVIDENCE,
        "Sustained device quiet at night",
        "IF the device stays non-interactive for at least 3 hours inside the night window THEN treat that run as a sleep candidate.",
        0.45f, "SleepInterpreter.findQuietWindow"
    )
    val CHARGING_OVERNIGHT = rule(
        "SLP-03", SLEEP, EVIDENCE,
        "Charging through the quiet window",
        "IF the phone charges during the quiet window THEN strengthen the sleep hypothesis — people plug in at night.",
        0.2f, "SleepInterpreter.scoreOnset"
    )
    val LOCATION_STABLE = rule(
        "SLP-04", SLEEP, EVIDENCE,
        "Location stable through the night",
        "IF location does not change meaningfully across the quiet window THEN strengthen the sleep hypothesis.",
        0.2f, "SleepInterpreter.scoreOnset"
    )
    val PLAUSIBLE_LENGTH = rule(
        "SLP-05", SLEEP, EVIDENCE,
        "Human-plausible duration",
        "IF the candidate window lasts between 3 and 11 hours THEN strengthen it; outside that range it is quiet time, not necessarily sleep.",
        0.18f, "SleepInterpreter.scoreOnset"
    )
    val ONSET_MATCHES_PATTERN = rule(
        "SLP-06", SLEEP, EVIDENCE,
        "Onset near your learned sleep time",
        "IF sleep onset falls inside the learned p10–p90 sleep band THEN strengthen it with your own history.",
        0.22f, "SleepInterpreter.scoreOnset"
    )
    val MOVEMENT_CONFIRMS_WAKE = rule(
        "SLP-07", SLEEP, EVIDENCE,
        "Movement confirms waking",
        "IF sustained movement is detected after a wake-intent candidate THEN confirm wake at the movement.",
        0.4f, "SleepInterpreter.confirmWake"
    )
    val INTERACTION_CONFIRMS_WAKE = rule(
        "SLP-08", SLEEP, EVIDENCE,
        "Repeated device use confirms waking",
        "IF the device is used in two or more consecutive samples after wake intent THEN confirm wake.",
        0.35f, "SleepInterpreter.confirmWake"
    )
    val DEPARTURE_CONFIRMS_WAKE = rule(
        "SLP-09", SLEEP, EVIDENCE,
        "Leaving the sleep location confirms waking",
        "IF the person leaves the place they slept at THEN wake is confirmed no later than the departure.",
        0.6f, "SleepInterpreter.confirmWake"
    )
    val BRIEF_STIR_IGNORED = rule(
        "SLP-10", SLEEP, GUARD,
        "A single stir does not end the night",
        "IF exactly one isolated interaction occurs inside the quiet window THEN absorb it — checking the time at 3am is not waking up.",
        enforcedIn = "SleepInterpreter.findQuietWindow"
    )
    val SLEEP_EXTENDS_TO_CONFIRMED_WAKE = rule(
        "SLP-11", SLEEP, STRUCTURAL,
        "Sleep ends at confirmed wake",
        "THEN the sleep episode runs to the confirmed wake time, not to the first alarm or the first stir.",
        enforcedIn = "SleepInterpreter.interpret"
    )
    val WAKE_MATCHES_PATTERN = rule(
        "SLP-12", SLEEP, EVIDENCE,
        "Wake near your learned wake time",
        "IF the confirmed wake falls inside the learned wake band THEN raise confidence in the reconstruction.",
        0.18f, "SleepInterpreter.confirmWake"
    )
    val TOO_SHORT_IS_NOT_NIGHT = rule(
        "SLP-13", SLEEP, GUARD,
        "Short night windows are not the night's sleep",
        "IF the candidate is under 90 minutes THEN do not record it as the night's sleep.",
        enforcedIn = "SleepInterpreter.findQuietWindow"
    )
    val WAKE_TRANSITION_EPISODE = rule(
        "SLP-14", SLEEP, STRUCTURAL,
        "The stir before waking is kept, not discarded",
        "THEN the sleep episode carries both the first stir and the confirmed wake, so you can always see the gap between deciding to get up and actually getting up.",
        enforcedIn = "SleepInterpreter.episodes"
    )
    val NO_SAMPLES_NO_SLEEP = rule(
        "SLP-15", SLEEP, GUARD,
        "No evidence, no sleep story",
        "IF fewer than four usable samples exist for the night THEN report sleep as unknown instead of estimating it.",
        enforcedIn = "SleepInterpreter.interpret"
    )
    val DAYTIME_REST = rule(
        "SLP-16", SLEEP, EVIDENCE,
        "Daytime rest is separate from the night",
        "IF a quiet stationary period of 45+ minutes happens at home in the afternoon THEN mark it as rest, never merged into the night's sleep total.",
        0.3f, "SleepInterpreter.findDaytimeRest"
    )

    val rules = listOf(
        WAKE_INTENT_ONLY, QUIET_WINDOW, CHARGING_OVERNIGHT, LOCATION_STABLE, PLAUSIBLE_LENGTH,
        ONSET_MATCHES_PATTERN, MOVEMENT_CONFIRMS_WAKE, INTERACTION_CONFIRMS_WAKE,
        DEPARTURE_CONFIRMS_WAKE, BRIEF_STIR_IGNORED, SLEEP_EXTENDS_TO_CONFIRMED_WAKE,
        WAKE_MATCHES_PATTERN, TOO_SHORT_IS_NOT_NIGHT, WAKE_TRANSITION_EPISODE,
        NO_SAMPLES_NO_SLEEP, DAYTIME_REST
    )
}

object MealRules {

    val NO_HARDCODED_LUNCH = rule(
        "MEA-01", MEAL, GUARD,
        "Lunch is never a hard-coded clock time",
        "IF no learned lunch pattern and no location evidence exist THEN leave the period unclassified rather than calling 13:00 lunch.",
        enforcedIn = "MealInterpreter.lunchCandidate"
    )
    val LEARNED_START = rule(
        "MEA-02", MEAL, EVIDENCE,
        "Start matches your learned lunch time",
        "IF the window starts within 25 minutes of your learned typical lunch start THEN treat it as lunch evidence.",
        0.4f, "MealInterpreter.lunchCandidate"
    )
    val LEARNED_DURATION = rule(
        "MEA-03", MEAL, EVIDENCE,
        "Duration matches your learned lunch length",
        "IF the window lasts within ±15 minutes of your typical lunch duration THEN strengthen the hypothesis.",
        0.25f, "MealInterpreter.lunchCandidate"
    )
    val EATING_PLACE = rule(
        "MEA-04", MEAL, EVIDENCE,
        "You were at an eating place",
        "IF the window is spent at a place categorised as a restaurant or cafeteria THEN strengthen the meal hypothesis.",
        0.35f, "MealInterpreter.lunchCandidate"
    )
    val WEEKDAY_REPEAT = rule(
        "MEA-05", MEAL, EVIDENCE,
        "The same window repeats on weekdays",
        "IF a similar window has occurred on 3 or more comparable days THEN strengthen the hypothesis with repetition.",
        0.3f, "MealInterpreter.lunchCandidate"
    )
    val MIDDAY_BAND = rule(
        "MEA-06", MEAL, EVIDENCE,
        "The window sits in the midday band",
        "IF the window starts between 11:00 and 15:30 THEN it is eligible to be lunch — necessary, never sufficient.",
        0.15f, "MealInterpreter.lunchCandidate"
    )
    val SURROUNDED_BY_WORK = rule(
        "MEA-07", MEAL, EVIDENCE,
        "Work before and work after",
        "IF the window is bracketed by work at the same place THEN it looks like a break taken out of the working day.",
        0.2f, "MealInterpreter.lunchCandidate"
    )
    val USER_CORRECTION_PRIOR = rule(
        "MEA-08", MEAL, EVIDENCE,
        "You corrected this before",
        "IF you previously relabelled a similar window as this meal THEN apply that correction as strong personal evidence.",
        0.45f, "CorrectionStore.priorFor"
    )
    val BREAKFAST_WINDOW = rule(
        "MEA-09", MEAL, EVIDENCE,
        "Breakfast sits between waking and leaving",
        "IF a home period of 15–60 minutes falls between confirmed wake and departure, inside a breakfast band you have either repeated or stated in your routine, THEN infer breakfast — with no such band, the period stays personal time and breakfast is only ever offered as a question.",
        0.3f, "MealInterpreter.breakfastCandidate"
    )
    val DINNER_WINDOW = rule(
        "MEA-10", MEAL, EVIDENCE,
        "Dinner follows the evening return",
        "IF a home period of 20–90 minutes falls after arriving home, inside an evening meal band you have either repeated or stated in your routine, THEN infer dinner — with no such band, the period stays personal time.",
        0.3f, "MealInterpreter.dinnerCandidate"
    )
    val MEAL_LENGTH_CAP = rule(
        "MEA-11", MEAL, GUARD,
        "A meal has an upper bound",
        "IF a candidate window exceeds 120 minutes THEN it is not a meal — long midday absences are classified separately.",
        enforcedIn = "MealInterpreter.lunchCandidate"
    )
    val STRONGEST_CANDIDATE_WINS = rule(
        "MEA-12", MEAL, STRUCTURAL,
        "One meal per window",
        "IF two meal candidates overlap THEN keep only the better-evidenced one.",
        enforcedIn = "DayBuilder.build"
    )
    val WALK_BOUNDARY = rule(
        "MEA-13", MEAL, EVIDENCE,
        "You got up and came back",
        "IF a walking burst opens and closes the window THEN strengthen the hypothesis that you left the desk to eat.",
        0.2f, "MealInterpreter.lunchCandidate"
    )

    val rules = listOf(
        NO_HARDCODED_LUNCH, LEARNED_START, LEARNED_DURATION, EATING_PLACE, WEEKDAY_REPEAT,
        MIDDAY_BAND, SURROUNDED_BY_WORK, USER_CORRECTION_PRIOR, BREAKFAST_WINDOW,
        DINNER_WINDOW, MEAL_LENGTH_CAP, STRONGEST_CANDIDATE_WINS, WALK_BOUNDARY
    )
}

object WorkRules {

    val PRESENCE_IS_NOT_WORK = rule(
        "WRK-01", WORK, GUARD,
        "Being at the office is not working",
        "IF the person is present at the office THEN never label the whole presence as work — presence and activity are different measurements.",
        enforcedIn = "WorkInterpreter.decompose"
    )
    val DECOMPOSE_PRESENCE = rule(
        "WRK-02", WORK, STRUCTURAL,
        "Presence decomposes into activities",
        "THEN office presence is split into work, meeting, lunch, break and unclassified time, and the parts must sum back to the presence.",
        enforcedIn = "WorkInterpreter.decompose"
    )
    val STATIONARY_IS_WORK = rule(
        "WRK-03", WORK, EVIDENCE,
        "Stationary office time outside breaks is work",
        "IF the person is stationary at the office and no meal or break candidate covers the period THEN infer work.",
        0.55f, "WorkInterpreter.decompose"
    )
    val SHORT_EXCURSION_IS_BREAK = rule(
        "WRK-04", WORK, EVIDENCE,
        "Short excursions are breaks",
        "IF the person leaves and returns to the office within 5–25 minutes THEN infer a break rather than a departure.",
        0.4f, "WorkInterpreter.decompose"
    )
    val UNTOUCHED_STILL_BLOCK = rule(
        "WRK-05", WORK, EVIDENCE,
        "A long untouched still block looks like a meeting",
        "IF a 30+ minute office block is stationary with no device interaction THEN suggest a meeting — never more than 'possible', because the app cannot see calendars.",
        0.28f, "WorkInterpreter.decompose"
    )
    val RESUME_AFTER_BREAK = rule(
        "WRK-06", WORK, EVIDENCE,
        "Work resumes after a break",
        "IF a break ends and office presence continues THEN resume the work episode instead of opening an unclassified gap.",
        0.3f, "WorkInterpreter.decompose"
    )
    val REPORT_BOTH = rule(
        "WRK-07", WORK, GUARD,
        "Report presence and work separately",
        "THEN the UI must show both 'at office' and 'working' totals, and must never present one as the other.",
        enforcedIn = "LensProjector / WorkInterpreter.decompose"
    )
    val ARRIVAL_STARTS_WORK = rule(
        "WRK-08", WORK, EVIDENCE,
        "Arrival in the learned band starts the working day",
        "IF office arrival falls inside your learned arrival band THEN treat it as the day's work start.",
        0.25f, "WorkInterpreter.decompose"
    )
    val LAST_DEPARTURE_ENDS_WORK = rule(
        "WRK-09", WORK, STRUCTURAL,
        "The last departure ends the working day",
        "THEN the final office departure of the day closes work; earlier departures that are returned from are breaks.",
        enforcedIn = "WorkInterpreter.decompose"
    )
    val REMOTE_WORK = rule(
        "WRK-10", WORK, EVIDENCE,
        "Working from home is still work",
        "IF a weekday has no office presence but shows a long stationary home block inside your learned working hours THEN infer work at home.",
        0.3f, "WorkInterpreter.remoteWork"
    )
    val WEEKEND_PRESENCE_NEUTRAL = rule(
        "WRK-11", WORK, GUARD,
        "Weekend office time is not assumed to be work",
        "IF the day is a weekend THEN office presence stays unclassified unless a weekend work pattern already exists.",
        enforcedIn = "WorkInterpreter.decompose"
    )

    val rules = listOf(
        PRESENCE_IS_NOT_WORK, DECOMPOSE_PRESENCE, STATIONARY_IS_WORK, SHORT_EXCURSION_IS_BREAK,
        UNTOUCHED_STILL_BLOCK, RESUME_AFTER_BREAK, REPORT_BOTH, ARRIVAL_STARTS_WORK,
        LAST_DEPARTURE_ENDS_WORK, REMOTE_WORK, WEEKEND_PRESENCE_NEUTRAL
    )
}

object CommuteRules {

    val LOCOMOTION_IS_COMMUTE = rule(
        "CMT-01", COMMUTE, EVIDENCE,
        "Sustained locomotion away from places is travel",
        "IF vehicle or cycling movement is detected outside every known place THEN infer travel.",
        0.5f, "CommuteInterpreter.interpret"
    )
    val JOURNEY_IS_ONE_EPISODE = rule(
        "CMT-02", COMMUTE, STRUCTURAL,
        "A journey is one episode",
        "THEN travel from one known place to the next is a single episode, whatever the transport mix along the way.",
        enforcedIn = "CommuteInterpreter.interpret"
    )
    val BRIEF_STOPS_DONT_SPLIT = rule(
        "CMT-03", COMMUTE, STRUCTURAL,
        "Brief stops do not split a journey",
        "IF a stop inside a journey lasts under 6 minutes THEN keep the journey continuous — traffic lights are not destinations.",
        enforcedIn = "CommuteInterpreter.interpret"
    )
    val MORNING_COMMUTE = rule(
        "CMT-04", COMMUTE, EVIDENCE,
        "Home to office in the morning band",
        "IF travel runs from home to the office inside your learned morning band THEN label it as your morning commute.",
        0.3f, "CommuteInterpreter.interpret"
    )
    val EVENING_COMMUTE = rule(
        "CMT-05", COMMUTE, EVIDENCE,
        "Office to home in the evening band",
        "IF travel runs from the office to home inside your learned evening band THEN label it as your evening commute.",
        0.3f, "CommuteInterpreter.interpret"
    )
    val WALKING_NEAR_PLACE_IS_ARRIVAL = rule(
        "CMT-06", COMMUTE, GUARD,
        "Walking beside a known place is arrival, not travel",
        "IF walking is detected within the arrival radius of a known place THEN treat it as part of arriving, not as a journey.",
        enforcedIn = "LocationReconciler.reconcile"
    )
    val TYPICAL_JOURNEY_LENGTH = rule(
        "CMT-07", COMMUTE, EVIDENCE,
        "Journey length matches the usual for this pair",
        "IF the journey duration is close to your learned typical for the same origin and destination THEN raise confidence.",
        0.2f, "CommuteInterpreter.interpret"
    )

    val rules = listOf(
        LOCOMOTION_IS_COMMUTE, JOURNEY_IS_ONE_EPISODE, BRIEF_STOPS_DONT_SPLIT, MORNING_COMMUTE,
        EVENING_COMMUTE, WALKING_NEAR_PLACE_IS_ARRIVAL, TYPICAL_JOURNEY_LENGTH
    )
}

object LocationRules {

    val CROSSING_IS_NOT_DEPARTURE = rule(
        "LOC-01", LOCATION, GUARD,
        "A geofence crossing is not a departure",
        "IF a geofence exit fires THEN raise a departure candidate only — the timeline is not touched until the departure is confirmed.",
        enforcedIn = "LocationReconciler.reconcile"
    )
    val DWELL_CONFIRMS_DEPARTURE = rule(
        "LOC-02", LOCATION, EVIDENCE,
        "Departure needs dwell away",
        "IF the person stays outside the place for at least 8 minutes THEN confirm the departure.",
        0.45f, "LocationReconciler.reconcile"
    )
    val QUICK_RETURN_MERGES = rule(
        "LOC-03", LOCATION, STRUCTURAL,
        "A quick return erases the excursion",
        "IF the person returns to the same place within the noise window THEN merge the excursion away instead of writing leave/arrive pairs.",
        enforcedIn = "LocationReconciler.reconcile"
    )
    val DWELL_CONFIRMS_ARRIVAL = rule(
        "LOC-04", LOCATION, EVIDENCE,
        "Stationary dwell confirms arrival",
        "IF the person stays inside the place and is stationary THEN confirm the arrival and raise its confidence.",
        0.4f, "LocationReconciler.reconcile"
    )
    val PARK_AND_WALK = rule(
        "LOC-05", LOCATION, STRUCTURAL,
        "Park, walk, arrive — one arrival",
        "IF a vehicle stop is followed by walking that ends inside a known place THEN treat the whole sequence as one continuous arrival.",
        enforcedIn = "LocationReconciler.reconcile"
    )
    val NO_DUPLICATE_ARRIVALS = rule(
        "LOC-06", LOCATION, GUARD,
        "No duplicate arrivals",
        "IF an arrival at a place is already open THEN never emit a second arrival without a confirmed departure between them.",
        enforcedIn = "LocationReconciler.reconcile"
    )
    val ACCURACY_WEIGHTING = rule(
        "LOC-07", LOCATION, EVIDENCE,
        "Poor fixes carry less weight",
        "IF a location fix reports accuracy worse than 120 m THEN halve the weight of the evidence it provides.",
        0.1f, "LocationReconciler.reconcile"
    )
    val REPEAT_VISITS_RAISE_PLACE = rule(
        "LOC-08", LOCATION, EVIDENCE,
        "Repeat visits make a place real",
        "IF a location is visited on 3 or more distinct days THEN promote it to a place candidate worth naming.",
        0.3f, "PlaceLearner.learnFromDay"
    )
    val CANDIDATES_NOT_FACTS = rule(
        "LOC-09", LOCATION, STRUCTURAL,
        "Unknown stays become candidates, not places",
        "THEN a stay at unrecognised coordinates is stored as a candidate for the user to name — the app never invents a place name.",
        enforcedIn = "PlaceLearner.learnFromDay"
    )
    val SINGLE_VISIT_EXPLAINS_NOTHING = rule(
        "LOC-10", LOCATION, GUARD,
        "One visit explains nothing",
        "IF a place has been visited on a single day THEN it is never used as evidence for an activity.",
        enforcedIn = "LocationReconciler.reconcile"
    )
    val SPEED_TRANSITION_ANCHOR = rule(
        "LOC-11", LOCATION, EVIDENCE,
        "Slowing down near a place anchors arrival",
        "IF movement changes from vehicle to walking within the approach radius of a place THEN anchor the arrival to that transition.",
        0.25f, "LocationReconciler.reconcile"
    )
    val ONE_PRESENCE_PER_STAY = rule(
        "LOC-12", LOCATION, STRUCTURAL,
        "Consecutive segments at one place are one presence",
        "THEN back-to-back segments at the same place collapse into a single presence before any activity is inferred.",
        enforcedIn = "LocationReconciler.reconcile"
    )

    val rules = listOf(
        CROSSING_IS_NOT_DEPARTURE, DWELL_CONFIRMS_DEPARTURE, QUICK_RETURN_MERGES,
        DWELL_CONFIRMS_ARRIVAL, PARK_AND_WALK, NO_DUPLICATE_ARRIVALS, ACCURACY_WEIGHTING,
        REPEAT_VISITS_RAISE_PLACE, CANDIDATES_NOT_FACTS, SINGLE_VISIT_EXPLAINS_NOTHING,
        SPEED_TRANSITION_ANCHOR, ONE_PRESENCE_PER_STAY
    )
}
