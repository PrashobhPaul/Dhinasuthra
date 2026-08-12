package com.dhinasuthra.app.ui.today

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dhinasuthra.app.DhinaSuthraApp
import com.dhinasuthra.app.core.TimeUtils
import com.dhinasuthra.app.core.database.DailySummaryEntity
import com.dhinasuthra.app.core.database.RoutineEventEntity
import com.dhinasuthra.app.core.database.RoutinePatternEntity
import com.dhinasuthra.app.core.model.RoutineEventType
import com.dhinasuthra.app.routine.GreetingEngine
import com.dhinasuthra.app.routine.RoutineStats
import com.dhinasuthra.app.ui.components.RoutineMatchRing
import com.dhinasuthra.app.ui.components.StatChip
import com.dhinasuthra.app.ui.components.ThreadItem
import com.dhinasuthra.app.ui.components.ThreadTimeline
import com.dhinasuthra.app.ui.components.TiltCard
import com.dhinasuthra.app.ui.motion.MotionTokens
import com.dhinasuthra.app.ui.theme.Ds
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.Instant

data class TodayUiState(
    val summary: DailySummaryEntity? = null,
    val events: List<RoutineEventEntity> = emptyList(),
    val patterns: List<RoutinePatternEntity> = emptyList(),
    val observedDays: Int = 0,
    val greeting: GreetingEngine.Greeting = GreetingEngine.Greeting("", null)
)

class TodayViewModel(app: DhinaSuthraApp) : ViewModel() {
    private val db = app.container.db
    private val today = TimeUtils.epochDay()
    private val greetingEngine = app.container.greetingEngine
    private val userName = app.container.settings.userName

    val state: StateFlow<TodayUiState> = combine(
        db.dailySummaryDao().observeForDay(today),
        db.routineEventDao().observeForDay(today),
        db.routinePatternDao().observeAll(),
        db.contextEventDao().observeOpenEvent(),
        db.placeDao().observeAll()
    ) { summary, events, patterns, openContext, places ->
        val place = openContext?.placeId?.let { id -> places.firstOrNull { it.id == id } }
        TodayUiState(
            summary, events, patterns,
            observedDays = patterns.maxOfOrNull { it.observationCount } ?: 0,
            greeting = greetingEngine.compose(userName, openContext, place, patterns)
        )
    }.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), TodayUiState()
    )
}

@Composable
fun TodayScreen() {
    val ctx = LocalContext.current
    val vm: TodayViewModel = viewModel(initializer = { TodayViewModel(DhinaSuthraApp.get(ctx)) })
    val state by vm.state.collectAsState()

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp)
    ) {
        Spacer(Modifier.height(10.dp))
        Header(state.greeting)
        Spacer(Modifier.height(16.dp))

        // Routine Match + time allocation
        TiltCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RoutineMatchRing(state.summary?.routineMatch, Modifier.size(150.dp))
                    Spacer(Modifier.padding(6.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Your day so far", style = MaterialTheme.typography.titleMedium)
                        val flowText = when {
                            state.summary?.routineMatch == null -> "Getting to know your rhythm"
                            state.summary!!.routineMatch!! >= 75 -> "Your day is in flow"
                            else -> "Today looks a little different"
                        }
                        Text(
                            flowText,
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = if ((state.summary?.routineMatch ?: 0) >= 75) Ds.Positive else Ds.Muted
                            )
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
                val s = state.summary
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    StatChip("Home", TimeUtils.formatDurationMin(s?.homeMin ?: 0), Ds.Active)
                    StatChip("Work", TimeUtils.formatDurationMin(s?.workMin ?: 0), Ds.Amber)
                    StatChip("Travel", TimeUtils.formatDurationMin(s?.travelMin ?: 0), Ds.Warm)
                    StatChip("Sleep", TimeUtils.formatDurationMin(s?.sleepMin ?: 0), Ds.Purple)
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        if (state.observedDays < 5) LearningCard(state.observedDays)

        // Timeline
        TiltCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp)) {
                Text("Today's thread", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(10.dp))
                if (state.events.isEmpty()) {
                    Text(
                        "No moments yet today. As DhinaSuthra observes your day, the thread appears here.",
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    ThreadTimeline(state.events.sortedBy { it.timestamp }.mapIndexed { i, e ->
                        ThreadItem(
                            time = TimeUtils.formatMinuteOfDay(
                                TimeUtils.minuteOfDay(Instant.ofEpochMilli(e.timestamp))
                            ),
                            title = eventTitle(e.eventType),
                            subtitle = eventSubtitle(e.eventType),
                            duration = e.durationMin?.let { TimeUtils.formatDurationMin(it) },
                            color = eventColor(e.eventType),
                            isCurrent = i == state.events.lastIndex,
                            manual = e.source == com.dhinasuthra.app.core.model.EventSource.USER
                        )
                    })
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        NextUpCard(state.patterns)
        Spacer(Modifier.height(90.dp))
    }
}

@Composable
private fun Header(greeting: GreetingEngine.Greeting) {
    Column {
        Text(
            buildAnnotatedString {
                withStyle(MaterialTheme.typography.headlineMedium.toSpanStyle().copy(color = Ds.Cream)) { append("Dhina") }
                withStyle(MaterialTheme.typography.headlineMedium.toSpanStyle().copy(color = Ds.Amber)) { append("Suthra") }
            },
            style = MaterialTheme.typography.headlineMedium
        )
        Text("The thread of your day", style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(10.dp))
        if (greeting.salutation.isNotBlank()) {
            Text(greeting.salutation, style = MaterialTheme.typography.headlineSmall)
        }
        // Live state transition (§12): the context line crossfades, the screen stays.
        AnimatedContent(
            targetState = greeting.contextLine,
            transitionSpec = {
                (fadeIn(tween(MotionTokens.STANDARD, easing = MotionTokens.EnterEasing)) togetherWith
                    fadeOut(tween(MotionTokens.FAST, easing = MotionTokens.ExitEasing)))
            },
            label = "contextLine"
        ) { line ->
            if (line != null) {
                Text(line, style = MaterialTheme.typography.bodySmall.copy(color = Ds.Muted))
            } else {
                Spacer(Modifier.height(0.dp))
            }
        }
    }
}

@Composable
private fun LearningCard(days: Int) {
    TiltCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp)) {
            Text("DhinaSuthra is learning your routine", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                "Reminders stay quiet until your patterns are confident. $days of ~14 learning days observed.",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { (days / 14f).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
                color = Ds.Amber,
                trackColor = Color.White.copy(alpha = 0.08f)
            )
        }
    }
    Spacer(Modifier.height(16.dp))
}

@Composable
private fun NextUpCard(patterns: List<RoutinePatternEntity>) {
    val nowMin = TimeUtils.minuteOfDay(Instant.now())
    val dayType = TimeUtils.dayType(TimeUtils.epochDay())
    val next = patterns
        .filter { it.dayType == dayType && it.confidence >= 0.5f }
        .map { it to RoutineStats.denormalize(it.medianMin) }
        .filter { it.second > nowMin }
        .minByOrNull { it.second }
    if (next != null) {
        TiltCard(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(42.dp).background(Ds.Amber.copy(alpha = 0.18f), CircleShape),
                    contentAlignment = Alignment.Center
                ) { Text("⏱", style = MaterialTheme.typography.titleMedium) }
                Spacer(Modifier.padding(6.dp))
                Column(Modifier.weight(1f)) {
                    Text("Next up · ${eventTitle(next.first.eventType)}", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Usually around ${TimeUtils.formatMinuteOfDay(next.second)}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Text(
                    TimeUtils.formatMinuteOfDay(next.second),
                    style = MaterialTheme.typography.titleMedium.copy(color = Ds.Amber)
                )
            }
        }
    }
}

fun eventTitle(t: RoutineEventType): String = when (t) {
    RoutineEventType.WAKE -> "Woke up"
    RoutineEventType.START_DAY -> "Started the day"
    RoutineEventType.LEAVE_HOME -> "Left home"
    RoutineEventType.TRAVEL -> "Travel"
    RoutineEventType.ARRIVE_OFFICE -> "Arrived at office"
    RoutineEventType.WORK -> "Work"
    RoutineEventType.SHORT_BREAK -> "Short break"
    RoutineEventType.LUNCH -> "Lunch"
    RoutineEventType.LEAVE_OFFICE -> "Left office"
    RoutineEventType.ARRIVE_HOME -> "Arrived home"
    RoutineEventType.SOCIAL_VISIT -> "Visit"
    RoutineEventType.SLEEP -> "Sleep"
    RoutineEventType.OTHER -> "Moment"
}

fun eventSubtitle(t: RoutineEventType): String = when (t) {
    RoutineEventType.WAKE -> "At home"
    RoutineEventType.LEAVE_HOME -> "Started your day"
    RoutineEventType.ARRIVE_OFFICE -> "Work mode on"
    RoutineEventType.LUNCH -> "Good food, good energy"
    RoutineEventType.SHORT_BREAK -> "A brief pause"
    RoutineEventType.LEAVE_OFFICE -> "Day wrapping up"
    RoutineEventType.ARRIVE_HOME -> "Back home"
    RoutineEventType.SOCIAL_VISIT -> "A known place"
    RoutineEventType.SLEEP -> "Estimated sleep window"
    else -> "Part of your thread"
}

fun eventColor(t: RoutineEventType): Color = when (t) {
    RoutineEventType.WAKE, RoutineEventType.START_DAY -> Ds.Active
    RoutineEventType.LEAVE_HOME, RoutineEventType.TRAVEL -> Ds.Warm
    RoutineEventType.ARRIVE_OFFICE, RoutineEventType.WORK -> Ds.Amber
    RoutineEventType.SHORT_BREAK, RoutineEventType.LUNCH -> Ds.Positive
    RoutineEventType.LEAVE_OFFICE, RoutineEventType.ARRIVE_HOME -> Ds.Blue
    RoutineEventType.SOCIAL_VISIT -> Ds.ThreadStart
    RoutineEventType.SLEEP -> Ds.Purple
    RoutineEventType.OTHER -> Ds.Muted
}
