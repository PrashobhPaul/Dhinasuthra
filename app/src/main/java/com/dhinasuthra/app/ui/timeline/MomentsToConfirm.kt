package com.dhinasuthra.app.ui.timeline

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dhinasuthra.app.activity.ActivityCatalog
import com.dhinasuthra.app.activity.EvidenceItem
import com.dhinasuthra.app.activity.StoredActivity
import com.dhinasuthra.app.core.TimeUtils
import com.dhinasuthra.app.intelligence.ConfidenceBand
import com.dhinasuthra.app.ui.foundation.CardBody
import com.dhinasuthra.app.ui.foundation.DsChip
import com.dhinasuthra.app.ui.foundation.GlassCard
import com.dhinasuthra.app.ui.foundation.Hairline
import com.dhinasuthra.app.ui.foundation.SectionTitle
import com.dhinasuthra.app.ui.state.ActivityViewModel
import com.dhinasuthra.app.ui.theme.DsTokens
import java.time.Instant
import java.time.ZoneId

/**
 * "Moments to confirm" (plan §7, §18, §20, §21).
 *
 * This is where the app stops asserting and starts asking. Everything here is
 * provisional: DhinaSuthra thinks you had lunch at 12:52, and would like to
 * know. One tap says yes and it becomes history; the alternatives are one tap
 * away too.
 *
 * Two deliberate choices:
 *
 *  - **Nothing is auto-accepted.** A finding stays a question until answered.
 *  - **Every finding can explain itself.** Tapping opens the reasons in plain
 *    language — plan §18's point that the timeline has to earn trust. What it
 *    never shows is the machinery behind them: the user wants the finding, not
 *    the workings.
 */
@Composable
fun MomentsToConfirm(vm: ActivityViewModel, pending: List<StoredActivity>) {
    if (pending.isEmpty()) return

    var open by remember { mutableStateOf<StoredActivity?>(null) }

    Column(Modifier.fillMaxWidth().animateContentSize()) {
        SectionTitle("Moments to confirm", trailing = "${pending.size}")
        Spacer(Modifier.height(DsTokens.GapS))
        pending.take(6).forEach { item ->
            PendingCard(
                item = item,
                onOpen = {
                    vm.loadEvidence(item.id)
                    open = item
                },
                onConfirm = { vm.confirm(item) },
                onDismiss = { vm.ignore(item) }
            )
            Spacer(Modifier.height(DsTokens.GapS))
        }
    }

    open?.let { item ->
        ActivitySheet(
            item = item,
            vm = vm,
            onDismiss = { open = null }
        )
    }
}

@Composable
private fun PendingCard(
    item: StoredActivity,
    onOpen: () -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val color = DsTokens.colorFor(ActivityCatalog.toActivityType(item.activityCode))
    GlassCard(Modifier.fillMaxWidth(), tint = color, onClick = onOpen, onLongClick = onOpen) {
        CardBody(padding = 15.dp, spacing = 8.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item.icon, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.headline(), style = MaterialTheme.typography.titleSmall)
                    Text(item.rangeLabel(), style = MaterialTheme.typography.labelSmall)
                }
                Box(
                    Modifier
                        .size(9.dp)
                        .background(color.copy(alpha = 0.85f), CircleShape)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DsChip(label = "Yes, that's right", selected = true, onClick = onConfirm)
                DsChip(label = "Change", selected = false, onClick = onOpen)
                DsChip(label = "Not this", selected = false, onClick = onDismiss)
            }
        }
    }
}

/**
 * The detail sheet: why we think so, then everything the user can do about it.
 *
 * Plan §21 lists the actions — change the activity, change the start, change the
 * end, split, ignore, confirm. They live behind a tap rather than a long-press
 * menu because a long press is undiscoverable and this is the screen where being
 * able to disagree is the whole point.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ActivitySheet(
    item: StoredActivity,
    vm: ActivityViewModel,
    onDismiss: () -> Unit
) {
    val state by vm.state.collectAsState()
    val evidence = state.evidence[item.id].orEmpty()
    var picking by remember { mutableStateOf<Picking?>(null) }

    LaunchedEffect(item.id) { vm.loadEvidence(item.id) }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = DsTokens.Elevated) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = DsTokens.ScreenPadding)
                .padding(bottom = 34.dp),
            verticalArrangement = Arrangement.spacedBy(DsTokens.GapM)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item.icon, style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.headline(), style = MaterialTheme.typography.titleMedium)
                    Text(item.rangeLabel(), style = MaterialTheme.typography.labelMedium)
                }
            }

            if (evidence.isNotEmpty()) {
                Hairline()
                Text("Why we think so", style = MaterialTheme.typography.labelLarge)
                evidence.reasons().forEach { line ->
                    Row(verticalAlignment = Alignment.Top) {
                        Text("·", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.width(8.dp))
                        Text(line, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            Hairline()
            Text("Is that right?", style = MaterialTheme.typography.labelLarge)

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DsChip(label = "Confirm", selected = true, onClick = { vm.confirm(item); onDismiss() })
                DsChip(label = "Change what it was", selected = false, onClick = { picking = Picking.Activity })
                DsChip(label = "Change the start", selected = false, onClick = { picking = Picking.Start })
                if (item.end != null && item.end != item.start) {
                    DsChip(label = "Change the end", selected = false, onClick = { picking = Picking.End })
                    DsChip(label = "Split in two", selected = false, onClick = { picking = Picking.Split })
                }
                DsChip(label = "Didn't happen", selected = false, onClick = { vm.ignore(item); onDismiss() })
            }
        }
    }

    when (picking) {
        Picking.Activity -> ActivityPicker(
            current = item.activityCode,
            onDismiss = { picking = null },
            onPick = { code ->
                vm.correct(item, activityCode = code)
                picking = null
                onDismiss()
            }
        )
        Picking.Start, Picking.End, Picking.Split -> {
            val mode = picking!!
            TimeCorrectionSheet(
                title = when (mode) {
                    Picking.Start -> "When did it actually start?"
                    Picking.End -> "When did it actually end?"
                    else -> "Where should it split?"
                },
                initial = if (mode == Picking.End) item.end ?: item.start else item.start,
                onDismiss = { picking = null },
                onPick = { at ->
                    when (mode) {
                        Picking.Start -> vm.correct(item, start = at)
                        Picking.End -> vm.correct(item, end = at)
                        else -> vm.split(item, at)
                    }
                    picking = null
                    onDismiss()
                }
            )
        }
        null -> Unit
    }
}

private enum class Picking { Activity, Start, End, Split }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ActivityPicker(
    current: String,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = DsTokens.Elevated) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = DsTokens.ScreenPadding)
                .padding(bottom = 34.dp),
            verticalArrangement = Arrangement.spacedBy(DsTokens.GapM)
        ) {
            Text("What was it really?", style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CORRECTABLE.forEach { code ->
                    DsChip(
                        label = "${ActivityCatalog.iconFor(code)}  ${ActivityCatalog.labelFor(code)}",
                        selected = code == current,
                        onClick = { onPick(code) }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeCorrectionSheet(
    title: String,
    initial: Long,
    onDismiss: () -> Unit,
    onPick: (Long) -> Unit
) {
    val local = Instant.ofEpochMilli(initial).atZone(ZoneId.systemDefault())
    val picker = rememberTimePickerState(
        initialHour = local.hour,
        initialMinute = local.minute,
        is24Hour = true
    )
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = DsTokens.Elevated) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = DsTokens.ScreenPadding)
                .padding(bottom = 34.dp),
            verticalArrangement = Arrangement.spacedBy(DsTokens.GapM),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            TimePicker(state = picker)
            DsChip(
                label = "Save",
                selected = true,
                onClick = {
                    val corrected = local
                        .withHour(picker.hour)
                        .withMinute(picker.minute)
                        .withSecond(0)
                        .toInstant()
                        .toEpochMilli()
                    onPick(corrected)
                }
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Copy
// ---------------------------------------------------------------------------

/** The activities a person can reasonably reassign a finding to. */
private val CORRECTABLE = listOf(
    ActivityCatalog.WAKE,
    ActivityCatalog.LUNCH,
    ActivityCatalog.TEA_BREAK,
    ActivityCatalog.MEETING,
    ActivityCatalog.WATCHING_TV,
    ActivityCatalog.PHONE_CALL,
    ActivityCatalog.EXERCISE,
    ActivityCatalog.PERSONAL,
    ActivityCatalog.INTERRUPTION
)

/**
 * "Likely Lunch · 89%" — the hedge scales with the confidence, so the app never
 * sounds more certain than it is.
 */
private fun StoredActivity.headline(): String {
    val band = ConfidenceBand.of(confidence)
    val prefix = when (band) {
        ConfidenceBand.CONFIDENT -> ""
        ConfidenceBand.LIKELY -> "Likely "
        ConfidenceBand.POSSIBLE -> "Possibly "
        ConfidenceBand.UNCERTAIN -> "Maybe "
    }
    return "$prefix$label"
}

private fun StoredActivity.rangeLabel(): String {
    val startMin = TimeUtils.minuteOfDay(Instant.ofEpochMilli(start))
    val endMin = end?.let { TimeUtils.minuteOfDay(Instant.ofEpochMilli(it)) }
    return if (endMin == null || endMin == startMin) {
        TimeUtils.formatMinuteOfDay(startMin)
    } else {
        "${TimeUtils.formatMinuteOfDay(startMin)}–${TimeUtils.formatMinuteOfDay(endMin)} · " +
            TimeUtils.formatDurationMin(durationMin)
    }
}

/** Strongest first, de-duplicated, four at most: enough to convince, not a wall. */
private fun List<EvidenceItem>.reasons(): List<String> =
    filter { it.weight > 0f }
        .sortedByDescending { it.weight }
        .map { it.detail }
        .distinct()
        .take(4)
