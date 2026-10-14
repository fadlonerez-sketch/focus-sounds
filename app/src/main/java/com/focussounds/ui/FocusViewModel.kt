package com.focussounds.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.focussounds.AppContainer
import com.focussounds.core.EngineState
import com.focussounds.core.FocusSound
import com.focussounds.core.Limits
import com.focussounds.core.StartRequest
import com.focussounds.data.ActivityEditResult
import com.focussounds.data.ActivityEntity
import com.focussounds.data.HistoryItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class Selection(
    val activityId: Long,
    val sound: FocusSound,
    /** Null = manual session without a timer. */
    val timerMinutes: Int?,
)

class FocusViewModel(private val c: AppContainer) : ViewModel() {
    val engineState: StateFlow<EngineState> = c.engine.state

    val activities: StateFlow<List<ActivityEntity>> =
        c.activities.activities.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val history: StateFlow<List<HistoryItem>> =
        c.history.items.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _selection = MutableStateFlow(
        Selection(
            activityId = c.prefs.lastActivityId,
            sound = c.prefs.lastSoundId?.let { FocusSound.fromId(it) } ?: FocusSound.BROWN_NOISE,
            timerMinutes = c.prefs.timerMinutes.takeIf { it > 0 },
        ),
    )
    val selection: StateFlow<Selection> = _selection.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun consumeMessage() {
        _message.value = null
    }

    /** The selected activity, falling back to the first one if it was deleted. */
    fun effectiveActivity(list: List<ActivityEntity>, sel: Selection): ActivityEntity? =
        list.firstOrNull { it.id == sel.activityId } ?: list.firstOrNull()

    fun selectActivity(id: Long) {
        _selection.update { it.copy(activityId = id) }
        c.prefs.lastActivityId = id
    }

    fun selectSound(sound: FocusSound) {
        _selection.update { it.copy(sound = sound) }
        c.prefs.lastSoundId = sound.id
    }

    fun selectTimer(minutes: Int?) {
        val m = minutes?.coerceIn(Limits.MIN_TIMED_MINUTES, Limits.MAX_TIMED_MINUTES)
        _selection.update { it.copy(timerMinutes = m) }
        c.prefs.timerMinutes = m ?: 0
    }

    fun start() {
        val sel = _selection.value
        val activity = effectiveActivity(activities.value, sel) ?: run {
            _message.value = "Activities are still loading"
            return
        }
        val started = c.engine.start(
            StartRequest(
                sound = sel.sound,
                activityId = activity.id,
                activityName = activity.name,
                plannedDurationMs = sel.timerMinutes?.let { it * 60_000L },
            ),
        )
        if (!started) _message.value = "The player is still starting. Try again in a moment."
    }

    fun pause() = c.engine.pause()
    fun resume() = c.engine.resume()
    fun stop() = c.engine.stop()
    fun setVolume(v: Float) = c.engine.setVolume(v)
    fun dismissResult() = c.engine.dismissLastResult()

    fun addActivity(name: String, onDone: () -> Unit) = viewModelScope.launch {
        when (val r = c.activities.add(name)) {
            is ActivityEditResult.Ok -> {
                selectActivity(r.id)
                onDone()
            }
            else -> _message.value = describe(r)
        }
    }

    fun renameActivity(id: Long, name: String, onDone: () -> Unit) = viewModelScope.launch {
        when (val r = c.activities.rename(id, name)) {
            is ActivityEditResult.Ok -> onDone()
            else -> _message.value = describe(r)
        }
    }

    fun deleteActivity(id: Long) = viewModelScope.launch {
        if (!c.activities.delete(id)) _message.value = "Built-in activities can't be deleted"
    }

    private fun describe(r: ActivityEditResult): String = when (r) {
        ActivityEditResult.InvalidName -> "Enter a name of 1–${Limits.MAX_ACTIVITY_NAME_LENGTH} characters"
        ActivityEditResult.DuplicateName -> "An activity with that name already exists"
        ActivityEditResult.NotEditable -> "Built-in activities can't be renamed"
        is ActivityEditResult.Ok -> ""
    }

    companion object {
        fun factory(c: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { FocusViewModel(c) }
        }
    }
}
