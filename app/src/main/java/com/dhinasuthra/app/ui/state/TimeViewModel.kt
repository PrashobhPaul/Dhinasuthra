package com.dhinasuthra.app.ui.state

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dhinasuthra.app.DhinaSuthraApp
import com.dhinasuthra.app.core.TimeUtils
import com.dhinasuthra.app.intelligence.ActivityType
import com.dhinasuthra.app.intelligence.Correction
import com.dhinasuthra.app.intelligence.DayReconstruction
import com.dhinasuthra.app.intelligence.LocationType
import com.dhinasuthra.app.intelligence.TimeEpisode
import com.dhinasuthra.app.intelligence.TimeIntelligenceRepository
import com.dhinasuthra.app.intelligence.Timetable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * One derived view of the world, shared by every screen.
 *
 * Reconstructing four months of days is real work, so it happens once here and
 * the tabs read from it. Anything that changes the underlying evidence — a
 * correction, a saved routine — invalidates and rebuilds.
 */
class TimeViewModel(private val app: DhinaSuthraApp) : ViewModel() {

    data class UiState(
        val loading: Boolean = true,
        val snapshot: TimeIntelligenceRepository.Snapshot? = null,
        val message: String? = null
    )

    private val repo = app.container.timeIntelligence
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh(force: Boolean = false) {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = _state.value.snapshot == null)
            val result = runCatching { repo.snapshot(force = force) }
            _state.value = result.fold(
                onSuccess = { UiState(loading = false, snapshot = it) },
                onFailure = { UiState(loading = false, snapshot = _state.value.snapshot, message = it.message) }
            )
        }
    }

    /** A day other than today, reconstructed on demand for timeline navigation. */
    suspend fun dayFor(epochDay: Long): DayReconstruction {
        val snap = _state.value.snapshot
        snap?.day(epochDay)?.let { return it }
        val patterns = snap?.patterns ?: com.dhinasuthra.app.intelligence.PatternIndex.empty()
        return repo.day(epochDay, patterns)
    }

    /**
     * Spec §42 — a correction is stored, replayed as evidence, and rebuilds the day.
     *
     * The times are parameters rather than being read off the episode, so
     * "actually I woke at 11:32" is expressible: the user can move a boundary,
     * not only relabel the block between two boundaries the engine chose.
     */
    fun correct(
        episode: TimeEpisode,
        activity: ActivityType,
        location: LocationType,
        startMin: Int = episode.startMin,
        endMin: Int = episode.endMin
    ) {
        viewModelScope.launch {
            app.container.correctionStore.add(
                Correction(
                    epochDay = episode.epochDay,
                    startMin = startMin,
                    endMin = endMin,
                    activity = activity,
                    location = location,
                    dayType = TimeUtils.dayType(episode.epochDay),
                    createdAt = System.currentTimeMillis()
                )
            )
            repo.invalidate()
            refresh(force = true)
            _state.value = _state.value.copy(
                message = "Saved. DhinaSuthra will use this when it sees a similar window."
            )
        }
    }

    fun addManualEpisode(
        epochDay: Long,
        startMin: Int,
        endMin: Int,
        activity: ActivityType,
        location: LocationType
    ) {
        viewModelScope.launch {
            app.container.correctionStore.add(
                Correction(
                    epochDay = epochDay,
                    startMin = startMin,
                    endMin = endMin,
                    activity = activity,
                    location = location,
                    dayType = TimeUtils.dayType(epochDay),
                    createdAt = System.currentTimeMillis()
                )
            )
            repo.invalidate()
            refresh(force = true)
            _state.value = _state.value.copy(message = "Added to your timeline.")
        }
    }

    fun saveTimetable(timetable: Timetable) {
        viewModelScope.launch {
            app.container.timetableStore.upsert(timetable)
            repo.invalidate()
            refresh(force = true)
            _state.value = _state.value.copy(
                message = if (timetable.active) "${timetable.name} is active." else "${timetable.name} saved."
            )
        }
    }

    fun deleteTimetable(id: String) {
        viewModelScope.launch {
            app.container.timetableStore.delete(id)
            repo.invalidate()
            refresh(force = true)
        }
    }

    fun clearMessage() {
        _state.value = _state.value.copy(message = null)
    }
}

@Composable
fun rememberTimeViewModel(): TimeViewModel {
    val context = LocalContext.current
    return viewModel(initializer = { TimeViewModel(DhinaSuthraApp.get(context)) })
}
