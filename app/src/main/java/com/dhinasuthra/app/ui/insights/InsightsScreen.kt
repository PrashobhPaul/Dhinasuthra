package com.dhinasuthra.app.ui.insights

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.dhinasuthra.app.DhinaSuthraApp
import com.dhinasuthra.app.core.TimeUtils
import com.dhinasuthra.app.core.database.DailySummaryEntity
import com.dhinasuthra.app.ui.components.Bar
import com.dhinasuthra.app.ui.components.DonutChart
import com.dhinasuthra.app.ui.components.Slice
import com.dhinasuthra.app.ui.components.TiltCard
import com.dhinasuthra.app.ui.components.WeekBars
import com.dhinasuthra.app.ui.theme.Ds
import kotlin.math.abs

@Composable
fun InsightsScreen() {
    val ctx = LocalContext.current
    val db = DhinaSuthraApp.get(ctx).container.db
    var tab by remember { mutableIntStateOf(0) }
    val today = TimeUtils.epochDay()
    val rangeDays = if (tab == 0) 7L else 30L

    val summaries by produceState<List<DailySummaryEntity>>(emptyList(), tab) {
        db.dailySummaryDao().observeBetweenDays(today - rangeDays + 1, today).collect { value = it }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp)
    ) {
        Spacer(Modifier.height(10.dp))
        Text("Insights", style = MaterialTheme.typography.headlineMedium)
        Text("Where did your time go?", style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(12.dp))

        TabRow(
            selectedTabIndex = tab,
            containerColor = Ds.NavyRaised,
            contentColor = Ds.Cream
        ) {
            Tab(tab == 0, onClick = { tab = 0 }, text = { Text("This week") })
            Tab(tab == 1, onClick = { tab = 1 }, text = { Text("This month") })
        }
        Spacer(Modifier.height(16.dp))

        val totals = remember(summaries) {
            listOf(
                Slice("Sleep", summaries.sumOf { it.sleepMin }, Ds.Purple),
                Slice("Work", summaries.sumOf { it.workMin }, Ds.Amber),
                Slice("Home", summaries.sumOf { it.homeMin }, Ds.Active),
                Slice("Travel", summaries.sumOf { it.travelMin }, Ds.Warm),
                Slice("Social", summaries.sumOf { it.socialMin }, Ds.ThreadStart),
                Slice("Other", summaries.sumOf { it.otherMin + it.unknownMin }, Ds.Muted)
            ).filter { it.minutes > 0 }
        }

        if (summaries.isEmpty()) {
            TiltCard(Modifier.fillMaxWidth()) {
                Text(
                    "Insights appear once DhinaSuthra has observed a few days of your thread.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(18.dp)
                )
            }
        } else {
            TiltCard(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(contentAlignment = Alignment.Center) {
                        DonutChart(totals, Modifier.size(150.dp))
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                TimeUtils.formatDurationMin(totals.sumOf { it.minutes }),
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text("tracked", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    Spacer(Modifier.padding(8.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        val total = totals.sumOf { it.minutes }.coerceAtLeast(1)
                        totals.forEach { s ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    Modifier
                                        .size(9.dp)
                                        .padding(0.dp)
                                ) {
                                    androidx.compose.foundation.Canvas(Modifier.size(9.dp)) {
                                        drawCircle(s.color)
                                    }
                                }
                                Spacer(Modifier.padding(3.dp))
                                Text(s.label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                Text(
                                    "${TimeUtils.formatDurationMin(s.minutes)} · ${s.minutes * 100 / total}%",
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Routine consistency by day
            TiltCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp)) {
                    Text("Routine consistency", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(12.dp))
                    val bars = summaries.takeLast(7).map { s ->
                        Bar(
                            label = TimeUtils.localDate(s.epochDay).dayOfWeek.name.take(1),
                            value = s.routineMatch ?: 0,
                            max = 100,
                            highlight = s.epochDay == today
                        )
                    }
                    WeekBars(bars)
                    Spacer(Modifier.height(8.dp))
                    val avg = summaries.mapNotNull { it.routineMatch }.let {
                        if (it.isEmpty()) null else it.average().toInt()
                    }
                    if (avg != null) {
                        Text(
                            "Average routine match: $avg%",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            TimeLeakageCard(summaries)
        }
        Spacer(Modifier.height(90.dp))
    }
}

/** Time leakage (product.md §24): deviations vs the user's own baseline — never judgmental. */
@Composable
private fun TimeLeakageCard(summaries: List<DailySummaryEntity>) {
    if (summaries.size < 5) return
    val latest = summaries.last()
    val prior = summaries.dropLast(1)
    fun median(sel: (DailySummaryEntity) -> Int): Int {
        val v = prior.map(sel).sorted(); return v[v.size / 2]
    }
    val leaks = buildList {
        val workDelta = latest.workMin - median { it.workMin }
        if (abs(workDelta) >= 60) add(
            if (workDelta > 0) "You spent ${TimeUtils.formatDurationMin(workDelta)} longer at work than your typical day."
            else "You spent ${TimeUtils.formatDurationMin(-workDelta)} less at work than your typical day."
        )
        val travelDelta = latest.travelMin - median { it.travelMin }
        if (travelDelta >= 30) add("Travel ran ${TimeUtils.formatDurationMin(travelDelta)} longer than your usual.")
        val sleepDelta = latest.sleepMin - median { it.sleepMin }
        if (sleepDelta <= -45) add("Your estimated sleep window was ${TimeUtils.formatDurationMin(-sleepDelta)} shorter than normal.")
    }
    if (leaks.isEmpty()) return
    TiltCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Time leakage", style = MaterialTheme.typography.titleMedium)
            leaks.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
