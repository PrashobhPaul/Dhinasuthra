package com.dhinasuthra.app.ui.lab

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dhinasuthra.app.core.TimeUtils
import com.dhinasuthra.app.intelligence.ActivityType
import com.dhinasuthra.app.intelligence.DayReconstruction
import com.dhinasuthra.app.intelligence.LensProjector
import com.dhinasuthra.app.intelligence.PatternEngine
import com.dhinasuthra.app.intelligence.StatisticsEngine
import com.dhinasuthra.app.ui.foundation.CardBody
import com.dhinasuthra.app.ui.foundation.DsChip
import com.dhinasuthra.app.ui.foundation.DsScreen
import com.dhinasuthra.app.ui.foundation.EmptyState
import com.dhinasuthra.app.ui.foundation.GlassCard
import com.dhinasuthra.app.ui.foundation.Hairline
import com.dhinasuthra.app.ui.foundation.Reveal
import com.dhinasuthra.app.ui.foundation.SectionTitle
import com.dhinasuthra.app.ui.foundation.StatTile
import com.dhinasuthra.app.ui.state.rememberTimeViewModel
import com.dhinasuthra.app.ui.theme.DsTokens
import com.dhinasuthra.app.ui.viz.ActivityLegend
import com.dhinasuthra.app.ui.viz.BandStrip
import com.dhinasuthra.app.ui.viz.BarChart
import com.dhinasuthra.app.ui.viz.Bar
import com.dhinasuthra.app.ui.viz.BoxPlot
import com.dhinasuthra.app.ui.viz.CalendarFingerprints
import com.dhinasuthra.app.ui.viz.DayRibbon
import com.dhinasuthra.app.ui.viz.DonutChart
import com.dhinasuthra.app.ui.viz.Histogram
import com.dhinasuthra.app.ui.viz.IntensityScale
import com.dhinasuthra.app.ui.viz.LandscapeCell
import com.dhinasuthra.app.ui.viz.Meter
import com.dhinasuthra.app.ui.viz.RadialDayClock
import com.dhinasuthra.app.ui.viz.ScatterPlot
import com.dhinasuthra.app.ui.viz.Slice
import com.dhinasuthra.app.ui.viz.StackedBars
import com.dhinasuthra.app.ui.viz.TemporalHeatmap
import com.dhinasuthra.app.ui.viz.TimeLandscape2D
import com.dhinasuthra.app.ui.viz.TimeLandscape3D
import com.dhinasuthra.app.ui.viz.TrendLine
import com.dhinasuthra.app.ui.viz.YearField
import com.dhinasuthra.app.ui.viz.percentOf
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/**
 * Time Lab = obsession (spec §25, §26, §53).
 *
 * The deliberately overwhelming half of the product. Range × lens × view is an
 * explicit matrix: pick a window of time, pick which question you are asking
 * (activity, location, or activity at location), then pick how you want to look
 * at it. Every view reads the same reconstructed episodes, so nothing here can
 * disagree with the timeline.
 */

private enum class Range(val label: String, val days: Int) {
    DAY("Day", 1), WEEK("Week", 7), MONTH("Month", 30), QUARTER("90 days", 90), YEAR("Year", 365)
}

private enum class LabView(val label: String) {
    TIMELINE("Timeline"), RADIAL("Radial"), HEATMAP("Heatmap"), CALENDAR("Calendar"),
    DISTRIBUTION("Distribution"), STATISTICS("Statistics"), COMPARISON("Comparison"),
    LANDSCAPE("3D")
}

@Composable
fun TimeLabScreen() {
    val vm = rememberTimeViewModel()
    val state by vm.state.collectAsState()
    val snapshot = state.snapshot

    var range by remember { mutableStateOf(Range.WEEK) }
    var lens by remember { mutableIntStateOf(0) }
    var view by remember { mutableStateOf(LabView.HEATMAP) }
    var focusActivity by remember { mutableStateOf(ActivityType.LUNCH) }
    var use3D by remember { mutableStateOf(true) }

    // Memoised in the composable body: the lazy-list content lambda is not a
    // composable scope, so remember() cannot live inside it.
    val history = snapshot?.history
    val fallbackDay = snapshot?.today
    val days = remember(history, range, fallbackDay) {
        val window = history.orEmpty().takeLast(range.days)
        when {
            window.isNotEmpty() -> window
            fallbackDay != null -> listOf(fallbackDay)
            else -> emptyList()
        }
    }

    DsScreen(
        title = "Time Lab",
        subtitle = "Everything, in as much detail as you want"
    ) {
        if (snapshot == null || days.isEmpty()) {
            item { EmptyState("Warming up", "Reconstructing your history.") }
            return@DsScreen
        }

        item {
            GlassCard(Modifier.fillMaxWidth(), interactive = false) {
                CardBody(spacing = DsTokens.GapM) {
                    Text("RANGE", style = MaterialTheme.typography.labelSmall)
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Range.entries.forEach {
                            DsChip(it.label, range == it, { range = it })
                        }
                    }
                    Text("LENS", style = MaterialTheme.typography.labelSmall)
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        LensProjector.Lens.entries.forEachIndexed { i, l ->
                            DsChip(
                                if (i == 2) "Activity @ Place" else l.label,
                                lens == i, { lens = i }, accent = DsTokens.Cyan
                            )
                        }
                    }
                    Text("VIEW", style = MaterialTheme.typography.labelSmall)
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        LabView.entries.forEach {
                            DsChip(it.label, view == it, { view = it }, accent = DsTokens.Violet)
                        }
                    }
                    Hairline()
                    Text(
                        "${days.size} day${if (days.size == 1) "" else "s"} · " +
                            "${LensProjector.Lens.entries[lens].question}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        // -- headline numbers for the selected window ------------------------
        item {
            Reveal(0) {
                GlassCard(Modifier.fillMaxWidth(), interactive = false) {
                    CardBody {
                        val known = days.sumOf { it.knownMin }
                        val unknown = days.sumOf { it.unknownMin }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            StatTile(
                                "Accounted", TimeUtils.formatDurationMin(known),
                                Modifier.weight(1f),
                                caption = percentOf(known, known + unknown)
                            )
                            StatTile(
                                "Unclassified", TimeUtils.formatDurationMin(unknown),
                                Modifier.weight(1f), accent = DsTokens.InkMuted,
                                caption = "honestly reported"
                            )
                            StatTile(
                                "Predictability",
                                StatisticsEngine.predictability(days)?.let { "${(it * 100).roundToInt()}%" } ?: "—",
                                Modifier.weight(1f), accent = DsTokens.Violet,
                                caption = "temporal entropy"
                            )
                        }
                    }
                }
            }
        }

        item { SectionTitle(view.label, "${range.label} · ${LensProjector.Lens.entries[lens].label}") }

        item {
            Reveal(1) {
                GlassCard(Modifier.fillMaxWidth().animateContentSize(), interactive = false) {
                    CardBody(spacing = DsTokens.GapM) {
                        when (view) {
                            LabView.TIMELINE -> TimelineView(days, lens)
                            LabView.RADIAL -> RadialView(days, snapshot.nowMin)
                            LabView.HEATMAP -> HeatmapView(days)
                            LabView.CALENDAR -> CalendarView(days)
                            LabView.DISTRIBUTION -> DistributionView(days, focusActivity) { focusActivity = it }
                            LabView.STATISTICS -> StatisticsView(days, focusActivity) { focusActivity = it }
                            LabView.COMPARISON -> ComparisonView(days)
                            LabView.LANDSCAPE -> LandscapeView(days, use3D) { use3D = it }
                        }
                    }
                }
            }
        }

        // -- lens breakdown, always visible ----------------------------------
        item { SectionTitle("Breakdown") }
        item {
            Reveal(2) {
                GlassCard(Modifier.fillMaxWidth(), interactive = false) {
                    CardBody(spacing = DsTokens.GapM) {
                        when (lens) {
                            0 -> {
                                val slices = LensProjector.activityLens(days)
                                val total = slices.sumOf { it.minutes }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    DonutChart(
                                        slices.map { Slice(it.label, it.minutes, DsTokens.colorFor(it.activity)) },
                                        Modifier.size(128.dp), ringWidth = 22.dp
                                    ) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text(
                                                "${(total / 60f / days.size).roundToInt()}h",
                                                style = MaterialTheme.typography.titleMedium
                                            )
                                            Text("per day", style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                    Spacer(Modifier.width(DsTokens.GapM))
                                    ActivityLegend(
                                        slices.take(7).map {
                                            it.activity to "${TimeUtils.formatDurationMin(it.minutes / days.size)}/day"
                                        },
                                        Modifier.weight(1f)
                                    )
                                }
                            }
                            1 -> {
                                val slices = LensProjector.locationLens(days)
                                val total = slices.sumOf { it.minutes }.coerceAtLeast(1)
                                slices.take(8).forEach { slice ->
                                    Column {
                                        Row {
                                            Text(
                                                "${slice.location.icon}  ${slice.label}",
                                                style = MaterialTheme.typography.titleSmall,
                                                modifier = Modifier.weight(1f)
                                            )
                                            Text(
                                                "${TimeUtils.formatDurationMin(slice.minutes / days.size)}/day · ${percentOf(slice.minutes, total)}",
                                                style = MaterialTheme.typography.labelMedium
                                            )
                                        }
                                        Spacer(Modifier.height(5.dp))
                                        Meter(
                                            slice.minutes.toFloat() / total,
                                            color = DsTokens.colorFor(slice.location)
                                        )
                                    }
                                }
                            }
                            else -> {
                                LensProjector.activityAtLocation(days).take(6).forEach { group ->
                                    Column {
                                        Row {
                                            Text(
                                                "${group.location.icon}  ${group.label}",
                                                style = MaterialTheme.typography.titleSmall,
                                                modifier = Modifier.weight(1f)
                                            )
                                            Text(
                                                TimeUtils.formatDurationMin(group.totalMinutes),
                                                style = MaterialTheme.typography.labelMedium.copy(color = DsTokens.Gold)
                                            )
                                        }
                                        Spacer(Modifier.height(6.dp))
                                        ActivityLegend(
                                            group.activities.take(6).map {
                                                it.activity to "${TimeUtils.formatDurationMin(it.minutes)} · ${percentOf(it.minutes, group.totalMinutes)}"
                                            }
                                        )
                                        Spacer(Modifier.height(8.dp))
                                    }
                                }
                                Text(
                                    "Presence and activity are shown side by side here and nowhere else. A location total and an activity total are never added together.",
                                    style = MaterialTheme.typography.labelSmall.copy(color = DsTokens.InkFaint)
                                )
                            }
                        }
                    }
                }
            }
        }

        item {
            Reveal(3) {
                GlassCard(Modifier.fillMaxWidth(), tint = DsTokens.Gold, interactive = false) {
                    CardBody {
                        Text("Presence is not activity", style = MaterialTheme.typography.titleMedium)
                        val pv = LensProjector.presenceVsActivity(
                            days,
                            com.dhinasuthra.app.intelligence.LocationType.OFFICE,
                            ActivityType.WORK
                        )
                        if (pv.presenceMinutes == 0) {
                            Text(
                                "No office presence in this window.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        } else {
                            Text(
                                "${TimeUtils.formatDurationMin(pv.presenceMinutes)} present at ${pv.placeName ?: "the office"}, " +
                                    "of which ${TimeUtils.formatDurationMin(pv.activityMinutes)} classified as work.",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Meter(
                                pv.activityMinutes.toFloat() / pv.presenceMinutes.coerceAtLeast(1),
                                color = DsTokens.Gold
                            )
                            Text(
                                "The remainder is meals, breaks, meetings and time DhinaSuthra will not pretend to know about.",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Views
// ---------------------------------------------------------------------------

@Composable
private fun TimelineView(days: List<DayReconstruction>, lens: Int) {
    Text(
        if (days.size == 1) "The day, minute by minute" else "One ribbon per day, most recent last",
        style = MaterialTheme.typography.bodySmall
    )
    days.takeLast(14).forEach { day ->
        Column {
            Text(
                TimeUtils.localDate(day.epochDay).format(DateTimeFormatter.ofPattern("EEE d MMM")),
                style = MaterialTheme.typography.labelSmall
            )
            Spacer(Modifier.height(4.dp))
            DayRibbon(day = day, height = if (days.size == 1) 74.dp else 26.dp, showHourAxis = days.size == 1)
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun RadialView(days: List<DayReconstruction>, nowMin: Int) {
    val day = days.last()
    RadialDayClock(
        day = day,
        modifier = Modifier.fillMaxWidth().height(320.dp),
        nowMin = if (day.epochDay == TimeUtils.epochDay()) nowMin else null
    )
    Text(
        "Midnight at the top, noon at the bottom. Spin the dial to read any minute of ${
            TimeUtils.localDate(day.epochDay).format(DateTimeFormatter.ofPattern("EEEE d MMMM"))
        }.",
        style = MaterialTheme.typography.bodySmall
    )
}

@Composable
private fun HeatmapView(days: List<DayReconstruction>) {
    val shown = days.takeLast(31)
    val cells = remember(shown) { StatisticsEngine.heatmap(shown) }
    TemporalHeatmap(
        cells = cells,
        rowLabels = shown.map {
            TimeUtils.localDate(it.epochDay).format(DateTimeFormatter.ofPattern("EEE d"))
        },
        cellHeight = if (shown.size > 14) 14.dp else 20.dp
    )
    Spacer(Modifier.height(8.dp))
    IntensityScale("less classified", "full hour", DsTokens.Cyan)
    Text(
        "Each cell is one hour, coloured by the activity that dominated it and shaded by how much of the hour is accounted for.",
        style = MaterialTheme.typography.bodySmall
    )
}

@Composable
private fun CalendarView(days: List<DayReconstruction>) {
    val fingerprints = remember(days) {
        days.map { it.epochDay to StatisticsEngine.fingerprint(it, slotMinutes = 30) }
    }
    if (days.size > 40) {
        YearField(
            values = StatisticsEngine.calendarValues(
                days,
                selector = { it.knownMin.toFloat() },
                formatter = { TimeUtils.formatDurationMin(it.roundToInt()) }
            ),
            color = DsTokens.Gold
        )
        Text(
            "A year of days: brighter squares are days DhinaSuthra could reconstruct more fully.",
            style = MaterialTheme.typography.bodySmall
        )
    } else {
        CalendarFingerprints(fingerprints)
        Text(
            "Each cell is a whole day compressed into a strip — its time fingerprint.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun DistributionView(
    days: List<DayReconstruction>,
    focus: ActivityType,
    onFocus: (ActivityType) -> Unit
) {
    ActivityPicker(days, focus, onFocus)
    val starts = remember(days, focus) {
        PatternEngine.observations(days).filter { it.activity == focus }.map { it.startMin }
    }
    val durations = remember(days, focus) {
        PatternEngine.observations(days).filter { it.activity == focus }.map { it.totalMin }
    }
    if (starts.size < 3) {
        Text(
            "Not enough observations of ${focus.label.lowercase()} in this window to draw a distribution.",
            style = MaterialTheme.typography.bodySmall
        )
        return
    }
    Text("Start-time distribution", style = MaterialTheme.typography.titleSmall)
    Histogram(
        StatisticsEngine.histogram(starts, binMinutes = 20),
        color = DsTokens.colorFor(focus)
    )
    Spacer(Modifier.height(6.dp))
    Text("Duration distribution", style = MaterialTheme.typography.titleSmall)
    Histogram(
        StatisticsEngine.histogram(durations, binMinutes = 15),
        color = DsTokens.Cyan
    )
    Spacer(Modifier.height(6.dp))
    Text("Start time against duration", style = MaterialTheme.typography.titleSmall)
    ScatterPlot(
        points = starts.indices.map { starts[it].toFloat() to durations.getOrElse(it) { 0 }.toFloat() },
        color = DsTokens.colorFor(focus)
    )
    Text(
        "Does starting later make it shorter? The scatter answers that for you specifically — there is no population to compare against.",
        style = MaterialTheme.typography.bodySmall
    )
}

@Composable
private fun StatisticsView(
    days: List<DayReconstruction>,
    focus: ActivityType,
    onFocus: (ActivityType) -> Unit
) {
    ActivityPicker(days, focus, onFocus)
    val obs = remember(days, focus) {
        PatternEngine.observations(days).filter { it.activity == focus }
    }
    val startBox = remember(obs) { StatisticsEngine.box(obs.map { it.startMin }) }
    val durationBox = remember(obs) { StatisticsEngine.box(obs.map { it.totalMin }) }

    if (startBox == null) {
        Text("No observations of ${focus.label.lowercase()} in this window.", style = MaterialTheme.typography.bodySmall)
        return
    }

    Text("Start time", style = MaterialTheme.typography.titleSmall)
    BoxPlot(
        stats = startBox,
        color = DsTokens.colorFor(focus),
        formatter = { TimeUtils.formatMinuteOfDay(PatternEngine.denormalise(it)) }
    )
    BandStrip(
        p10 = PatternEngine.denormalise(startBox.min),
        p25 = PatternEngine.denormalise(startBox.p25),
        median = PatternEngine.denormalise(startBox.median),
        p75 = PatternEngine.denormalise(startBox.p75),
        p90 = PatternEngine.denormalise(startBox.max),
        color = DsTokens.colorFor(focus)
    )
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatTile("Median", TimeUtils.formatMinuteOfDay(PatternEngine.denormalise(startBox.median)), Modifier.weight(1f))
        StatTile("IQR", "${startBox.iqr}m", Modifier.weight(1f), accent = DsTokens.Cyan)
        StatTile("SD", "${startBox.standardDeviation.roundToInt()}m", Modifier.weight(1f), accent = DsTokens.Violet)
    }
    Spacer(Modifier.height(6.dp))
    if (durationBox != null) {
        Text("Duration", style = MaterialTheme.typography.titleSmall)
        BoxPlot(
            stats = durationBox,
            color = DsTokens.Cyan,
            formatter = { TimeUtils.formatDurationMin(it) }
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile("Median", TimeUtils.formatDurationMin(durationBox.median), Modifier.weight(1f))
            StatTile("Range", TimeUtils.formatDurationMin(durationBox.range), Modifier.weight(1f), accent = DsTokens.Gold)
            StatTile("N", "${durationBox.count}", Modifier.weight(1f), accent = DsTokens.Green)
        }
    }
    Hairline()
    Text("Percentiles", style = MaterialTheme.typography.titleSmall)
    StatisticsEngine.percentileBands(obs.map { it.startMin }).forEach { (label, value) ->
        Row {
            Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            Text(
                TimeUtils.formatMinuteOfDay(PatternEngine.denormalise(value)),
                style = MaterialTheme.typography.labelMedium
            )
        }
    }
    Text(
        "Outliers are excluded from the typical value but still drawn — a 3am night is real, it just should not move your normal bedtime.",
        style = MaterialTheme.typography.bodySmall
    )
}

@Composable
private fun ComparisonView(days: List<DayReconstruction>) {
    val columns = remember(days) {
        days.takeLast(14).map { day ->
            LensProjector.activityLens(day)
                .filter { it.activity != ActivityType.UNKNOWN }
                .map { DsTokens.colorFor(it.activity) to it.minutes }
        }
    }
    val labels = days.takeLast(14).map {
        TimeUtils.localDate(it.epochDay).format(DateTimeFormatter.ofPattern("d"))
    }
    Text("Composition of each day", style = MaterialTheme.typography.titleSmall)
    StackedBars(columns = columns, labels = labels)

    Spacer(Modifier.height(10.dp))
    Text("Reconstruction coverage", style = MaterialTheme.typography.titleSmall)
    BarChart(
        bars = days.takeLast(14).map { day ->
            Bar(
                label = TimeUtils.localDate(day.epochDay).format(DateTimeFormatter.ofPattern("d")),
                value = day.coverageFraction * 100,
                max = 100f,
                color = DsTokens.rhythmColor((day.coverageFraction * 100).roundToInt()),
                caption = "${(day.coverageFraction * 100).roundToInt()}%"
            )
        }
    )

    val similarities = remember(days) {
        val sample = days.takeLast(7)
        sample.map { a -> sample.map { b -> StatisticsEngine.similarity(a, b) } }
    }
    if (similarities.size >= 3) {
        Spacer(Modifier.height(10.dp))
        Text("How alike were your days?", style = MaterialTheme.typography.titleSmall)
        com.dhinasuthra.app.ui.viz.SimilarityMatrix(
            labels = days.takeLast(7).map {
                TimeUtils.localDate(it.epochDay).dayOfWeek.name.take(1)
            },
            values = similarities
        )
        Text(
            "Each cell is the share of minutes on which two days were doing the same thing.",
            style = MaterialTheme.typography.bodySmall
        )
    }

    val typical = remember(days) { StatisticsEngine.mostTypicalDay(days) }
    if (typical != null) {
        Spacer(Modifier.height(8.dp))
        Text(
            "Your most representative day in this window: ${
                TimeUtils.localDate(typical.epochDay).format(DateTimeFormatter.ofPattern("EEEE d MMMM"))
            }.",
            style = MaterialTheme.typography.bodySmall
        )
        DayRibbon(typical, height = 30.dp, showHourAxis = false)
    }
}

@Composable
private fun LandscapeView(days: List<DayReconstruction>, use3D: Boolean, onToggle: (Boolean) -> Unit) {
    val shown = days.takeLast(45)
    val cells = remember(shown) {
        StatisticsEngine.heatmap(shown).map {
            LandscapeCell(
                dayIndex = it.row,
                hour = it.hour,
                height = (it.minutes / 60f).coerceIn(0f, 1f),
                activity = it.activity
            )
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DsChip("3D landscape", use3D, { onToggle(true) }, accent = DsTokens.Violet)
        DsChip("2D fallback", !use3D, { onToggle(false) }, accent = DsTokens.Cyan)
    }
    if (use3D) {
        TimeLandscape3D(cells = cells, dayCount = shown.size, height = 300.dp)
    } else {
        TimeLandscape2D(cells = cells, dayCount = shown.size)
    }
    Text(
        "Days run across, hours run back, and height is how much of that hour DhinaSuthra could account for. " +
            "The ridge you can see in the mornings is your commute.",
        style = MaterialTheme.typography.bodySmall
    )
}

@Composable
private fun ActivityPicker(
    days: List<DayReconstruction>,
    focus: ActivityType,
    onFocus: (ActivityType) -> Unit
) {
    val available = remember(days) {
        LensProjector.activityLens(days)
            .filter { it.activity != ActivityType.UNKNOWN }
            .map { it.activity }
    }
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        available.forEach {
            DsChip(it.label, it == focus, { onFocus(it) }, accent = DsTokens.colorFor(it))
        }
    }
    Spacer(Modifier.height(4.dp))
}
