package com.focussounds.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.focussounds.AppContainer
import com.focussounds.core.Durations
import com.focussounds.core.EndReason
import com.focussounds.core.EngineState
import com.focussounds.core.FocusSound
import com.focussounds.core.Limits
import com.focussounds.core.SessionResult
import com.focussounds.core.StartRequest
import com.focussounds.data.ActivityEditResult
import com.focussounds.data.ActivityEntity
import com.focussounds.data.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields
import java.util.Locale

data class Selection(
    val activityId: Long,
    val sound: FocusSound,
    /** Null = manual session without a timer. */
    val timerMinutes: Int?,
)

/** Compact feedback after a session ends, with an optional goal milestone. */
data class SessionFeedback(val result: SessionResult, val milestone: String?)

data class TodaySummary(val focusMs: Long, val dailyGoalMs: Long?)

class FocusViewModel(private val c: AppContainer) : ViewModel() {
    val engineState: StateFlow<EngineState> = c.engine.state
    val settings: StateFlow<AppSettings> = c.settings.settings

    val activities: StateFlow<List<ActivityEntity>> =
        c.activities.activities.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _selection = MutableStateFlow(initialSelection())
    val selection: StateFlow<Selection> = _selection.asStateFlow()

    /** Seconds left in the optional pre-start countdown; null when not counting down. */
    private val _countdown = MutableStateFlow<Int?>(null)
    val countdown: StateFlow<Int?> = _countdown.asStateFlow()
    private var countdownJob: Job? = null

    /**
     * True while a session (or its pre-start countdown) is running. Changes only on start/stop,
     * not on every engine tick, so the app shell does not recompose each second.
     */
    val sessionLive: StateFlow<Boolean> =
        combine(engineState, countdown) { e, cd -> e.active != null || cd != null }
            .stateIn(viewModelScope, SharingStarted.Eagerly, engineState.value.active != null || countdown.value != null)

    private val _feedback = MutableStateFlow<SessionFeedback?>(null)
    val feedback: StateFlow<SessionFeedback?> = _feedback.asStateFlow()

    /** Today's recorded focus (finished sessions) for the idle header. */
    val today: StateFlow<TodaySummary> = combine(c.facts.all, c.settings.settings.map { it.dailyGoalMinutes }.distinctUntilChanged()) { facts, goal ->
        val zone = ZoneId.systemDefault()
        val day = LocalDate.now(zone)
        TodaySummary(
            facts.filter { it.outcome != null && it.counted && Instant.ofEpochMilli(it.startedAtMs).atZone(zone).toLocalDate() == day }.sumOf { it.activeMs },
            goal.takeIf { it > 0 }?.let { it * 60_000L },
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodaySummary(0, null))

    init {
        viewModelScope.launch {
            c.engine.state.map { it.lastResult }.distinctUntilChanged { a, b -> a?.id == b?.id }.collect { r ->
                _feedback.value = r?.let { SessionFeedback(it, milestoneFor(it)) }
            }
        }
    }

    private fun initialSelection(): Selection {
        val s = c.settings.current
        return if (s.rememberLastSelection) {
            Selection(
                activityId = c.prefs.lastActivityId,
                sound = c.prefs.lastSoundId?.let { FocusSound.fromId(it) } ?: FocusSound.BROWN_NOISE,
                timerMinutes = c.prefs.timerMinutes.takeIf { it > 0 },
            )
        } else {
            Selection(s.defaultActivityId, FocusSound.fromId(s.defaultSoundId) ?: FocusSound.BROWN_NOISE, s.defaultTimerMinutes.takeIf { it > 0 })
        }
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

    /** Starts now, or after the optional countdown from Settings. */
    fun requestStart() {
        if (c.engine.state.value.active != null || _countdown.value != null) return
        val seconds = c.settings.current.countdownSeconds
        if (seconds <= 0) {
            startNow()
            return
        }
        countdownJob = viewModelScope.launch {
            for (s in seconds downTo 1) {
                _countdown.value = s
                delay(1_000)
            }
            _countdown.value = null
            startNow()
        }
    }

    /** Also called when the app leaves the foreground: never start playback from the background. */
    fun cancelCountdown() {
        countdownJob?.cancel()
        countdownJob = null
        _countdown.value = null
    }

    private fun startNow() {
        val sel = _selection.value
        val activity = effectiveActivity(activities.value, sel) ?: run {
            c.messages.tryEmit("Activities are still loading")
            return
        }
        _feedback.value = null
        val started = c.engine.start(
            StartRequest(
                sound = sel.sound,
                activityId = activity.id,
                activityName = activity.name,
                plannedDurationMs = sel.timerMinutes?.let { it * 60_000L },
            ),
        )
        if (!started) c.messages.tryEmit("The player is still starting. Try again in a moment.")
    }

    fun pause() = c.engine.pause()
    fun resume() = c.engine.resume()
    fun stop() = c.engine.stop()
    fun setVolume(v: Float) = c.engine.setVolume(v)

    fun dismissFeedback() {
        _feedback.value = null
        c.engine.dismissLastResult()
    }

    fun addActivity(name: String, select: Boolean = true, onDone: () -> Unit) = viewModelScope.launch {
        when (val r = c.activities.add(name)) {
            is ActivityEditResult.Ok -> {
                if (select) selectActivity(r.id)
                onDone()
            }
            else -> c.messages.tryEmit(describe(r))
        }
    }

    fun renameActivity(id: Long, name: String, onDone: () -> Unit) = viewModelScope.launch {
        when (val r = c.activities.rename(id, name)) {
            is ActivityEditResult.Ok -> onDone()
            else -> c.messages.tryEmit(describe(r))
        }
    }

    fun deleteActivity(id: Long) = viewModelScope.launch {
        if (!c.activities.delete(id)) c.messages.tryEmit("Built-in activities can't be deleted")
    }

    /**
     * A milestone message when THIS session pushed today's or this week's focus across a goal.
     * The session itself is excluded from the "before" total, so it is never counted twice.
     */
    private suspend fun milestoneFor(r: SessionResult): String? {
        val s = c.settings.current
        if (!s.goalCelebrations || r.reason == EndReason.CANCELLED || r.activeMs <= 0) return null
        if (s.dailyGoalMinutes <= 0 && s.weeklyGoalMinutes <= 0) return null
        val facts = c.facts.all.first()
        return withContext(Dispatchers.Default) {
            val zone = ZoneId.systemDefault()
            fun date(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
            val day = date(r.startedAtWallMs)
            val today = LocalDate.now(zone)
            val others = facts.filter { it.id != r.id && it.outcome != null && it.counted }
            val dayBefore = others.filter { date(it.startedAtMs) == day }.sumOf { it.activeMs }
            val weekStart = today.with(TemporalAdjusters.previousOrSame(WeekFields.of(Locale.getDefault()).firstDayOfWeek))
            val weekBefore = others.filter { val d = date(it.startedAtMs); !d.isBefore(weekStart) && !d.isAfter(today) }.sumOf { it.activeMs }
            val dailyGoal = s.dailyGoalMinutes * 60_000L
            val weeklyGoal = s.weeklyGoalMinutes * 60_000L
            when {
                day == today && dailyGoal > 0 && dayBefore < dailyGoal && dayBefore + r.activeMs >= dailyGoal ->
                    "Daily goal reached: ${Durations.format(dayBefore + r.activeMs)} today"
                !day.isBefore(weekStart) && weeklyGoal > 0 && weekBefore < weeklyGoal && weekBefore + r.activeMs >= weeklyGoal ->
                    "Weekly goal reached: ${Durations.format(weekBefore + r.activeMs)} this week"
                else -> null
            }
        }
    }

    private fun describe(r: ActivityEditResult): String = when (r) {
        ActivityEditResult.InvalidName -> "Enter a name of 1–${Limits.MAX_ACTIVITY_NAME_LENGTH} characters"
        ActivityEditResult.DuplicateName -> "An activity with that name already exists"
        ActivityEditResult.NotEditable -> "Built-in activities can't be renamed"
        is ActivityEditResult.Ok -> ""
    }

    companion object {
        fun factory(c: AppContainer): ViewModelProvider.Factory = viewModelFactory { initializer { FocusViewModel(c) } }
    }
}
