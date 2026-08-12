package com.dhinasuthra.app.ui.more

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.dhinasuthra.app.BuildConfig
import com.dhinasuthra.app.DhinaSuthraApp
import com.dhinasuthra.app.core.model.NotificationPrivacy
import com.dhinasuthra.app.simulate.DaySimulator
import com.dhinasuthra.app.ui.components.TiltCard
import com.dhinasuthra.app.ui.theme.Ds
import com.dhinasuthra.app.ui.today.eventTitle
import com.dhinasuthra.app.work.Workers
import kotlinx.coroutines.launch

/**
 * More: reminders (rules + privacy), tracking controls with pause (§70),
 * privacy dashboard (§72), export/delete (§42/§50/§71), personalization (§34),
 * and — debug only — the §58-compliant simulator.
 */
@Composable
fun MoreScreen() {
    val ctx = LocalContext.current
    val app = DhinaSuthraApp.get(ctx)
    val scope = rememberCoroutineScope()
    val settings = app.container.settings

    var trackingOn by remember { mutableStateOf(settings.trackingEnabled) }
    var privacyMinimal by remember { mutableStateOf(settings.notificationPrivacy == NotificationPrivacy.MINIMAL) }
    var name by remember { mutableStateOf(settings.userName) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var statusLine by remember { mutableStateOf<String?>(null) }

    val rules by app.container.db.reminderDao().observeRules().collectAsState(initial = emptyList())

    val exportJsonLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> uri?.let { scope.launch { app.container.dataExporter.exportJson(it); statusLine = "Exported JSON." } } }
    val exportCsvLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri -> uri?.let { scope.launch { app.container.dataExporter.exportCsv(it); statusLine = "Exported CSV." } } }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp)
    ) {
        Spacer(Modifier.height(10.dp))
        Text("More", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(14.dp))

        // ------- Tracking (§70) -------
        TiltCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text("DhinaSuthra tracking", style = MaterialTheme.typography.titleMedium)
                        Text("The engine behind your thread", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(
                        checked = trackingOn,
                        onCheckedChange = { on ->
                            trackingOn = on
                            settings.trackingEnabled = on
                            scope.launch {
                                if (on) {
                                    Workers.scheduleAll(ctx)
                                    app.container.sensorPolicyEngine.applyCurrentPolicy()
                                    app.container.reminderScheduler.planToday()
                                } else {
                                    Workers.cancelAll(ctx)
                                    app.container.sensorPolicyEngine.stopAll()
                                    app.container.reminderScheduler.cancelAll()
                                }
                            }
                        },
                        colors = SwitchDefaults.colors(checkedTrackColor = Ds.Amber)
                    )
                }
                if (trackingOn) {
                    Row {
                        TextButton(onClick = {
                            settings.pausedUntil = System.currentTimeMillis() + 60 * 60 * 1000L
                            statusLine = "Paused for 1 hour."
                        }) { Text("Pause 1h", color = Ds.Active) }
                        TextButton(onClick = {
                            settings.pausedUntil = com.dhinasuthra.app.core.TimeUtils.dayEnd(
                                com.dhinasuthra.app.core.TimeUtils.epochDay()
                            ).toEpochMilli()
                            statusLine = "Paused until tomorrow."
                        }) { Text("Pause today", color = Ds.Active) }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        // ------- Reminders -------
        TiltCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Reminders", style = MaterialTheme.typography.titleMedium)
                Text("A quiet day with zero reminders is a good day.", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(6.dp))
                rules.forEach { rule ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(eventTitle(rule.eventType), style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f).padding(top = 12.dp))
                        Switch(
                            checked = rule.enabled,
                            onCheckedChange = { on ->
                                scope.launch {
                                    app.container.db.reminderDao().upsertRule(rule.copy(enabled = on, userConfigured = true))
                                    app.container.reminderScheduler.planToday()
                                }
                            },
                            colors = SwitchDefaults.colors(checkedTrackColor = Ds.Amber)
                        )
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text("Discreet notifications", style = MaterialTheme.typography.bodyMedium)
                        Text("Show \"DhinaSuthra reminder\" without details on the lock screen", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(
                        checked = privacyMinimal,
                        onCheckedChange = { on ->
                            privacyMinimal = on
                            settings.notificationPrivacy = if (on) NotificationPrivacy.MINIMAL else NotificationPrivacy.FULL
                        },
                        colors = SwitchDefaults.colors(checkedTrackColor = Ds.Amber)
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        // ------- Privacy dashboard (§72) -------
        TiltCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("What DhinaSuthra uses", style = MaterialTheme.typography.titleMedium)
                Text("📍 Location — to understand places and travel", style = MaterialTheme.typography.bodySmall)
                Text("🚶 Movement — to know moving vs stationary", style = MaterialTheme.typography.bodySmall)
                Text("📱 Screen on/off — only to estimate sleep and inactivity", style = MaterialTheme.typography.bodySmall)
                Text("🔋 Charging — as a weak context signal", style = MaterialTheme.typography.bodySmall)
                Text("🔔 Notifications — for your routine reminders", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(2.dp))
                Text(
                    "DhinaSuthra does not track which apps you use, read messages or contacts, or send your data anywhere. Everything stays on this phone.",
                    style = MaterialTheme.typography.bodySmall.copy(color = Ds.Positive)
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        // ------- Personalization (§34) -------
        TiltCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("What should DhinaSuthra call you?", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it.take(24)
                        settings.userName = name.trim()
                    },
                    label = { Text("Your name") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        // ------- Your data (§42/§50/§71) -------
        TiltCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Your data", style = MaterialTheme.typography.titleMedium)
                Text("You own everything DhinaSuthra learns.", style = MaterialTheme.typography.bodySmall)
                Row {
                    TextButton(onClick = { exportJsonLauncher.launch("dhinasuthra-export.json") }) {
                        Text("Export JSON", color = Ds.Active)
                    }
                    TextButton(onClick = { exportCsvLauncher.launch("dhinasuthra-events.csv") }) {
                        Text("Export CSV", color = Ds.Active)
                    }
                }
                TextButton(onClick = { showDeleteConfirm = true }) {
                    Text("Delete all my data", color = Ds.Warm)
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        if (BuildConfig.SIMULATOR_ENABLED) {
            TiltCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Developer · Simulator", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Feeds 21 days of realistic sensor signals through the real pipeline so every dashboard is engine-derived. Debug builds only.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row {
                        TextButton(onClick = {
                            statusLine = "Simulating 21 days…"
                            scope.launch {
                                DaySimulator(ctx).loadDays(21)
                                statusLine = "Simulation loaded. Open Today."
                            }
                        }) { Text("Load 21 days", color = Ds.Amber) }
                        TextButton(onClick = {
                            scope.launch {
                                app.container.dataExporter.deleteAllData()
                                statusLine = "Cleared."
                            }
                        }) { Text("Clear", color = Ds.Muted) }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        statusLine?.let {
            Text(it, style = MaterialTheme.typography.bodySmall.copy(color = Ds.Positive))
            Spacer(Modifier.height(8.dp))
        }
        Text(
            "DhinaSuthra ${BuildConfig.VERSION_NAME} · The thread of your day",
            style = MaterialTheme.typography.labelSmall
        )
        Spacer(Modifier.height(90.dp))
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            containerColor = Ds.NavyRaised,
            title = { Text("Delete everything?", color = Ds.Cream) },
            text = { Text("Places, events, learned routine, reminders and summaries will be permanently removed from this phone.", color = Ds.Muted) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        app.container.reminderScheduler.cancelAll()
                        app.container.dataExporter.deleteAllData()
                        statusLine = "All data deleted."
                        showDeleteConfirm = false
                    }
                }) { Text("Delete", color = Ds.Warm) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel", color = Ds.Muted) }
            }
        )
    }
}
