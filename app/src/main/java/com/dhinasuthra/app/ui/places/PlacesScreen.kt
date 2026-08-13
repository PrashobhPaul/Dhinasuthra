package com.dhinasuthra.app.ui.places

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
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
import com.dhinasuthra.app.DhinaSuthraApp
import com.dhinasuthra.app.core.TimeUtils
import com.dhinasuthra.app.core.database.PlaceEntity
import com.dhinasuthra.app.core.model.PlaceCategory
import com.dhinasuthra.app.intelligence.LensProjector
import com.dhinasuthra.app.intelligence.LocationType
import com.dhinasuthra.app.ui.foundation.CardBody
import com.dhinasuthra.app.ui.foundation.DsChip
import com.dhinasuthra.app.ui.foundation.DsScreen
import com.dhinasuthra.app.ui.foundation.GlassCard
import com.dhinasuthra.app.ui.foundation.Hairline
import com.dhinasuthra.app.ui.foundation.Reveal
import com.dhinasuthra.app.ui.foundation.SectionTitle
import com.dhinasuthra.app.ui.foundation.StatTile
import com.dhinasuthra.app.ui.state.rememberTimeViewModel
import com.dhinasuthra.app.ui.theme.DsTokens
import com.dhinasuthra.app.ui.viz.Meter
import kotlinx.coroutines.launch

/**
 * Places = time by place (spec §54).
 *
 * Strictly location-oriented. There is no activity total anywhere on this screen:
 * "Home 15h 05m" belongs here, "Sleep 8h 15m" does not.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlacesScreen(onBack: (() -> Unit)? = null) {
    val ctx = LocalContext.current
    val app = DhinaSuthraApp.get(ctx)
    val scope = rememberCoroutineScope()
    val vm = rememberTimeViewModel()
    val state by vm.state.collectAsState()

    var editing by remember { mutableStateOf<PlaceEntity?>(null) }
    var saveStatus by remember { mutableStateOf<String?>(null) }

    val places by app.container.db.placeDao().observeAll().collectAsState(initial = emptyList())

    // Derived once here: the lazy-list content lambda below is not a composable scope.
    val recentDays = state.snapshot?.history?.takeLast(30).orEmpty()
    val locationSlices = remember(recentDays) { LensProjector.locationLens(recentDays) }

    DsScreen(
        title = "Places",
        subtitle = "Where your time happens — and only that",
        trailing = onBack?.let { back ->
            {
                Text(
                    "Back",
                    style = MaterialTheme.typography.labelLarge.copy(color = DsTokens.Gold),
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(DsTokens.Hairline)
                        .clickable { back() }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }
    ) {
        // Time by location across the recent window.
        if (recentDays.isNotEmpty()) {
            val days = recentDays
            val slices = locationSlices
            val total = slices.sumOf { it.minutes }.coerceAtLeast(1)
            item { SectionTitle("Last ${days.size} days") }
            item {
                Reveal(0) {
                    GlassCard(Modifier.fillMaxWidth(), interactive = false) {
                        CardBody {
                            slices.take(8).forEach { slice ->
                                Column {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            "${slice.location.icon}  ${slice.label}",
                                            style = MaterialTheme.typography.titleSmall,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Text(
                                            "${TimeUtils.formatDurationMin(slice.minutes / days.size.coerceAtLeast(1))}/day",
                                            style = MaterialTheme.typography.labelMedium
                                        )
                                    }
                                    Spacer(Modifier.height(5.dp))
                                    Meter(
                                        slice.minutes.toFloat() / total,
                                        color = DsTokens.colorFor(slice.location)
                                    )
                                    Spacer(Modifier.height(9.dp))
                                }
                            }
                            Text(
                                "These are presence totals. What you were doing at each place lives under the Activity @ Place lens in Time Lab.",
                                style = MaterialTheme.typography.labelSmall.copy(color = DsTokens.InkFaint)
                            )
                        }
                    }
                }
            }
        }

        item { SectionTitle("Add a place") }
        item {
            Reveal(1) {
                GlassCard(Modifier.fillMaxWidth(), interactive = false) {
                    CardBody {
                        Text("Save where you are now", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Mark your current location as home, the office, or anywhere else. Entirely offline — no map tiles are fetched and no address is looked up.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        TextButton(onClick = {
                            saveStatus = "Getting your location…"
                            scope.launch {
                                val fix = app.container.locationProvider.currentFix()
                                    ?: app.container.locationProvider.lastKnown()
                                if (fix == null) {
                                    saveStatus = "Couldn't get a fix. Check the location permission and that GPS is on."
                                } else {
                                    val id = app.container.db.placeDao().upsert(
                                        PlaceEntity(
                                            name = "New place", category = PlaceCategory.UNLABELED,
                                            lat = fix.first.lat, lon = fix.first.lon, radiusM = 150f,
                                            confidence = 0.6f, firstSeen = System.currentTimeMillis(),
                                            lastSeen = System.currentTimeMillis(), visitCount = 1,
                                            confirmed = false, visitDays = 1
                                        )
                                    )
                                    saveStatus = null
                                    editing = app.container.db.placeDao().byId(id)
                                }
                            }
                        }) { Text("Save current location", color = DsTokens.Gold) }
                        saveStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }

        val suggestions = places.filter { !it.confirmed && it.visitDays >= 3 }
        if (suggestions.isNotEmpty()) {
            item { SectionTitle("Looks familiar", "${suggestions.size}") }
            items(suggestions.size) { index ->
                val place = suggestions[index]
                GlassCard(Modifier.fillMaxWidth(), tint = DsTokens.Cyan, onClick = { editing = place }) {
                    CardBody {
                        Text(
                            "Somewhere you visited on ${place.visitDays} different days",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            "DhinaSuthra won't name it for you — tap to tell it what this place is.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }

        item { SectionTitle("Known places") }
        val known = places.filter { it.confirmed }
        if (known.isEmpty()) {
            item {
                GlassCard(Modifier.fillMaxWidth(), interactive = false) {
                    CardBody {
                        Text(
                            "No confirmed places yet. Save your current location, or wait for DhinaSuthra to notice somewhere you keep returning to.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
        items(known.size) { index ->
            val place = known[index]
            Reveal(index.coerceAtMost(5)) {
                PlaceCard(
                    place = place,
                    minutesInWindow = locationSlices
                        .firstOrNull { it.placeName == place.name }?.minutes,
                    onEdit = { editing = place }
                )
            }
        }
    }

    editing?.let { place ->
        PlaceEditSheet(
            place = place,
            onDismiss = { editing = null },
            onSave = { updated ->
                scope.launch {
                    app.container.db.placeDao().update(updated)
                    app.container.sensorPolicyEngine.applyCurrentPolicy()
                    app.container.timeIntelligence.invalidate()
                    vm.refresh(force = true)
                    editing = null
                }
            },
            onDelete = {
                scope.launch {
                    app.container.db.placeDao().delete(place.id)
                    app.container.sensorPolicyEngine.applyCurrentPolicy()
                    app.container.timeIntelligence.invalidate()
                    vm.refresh(force = true)
                    editing = null
                }
            }
        )
    }
}

@Composable
private fun PlaceCard(place: PlaceEntity, minutesInWindow: Int?, onEdit: () -> Unit) {
    GlassCard(
        Modifier.fillMaxWidth().animateContentSize(),
        tint = DsTokens.colorFor(locationTypeOf(place.category)),
        onClick = onEdit
    ) {
        CardBody {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(categoryEmoji(place.category), style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Text(place.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${categoryLabel(place.category)} · seen on ${place.visitDays} days · last ${relativeDay(place.lastSeen)}",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
            if (minutesInWindow != null && minutesInWindow > 0) {
                Hairline()
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile(
                        "Total", TimeUtils.formatDurationMin(minutesInWindow),
                        Modifier.weight(1f), caption = "last 30 days"
                    )
                    StatTile(
                        "Per visit",
                        TimeUtils.formatDurationMin(minutesInWindow / place.visitDays.coerceAtLeast(1)),
                        Modifier.weight(1f), accent = DsTokens.Cyan, caption = "average"
                    )
                    StatTile(
                        "Frequency", "${place.visitDays}d",
                        Modifier.weight(1f), accent = DsTokens.Violet, caption = "distinct days"
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaceEditSheet(
    place: PlaceEntity,
    onDismiss: () -> Unit,
    onSave: (PlaceEntity) -> Unit,
    onDelete: () -> Unit
) {
    var name by remember { mutableStateOf(if (place.name == "New place") "" else place.name) }
    var category by remember { mutableStateOf(place.category) }
    val categories = listOf(
        PlaceCategory.HOME, PlaceCategory.OFFICE, PlaceCategory.CHURCH,
        PlaceCategory.FRIEND, PlaceCategory.RELATIVE, PlaceCategory.RESTAURANT,
        PlaceCategory.GYM, PlaceCategory.SCHOOL, PlaceCategory.OTHER
    )

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = DsTokens.Elevated) {
        Column(
            Modifier.padding(horizontal = DsTokens.ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(DsTokens.GapM)
        ) {
            Text("What is this place?", style = MaterialTheme.typography.headlineSmall)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                categories.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { option ->
                            DsChip(
                                label = "${categoryEmoji(option)} ${categoryLabel(option)}",
                                selected = category == option,
                                onClick = { category = option },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(40) },
                label = { Text("Name (for example, Amma's house)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = onDelete) { Text("Forget place", color = DsTokens.Rose) }
                Row {
                    TextButton(onClick = onDismiss) { Text("Cancel", color = DsTokens.InkMuted) }
                    TextButton(onClick = {
                        onSave(
                            place.copy(
                                name = name.ifBlank { categoryLabel(category) },
                                category = category,
                                confirmed = true,
                                confidence = maxOf(place.confidence, 0.9f)
                            )
                        )
                    }) { Text("Save", color = DsTokens.Gold) }
                }
            }
            Spacer(Modifier.height(DsTokens.GapL))
        }
    }
}

fun locationTypeOf(category: PlaceCategory): LocationType = when (category) {
    PlaceCategory.HOME -> LocationType.HOME
    PlaceCategory.OFFICE -> LocationType.OFFICE
    PlaceCategory.GYM -> LocationType.GYM
    PlaceCategory.RESTAURANT -> LocationType.RESTAURANT
    PlaceCategory.FRIEND, PlaceCategory.RELATIVE -> LocationType.FRIEND
    else -> LocationType.OTHER_KNOWN
}

fun categoryLabel(c: PlaceCategory): String = when (c) {
    PlaceCategory.HOME -> "Home"
    PlaceCategory.OFFICE -> "Office"
    PlaceCategory.CHURCH -> "Religious place"
    PlaceCategory.FRIEND -> "Friend"
    PlaceCategory.RELATIVE -> "Relative"
    PlaceCategory.RESTAURANT -> "Restaurant"
    PlaceCategory.GYM -> "Gym"
    PlaceCategory.SCHOOL -> "School"
    PlaceCategory.OTHER -> "Other"
    PlaceCategory.UNLABELED -> "Unnamed"
}

fun categoryEmoji(c: PlaceCategory): String = when (c) {
    PlaceCategory.HOME -> "🏠"
    PlaceCategory.OFFICE -> "🏢"
    PlaceCategory.CHURCH -> "⛪"
    PlaceCategory.FRIEND -> "👥"
    PlaceCategory.RELATIVE -> "👨‍👩‍👧"
    PlaceCategory.RESTAURANT -> "🍽"
    PlaceCategory.GYM -> "💪"
    PlaceCategory.SCHOOL -> "🎓"
    PlaceCategory.OTHER -> "📍"
    PlaceCategory.UNLABELED -> "❔"
}

private fun relativeDay(ts: Long): String {
    val days = TimeUtils.epochDay() - TimeUtils.epochDay(java.time.Instant.ofEpochMilli(ts))
    return when {
        days <= 0L -> "today"
        days == 1L -> "yesterday"
        else -> "$days days ago"
    }
}
