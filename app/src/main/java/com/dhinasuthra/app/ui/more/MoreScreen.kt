package com.dhinasuthra.app.ui.more

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.dhinasuthra.app.BuildConfig
import com.dhinasuthra.app.DhinaSuthraApp
import com.dhinasuthra.app.core.model.NotificationPrivacy
import com.dhinasuthra.app.intelligence.RuleBook
import com.dhinasuthra.app.simulate.DaySimulator
import com.dhinasuthra.app.ui.foundation.CardBody
import com.dhinasuthra.app.ui.foundation.DsScreen
import com.dhinasuthra.app.ui.foundation.GlassCard
import com.dhinasuthra.app.ui.foundation.Hairline
import com.dhinasuthra.app.ui.foundation.Reveal
import com.dhinasuthra.app.ui.foundation.SectionTitle
import com.dhinasuthra.app.ui.places.PlacesScreen
import com.dhinasuthra.app.ui.rules.RuleBookScreen
import com.dhinasuthra.app.ui.state.rememberTimeViewModel
import com.dhinasuthra.app.ui.theme.DsTokens
import com.dhinasuthra.app.ui.today.eventTitle
import com.dhinasuthra.app.work.Workers
import kotlinx.coroutines.launch

private enum class MorePage { ROOT, PLACES, RULES }

/**
 * More: places, the Rule Book, prompting, privacy and your data.
 *
 * Spec §48 says not to add a tab merely because functionality exists, so the two
 * heavyweight secondary screens live behind this one.
 */
@Composable
fun MoreScreen() {
    var page by remember { mutableStateOf(MorePage.ROOT) }

    when (page) {
        MorePage.PLACES -> PlacesScreen(onBack = { page = MorePage.ROOT })
        MorePage.RULES -> RuleBookScreen(onBack = { page = MorePage.ROOT })
        MorePage.ROOT -> MoreRoot(onOpen = { page = it })
    }
}

@Composable
private fun MoreRoot(onOpen: (MorePage) -> Unit) {
    val ctx = LocalContext.current
    val app = DhinaSuthraApp.get(ctx)
    val scope = rememberCoroutineScope()
    val settings = app.container.settings
    val vm = rememberTimeViewModel()

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

    DsScreen(title = "More", subtitle = "Places, rules, privacy and your data") {

        item { SectionTitle("Explore") }
        item {
            Reveal(0) {
                GlassCard(Modifier.fillMaxWidth(), tint = DsTokens.Cyan, onClick = { onOpen(MorePage.PLACES) }) {
                    CardBody {
                        Text("📍  Places", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Where your time happens. Strictly location — never mixed with what you were doing.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
        item {
            Reveal(1) {
                GlassCard(Modifier.fillMaxWidth(), tint = DsTokens.Violet, onClick = { onOpen(MorePage.RULES) }) {
                    CardBody {
                        Text("🧠  Rule Book", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "All ${RuleBook.count} rules behind every conclusion this app reaches — readable, searchable, and each one naming the code that enforces it.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }

        item { SectionTitle("Observation") }
        item {
            Reveal(2) {
                GlassCard(Modifier.fillMaxWidth(), interactive = false) {
                    CardBody {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("DhinaSuthra tracking", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "The engine behind your timeline",
                                    style = MaterialTheme.typography.bodySmall
                                )
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
                                colors = SwitchDefaults.colors(checkedTrackColor = DsTokens.Gold)
                            )
                        }
                        AnimatedVisibility(trackingOn) {
                            Row {
                                TextButton(onClick = {
                                    settings.pausedUntil = System.currentTimeMillis() + 60 * 60 * 1000L
                                    statusLine = "Paused for 1 hour."
                                }) { Text("Pause 1h", color = DsTokens.Cyan) }
                                TextButton(onClick = {
                                    settings.pausedUntil = com.dhinasuthra.app.core.TimeUtils.dayEnd(
                                        com.dhinasuthra.app.core.TimeUtils.epochDay()
                                    ).toEpochMilli()
                                    statusLine = "Paused until tomorrow."
                                }) { Text("Pause today", color = DsTokens.Cyan) }
                            }
                        }
                    }
                }
            }
        }

        item { SectionTitle("Prompting") }
        item {
            Reveal(3) {
                GlassCard(Modifier.fillMaxWidth(), interactive = false) {
                    CardBody {
                        Text("Reminders", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "A quiet day with no reminders is a success. Prompts only fire for established patterns, never for something you have already done, and never inside your sleep window.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Hairline()
                        rules.forEach { rule ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    eventTitle(rule.eventType),
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f)
                                )
                                Switch(
                                    checked = rule.enabled,
                                    onCheckedChange = { on ->
                                        scope.launch {
                                            app.container.db.reminderDao()
                                                .upsertRule(rule.copy(enabled = on, userConfigured = true))
                                            app.container.reminderScheduler.planToday()
                                        }
                                    },
                                    colors = SwitchDefaults.colors(checkedTrackColor = DsTokens.Gold)
                                )
                            }
                        }
                        Hairline()
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Discreet notifications", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "Show only \"DhinaSuthra reminder\" on the lock screen",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Switch(
                                checked = privacyMinimal,
                                onCheckedChange = { on ->
                                    privacyMinimal = on
                                    settings.notificationPrivacy =
                                        if (on) NotificationPrivacy.MINIMAL else NotificationPrivacy.FULL
                                },
                                colors = SwitchDefaults.colors(checkedTrackColor = DsTokens.Gold)
                            )
                        }
                    }
                }
            }
        }

        item { SectionTitle("Privacy") }
        item {
            Reveal(4) {
                GlassCard(Modifier.fillMaxWidth(), tint = DsTokens.Green, interactive = false) {
                    CardBody(spacing = 6.dp) {
                        Text("What DhinaSuthra uses", style = MaterialTheme.typography.titleMedium)
                        Text("📍 Location — to recognise places and travel", style = MaterialTheme.typography.bodySmall)
                        Text("🚶 Movement — moving against stationary", style = MaterialTheme.typography.bodySmall)
                        Text("📱 Screen on/off — only to estimate sleep and inactivity", style = MaterialTheme.typography.bodySmall)
                        Text("🔋 Charging — a weak overnight signal", style = MaterialTheme.typography.bodySmall)
                        Text("🔔 Notifications — for your own routine prompts", style = MaterialTheme.typography.bodySmall)
                        Hairline()
                        Text(
                            "The app holds no INTERNET permission at all: it physically cannot send your data anywhere. No accounts, no analytics, no advertising identifiers, no model files, no cloud inference.",
                            style = MaterialTheme.typography.bodySmall.copy(color = DsTokens.Green)
                        )
                    }
                }
            }
        }

        item { SectionTitle("Personal") }
        item {
            Reveal(5) {
                GlassCard(Modifier.fillMaxWidth(), interactive = false) {
                    CardBody {
                        Text("What should DhinaSuthra call you?", style = MaterialTheme.typography.titleMedium)
                        OutlinedTextField(
                            value = name,
                            onValueChange = {
                                name = it.take(24)
                                settings.userName = name.trim()
                            },
                            label = { Text("Your name") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }

        item { SectionTitle("Your data") }
        item {
            Reveal(6) {
                GlassCard(Modifier.fillMaxWidth(), interactive = false) {
                    CardBody {
                        Text("You own everything DhinaSuthra learns", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Export it in full, or delete it in full. There is no copy anywhere else.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Row {
                            TextButton(onClick = { exportJsonLauncher.launch("dhinasuthra-export.json") }) {
                                Text("Export JSON", color = DsTokens.Cyan)
                            }
                            TextButton(onClick = { exportCsvLauncher.launch("dhinasuthra-events.csv") }) {
                                Text("Export CSV", color = DsTokens.Cyan)
                            }
                        }
                        TextButton(onClick = { showDeleteConfirm = true }) {
                            Text("Delete all my data", color = DsTokens.Rose)
                        }
                    }
                }
            }
        }

        if (BuildConfig.SIMULATOR_ENABLED) {
            item { SectionTitle("Developer") }
            item {
                GlassCard(Modifier.fillMaxWidth(), interactive = false) {
                    CardBody {
                        Text("Simulator", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Pushes weeks of realistic sensor signals through the real pipeline, so every screen you see is engine-derived rather than mocked. Debug builds only.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Row {
                            TextButton(onClick = {
                                statusLine = "Simulating 45 days…"
                                scope.launch {
                                    DaySimulator(ctx).loadDays(45)
                                    app.container.timeIntelligence.invalidate()
                                    vm.refresh(force = true)
                                    statusLine = "Simulation loaded. Open Today."
                                }
                            }) { Text("Load 45 days", color = DsTokens.Gold) }
                            TextButton(onClick = {
                                scope.launch {
                                    app.container.dataExporter.deleteAllData()
                                    app.container.correctionStore.clear()
                                    app.container.timeIntelligence.invalidate()
                                    vm.refresh(force = true)
                                    statusLine = "Cleared."
                                }
                            }) { Text("Clear", color = DsTokens.InkMuted) }
                        }
                    }
                }
            }
        }

        item {
            statusLine?.let {
                Text(it, style = MaterialTheme.typography.bodySmall.copy(color = DsTokens.Green))
            }
        }
        item {
            Column {
                Text(
                    "DhinaSuthra ${BuildConfig.VERSION_NAME} · a personal time instrument",
                    style = MaterialTheme.typography.labelSmall
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "${RuleBook.count} deterministic rules · zero network calls · everything derived on this device",
                    style = MaterialTheme.typography.labelSmall.copy(color = DsTokens.InkFaint)
                )
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            containerColor = DsTokens.Elevated,
            title = { Text("Delete everything?", color = DsTokens.Ink) },
            text = {
                Text(
                    "Every place, episode, pattern, routine and correction will be removed from this phone. This cannot be undone.",
                    color = DsTokens.InkSoft
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        app.container.dataExporter.deleteAllData()
                        app.container.correctionStore.clear()
                        app.container.timetableStore.save(emptyList())
                        app.container.timeIntelligence.invalidate()
                        vm.refresh(force = true)
                        statusLine = "All data deleted."
                        showDeleteConfirm = false
                    }
                }) { Text("Delete everything", color = DsTokens.Rose) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel", color = DsTokens.InkMuted)
                }
            }
        )
    }
}
