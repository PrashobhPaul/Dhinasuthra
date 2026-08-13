package com.dhinasuthra.app.ui.routine

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
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
import com.dhinasuthra.app.intelligence.ActivityType
import com.dhinasuthra.app.intelligence.LocationType
import com.dhinasuthra.app.intelligence.PatternEngine
import com.dhinasuthra.app.intelligence.RoutineComposer
import com.dhinasuthra.app.intelligence.RoutineRules
import com.dhinasuthra.app.intelligence.Timetable
import com.dhinasuthra.app.intelligence.TimetableAdherence
import com.dhinasuthra.app.intelligence.TimetableEntry
import com.dhinasuthra.app.ui.foundation.CardBody
import com.dhinasuthra.app.ui.foundation.DsChip
import com.dhinasuthra.app.ui.foundation.DsScreen
import com.dhinasuthra.app.ui.foundation.EmptyState
import com.dhinasuthra.app.ui.foundation.GlassCard
import com.dhinasuthra.app.ui.foundation.Hairline
import com.dhinasuthra.app.ui.foundation.Reveal
import com.dhinasuthra.app.ui.foundation.RuleIdRow
import com.dhinasuthra.app.ui.foundation.SectionTitle
import com.dhinasuthra.app.ui.state.rememberTimeViewModel
import com.dhinasuthra.app.ui.theme.DsTokens
import com.dhinasuthra.app.ui.viz.Meter
import com.dhinasuthra.app.ui.viz.PlanActualRow
import com.dhinasuthra.app.ui.viz.PlannedVsActual
import com.dhinasuthra.app.ui.viz.RadialDayClock
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Routine = action (spec §5, §18, §52).
 *
 * Observe → measure → recognise → establish → save → follow → measure adherence →
 * refine. This screen is the second half of that loop: it turns what DhinaSuthra
 * has noticed into a timetable you chose, then shows honestly how the day sat
 * against it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutineScreen() {
    val vm = rememberTimeViewModel()
    val state by vm.state.collectAsState()
    val snapshot = state.snapshot
    var editing by remember { mutableStateOf<Timetable?>(null) }
    var addingTo by remember { mutableStateOf<Timetable?>(null) }

    // Derived in the composable body — the lazy-list content lambda below is not
    // a composable scope, so remember() has to live out here.
    val todayType = TimeUtils.dayType(snapshot?.today?.epochDay ?: TimeUtils.epochDay())
    val suggestion = remember(snapshot, todayType) {
        val current = snapshot ?: return@remember null
        RoutineComposer.suggest(
            current.patterns, todayType,
            if (todayType == DayType.WEEKDAY) "My weekday rhythm" else "My weekend rhythm"
        )?.takeIf { proposal -> current.timetables.none { it.id == proposal.id } }
    }
    val drift = remember(snapshot, todayType) {
        val current = snapshot ?: return@remember emptyList<Pair<Timetable, RoutineComposer.DriftProposal>>()
        current.timetables.filter { it.active }.flatMap { timetable ->
            RoutineComposer.driftProposals(timetable, current.patterns, todayType)
                .map { timetable to it }
        }
    }

    DsScreen(
        title = "Routine",
        subtitle = "The timetable you are choosing to keep"
    ) {
        if (snapshot == null) {
            item { EmptyState("Loading", "Reading your patterns.") }
            return@DsScreen
        }

        val dayType = TimeUtils.dayType(snapshot.today.epochDay)

        // Today's plan against today's reality.
        snapshot.activeReport?.let { report ->
            item { SectionTitle("Today against plan", report.adherence?.let { "$it% adherence" }) }
            item { Reveal(0) { AdherenceDetail(report, snapshot) } }
        }

        item { SectionTitle("Your routines") }

        if (snapshot.timetables.isEmpty()) {
            item {
                EmptyState(
                    "No routine saved yet",
                    "DhinaSuthra will offer one as soon as enough of your patterns are established — and you can always build one by hand."
                )
            }
        } else {
            items(snapshot.timetables.size) { index ->
                val timetable = snapshot.timetables[index]
                Reveal(index) {
                    TimetableCard(
                        timetable = timetable,
                        onToggle = { vm.saveTimetable(timetable.copy(active = !timetable.active)) },
                        onEdit = { editing = timetable },
                        onAddEntry = { addingTo = timetable },
                        onDelete = { vm.deleteTimetable(timetable.id) }
                    )
                }
            }
        }

        // A routine offer, built from established patterns only.
        if (suggestion != null) {
            item { SectionTitle("DhinaSuthra noticed a routine") }
            item {
                Reveal(0) {
                    GlassCard(Modifier.fillMaxWidth(), tint = DsTokens.Green, interactive = false) {
                        CardBody {
                            Text(
                                "You have repeated ${suggestion.entries.size} of these timings often enough to call them established.",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                "Saving this creates a timetable you can edit. Nothing is switched on for you, and no reminder is scheduled until you ask for one.",
                                style = MaterialTheme.typography.bodySmall
                            )
                            suggestion.sortedEntries.forEach { entry ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        Modifier.size(8.dp)
                                            .background(DsTokens.colorFor(entry.activity), CircleShape)
                                    )
                                    Spacer(Modifier.width(9.dp))
                                    Text(
                                        entry.activity.label,
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        "${entry.startLabel()} ±${entry.toleranceMin}m",
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                }
                            }
                            RuleIdRow(
                                listOf(
                                    RoutineRules.SUGGEST_NEVER_IMPOSE.id,
                                    RoutineRules.NEEDS_ESTABLISHED_PATTERN.id,
                                    RoutineRules.TOLERANCE_FROM_YOU.id
                                )
                            )
                            TextButton(onClick = { vm.saveTimetable(suggestion) }) {
                                Text("Save this routine", color = DsTokens.Gold)
                            }
                        }
                    }
                }
            }
        }

        // Drift: life moved, so offer to move the plan (RTN-09).
        if (drift.isNotEmpty()) {
            item { SectionTitle("Your routine has drifted") }
            items(drift.size) { index ->
                val (timetable, proposal) = drift[index]
                GlassCard(Modifier.fillMaxWidth(), tint = DsTokens.Gold, interactive = false) {
                    CardBody {
                        Text(
                            "${proposal.entry.activity.label} has settled around " +
                                TimeUtils.formatMinuteOfDay(proposal.observedStartMin) +
                                ", not ${proposal.entry.startLabel()}.",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            "That is ${TimeUtils.formatDurationMin(abs(proposal.deltaMin))} " +
                                (if (proposal.deltaMin > 0) "later" else "earlier") +
                                " across ${proposal.observations} observations. Update the plan rather than being told you are late every day?",
                            style = MaterialTheme.typography.bodySmall
                        )
                        RuleIdRow(listOf(RoutineRules.DRIFT_OFFERS_UPDATE.id))
                        TextButton(onClick = {
                            vm.saveTimetable(
                                timetable.copy(
                                    entries = timetable.entries.map {
                                        if (it.activity == proposal.entry.activity)
                                            it.copy(targetStartMin = proposal.observedStartMin) else it
                                    }
                                )
                            )
                        }) { Text("Update to ${TimeUtils.formatMinuteOfDay(proposal.observedStartMin)}", color = DsTokens.Gold) }
                    }
                }
            }
        }

        item { SectionTitle("Build one yourself") }
        item {
            GlassCard(Modifier.fillMaxWidth(), interactive = false) {
                CardBody {
                    Text("Create a timetable", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Tell DhinaSuthra the shape you are aiming for. Your stated times work immediately, are labelled as yours, and never masquerade as something it observed.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = {
                            editing = Timetable(
                                id = "user-${System.currentTimeMillis()}",
                                name = "My weekday routine",
                                days = Timetable.WEEKDAYS,
                                active = false,
                                entries = emptyList(),
                                createdAt = System.currentTimeMillis()
                            )
                        }) { Text("New weekday routine", color = DsTokens.Gold) }
                        TextButton(onClick = {
                            editing = Timetable(
                                id = "user-${System.currentTimeMillis()}",
                                name = "My weekend routine",
                                days = Timetable.WEEKENDS,
                                active = false,
                                entries = emptyList(),
                                createdAt = System.currentTimeMillis()
                            )
                        }) { Text("New weekend routine", color = DsTokens.Cyan) }
                    }
                    RuleIdRow(
                        listOf(
                            RoutineRules.TIMETABLE_SHAPE.id,
                            com.dhinasuthra.app.intelligence.PatternRules.PRIOR_IS_NOT_OBSERVATION.id
                        )
                    )
                }
            }
        }
    }

    editing?.let { timetable ->
        TimetableEditorSheet(
            timetable = timetable,
            onDismiss = { editing = null },
            onSave = {
                vm.saveTimetable(it)
                editing = null
            }
        )
    }

    addingTo?.let { timetable ->
        EntryEditorSheet(
            onDismiss = { addingTo = null },
            onSave = { entry ->
                vm.saveTimetable(timetable.copy(entries = timetable.entries + entry))
                addingTo = null
            }
        )
    }
}

@Composable
private fun AdherenceDetail(
    report: TimetableAdherence.Report,
    snapshot: com.dhinasuthra.app.intelligence.TimeIntelligenceRepository.Snapshot
) {
    GlassCard(Modifier.fillMaxWidth(), tint = DsTokens.Green, interactive = false) {
        CardBody(spacing = DsTokens.GapM) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(report.timetable.name, style = MaterialTheme.typography.titleMedium)
                    Text(report.explanation, style = MaterialTheme.typography.bodySmall)
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

            RadialDayClock(
                day = snapshot.today,
                modifier = Modifier.fillMaxWidth().height(280.dp),
                plannedRing = report.timetable.sortedEntries.map { entry ->
                    val end = entry.targetEndMin ?: (entry.targetStartMin + 30)
                    (entry.targetStartMin..end.coerceAtMost(1440)) to entry.activity
                },
                nowMin = snapshot.nowMin
            )

            Hairline()
            Text("PLANNED AGAINST ACTUAL", style = MaterialTheme.typography.labelSmall)
            PlannedVsActual(
                rows = report.results.map { r ->
                    PlanActualRow(
                        label = r.entry.activity.label,
                        plannedMin = r.entry.targetStartMin,
                        actualMin = r.actualStartMin,
                        toleranceMin = r.entry.toleranceMin,
                        color = DsTokens.colorFor(r.entry.activity)
                    )
                }
            )
            report.results.forEach { r ->
                Row {
                    Text(
                        r.entry.activity.label,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    Text(r.note, style = MaterialTheme.typography.labelSmall)
                }
            }
            RuleIdRow(report.ruleIds, max = 5)
        }
    }
}

@Composable
private fun TimetableCard(
    timetable: Timetable,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onAddEntry: () -> Unit,
    onDelete: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    GlassCard(
        Modifier.fillMaxWidth().animateContentSize(),
        onClick = { expanded = !expanded }
    ) {
        CardBody {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(timetable.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${timetable.dayLabel} · ${timetable.entries.size} entries",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                Switch(
                    checked = timetable.active,
                    onCheckedChange = { onToggle() },
                    colors = SwitchDefaults.colors(checkedTrackColor = DsTokens.Gold)
                )
            }
            if (!timetable.active) {
                Text(
                    "Inactive — not scored, not prompted, and absent from Today.",
                    style = MaterialTheme.typography.labelSmall.copy(color = DsTokens.InkFaint)
                )
            }
            timetable.sortedEntries.forEach { entry ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        entry.startLabel(),
                        style = MaterialTheme.typography.labelMedium.copy(color = DsTokens.Gold),
                        modifier = Modifier.width(50.dp)
                    )
                    Box(
                        Modifier.size(7.dp).background(DsTokens.colorFor(entry.activity), CircleShape)
                    )
                    Spacer(Modifier.width(9.dp))
                    Text(
                        entry.activity.label,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    Text("±${entry.toleranceMin}m", style = MaterialTheme.typography.labelSmall)
                }
            }
            if (expanded) {
                Hairline()
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = onAddEntry) { Text("Add entry", color = DsTokens.Cyan) }
                    TextButton(onClick = onEdit) { Text("Rename", color = DsTokens.InkSoft) }
                    TextButton(onClick = onDelete) { Text("Delete", color = DsTokens.Rose) }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimetableEditorSheet(
    timetable: Timetable,
    onDismiss: () -> Unit,
    onSave: (Timetable) -> Unit
) {
    var name by remember { mutableStateOf(timetable.name) }
    var days by remember { mutableStateOf(timetable.days) }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = DsTokens.Elevated) {
        Column(
            Modifier.padding(horizontal = DsTokens.ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(DsTokens.GapM)
        ) {
            Text("Name your routine", style = MaterialTheme.typography.headlineSmall)
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(40) },
                label = { Text("Routine name") },
                modifier = Modifier.fillMaxWidth()
            )
            Text("Which days?", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Timetable.DAY_NAMES.forEachIndexed { index, label ->
                    val dayNumber = index + 1
                    DsChip(
                        label = label,
                        selected = dayNumber in days,
                        onClick = {
                            days = if (dayNumber in days) days - dayNumber else days + dayNumber
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Cancel", color = DsTokens.InkMuted) }
                TextButton(
                    onClick = { onSave(timetable.copy(name = name.ifBlank { "My routine" }, days = days)) }
                ) { Text("Save", color = DsTokens.Gold) }
            }
            Spacer(Modifier.height(DsTokens.GapL))
        }
    }
}

/**
 * The onboarding bootstrap (spec §17, §43): before there is any history, the user
 * can simply say when their day usually happens. Those times are stored as a
 * timetable and surface as *priors* — labelled as yours, usable immediately, and
 * outweighed by real observations as they accumulate (PAT-13).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutineTemplateSheet(dayType: DayType, onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val app = remember { com.dhinasuthra.app.DhinaSuthraApp.get(context) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val steps = listOf(
        ActivityType.WAKE_TRANSITION, ActivityType.BREAKFAST, ActivityType.COMMUTE,
        ActivityType.WORK, ActivityType.LUNCH, ActivityType.DINNER, ActivityType.SLEEP
    )
    var selected by remember { mutableStateOf(steps.first()) }
    var entries by remember { mutableStateOf(listOf<TimetableEntry>()) }
    val timeState = rememberTimePickerState(is24Hour = true)

    fun persist(all: List<TimetableEntry>) {
        scope.launch {
            app.container.timetableStore.upsert(
                Timetable(
                    id = "template-${dayType.name.lowercase()}",
                    name = if (dayType == DayType.WEEKDAY) "My usual weekday" else "My usual weekend",
                    days = if (dayType == DayType.WEEKDAY) Timetable.WEEKDAYS else Timetable.WEEKENDS,
                    active = true,
                    entries = all,
                    createdAt = System.currentTimeMillis()
                )
            )
            app.container.timeIntelligence.invalidate()
            // Keep the existing reminder engine in step with the same stated times.
            legacyEventFor(all.last().activity)?.let { legacy ->
                app.container.routineLearningEngine.seedUserPrior(legacy, dayType, all.last().targetStartMin)
                app.container.reminderScheduler.planToday()
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = DsTokens.Elevated) {
        Column(
            Modifier.padding(horizontal = DsTokens.ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(DsTokens.GapM)
        ) {
            Text(
                if (dayType == DayType.WEEKDAY) "Your usual workday" else "Your usual weekend",
                style = MaterialTheme.typography.headlineSmall
            )
            Text(
                "Optional, and only a starting point. Pick a moment, set the time you usually do it, and save. DhinaSuthra will show these as yours until it has watched enough real days to know better.",
                style = MaterialTheme.typography.bodySmall
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                steps.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { option ->
                            DsChip(
                                label = option.label + if (entries.any { it.activity == option }) " ✓" else "",
                                selected = option == selected,
                                onClick = { selected = option },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
            TimePicker(state = timeState)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Done", color = DsTokens.InkMuted) }
                TextButton(onClick = {
                    val minute = timeState.hour * 60 + timeState.minute
                    val next = entries.filterNot { it.activity == selected } + TimetableEntry(
                        activity = selected,
                        targetStartMin = minute,
                        expectedLocation = when (selected) {
                            ActivityType.WORK -> LocationType.OFFICE
                            ActivityType.SLEEP, ActivityType.BREAKFAST, ActivityType.DINNER -> LocationType.HOME
                            else -> LocationType.UNKNOWN
                        },
                        toleranceMin = 20,
                        reminderEnabled = false
                    )
                    entries = next
                    persist(next)
                    val index = steps.indexOf(selected)
                    if (index < steps.lastIndex) selected = steps[index + 1]
                }) { Text("Save time", color = DsTokens.Gold) }
            }
            Spacer(Modifier.height(DsTokens.GapL))
        }
    }
}

private fun legacyEventFor(activity: ActivityType): com.dhinasuthra.app.core.model.RoutineEventType? = when (activity) {
    ActivityType.WAKE_TRANSITION -> com.dhinasuthra.app.core.model.RoutineEventType.WAKE
    ActivityType.COMMUTE -> com.dhinasuthra.app.core.model.RoutineEventType.LEAVE_HOME
    ActivityType.WORK -> com.dhinasuthra.app.core.model.RoutineEventType.ARRIVE_OFFICE
    ActivityType.LUNCH -> com.dhinasuthra.app.core.model.RoutineEventType.LUNCH
    ActivityType.SLEEP -> com.dhinasuthra.app.core.model.RoutineEventType.SLEEP
    else -> null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EntryEditorSheet(
    onDismiss: () -> Unit,
    onSave: (TimetableEntry) -> Unit
) {
    var activity by remember { mutableStateOf(ActivityType.WORK) }
    var tolerance by remember { mutableStateOf(20) }
    var location by remember { mutableStateOf(LocationType.UNKNOWN) }
    val timeState = rememberTimePickerState(is24Hour = true)

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = DsTokens.Elevated) {
        Column(
            Modifier.padding(horizontal = DsTokens.ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(DsTokens.GapM)
        ) {
            Text("Add a target time", style = MaterialTheme.typography.headlineSmall)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ActivityType.entries.filter { it != ActivityType.UNKNOWN }.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { option ->
                            DsChip(
                                label = "${option.icon} ${option.label}",
                                selected = option == activity,
                                onClick = { activity = option },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
            TimePicker(state = timeState)
            Text("How much drift is fine?", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(10, 15, 20, 30, 45).forEach { minutes ->
                    DsChip(
                        label = "±${minutes}m",
                        selected = tolerance == minutes,
                        onClick = { tolerance = minutes },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            Text("Where do you expect to be?", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    LocationType.HOME, LocationType.OFFICE,
                    LocationType.GYM, LocationType.UNKNOWN
                ).forEach { option ->
                    DsChip(
                        label = option.label,
                        selected = option == location,
                        onClick = { location = option },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Cancel", color = DsTokens.InkMuted) }
                TextButton(onClick = {
                    onSave(
                        TimetableEntry(
                            activity = activity,
                            targetStartMin = timeState.hour * 60 + timeState.minute,
                            expectedLocation = location,
                            toleranceMin = tolerance,
                            reminderEnabled = false
                        )
                    )
                }) { Text("Add", color = DsTokens.Gold) }
            }
            Spacer(Modifier.height(DsTokens.GapL))
        }
    }
}
