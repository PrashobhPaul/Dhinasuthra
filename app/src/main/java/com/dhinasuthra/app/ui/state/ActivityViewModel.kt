package com.dhinasuthra.app.ui.state

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dhinasuthra.app.DhinaSuthraApp
import com.dhinasuthra.app.activity.EvidenceItem
import com.dhinasuthra.app.activity.StoredActivity
import com.dhinasuthra.app.core.TimeUtils
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
            _state.value = _state.value.copy(message = "${activity.label} confirmed")
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
}

@Composable
fun rememberActivityViewModel(): ActivityViewModel {
    val context = LocalContext.current
    return viewModel(initializer = { ActivityViewModel(DhinaSuthraApp.get(context)) })
}
