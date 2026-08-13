package com.dhinasuthra.app.ui.today

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.dhinasuthra.app.DhinaSuthraApp
import com.dhinasuthra.app.core.TimeUtils
import com.dhinasuthra.app.intelligence.ActivityType
import com.dhinasuthra.app.intelligence.DayReconstruction
import com.dhinasuthra.app.intelligence.Insight
import com.dhinasuthra.app.intelligence.LensProjector
import com.dhinasuthra.app.intelligence.PatternEngine
import com.dhinasuthra.app.intelligence.PatternIndex
import com.dhinasuthra.app.intelligence.PatternLifecycle
import com.dhinasuthra.app.intelligence.RhythmScorer
import com.dhinasuthra.app.intelligence.RuleBook
import com.dhinasuthra.app.intelligence.TimeEpisode
import com.dhinasuthra.app.intelligence.TimetableAdherence
import com.dhinasuthra.app.ui.foundation.CardBody
import com.dhinasuthra.app.ui.foundation.ConfidenceTag
import com.dhinasuthra.app.ui.foundation.DsChip
import com.dhinasuthra.app.ui.foundation.DsSafeArea
import com.dhinasuthra.app.ui.foundation.GlassCard
import com.dhinasuthra.app.ui.foundation.Hairline
import com.dhinasuthra.app.ui.foundation.Reveal
import com.dhinasuthra.app.ui.foundation.RuleIdRow
import com.dhinasuthra.app.ui.foundation.SectionTitle
import com.dhinasuthra.app.ui.foundation.ShimmerBox
import com.dhinasuthra.app.ui.foundation.StatTile
import com.dhinasuthra.app.ui.state.rememberTimeViewModel
import com.dhinasuthra.app.ui.theme.DsTokens
import com.dhinasuthra.app.ui.viz.ActivityLegend
import com.dhinasuthra.app.ui.viz.DayRibbon
import com.dhinasuthra.app.ui.viz.DonutChart
import com.dhinasuthra.app.ui.viz.Meter
import com.dhinasuthra.app.ui.viz.ScoreRing
import com.dhinasuthra.app.ui.viz.Slice
import com.dhinasuthra.app.ui.viz.percentOf
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * Today = human (spec §49).
 *
 * Four questions, in order: how is my day going, what did DhinaSuthra notice,
 * what happens next, and how close is this to my normal? Nothing on this screen
 * is a dashboard — the laboratory is two tabs away.
 */
@Composable
fun TodayScreen() {
    val context = LocalContext.current
    val vm = rememberTimeViewModel()
    val state by vm.state.collectAsState()
    val snapshot = state.snapshot
    val userName = remember { DhinaSuthraApp.get(context).container.settings.userName }
    var selectedEpisode by remember { mutableStateOf<Int?>(null) }
    var lens by remember { mutableIntStateOf(0) }

    val today = snapshot?.today
    val dayName = TimeUtils.localDate(today?.epochDay ?: TimeUtils.epochDay())
        .dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())

    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(DsSafeArea.topAndSides)
    ) {
        BrandRow()
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = DsTokens.ScreenPadding,
                end = DsTokens.ScreenPadding,
                bottom = DsTokens.BottomInset
            ),
            verticalArrangement = Arrangement.spacedBy(DsTokens.GapM)
        ) {
            item { Greeting(userName, snapshot?.rhythm, today, dayName) }

            if (snapshot == null) {
                item { LoadingHero() }
                return@LazyColumn
            }

            item {
                Reveal(0) {
                    HeroCard(
                        day = snapshot.today,
                        rhythm = snapshot.rhythm,
                        nowMin = snapshot.nowMin,
                        selected = selectedEpisode,
                        onSelect = { selectedEpisode = it }
                    )
                }
            }

            item { Reveal(1) { NowAndNext(snapshot.today, snapshot.patterns, snapshot.nowMin) } }

            if (snapshot.insights.isNotEmpty()) {
                item { SectionTitle("What DhinaSuthra noticed") }
                items(snapshot.insights, key = { it.id }) { insight ->
                    Reveal(2) { InsightCard(insight) }
                }
            }

            item { SectionTitle("Where the day went") }
            item {
                Reveal(3) {
                    LensCard(
                        day = snapshot.today,
                        lens = lens,
                        onLens = { lens = it }
                    )
                }
            }

            snapshot.activeReport?.let { report ->
                item { SectionTitle("Your routine today") }
                item { Reveal(4) { AdherenceCard(report) } }
            }

            if (snapshot.observedDays < 7) {
                item { Reveal(5) { LearningCard(snapshot.observedDays, snapshot.patterns) } }
            }

            item { Reveal(6) { IntelligenceFooter(snapshot.today) } }
        }
    }
}

@Composable
private fun BrandRow() {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = DsTokens.ScreenPadding, vertical = DsTokens.GapM),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            buildAnnotatedString {
                withStyle(MaterialTheme.typography.titleMedium.toSpanStyle().copy(color = DsTokens.Ink)) {
                    append("Dhina")
                }
                withStyle(MaterialTheme.typography.titleMedium.toSpanStyle().copy(color = DsTokens.Gold)) {
                    append("Suthra")
                }
            },
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f)
        )
        Text(
            TimeUtils.localDate(TimeUtils.epochDay())
                .format(DateTimeFormatter.ofPattern("EEE d MMM")),
            style = MaterialTheme.typography.labelMedium
        )
    }
}

@Composable
private fun Greeting(
    name: String,
    rhythm: RhythmScorer.RhythmScore?,
    day: DayReconstruction?,
    dayName: String
) {
    val hour = TimeUtils.minuteOfDay(java.time.Instant.now()) / 60
    val base = when (hour) {
        in 5..11 -> "Good morning"
        in 12..16 -> "Good afternoon"
        in 17..20 -> "Good evening"
        else -> "Good night"
    }
    Column(Modifier.padding(top = 4.dp, bottom = 2.dp)) {
        Text(
            if (name.isBlank()) base else "$base, $name",
            style = MaterialTheme.typography.headlineMedium
        )
        AnimatedContent(
            targetState = day?.let {
                com.dhinasuthra.app.intelligence.NarrativeEngine.todayLine(rhythm, it, dayName)
            } ?: "Reading your day…",
            transitionSpec = { fadeIn(tween(260)) togetherWith fadeOut(tween(140)) },
            label = "todayLine"
        ) { line ->
            Text(line, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun LoadingHero() {
    Column(verticalArrangement = Arrangement.spacedBy(DsTokens.GapM)) {
        ShimmerBox(Modifier.fillMaxWidth().height(230.dp), corner = DsTokens.CardCorner)
        ShimmerBox(Modifier.fillMaxWidth().height(96.dp), corner = DsTokens.CardCorner)
    }
}

/** The hero: score, sentence, and the whole day as one scrubbable ribbon. */
@Composable
private fun HeroCard(
    day: DayReconstruction,
    rhythm: RhythmScorer.RhythmScore?,
    nowMin: Int,
    selected: Int?,
    onSelect: (Int?) -> Unit
) {
    GlassCard(Modifier.fillMaxWidth(), tint = DsTokens.Gold, interactive = false) {
        CardBody(spacing = DsTokens.GapM) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ScoreRing(rhythm?.value, Modifier.size(132.dp))
                Spacer(Modifier.width(DsTokens.GapM))
                Column(Modifier.weight(1f)) {
                    Text(
                        rhythm?.band ?: "Still learning your shape",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        rhythm?.explanation
                            ?: "A rhythm score appears once a few of your patterns are established.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (rhythm != null) {
                        Spacer(Modifier.height(8.dp))
                        RuleIdRow(rhythm.ruleIds)
                    }
                }
            }

            Hairline()

            Text("Today's thread", style = MaterialTheme.typography.labelSmall)
            DayRibbon(
                day = day,
                nowMin = nowMin,
                selectedIndex = selected,
                onSelect = onSelect
            )

            AnimatedContent(
                targetState = selected?.let { day.episodes.getOrNull(it) },
                transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(120)) },
                label = "episodeDetail",
                modifier = Modifier.animateContentSize()
            ) { episode ->
                if (episode == null) {
                    Text(
                        "Tap or drag the thread to inspect any moment of your day.",
                        style = MaterialTheme.typography.labelSmall
                    )
                } else {
                    EpisodeDetail(episode)
                }
            }
        }
    }
}

@Composable
fun EpisodeDetail(episode: TimeEpisode, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .background(
                Color.White.copy(alpha = 0.04f),
                androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
            )
            .padding(13.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(10.dp).background(DsTokens.colorFor(episode.activity), CircleShape)
            )
            Spacer(Modifier.width(9.dp))
            Text(
                "${episode.activity.icon}  ${episode.activity.label}",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f)
            )
            Text(episode.durationLabel(), style = MaterialTheme.typography.labelMedium)
        }
        Text(
            "${episode.rangeLabel()}  ·  ${episode.placeName ?: episode.location.label}  ·  ${episode.status.label}",
            style = MaterialTheme.typography.labelSmall
        )
        ConfidenceTag(episode.band)
        if (episode.evidence.isNotEmpty()) {
            Hairline()
            Text("WHY DHINASUTHRA THINKS SO", style = MaterialTheme.typography.labelSmall)
            episode.evidence.take(4).forEach { line ->
                Row {
                    Text("· ", style = MaterialTheme.typography.bodySmall)
                    Text(line, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun NowAndNext(day: DayReconstruction, patterns: PatternIndex, nowMin: Int) {
    val current = day.at((nowMin - 1).coerceAtLeast(0))
    val dayType = TimeUtils.dayType(day.epochDay)
    val next = patterns.forDayType(dayType)
        .filter { it.observations >= 4 || it.isPrior }
        .map { it to PatternEngine.denormalise(it.typicalStartMin) }
        .filter { it.second > nowMin }
        .minByOrNull { it.second }

    GlassCard(Modifier.fillMaxWidth(), tint = DsTokens.Cyan, interactive = false) {
        CardBody {
            Row {
                Column(Modifier.weight(1f)) {
                    Text("RIGHT NOW", style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        current?.activity?.label ?: "Unclassified",
                        style = MaterialTheme.typography.titleLarge.copy(
                            color = DsTokens.colorFor(current?.activity ?: ActivityType.UNKNOWN)
                        )
                    )
                    Text(
                        current?.let {
                            "since ${it.startLabel()} · ${TimeUtils.formatDurationMin(nowMin - it.startMin)}"
                        } ?: "no evidence for this moment",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                if (next != null) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text("NEXT IN YOUR RHYTHM", style = MaterialTheme.typography.labelSmall)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            next.first.activity.label,
                            style = MaterialTheme.typography.titleMedium.copy(color = DsTokens.Gold)
                        )
                        Text(
                            "${TimeUtils.formatMinuteOfDay(next.second)} ± ${(next.first.iqr / 2).coerceAtLeast(5)}m",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
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
            RuleIdRow(insight.ruleIds)
            if (expanded && insight.evidence.isNotEmpty()) {
                Hairline()
                insight.evidence.forEach {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    "Every sentence here is produced by the rules above — tap Rule Book in More to read them.",
                    style = MaterialTheme.typography.labelSmall.copy(color = DsTokens.InkFaint)
                )
            }
        }
    }
}

/** The three lenses, never mixed (spec §6). */
@Composable
private fun LensCard(day: DayReconstruction, lens: Int, onLens: (Int) -> Unit) {
    GlassCard(Modifier.fillMaxWidth(), interactive = false) {
        CardBody(spacing = DsTokens.GapM) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LensProjector.Lens.entries.forEachIndexed { i, l ->
                    DsChip(
                        label = if (i == 2) "Activity @ Place" else l.label,
                        selected = lens == i,
                        onClick = { onLens(i) }
                    )
                }
            }
            Text(
                LensProjector.Lens.entries[lens].question,
                style = MaterialTheme.typography.bodySmall
            )

            when (lens) {
                0 -> {
                    val slices = LensProjector.activityLens(day)
                        .map { Slice(it.label, it.minutes, DsTokens.colorFor(it.activity)) }
                    val total = slices.sumOf { it.minutes }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        DonutChart(slices, Modifier.size(126.dp), ringWidth = 20.dp) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(TimeUtils.formatDurationMin(total), style = MaterialTheme.typography.titleMedium)
                                Text("accounted", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        Spacer(Modifier.width(DsTokens.GapM))
                        ActivityLegend(
                            LensProjector.activityLens(day).take(6).map {
                                it.activity to "${TimeUtils.formatDurationMin(it.minutes)} · ${percentOf(it.minutes, total)}"
                            },
                            Modifier.weight(1f)
                        )
                    }
                }
                1 -> {
                    val locations = LensProjector.locationLens(day)
                    val total = locations.sumOf { it.minutes }
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        locations.take(6).forEach { slice ->
                            Column {
                                Row {
                                    Text(
                                        "${slice.location.icon}  ${slice.label}",
                                        style = MaterialTheme.typography.titleSmall,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        "${TimeUtils.formatDurationMin(slice.minutes)} · ${percentOf(slice.minutes, total)}",
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                }
                                Spacer(Modifier.height(5.dp))
                                Meter(
                                    fraction = if (total == 0) 0f else slice.minutes.toFloat() / total,
                                    color = DsTokens.colorFor(slice.location)
                                )
                            }
                        }
                    }
                }
                else -> {
                    Column(verticalArrangement = Arrangement.spacedBy(DsTokens.GapM)) {
                        LensProjector.activityAtLocation(day).take(4).forEach { group ->
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
                                    group.activities.take(5).map {
                                        it.activity to TimeUtils.formatDurationMin(it.minutes)
                                    }
                                )
                            }
                        }
                        Text(
                            "This is the only view that combines the two — everywhere else they stay apart, so no hour is ever counted twice.",
                            style = MaterialTheme.typography.labelSmall.copy(color = DsTokens.InkFaint)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AdherenceCard(report: TimetableAdherence.Report) {
    GlassCard(Modifier.fillMaxWidth(), tint = DsTokens.Green, interactive = false) {
        CardBody {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(report.timetable.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${report.onTimeCount} of ${report.scoredCount} entries within tolerance so far",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Text(
                    report.adherence?.let { "$it%" } ?: "—",
                    style = MaterialTheme.typography.displaySmall.copy(
                        color = DsTokens.rhythmColor(report.adherence ?: 0)
                    )
                )
            }
            Meter(
                fraction = (report.adherence ?: 0) / 100f,
                color = DsTokens.rhythmColor(report.adherence ?: 0)
            )
            RuleIdRow(report.ruleIds)
        }
    }
}

@Composable
private fun LearningCard(observedDays: Int, patterns: PatternIndex) {
    GlassCard(Modifier.fillMaxWidth(), tint = DsTokens.Cyan, interactive = false) {
        CardBody {
            Text("Learning your rhythm", style = MaterialTheme.typography.titleMedium)
            Text(
                "DhinaSuthra is watching how your days actually unfold. Patterns appear as evidence accumulates — nothing is guessed to fill the space.",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(2.dp))
            val tracked = listOf(
                ActivityType.SLEEP, ActivityType.WORK, ActivityType.LUNCH, ActivityType.COMMUTE
            )
            tracked.forEach { activity ->
                val band = patterns.all().firstOrNull { it.activity == activity }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).background(DsTokens.colorFor(activity), CircleShape))
                    Spacer(Modifier.width(9.dp))
                    Text(
                        activity.label,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        (band?.lifecycle ?: PatternLifecycle.LEARNING).label,
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = DsTokens.colorFor(band?.lifecycle ?: PatternLifecycle.LEARNING)
                        )
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "$observedDays day${if (observedDays == 1) "" else "s"} observed so far.",
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@Composable
private fun IntelligenceFooter(day: DayReconstruction) {
    val ruleCount = remember { RuleBook.count }
    GlassCard(Modifier.fillMaxWidth(), interactive = false) {
        CardBody {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile(
                    "Rules applied", "$ruleCount", Modifier.weight(1f),
                    caption = "deterministic, on device"
                )
                StatTile(
                    "Day reconstructed",
                    "${(day.coverageFraction * 100).toInt()}%",
                    Modifier.weight(1f),
                    accent = DsTokens.Green,
                    caption = "${TimeUtils.formatDurationMin(day.unknownMin)} unclassified"
                )
            }
            Text(
                "No accounts, no servers, no model files. Everything above was derived on this phone from your own signals.",
                style = MaterialTheme.typography.labelSmall.copy(color = DsTokens.InkFaint)
            )
        }
    }
}
