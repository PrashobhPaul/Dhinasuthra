package com.dhinasuthra.app.ui.timeline

import androidx.compose.animation.animateContentSize
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dhinasuthra.app.core.TimeUtils
import com.dhinasuthra.app.intelligence.ActivityType
import com.dhinasuthra.app.intelligence.DayReconstruction
import com.dhinasuthra.app.intelligence.EpisodeStatus
import com.dhinasuthra.app.intelligence.LocationType
import com.dhinasuthra.app.intelligence.TimeEpisode
import com.dhinasuthra.app.ui.foundation.CardBody
import com.dhinasuthra.app.ui.foundation.ConfidenceTag
import com.dhinasuthra.app.ui.foundation.DsChip
import com.dhinasuthra.app.ui.foundation.DsSafeArea
import com.dhinasuthra.app.ui.foundation.GlassCard
import com.dhinasuthra.app.ui.foundation.Hairline
import com.dhinasuthra.app.ui.foundation.Reveal
import com.dhinasuthra.app.ui.state.rememberTimeViewModel
import com.dhinasuthra.app.ui.theme.DsTokens
import com.dhinasuthra.app.ui.viz.DayRibbon
import com.dhinasuthra.app.ui.viz.EpisodeSpine
import java.time.format.DateTimeFormatter

/**
 * Timeline = truth (spec §23, §50).
 *
 * The chronological reconstruction of a day, every episode carrying its status
 * (observed, inferred, confirmed, corrected) and the evidence behind it. Long
 * press anything to tell DhinaSuthra it was wrong — and that correction outranks
 * every inference from then on (TML-08).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimelineScreen() {
    val vm = rememberTimeViewModel()
    val state by vm.state.collectAsState()
    var day by remember { mutableLongStateOf(TimeUtils.epochDay()) }
    var reconstruction by remember { mutableStateOf<DayReconstruction?>(null) }
    var correcting by remember { mutableStateOf<TimeEpisode?>(null) }
    var adding by remember { mutableStateOf(false) }
    var expandedIndex by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(day, state.snapshot) {
        reconstruction = vm.dayFor(day)
    }

    val today = TimeUtils.epochDay()

    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(DsSafeArea.topAndSides)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = DsTokens.ScreenPadding, vertical = DsTokens.GapM),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Timeline", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "What actually happened, and why we think so",
                    style = MaterialTheme.typography.labelMedium
                )
            }
            IconButton(onClick = { adding = true }) {
                Icon(Icons.Filled.Add, contentDescription = "Add an episode to this day", tint = DsTokens.Gold)
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = DsTokens.ScreenPadding),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { day -= 1 }) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous day",
                    tint = DsTokens.InkSoft
                )
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    when (day) {
                        today -> "Today"
                        today - 1 -> "Yesterday"
                        else -> TimeUtils.localDate(day).format(DateTimeFormatter.ofPattern("EEEE"))
                    },
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    TimeUtils.localDate(day).format(DateTimeFormatter.ofPattern("d MMMM yyyy")),
                    style = MaterialTheme.typography.labelSmall
                )
            }
            IconButton(onClick = { if (day < today) day += 1 }, enabled = day < today) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next day",
                    tint = if (day < today) DsTokens.InkSoft else DsTokens.InkFaint
                )
            }
        }

        val recon = reconstruction
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = DsTokens.ScreenPadding,
                end = DsTokens.ScreenPadding,
                top = DsTokens.GapM,
                bottom = DsTokens.BottomInset
            ),
            verticalArrangement = Arrangement.spacedBy(DsTokens.GapS)
        ) {
            if (recon == null) {
                item { Text("Reconstructing…", style = MaterialTheme.typography.bodySmall) }
                return@LazyColumn
            }

            item {
                GlassCard(Modifier.fillMaxWidth(), interactive = false) {
                    CardBody {
                        DayRibbon(
                            day = recon,
                            nowMin = if (day == today) state.snapshot?.nowMin else null,
                            onSelect = { idx -> expandedIndex = idx }
                        )
                        Hairline()
                        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            Text(
                                "${(recon.coverageFraction * 100).toInt()}% reconstructed",
                                style = MaterialTheme.typography.labelSmall
                            )
                            Text(
                                "${TimeUtils.formatDurationMin(recon.unknownMin)} unclassified",
                                style = MaterialTheme.typography.labelSmall
                            )
                            Text(
                                "${recon.episodes.size} episodes",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
            }

            if (recon.unknownMin >= 45) {
                item {
                    GlassCard(Modifier.fillMaxWidth(), tint = DsTokens.Gold, interactive = false) {
                        CardBody {
                            Text(
                                "There is ${TimeUtils.formatDurationMin(recon.unknownMin)} of this day DhinaSuthra could not explain.",
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                "It would rather admit that than invent a plausible-looking episode. Long-press any block to tell it what happened.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }

            itemsIndexed(recon.episodes, key = { _, e -> e.id }) { index, episode ->
                Reveal(index.coerceAtMost(6)) {
                    EpisodeRow(
                        episode = episode,
                        isFirst = index == 0,
                        isLast = index == recon.episodes.lastIndex,
                        isNow = day == today && state.snapshot?.nowMin?.let {
                            it >= episode.startMin && it < episode.endMin
                        } == true,
                        expanded = expandedIndex == index,
                        onToggle = { expandedIndex = if (expandedIndex == index) null else index },
                        onCorrect = { correcting = episode }
                    )
                }
            }

            item {
                Text(
                    "Long press anything to correct it. What you tell DhinaSuthra always wins, today and every day after.",
                    style = MaterialTheme.typography.labelSmall.copy(color = DsTokens.InkFaint),
                    modifier = Modifier.padding(top = 10.dp)
                )
            }
        }
    }

    correcting?.let { episode ->
        CorrectionSheet(
            episode = episode,
            onDismiss = { correcting = null },
            onSave = { activity, location ->
                vm.correct(episode, activity, location)
                correcting = null
            }
        )
    }

    if (adding) {
        AddEpisodeSheet(
            epochDay = day,
            onDismiss = { adding = false },
            onSave = { start, end, activity, location ->
                vm.addManualEpisode(day, start, end, activity, location)
                adding = false
            }
        )
    }
}

@Composable
private fun EpisodeRow(
    episode: TimeEpisode,
    isFirst: Boolean,
    isLast: Boolean,
    isNow: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    onCorrect: () -> Unit
) {
    val color = DsTokens.colorFor(episode.activity)
    Row(Modifier.fillMaxWidth().animateContentSize()) {
        Column(Modifier.width(46.dp).padding(top = 15.dp)) {
            Text(episode.startLabel(), style = MaterialTheme.typography.labelMedium)
            Text(episode.durationLabel(), style = MaterialTheme.typography.labelSmall)
        }
        EpisodeSpine(
            episode = episode,
            isFirst = isFirst,
            isLast = isLast,
            isNow = isNow,
            modifier = Modifier.width(26.dp).height(if (expanded) 200.dp else 74.dp)
        )
        GlassCard(
            Modifier.weight(1f).padding(bottom = 6.dp),
            tint = color,
            onClick = onToggle,
            onLongClick = onCorrect
        ) {
            CardBody(padding = 14.dp, spacing = 5.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(episode.activity.icon, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.width(9.dp))
                    Column(Modifier.weight(1f)) {
                        Text(episode.activity.label, style = MaterialTheme.typography.titleSmall)
                        Text(
                            "${episode.placeName ?: episode.location.label} · ${episode.rangeLabel()}",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    StatusPill(episode.status)
                }
                if (expanded) {
                    Hairline()
                    ConfidenceTag(episode.band)
                    if (episode.evidence.isEmpty()) {
                        Text(
                            "Nothing explained this period, so it is left unclassified rather than guessed at.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    } else {
                        episode.evidence.forEach {
                            Text(it, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Text(
                        "Long press to correct",
                        style = MaterialTheme.typography.labelSmall.copy(color = DsTokens.Gold)
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusPill(status: EpisodeStatus) {
    val color = when (status) {
        EpisodeStatus.OBSERVED -> DsTokens.Green
        EpisodeStatus.CONFIRMED -> DsTokens.Cyan
        EpisodeStatus.CORRECTED -> DsTokens.Gold
        EpisodeStatus.INFERRED -> DsTokens.InkMuted
    }
    Box(
        Modifier
            .background(color.copy(alpha = 0.14f), RoundedCornerShape(8.dp))
            .padding(horizontal = 7.dp, vertical = 3.dp)
    ) {
        Text(status.short, style = MaterialTheme.typography.labelSmall.copy(color = color))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CorrectionSheet(
    episode: TimeEpisode,
    onDismiss: () -> Unit,
    onSave: (ActivityType, LocationType) -> Unit
) {
    var activity by remember { mutableStateOf(episode.activity) }
    var location by remember { mutableStateOf(episode.location) }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = DsTokens.Elevated) {
        Column(
            Modifier.padding(horizontal = DsTokens.ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(DsTokens.GapM)
        ) {
            Text("What were you actually doing?", style = MaterialTheme.typography.headlineSmall)
            Text(
                "${episode.rangeLabel()} · ${episode.durationLabel()}. DhinaSuthra will remember this and weigh it when it sees a similar window again.",
                style = MaterialTheme.typography.bodySmall
            )
            SelectorGrid(
                items = ActivityType.entries.filter { it != ActivityType.UNKNOWN },
                selected = activity,
                label = { "${it.icon} ${it.label}" },
                onSelect = { activity = it }
            )
            Hairline()
            Text("Where?", style = MaterialTheme.typography.titleSmall)
            SelectorGrid(
                items = LocationType.entries.toList(),
                selected = location,
                label = { "${it.icon} ${it.label}" },
                onSelect = { location = it }
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Cancel", color = DsTokens.InkMuted) }
                TextButton(onClick = { onSave(activity, location) }) {
                    Text("Save correction", color = DsTokens.Gold)
                }
            }
            Spacer(Modifier.height(DsTokens.GapL))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddEpisodeSheet(
    epochDay: Long,
    onDismiss: () -> Unit,
    onSave: (Int, Int, ActivityType, LocationType) -> Unit
) {
    var activity by remember { mutableStateOf(ActivityType.PERSONAL) }
    var location by remember { mutableStateOf(LocationType.HOME) }
    var durationMin by remember { mutableStateOf(60) }
    val timeState = rememberTimePickerState(is24Hour = true)

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = DsTokens.Elevated) {
        Column(
            Modifier.padding(horizontal = DsTokens.ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(DsTokens.GapM)
        ) {
            Text("Add what happened", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Manual episodes join the same 24-hour model as everything else, marked as yours.",
                style = MaterialTheme.typography.bodySmall
            )
            SelectorGrid(
                items = ActivityType.entries.filter { it != ActivityType.UNKNOWN },
                selected = activity,
                label = { "${it.icon} ${it.label}" },
                onSelect = { activity = it }
            )
            TimePicker(state = timeState)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(15, 30, 45, 60, 90, 120).forEach { minutes ->
                    DsChip(
                        label = TimeUtils.formatDurationMin(minutes),
                        selected = durationMin == minutes,
                        onClick = { durationMin = minutes }
                    )
                }
            }
            SelectorGrid(
                items = LocationType.entries.toList(),
                selected = location,
                label = { "${it.icon} ${it.label}" },
                onSelect = { location = it }
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Cancel", color = DsTokens.InkMuted) }
                TextButton(onClick = {
                    val start = timeState.hour * 60 + timeState.minute
                    onSave(start, (start + durationMin).coerceAtMost(1440), activity, location)
                }) { Text("Add", color = DsTokens.Gold) }
            }
            Spacer(Modifier.height(DsTokens.GapL))
        }
    }
}

@Composable
private fun <T> SelectorGrid(
    items: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { item ->
                    DsChip(
                        label = label(item),
                        selected = item == selected,
                        onClick = { onSelect(item) },
                        modifier = Modifier.weight(1f)
                    )
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}
