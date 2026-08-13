package com.dhinasuthra.app.ui.insights

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dhinasuthra.app.core.TimeUtils
import com.dhinasuthra.app.core.model.DayType
import com.dhinasuthra.app.intelligence.ActivityBand
import com.dhinasuthra.app.intelligence.ActivityType
import com.dhinasuthra.app.intelligence.Insight
import com.dhinasuthra.app.intelligence.PatternEngine
import com.dhinasuthra.app.intelligence.PatternLifecycle
import com.dhinasuthra.app.intelligence.StatisticsEngine
import com.dhinasuthra.app.ui.foundation.CardBody
import com.dhinasuthra.app.ui.foundation.DsScreen
import com.dhinasuthra.app.ui.foundation.EmptyState
import com.dhinasuthra.app.ui.foundation.GlassCard
import com.dhinasuthra.app.ui.foundation.Hairline
import com.dhinasuthra.app.ui.foundation.Reveal
import com.dhinasuthra.app.ui.foundation.SectionTitle
import com.dhinasuthra.app.ui.foundation.StatTile
import com.dhinasuthra.app.ui.state.rememberTimeViewModel
import com.dhinasuthra.app.ui.theme.DsTokens
import com.dhinasuthra.app.ui.viz.BandStrip
import com.dhinasuthra.app.ui.viz.Meter
import com.dhinasuthra.app.ui.viz.TrendLine
import kotlin.math.roundToInt

/**
 * Insights = meaning (spec §24, §51).
 *
 * Deliberately not a dashboard. Sentences first, one chart per idea, and nothing
 * printed that the app would not be willing to say out loud.
 */
@Composable
fun InsightsScreen() {
    val vm = rememberTimeViewModel()
    val state by vm.state.collectAsState()
    val snapshot = state.snapshot

    DsScreen(
        title = "Insights",
        subtitle = "What your own data is saying"
    ) {
        if (snapshot == null) {
            item { EmptyState("Reading your history", "One moment — reconstructing your days.") }
            return@DsScreen
        }

        val dayType = TimeUtils.dayType(snapshot.today.epochDay)
        val comparable = snapshot.history.filter { TimeUtils.dayType(it.epochDay) == dayType }

        // Rhythm trend
        item {
            Reveal(0) {
                GlassCard(Modifier.fillMaxWidth(), interactive = false) {
                    CardBody {
                        Text("Your rhythm over time", style = MaterialTheme.typography.titleMedium)
                        val scores = comparable.takeLast(21).mapNotNull { day ->
                            com.dhinasuthra.app.intelligence.RhythmScorer
                                .score(day, snapshot.patterns, 1440)?.value?.toFloat()
                        }
                        if (scores.size < 3) {
                            Text(
                                "A trend needs a few comparable days. DhinaSuthra will draw it once it has them.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        } else {
                            val trend = StatisticsEngine.trend(scores)
                            TrendLine(scores, trend = trend, color = DsTokens.Cyan)
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                StatTile(
                                    "Average", "${scores.average().roundToInt()}",
                                    Modifier.weight(1f), caption = "${scores.size} ${dayType.name.lowercase()}s"
                                )
                                StatTile(
                                    "Direction",
                                    trend?.let {
                                        val change = it.changeOver(scores.size).roundToInt()
                                        if (change > 0) "+$change" else "$change"
                                    } ?: "—",
                                    Modifier.weight(1f),
                                    accent = DsTokens.Gold,
                                    caption = "over the window"
                                )
                                StatTile(
                                    "Predictability",
                                    StatisticsEngine.predictability(comparable)
                                        ?.let { "${(it * 100).roundToInt()}%" } ?: "—",
                                    Modifier.weight(1f),
                                    accent = DsTokens.Violet,
                                    caption = "entropy based"
                                )
                            }
                        }
                    }
                }
            }
        }

        if (snapshot.insights.isNotEmpty()) {
            item { SectionTitle("What matters right now") }
            items(snapshot.insights.size) { index ->
                Reveal(index) { InsightCard(snapshot.insights[index]) }
            }
        } else {
            item {
                EmptyState(
                    "Nothing worth saying today",
                    "DhinaSuthra would rather stay quiet than invent an observation. Insights appear when a measurement genuinely moved."
                )
            }
        }

        item { SectionTitle("Your patterns", "${snapshot.patterns.forDayType(dayType).size} learned") }
        val bands = snapshot.patterns.forDayType(dayType)
            .filter { it.activity != ActivityType.UNKNOWN }
            .sortedByDescending { it.confidence }
        if (bands.isEmpty()) {
            item {
                EmptyState(
                    "No established patterns yet",
                    "A pattern needs at least three observations before DhinaSuthra will use it for anything."
                )
            }
        } else {
            items(bands.size) { index ->
                Reveal(index.coerceAtMost(5)) { PatternCard(bands[index], comparable, dayType) }
            }
        }

        item { SectionTitle("Weekday against weekend") }
        item {
            Reveal(0) {
                GlassCard(Modifier.fillMaxWidth(), interactive = false) {
                    CardBody {
                        val rows = listOf(
                            ActivityType.SLEEP, ActivityType.WORK,
                            ActivityType.LUNCH, ActivityType.PERSONAL
                        ).mapNotNull { StatisticsEngine.compareDayTypes(snapshot.history, it) }
                        if (rows.isEmpty()) {
                            Text(
                                "Not enough of both kinds of day to compare yet.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        } else {
                            rows.forEach { row ->
                                Column {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            Modifier.size(8.dp)
                                                .background(DsTokens.colorFor(row.activity), CircleShape)
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            row.activity.label,
                                            style = MaterialTheme.typography.titleSmall,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Text(
                                            if (row.deltaMin >= 0)
                                                "+${TimeUtils.formatDurationMin(row.deltaMin)} at weekends"
                                            else
                                                "−${TimeUtils.formatDurationMin(-row.deltaMin)} at weekends",
                                            style = MaterialTheme.typography.labelMedium.copy(color = DsTokens.Gold)
                                        )
                                    }
                                    Spacer(Modifier.height(6.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Column(Modifier.weight(1f)) {
                                            Text("Weekday", style = MaterialTheme.typography.labelSmall)
                                            Meter(
                                                fraction = row.weekdayMedian / 1440f,
                                                color = DsTokens.Cyan
                                            )
                                            Text(
                                                TimeUtils.formatDurationMin(row.weekdayMedian),
                                                style = MaterialTheme.typography.labelSmall
                                            )
                                        }
                                        Column(Modifier.weight(1f)) {
                                            Text("Weekend", style = MaterialTheme.typography.labelSmall)
                                            Meter(
                                                fraction = row.weekendMedian / 1440f,
                                                color = DsTokens.Violet
                                            )
                                            Text(
                                                TimeUtils.formatDurationMin(row.weekendMedian),
                                                style = MaterialTheme.typography.labelSmall
                                            )
                                        }
                                    }
                                    Spacer(Modifier.height(8.dp))
                                }
                            }
                        }
                    }
                }
            }
        }

        item {
            val unusual = StatisticsEngine.unusualDays(snapshot.history.takeLast(30))
            if (unusual.isNotEmpty()) {
                GlassCard(Modifier.fillMaxWidth(), tint = DsTokens.Rose, interactive = false) {
                    CardBody {
                        Text("Days unlike your others", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Measured by how many minutes of the day matched your other comparable days — not by whether the day was good.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        unusual.take(4).forEach { (epochDay, similarity) ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    TimeUtils.localDate(epochDay)
                                        .format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM")),
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    "${(similarity * 100).roundToInt()}% like your usual",
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        }
                    }
                }
            }
        }

        item {
            GlassCard(Modifier.fillMaxWidth(), tint = DsTokens.Gold, interactive = false) {
                CardBody {
                    Text("Want everything?", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Time Lab holds the full instrument: heatmaps, distributions, box plots, radial clocks, day similarity and a three-dimensional map of your months.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@Composable
private fun InsightCard(insight: Insight) {
    var expanded by remember { mutableStateOf(false) }
    GlassCard(
        Modifier.fillMaxWidth().animateContentSize(),
        tint = DsTokens.Violet,
        onClick = { expanded = !expanded }
    ) {
        CardBody {
            Text(insight.kind.label.uppercase(), style = MaterialTheme.typography.labelSmall)
            Text(insight.headline, style = MaterialTheme.typography.titleMedium)
            Text(insight.detail, style = MaterialTheme.typography.bodySmall)
            if (expanded) {
                Hairline()
                insight.evidence.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

@Composable
private fun PatternCard(
    band: ActivityBand,
    days: List<com.dhinasuthra.app.intelligence.DayReconstruction>,
    dayType: DayType
) {
    var expanded by remember { mutableStateOf(false) }
    val starts = remember(band, days) {
        PatternEngine.observations(days)
            .filter { it.activity == band.activity && it.dayType == dayType }
            .map { it.startMin }
    }

    GlassCard(
        Modifier.fillMaxWidth().animateContentSize(),
        tint = DsTokens.colorFor(band.activity),
        onClick = { expanded = !expanded }
    ) {
        CardBody {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(band.activity.icon, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f)) {
                    Text(band.activity.label, style = MaterialTheme.typography.titleSmall)
                    Text(
                        "usually ${TimeUtils.formatMinuteOfDay(PatternEngine.denormalise(band.typicalStartMin))}" +
                            (band.typicalDurationMin?.let { " · ~${TimeUtils.formatDurationMin(it)}" } ?: ""),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                LifecyclePill(band)
            }
            BandStrip(
                p10 = PatternEngine.denormalise(band.p10),
                p25 = PatternEngine.denormalise(band.p25),
                median = PatternEngine.denormalise(band.typicalStartMin),
                p75 = PatternEngine.denormalise(band.p75),
                p90 = PatternEngine.denormalise(band.p90),
                color = DsTokens.colorFor(band.activity)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    "${band.observations} observation${if (band.observations == 1) "" else "s"}",
                    style = MaterialTheme.typography.labelSmall
                )
                band.consistency?.let {
                    Text("$it% consistent", style = MaterialTheme.typography.labelSmall)
                }
                Text("±${band.iqr / 2}m spread", style = MaterialTheme.typography.labelSmall)
            }
            if (expanded) {
                Hairline()
                val box = StatisticsEngine.box(starts)
                if (box != null) {
                    Text("Distribution of start times", style = MaterialTheme.typography.labelSmall)
                    com.dhinasuthra.app.ui.viz.BoxPlot(
                        stats = box,
                        color = DsTokens.colorFor(band.activity),
                        formatter = { TimeUtils.formatMinuteOfDay(PatternEngine.denormalise(it)) }
                    )
                    Text(
                        "Mean ${TimeUtils.formatMinuteOfDay(PatternEngine.denormalise(box.mean.roundToInt()))} · " +
                            "SD ${box.standardDeviation.roundToInt()}m · " +
                            "${box.outliers.size} outlier${if (box.outliers.size == 1) "" else "s"} excluded from the typical value",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Text(band.lifecycle.description, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun LifecyclePill(band: ActivityBand) {
    val color = DsTokens.colorFor(band.lifecycle)
    Box(
        Modifier
            .background(color.copy(alpha = 0.15f), androidx.compose.foundation.shape.RoundedCornerShape(9.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            if (band.isPrior) "Yours" else band.lifecycle.label,
            style = MaterialTheme.typography.labelSmall.copy(color = color)
        )
    }
}
