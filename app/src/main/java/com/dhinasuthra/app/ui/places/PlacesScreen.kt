package com.dhinasuthra.app.ui.places

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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.dhinasuthra.app.DhinaSuthraApp
import com.dhinasuthra.app.core.TimeUtils
import com.dhinasuthra.app.core.database.PlaceEntity
import com.dhinasuthra.app.core.model.PlaceCategory
import com.dhinasuthra.app.ui.components.TiltCard
import com.dhinasuthra.app.ui.theme.Ds
import kotlinx.coroutines.launch

/**
 * Places (product.md §25 + experience spec §49P): local place intelligence with
 * zero internet dependency. Save current location as a place (§49P.1), custom
 * names independent of category (§49P.3), correction (§49P.8), deletion
 * (§49P.11), discovery suggestions (§49P.4), semantic names over raw
 * coordinates (§49P.14).
 */
@Composable
fun PlacesScreen() {
    val ctx = LocalContext.current
    val app = DhinaSuthraApp.get(ctx)
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<PlaceEntity?>(null) }
    var saveHereState by remember { mutableStateOf<String?>(null) }   // null=idle, else status text

    val places by app.container.db.placeDao().observeAll().collectAsState(initial = emptyList())

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp)
    ) {
        Spacer(Modifier.height(10.dp))
        Text("Places", style = MaterialTheme.typography.headlineMedium)
        Text("Your places stay on your phone", style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(14.dp))

        // §49P.1 / §49P.23 — save current location, fully offline.
        TiltCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Save this location", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Mark where you are right now as Home, Office, or any place you choose. Works without internet.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = {
                    saveHereState = "Getting your location…"
                    scope.launch {
                        val fix = app.container.locationProvider.currentFix()
                            ?: app.container.locationProvider.lastKnown()
                        if (fix == null) {
                            saveHereState = "Couldn't get a location fix. Check location permission and GPS."
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
                            saveHereState = null
                            editing = app.container.db.placeDao().byId(id)
                        }
                    }
                }) { Text("Save current location", color = Ds.Amber) }
                saveHereState?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
        Spacer(Modifier.height(14.dp))

        // §49P.4 discovery suggestions
        val suggestions = places.filter { !it.confirmed && it.visitDays >= 3 }
        if (suggestions.isNotEmpty()) {
            Text("Looks familiar", style = MaterialTheme.typography.titleMedium)
            Text("You visit these often. What should DhinaSuthra call them?", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            suggestions.forEach { p ->
                TiltCard(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Visited on ${p.visitDays} days", style = MaterialTheme.typography.titleSmall)
                            Text("Tap to name this place", style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { editing = p }) { Text("Name it", color = Ds.Amber) }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            Spacer(Modifier.height(8.dp))
        }

        Text("Known places", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        val known = places.filter { it.confirmed }
        if (known.isEmpty()) {
            Text(
                "No confirmed places yet. Save your current location, or wait for DhinaSuthra to notice recurring ones.",
                style = MaterialTheme.typography.bodySmall
            )
        }
        known.forEach { p ->
            TiltCard(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(categoryEmoji(p.category), style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.padding(6.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.name, style = MaterialTheme.typography.titleSmall)
                        Text(
                            "${categoryLabel(p.category)} · ${p.visitDays} days · last ${relativeDay(p.lastSeen)}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    TextButton(onClick = { editing = p }) { Text("Edit", color = Ds.Active) }
                }
            }
            Spacer(Modifier.height(10.dp))
        }
        Spacer(Modifier.height(90.dp))
    }

    editing?.let { place ->
        PlaceEditSheet(
            place = place,
            onDismiss = { editing = null },
            onSave = { updated ->
                scope.launch {
                    app.container.db.placeDao().update(updated)
                    app.container.sensorPolicyEngine.applyCurrentPolicy()   // §49P.17 regenerate geofences
                    editing = null
                }
            },
            onDelete = {
                scope.launch {
                    app.container.db.placeDao().delete(place.id)
                    app.container.sensorPolicyEngine.applyCurrentPolicy()
                    editing = null
                }
            }
        )
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

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Ds.NavyRaised) {
        Column(Modifier.padding(horizontal = 18.dp)) {
            Text("What is this place?", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(10.dp))
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.height(150.dp)
            ) {
                items(categories) { c ->
                    FilterChip(
                        selected = category == c,
                        onClick = { category = c },
                        label = { Text("${categoryEmoji(c)} ${categoryLabel(c)}") }
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(40) },
                label = { Text("Name (e.g. Rahul's House)") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = onDelete) { Text("Forget place", color = Ds.Warm) }
                Row {
                    TextButton(onClick = onDismiss) { Text("Cancel", color = Ds.Muted) }
                    TextButton(onClick = {
                        onSave(
                            place.copy(
                                name = name.ifBlank { categoryLabel(category) },
                                category = category,
                                confirmed = true,
                                confidence = maxOf(place.confidence, 0.9f)   // §49P.13 user confirmation
                            )
                        )
                    }) { Text("Save", color = Ds.Amber) }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
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
