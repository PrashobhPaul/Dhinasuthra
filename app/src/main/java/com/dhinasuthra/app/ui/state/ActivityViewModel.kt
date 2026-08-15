package com.dhinasuthra.app.ui.state

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dhinasuthra.app.DhinaSuthraApp
import com.dhinasuthra.app.activity.ActivityCatalog
import com.dhinasuthra.app.activity.EvidenceItem
import com.dhinasuthra.app.activity.StoredActivity
import com.dhinasuthra.app.core.TimeUtils
import com.dhinasuthra.app.intelligence.Correction
import com.dhinasuthra.app.intelligence.LocationType
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * State for the things DhinaSuthra has noticed but not yet been told it got right.
 *
 * Kept apart from [TimeViewModel] on purpose. That one reconstructs the whole
 * day and is expensive; this one is a short list that changes every time the
 * user taps Yes, and it needs to feel instant.
 */
class ActivityViewModel(private val app: DhinaSuthraApp) : ViewModel() {

    data class UiState(
        val pending: List<StoredActivity> = emptyList(),
        val evidence: Map<Long, List<EvidenceItem>> = emptyMap(),
        val busy: Boolean = false,
        /** A one-line acknowledgement after an action, cleared when read. */
        val message: String? = null
    )

    private val intelligence = app.container.activityIntelligence
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            intelligence.observePending().collect { pending ->
                _state.value = _state.value.copy(pending = pending)
            }
        }
        refresh()
    }

    fun refresh(epochDay: Long = TimeUtils.epochDay()) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            runCatching { intelligence.refresh(epochDay) }
            _state.value = _state.value.copy(busy = false)
        }
    }

    /** Loads the plain-language reasons behind one finding, on demand. */
    fun loadEvidence(eventId: Long) {
        if (_state.value.evidence.containsKey(eventId)) return
        viewModelScope.launch {
            val items = runCatching { intelligence.evidenceFor(eventId) }.getOrDefault(emptyList())
            _state.value = _state.value.copy(evidence = _state.value.evidence + (eventId to items))
        }
    }

    fun confirm(activity: StoredActivity) {
        viewModelScope.launch {
            runCatching { intelligence.confirm(activity.id) }
            // Confirming has to *show up*. Without this the user says "yes, that
            // was lunch", and the timeline goes on saying Unclassified — which
            // reads as the app ignoring them.
            runCatching { writeToTimeline(activity) }
            _state.value = _state.value.copy(message = "${activity.label} added to your day")
        }
    }

    fun ignore(activity: StoredActivity) {
        viewModelScope.launch {
            runCatching { intelligence.ignore(activity.id) }
            _state.value = _state.value.copy(message = "Removed from your day")
        }
    }

    fun correct(
        activity: StoredActivity,
        activityCode: String? = null,
        start: Long? = null,
        end: Long? = null
    ) {
        viewModelScope.launch {
            runCatching {
                intelligence.correct(activity.id, activityCode = activityCode, start = start, end = end)
            }
            runCatching {
                writeToTimeline(
                    activity.copy(
                        activityCode = activityCode ?: activity.activityCode,
                        start = start ?: activity.start,
                        end = end ?: activity.end
                    )
                )
            }
            _state.value = _state.value.copy(message = "Updated — DhinaSuthra will remember that")
        }
    }

    fun split(activity: StoredActivity, at: Long) {
        viewModelScope.launch {
            runCatching { intelligence.split(activity.id, at) }
            _state.value = _state.value.copy(message = "Split in two")
        }
    }

    fun clearMessage() {
        _state.value = _state.value.copy(message = null)
    }

    /**
     * Mirror a confirmed activity into the day timeline.
     *
     * The two layers reason differently — one about signals and evidence, one
     * about a day that tiles to 1440 minutes — but the user has exactly one day,
     * and an answer given in one place has to be visible in the other.
     *
     * The place is inherited from whatever the timeline already believed about
     * those minutes, so confirming lunch while at home stays at home rather than
     * blanking a location the app already knew.
     */
    private suspend fun writeToTimeline(activity: StoredActivity) {
        val startMin = TimeUtils.minuteOfDay(Instant.ofEpochMilli(activity.start))
        val endMin = activity.end
            ?.let { TimeUtils.minuteOfDay(Instant.ofEpochMilli(it)) }
            ?.takeIf { it > startMin }
            ?: (startMin + 1).coerceAtMost(1440)

        val inherited = runCatching {
            app.container.timeIntelligence.snapshot().day(activity.epochDay)
                ?.episodes
                ?.firstOrNull { it.startMin <= startMin && it.endMin > startMin }
                ?.location
        }.getOrNull() ?: LocationType.UNKNOWN

        app.container.correctionStore.add(
            Correction(
                epochDay = activity.epochDay,
                startMin = startMin,
                endMin = endMin,
                activity = ActivityCatalog.toActivityType(activity.activityCode),
                location = inherited,
                dayType = TimeUtils.dayType(activity.epochDay),
                createdAt = System.currentTimeMillis()
            )
        )
        app.container.timeIntelligence.invalidate()
    }
}

@Composable
fun rememberActivityViewModel(): ActivityViewModel {
    val context = LocalContext.current
    return viewModel(initializer = { ActivityViewModel(DhinaSuthraApp.get(context)) })
}
