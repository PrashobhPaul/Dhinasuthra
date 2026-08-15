package com.dhinasuthra.app.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.dhinasuthra.app.BuildConfig
import com.dhinasuthra.app.DhinaSuthraApp
import com.dhinasuthra.app.core.model.NotificationPrivacy
import com.dhinasuthra.app.simulate.DaySimulator
import com.dhinasuthra.app.ui.foundation.CardBody
import android.Manifest
import android.content.Intent
import android.provider.Settings
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.dhinasuthra.app.activity.CallNotificationListener
import com.dhinasuthra.app.activity.UsageStatsSource
import com.dhinasuthra.app.core.database.DatabaseGuardian
import com.dhinasuthra.app.core.database.DatabaseStatus
import com.dhinasuthra.app.ui.foundation.DsScreen
import com.dhinasuthra.app.ui.foundation.GlassCard
import com.dhinasuthra.app.ui.foundation.Hairline
import com.dhinasuthra.app.ui.foundation.Reveal
import com.dhinasuthra.app.ui.foundation.SectionTitle
import com.dhinasuthra.app.ui.places.PlacesScreen
import com.dhinasuthra.app.ui.state.rememberTimeViewModel
import com.dhinasuthra.app.ui.theme.DsTokens
import com.dhinasuthra.app.ui.today.eventTitle
import com.dhinasuthra.app.work.Workers
import kotlinx.coroutines.launch

private enum class SettingsPage { ROOT, PLACES }

/**
 * Settings — reached from the gear on Today rather than a tab of its own.
 *
 * Spec §48: don't spend a tab on something people open twice a month. Places
 * lives here too, since it is a reference list rather than a daily destination.
 */
@Composable
fun SettingsScreen(onBack: () -> Unit = {}) {
    var page by remember { mutableStateOf(SettingsPage.ROOT) }

    when (page) {
        SettingsPage.PLACES -> PlacesScreen(onBack = { page = SettingsPage.ROOT })
        SettingsPage.ROOT -> SettingsRoot(onOpen = { page = it }, onBack = onBack)
    }
}

@Composable
private fun SettingsRoot(onOpen: (SettingsPage) -> Unit, onBack: () -> Unit) {
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
    var callsOn by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CALL_LOG) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    // Special accesses are granted on Android's own screens, not by a dialog, so
    // the switches re-read the real state when the user comes back rather than
    // assuming the trip succeeded.
    var usageOn by remember { mutableStateOf(UsageStatsSource.hasUsageAccess(ctx)) }
    var appCallsOn by remember { mutableStateOf(CallNotificationListener.isEnabled(ctx)) }

    val usageAccess = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        usageOn = UsageStatsSource.hasUsageAccess(ctx)
        app.container.activityIntelligence.invalidatePolling()
        if (usageOn) {
            scope.launch {
                app.container.activityIntelligence.refreshRecent(7)
                statusLine = "Your viewing time is on your timeline."
            }
        }
    }
    val notificationAccess = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        appCallsOn = CallNotificationListener.isEnabled(ctx)
        // Nothing to backfill: notifications are only visible as they happen,
        // so this starts counting from now rather than pretending otherwise.
        statusLine = if (appCallsOn) "WhatsApp and Teams calls will appear from now on." else null
    }

    val callPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        callsOn = granted[Manifest.permission.READ_CALL_LOG] == true
        if (callsOn) {
            // Backfill immediately, so turning it on shows a fortnight of calls
            // rather than an empty promise.
            scope.launch {
                app.container.activityIntelligence.refreshRecent(14)
                statusLine = "Your calls are on your timeline."
            }
        }
    }

    val rules by app.container.db.reminderDao().observeRules().collectAsState(initial = emptyList())

    // Hoisted out of the list: DsScreen's content lambda is a LazyListScope, not
    // a composable scope, so remember cannot be called inside it.
    val dataSafetySummary = remember {
        val guarded = app.container.database
        val backups = DatabaseGuardian.backups(ctx).size
        val kept = if (backups == 1) "A copy of it is" else "$backups copies of it are"
        when (guarded.status) {
            DatabaseStatus.SAFE_MODE ->
                "Something went wrong updating your history, so DhinaSuthra left it completely untouched " +
                    "rather than risk it. Nothing was deleted. Reinstalling the previous version will " +
                    "open it again."
            DatabaseStatus.MIGRATED, DatabaseStatus.RECOVERED ->
                "This update moved your history forward and checked every record afterwards. " +
                    "$kept still saved on this device, just in case."
            else ->
                if (backups == 0) "Before any update changes your history, DhinaSuthra copies it first."
                else "$kept saved on this device from the last update, in case anything ever needs going back."
        }
    }

    val exportJsonLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> uri?.let { scope.launch { app.container.dataExporter.exportJson(it); statusLine = "Exported JSON." } } }
    val exportCsvLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri -> uri?.let { scope.launch { app.container.dataExporter.exportCsv(it); statusLine = "Exported CSV." } } }

    DsScreen(
        title = "Settings",
        subtitle = "Your places, your prompts, your data",
        trailing = {
            Text(
                "Done",
                style = MaterialTheme.typography.labelLarge.copy(color = DsTokens.Gold),
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(DsTokens.Hairline)
                    .clickable { onBack() }
                    .padding(horizontal = 14.dp, vertical = 7.dp)
            )
        }
    ) {

        item { SectionTitle("Your places") }
        item {
            Reveal(0) {
                GlassCard(Modifier.fillMaxWidth(), tint = DsTokens.Cyan, onClick = { onOpen(SettingsPage.PLACES) }) {
                    CardBody {
                        Text("📍  Places", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Where your time happens. Name the places you keep returning to so DhinaSuthra can talk about them properly.",
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

        // Plan §27: the safety net is worth showing. A user who is asked to
        // trust an app with years of their life should be able to see that the
        // app takes a copy before it changes anything, and that the copy is
        // still there.
        // The one thing the app cannot work out on its own: who you spoke to.
        // Off until asked for, and honest about what it can and cannot see.
        item { SectionTitle("Phone calls") }
        item {
            Reveal(6) {
                GlassCard(Modifier.fillMaxWidth(), tint = DsTokens.Cyan, interactive = false) {
                    CardBody {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Put your calls on your day", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    if (callsOn)
                                        "Answered calls appear on your timeline with who you spoke to. Calls you missed are left off."
                                    else
                                        "DhinaSuthra can show the calls you actually took, and who they were with. It reads nothing until you say yes.",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Switch(
                                checked = callsOn,
                                onCheckedChange = { wanted ->
                                    if (wanted) {
                                        callPermission.launch(
                                            arrayOf(
                                                Manifest.permission.READ_CALL_LOG,
                                                Manifest.permission.READ_CONTACTS
                                            )
                                        )
                                    } else {
                                        callsOn = false
                                        statusLine =
                                            "Turn it off fully in Android Settings › Apps › DhinaSuthra › Permissions."
                                    }
                                }
                            )
                        }
                        Text(
                            "Video and internet calls from other apps aren't visible to any app but their own, so those won't appear. Nothing here can leave your phone.",
                            style = MaterialTheme.typography.labelSmall.copy(color = DsTokens.InkFaint)
                        )
                    }
                }
            }
        }

        // Two things a phone can only know if the user opens a door for it.
        // Both are off, both are described in terms of what appears on the
        // timeline rather than what is technically read.
        item { SectionTitle("What you were watching") }
        item {
            Reveal(6) {
                GlassCard(Modifier.fillMaxWidth(), tint = DsTokens.Violet, interactive = false) {
                    CardBody {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("TV and viewing time", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    if (usageOn)
                                        "Your evenings in front of the TV show up on your timeline, named by what was playing."
                                    else
                                        "DhinaSuthra can see when you were using a TV remote or watching something, and put it on your day.",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Switch(
                                checked = usageOn,
                                onCheckedChange = {
                                    runCatching {
                                        usageAccess.launch(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                                    }.onFailure {
                                        statusLine = "Open Settings › Apps › Special access › Usage access."
                                    }
                                },
                                colors = SwitchDefaults.colors(checkedTrackColor = DsTokens.Gold)
                            )
                        }
                        Text(
                            "Android grants this on its own screen. Only remote and video apps are ever read — everything else you open stays invisible to DhinaSuthra, and none of it can leave the phone.",
                            style = MaterialTheme.typography.labelSmall.copy(color = DsTokens.InkFaint)
                        )
                    }
                }
            }
        }

        item { SectionTitle("WhatsApp and Teams calls") }
        item {
            Reveal(6) {
                GlassCard(Modifier.fillMaxWidth(), tint = DsTokens.Cyan, interactive = false) {
                    CardBody {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Calls from other apps", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    if (appCallsOn)
                                        "WhatsApp calls appear as calls, and Teams calls as meetings, for as long as you were actually connected."
                                    else
                                        "These never reach the phone's call log, so DhinaSuthra needs notification access to notice them at all.",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Switch(
                                checked = appCallsOn,
                                onCheckedChange = {
                                    runCatching {
                                        notificationAccess.launch(
                                            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                                        )
                                    }.onFailure {
                                        statusLine = "Open Settings › Notifications › Notification access."
                                    }
                                },
                                colors = SwitchDefaults.colors(checkedTrackColor = DsTokens.Gold)
                            )
                        }
                        Text(
                            "It reads no notification text of any kind — not the sender, not a word of a message. Only which of four call apps rang, whether the call clock was running, and when. Calls you didn't answer are left off your day.",
                            style = MaterialTheme.typography.labelSmall.copy(color = DsTokens.InkFaint)
                        )
                    }
                }
            }
        }

        item { SectionTitle("Updates") }
        item {
            Reveal(6) {
                GlassCard(Modifier.fillMaxWidth(), tint = DsTokens.Green, interactive = false) {
                    CardBody {
                        Text("Your history survives every update", style = MaterialTheme.typography.titleMedium)
                        Text(
                            dataSafetySummary,
                            style = MaterialTheme.typography.bodySmall
                        )
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
                    "Zero network calls · everything worked out on this device",
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
