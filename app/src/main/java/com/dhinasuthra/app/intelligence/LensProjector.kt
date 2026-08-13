package com.dhinasuthra.app.intelligence

/**
 * The three lenses (spec §6) — and the wall between them.
 *
 * The single most important data rule in DhinaSuthra: **activity and location are
 * never mixed in one aggregation.** "Sleep 8h, Work 6h, Home 15h" is a broken
 * chart, because the hours are counted twice and the categories answer two
 * different questions. So there are exactly three legal projections, and the type
 * system makes it awkward to build a fourth.
 */
object LensProjector {

    enum class Lens(val label: String, val question: String) {
        ACTIVITY("Activity", "What did I spend my time doing?"),
        LOCATION("Location", "Where did I spend my time?"),
        ACTIVITY_AT_LOCATION("Activity @ Location", "What did I do at each place?")
    }

    data class ActivitySlice(val activity: ActivityType, val minutes: Int) {
        val label: String get() = activity.label
    }

    data class LocationSlice(val location: LocationType, val placeName: String?, val minutes: Int) {
        val label: String get() = placeName ?: location.label
    }

    data class LocationGroup(
        val location: LocationType,
        val placeName: String?,
        val totalMinutes: Int,
        val activities: List<ActivitySlice>
    ) {
        val label: String get() = placeName ?: location.label
    }

    // -- Lens 1: activity ---------------------------------------------------

    fun activityLens(day: DayReconstruction): List<ActivitySlice> = activityLens(listOf(day))

    fun activityLens(days: List<DayReconstruction>): List<ActivitySlice> =
        days.flatMap { it.episodes }
            .groupBy { it.activity }
            .map { (activity, list) -> ActivitySlice(activity, list.sumOf { it.durationMin }) }
            .filter { it.minutes > 0 }
            .sortedByDescending { it.minutes }

    // -- Lens 2: location ---------------------------------------------------

    fun locationLens(day: DayReconstruction): List<LocationSlice> = locationLens(listOf(day))

    fun locationLens(days: List<DayReconstruction>): List<LocationSlice> =
        days.flatMap { it.episodes }
            .groupBy { it.location to it.placeName }
            .map { (key, list) -> LocationSlice(key.first, key.second, list.sumOf { it.durationMin }) }
            .filter { it.minutes > 0 }
            .sortedByDescending { it.minutes }

    // -- Lens 3: the only legal crossing ------------------------------------

    fun activityAtLocation(day: DayReconstruction): List<LocationGroup> = activityAtLocation(listOf(day))

    fun activityAtLocation(days: List<DayReconstruction>): List<LocationGroup> =
        days.flatMap { it.episodes }
            .groupBy { it.location to it.placeName }
            .map { (key, list) ->
                LocationGroup(
                    location = key.first,
                    placeName = key.second,
                    totalMinutes = list.sumOf { it.durationMin },
                    activities = list.groupBy { it.activity }
                        .map { (a, l) -> ActivitySlice(a, l.sumOf { it.durationMin }) }
                        .sortedByDescending { it.minutes }
                )
            }
            .filter { it.totalMinutes > 0 }
            .sortedByDescending { it.totalMinutes }

    /**
     * WRK-07 — presence and activity, side by side and clearly distinct.
     * "6h 42m at the office, of which 4h 51m working" is one honest sentence;
     * "Office 6h 42m" in an activity chart is not.
     */
    data class PresenceVsActivity(
        val location: LocationType,
        val placeName: String?,
        val presenceMinutes: Int,
        val activityMinutes: Int,
        val activity: ActivityType
    )

    fun presenceVsActivity(
        days: List<DayReconstruction>,
        location: LocationType,
        activity: ActivityType
    ): PresenceVsActivity {
        val episodes = days.flatMap { it.episodes }.filter { it.location == location }
        return PresenceVsActivity(
            location = location,
            placeName = episodes.firstOrNull { it.placeName != null }?.placeName,
            presenceMinutes = episodes.sumOf { it.durationMin },
            activityMinutes = episodes.filter { it.activity == activity }.sumOf { it.durationMin },
            activity = activity
        )
    }

    /** Each lens totals the same 24 hours per day — the invariant the UI can rely on. */
    fun reconciles(day: DayReconstruction): Boolean {
        val byActivity = activityLens(day).sumOf { it.minutes }
        val byLocation = locationLens(day).sumOf { it.minutes }
        return byActivity == byLocation && byActivity == day.coveredMin
    }
}
