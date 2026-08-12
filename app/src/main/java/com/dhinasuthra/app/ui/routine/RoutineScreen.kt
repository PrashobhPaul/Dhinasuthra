package com.dhinasuthra.app.ui.routine

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxWidth as fmw
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.dhinasuthra.app.DhinaSuthraApp
import com.dhinasuthra.app.core.TimeUtils
import com.dhinasuthra.app.core.database.RoutinePatternEntity
import com.dhinasuthra.app.core.model.DayType
import com.dhinasuthra.app.core.model.RoutineEventType
import com.dhinasuthra.app.routine.RoutineStats
import com.dhinasuthra.app.ui.components.TiltCard
import com.dhinasuthra.app.ui.theme.Ds
import com.dhinasuthra.app.ui.today.eventTitle
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Routine screen (product.md §26): learned patterns with ranges + confidence +
 * observation counts, "Initial" badges for user-template priors (§49E — a
 * template is never presented as observed), and the routine builder (§49C).
 */
@Composable
fun RoutineScreen() {
    val ctx = LocalContext.current
    val app = DhinaSuthraApp.get(ctx)
    var tab by remember { mutableIntStateOf(0) }
    var showBuilder by remember { mutableStateOf(false) }
    val dayType = if (tab == 0) DayType.WEEKDAY else DayType.WEEKEND

    val patterns by app.container.db.routinePatternDao().observeAll()
        .map { list -> list.sortedBy { it.medianMin } }
        .collectAsState(initial = emptyList())

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp)
    ) {
        Spacer(Modifier.height(10.dp))
        Text("Routine", style = MaterialTheme.typography.headlineMedium)
        Text("What DhinaSuthra has learned about your rhythm", style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(12.dp))

        TabRow(selectedTabIndex = tab, containerColor = Ds.NavyRaised, contentColor = Ds.Cream) {
            Tab(tab == 0, onClick = { tab = 0 }, text = { Text("Workdays") })
            Tab(tab == 1, onClick = { tab = 1 }, text = { Text("Weekends") })
        }
        Spacer(Modifier.height(14.dp))

        val visible = patterns.filter { it.dayType == dayType }
        if (visible.isEmpty()) {
            TiltCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp)) {
                    Text("Nothing learned yet for this day type.", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "DhinaSuthra learns quietly from your days. You can also tell it your usual times below.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        } else {
            visible.forEach { p ->
                PatternRow(p)
                Spacer(Modifier.height(10.dp))
            }
        }

        Spacer(Modifier.height(8.dp))
        TiltCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp)) {
                Text("Tell DhinaSuthra your routine", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Optional. Your stated times work immediately for Next Up and reminders, then fade as real observations take over.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = { showBuilder = true }) { Text("Set my usual times", color = Ds.Amber) }
            }
        }
        Spacer(Modifier.height(90.dp))
    }

    if (showBuilder) {
        RoutineTemplateSheet(dayType = dayType, onDismiss = { showBuilder = false })
    }
}

@Composable
private fun PatternRow(p: RoutinePatternEntity) {
    TiltCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(eventTitle(p.eventType), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (p.observationCount == 0 && p.priorMin != null) {
                    Box(
                        Modifier
                            .background(Ds.Active.copy(alpha = 0.18f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) { Text("Initial · yours", style = MaterialTheme.typography.labelSmall) }
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    TimeUtils.formatMinuteOfDay(RoutineStats.denormalize(p.medianMin)),
                    style = MaterialTheme.typography.titleMedium.copy(color = Ds.Amber)
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Usually ${TimeUtils.formatMinuteOfDay(RoutineStats.denormalize(p.p10))}–${TimeUtils.formatMinuteOfDay(RoutineStats.denormalize(p.p90))}" +
                    (p.typicalDurationMin?.let { " · ~${TimeUtils.formatDurationMin(it)}" } ?: ""),
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(8.dp))
            // Confidence bar (§35: every meaningful inference has confidence)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .weight(1f)
                        .height(5.dp)
                        .background(Ds.Muted.copy(alpha = 0.12f), RoundedCornerShape(3.dp))
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(p.confidence.coerceIn(0f, 1f))
                            .height(5.dp)
                            .background(
                                if (p.confidence >= 0.7f) Ds.Positive else Ds.Amber,
                                RoundedCornerShape(3.dp)
                            )
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    if (p.observationCount == 0) "Initial"
                    else "${(p.confidence * 100).toInt()}% · ${p.observationCount} days",
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

/**
 * §49C routine builder: user states usual times per event; stored as priors via
 * RoutineLearningEngine.seedUserPrior — separate from observed truth (§49C.1).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutineTemplateSheet(dayType: DayType, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val app = DhinaSuthraApp.get(ctx)
    val scope = rememberCoroutineScope()
    val steps = listOf(
        RoutineEventType.WAKE, RoutineEventType.LEAVE_HOME, RoutineEventType.ARRIVE_OFFICE,
        RoutineEventType.LUNCH, RoutineEventType.LEAVE_OFFICE, RoutineEventType.SLEEP
    )
    var selected by remember { mutableStateOf(steps.first()) }
    var saved by remember { mutableStateOf(setOf<RoutineEventType>()) }
    val timeState = rememberTimePickerState(is24Hour = true)

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Ds.NavyRaised) {
        Column(Modifier.padding(horizontal = 18.dp)) {
            Text("Your usual ${if (dayType == DayType.WEEKDAY) "workday" else "weekend"}", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Pick a moment, set its usual time, save. Skip anything that doesn't apply.",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fmw(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                steps.take(3).forEach { t ->
                    FilterChip(selected = selected == t, onClick = { selected = t },
                        label = { Text(eventTitle(t) + if (t in saved) " ✓" else "") })
                }
            }
            Row(Modifier.fmw(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                steps.drop(3).forEach { t ->
                    FilterChip(selected = selected == t, onClick = { selected = t },
                        label = { Text(eventTitle(t) + if (t in saved) " ✓" else "") })
                }
            }
            Spacer(Modifier.height(10.dp))
            TimePicker(state = timeState)
            Row(Modifier.fmw(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Done", color = Ds.Muted) }
                TextButton(onClick = {
                    val minute = timeState.hour * 60 + timeState.minute
                    scope.launch {
                        app.container.routineLearningEngine.seedUserPrior(selected, dayType, minute)
                        app.container.reminderScheduler.planToday()
                    }
                    saved = saved + selected
                    val idx = steps.indexOf(selected)
                    if (idx < steps.lastIndex) selected = steps[idx + 1]
                }) { Text("Save time", color = Ds.Amber) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
