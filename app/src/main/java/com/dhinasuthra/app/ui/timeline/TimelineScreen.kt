package com.dhinasuthra.app.ui.timeline

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.dhinasuthra.app.DhinaSuthraApp
import com.dhinasuthra.app.core.TimeUtils
import com.dhinasuthra.app.core.database.RoutineEventEntity
import com.dhinasuthra.app.core.model.EventSource
import com.dhinasuthra.app.core.model.RoutineEventType
import com.dhinasuthra.app.routine.TimelineEditor
import com.dhinasuthra.app.ui.components.ThreadItem
import com.dhinasuthra.app.ui.components.ThreadTimeline
import com.dhinasuthra.app.ui.components.TiltCard
import com.dhinasuthra.app.ui.theme.Ds
import com.dhinasuthra.app.ui.today.eventColor
import com.dhinasuthra.app.ui.today.eventSubtitle
import com.dhinasuthra.app.ui.today.eventTitle
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * Timeline (product.md §21) + Edit Day (experience spec §49A.1/§49L):
 * every historical day supports view, add, and manual-event deletion.
 * All edits flow through TimelineEditor → same event model → recalculation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimelineScreen() {
    val ctx = LocalContext.current
    val app = DhinaSuthraApp.get(ctx)
    val db = app.container.db
    val scope = rememberCoroutineScope()
    var day by remember { mutableLongStateOf(TimeUtils.epochDay()) }
    var showAddSheet by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<RoutineEventEntity?>(null) }
    var editMessage by remember { mutableStateOf<String?>(null) }

    val events by produceState<List<RoutineEventEntity>>(emptyList(), day) {
        db.routineEventDao().observeForDay(day).collect { value = it }
    }
    val summary by produceState<com.dhinasuthra.app.core.database.DailySummaryEntity?>(null, day) {
        db.dailySummaryDao().observeForDay(day).collect { value = it }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp)
    ) {
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Timeline", style = MaterialTheme.typography.headlineMedium)
            IconButton(onClick = { showAddSheet = true }) {
                Icon(Icons.Filled.Add, contentDescription = "Add event to this day", tint = Ds.Amber)
            }
        }
        Spacer(Modifier.height(4.dp))

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { day-- }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous day", tint = MaterialTheme.colorScheme.onBackground)
            }
            Text(
                TimeUtils.localDate(day).format(DateTimeFormatter.ofPattern("EEE, d MMM yyyy")),
                style = MaterialTheme.typography.titleMedium
            )
            IconButton(onClick = { if (day < TimeUtils.epochDay()) day++ }, enabled = day < TimeUtils.epochDay()) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next day", tint = MaterialTheme.colorScheme.onBackground)
            }
        }
        Spacer(Modifier.height(8.dp))

        // §49N — honest coverage instead of false precision.
        val unknown = summary?.unknownMin ?: 0
        if (unknown >= 45) {
            TiltCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        "We couldn't fully reconstruct ${TimeUtils.formatDurationMin(unknown)} of this day.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "You can add what happened with + — or leave it unknown. Unknown is valid.",
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        TiltCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp)) {
                if (events.isEmpty()) {
                    Text(
                        "No thread recorded for this day. Use + to add what you remember.",
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    val sorted = events.sortedBy { it.timestamp }
                    ThreadTimeline(
                        sorted.map { e ->
                            ThreadItem(
                                time = TimeUtils.formatMinuteOfDay(TimeUtils.minuteOfDay(Instant.ofEpochMilli(e.timestamp))),
                                title = eventTitle(e.eventType),
                                subtitle = if (e.source == EventSource.USER) "Added by you" else eventSubtitle(e.eventType),
                                duration = e.durationMin?.let { TimeUtils.formatDurationMin(it) },
                                color = eventColor(e.eventType),
                                manual = e.source == EventSource.USER
                            )
                        },
                        onItemLongPress = { idx -> deleteTarget = sorted[idx] }
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("Hold a moment to remove it. Blue-dot moments were added by you.",
                        style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        editMessage?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, style = MaterialTheme.typography.bodySmall.copy(color = Ds.Warm))
        }
        Spacer(Modifier.height(90.dp))
    }

    if (showAddSheet) {
        AddEventSheet(
            onDismiss = { showAddSheet = false },
            onAdd = { type, minute, duration ->
                scope.launch {
                    val result = app.container.timelineEditor.addEvent(day, type, minute, duration)
                    editMessage = (result as? TimelineEditor.Result.Invalid)?.reason
                    if (result is TimelineEditor.Result.Ok) showAddSheet = false
                }
            }
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            containerColor = Ds.NavyRaised,
            title = { Text("Remove this moment?", color = Ds.Cream) },
            text = {
                Text(
                    if (target.source == EventSource.USER)
                        "This manual entry will be removed and analytics recalculated."
                    else
                        "Automatic moments are re-derived from sensing and can't be deleted here.",
                    color = Ds.Muted
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        val r = app.container.timelineEditor.deleteEvent(target)
                        editMessage = (r as? TimelineEditor.Result.Invalid)?.reason
                        deleteTarget = null
                    }
                }) { Text("Remove", color = Ds.Warm) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel", color = Ds.Muted) }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddEventSheet(
    onDismiss: () -> Unit,
    onAdd: (RoutineEventType, Int, Int?) -> Unit
) {
    val addable = listOf(
        RoutineEventType.WAKE, RoutineEventType.LEAVE_HOME, RoutineEventType.TRAVEL,
        RoutineEventType.ARRIVE_OFFICE, RoutineEventType.SHORT_BREAK, RoutineEventType.LUNCH,
        RoutineEventType.LEAVE_OFFICE, RoutineEventType.ARRIVE_HOME,
        RoutineEventType.SOCIAL_VISIT, RoutineEventType.SLEEP
    )
    var selected by remember { mutableStateOf(RoutineEventType.LUNCH) }
    var durationText by remember { mutableStateOf("") }
    val timeState = rememberTimePickerState(is24Hour = true)

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Ds.NavyRaised) {
        Column(Modifier.padding(horizontal = 18.dp)) {
            Text("Add a moment", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Manual moments join your thread with a blue dot and update your analytics.",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(12.dp))
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.height(170.dp)
            ) {
                items(addable) { t ->
                    FilterChip(
                        selected = selected == t,
                        onClick = { selected = t },
                        label = { Text(eventTitle(t)) }
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            TimePicker(state = timeState)
            OutlinedTextField(
                value = durationText,
                onValueChange = { durationText = it.filter { c -> c.isDigit() }.take(3) },
                label = { Text("Duration in minutes (optional)") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Cancel", color = Ds.Muted) }
                TextButton(onClick = {
                    onAdd(selected, timeState.hour * 60 + timeState.minute, durationText.toIntOrNull())
                }) { Text("Add", color = Ds.Amber) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
