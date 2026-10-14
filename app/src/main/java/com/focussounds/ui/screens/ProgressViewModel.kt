package com.focussounds.ui.screens

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.focussounds.AppContainer
import com.focussounds.core.Phase
import com.focussounds.core.analytics.AnalyticsSettings
import com.focussounds.core.analytics.CalendarMonth
import com.focussounds.core.analytics.DateRange
import com.focussounds.core.analytics.ProgressAnalytics
import com.focussounds.core.analytics.ProgressReport
import com.focussounds.core.analytics.RangePreset
import com.focussounds.core.analytics.Resolution
import com.focussounds.data.SessionFactsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.WeekFields
import java.util.Locale

data class ProgressQuery(
    val preset: RangePreset = RangePreset.LAST_7_DAYS,
    val custom: DateRange? = null,
    val trendResolution: Resolution? = null,
    val barResolution: Resolution? = null,
    /** Null = the current month. */
    val calendarMonth: YearMonth? = null,
)

data class ProgressUi(
    val query: ProgressQuery,
    val report: ProgressReport,
    val calendar: CalendarMonth,
    val dailyGoalMinutes: Int,
    val weeklyGoalMinutes: Int,
)

@OptIn(ExperimentalCoroutinesApi::class)
class ProgressViewModel(private val c: AppContainer) : ViewModel() {
    private val query = MutableStateFlow(ProgressQuery())

    /** Only the parts of the running session that change its focus time. */
    private val live = c.engine.state
        .map { s -> s.active?.let { a -> Triple(a, a.accumulatedActiveMs, a.playingSinceElapsedMs) } }
        .distinctUntilChanged { a, b -> a?.first?.id == b?.first?.id && a?.second == b?.second && a?.third == b?.third }

    /** Refresh while a session is playing (its time grows) and occasionally otherwise (date change). */
    private val tick = c.engine.state.map { it.active?.phase == Phase.PLAYING }.distinctUntilChanged().flatMapLatest { playing ->
        flow {
            while (true) {
                emit(SystemClock.elapsedRealtime())
                delay(if (playing) 30_000 else 300_000)
            }
        }
    }

    private val goals = c.settings.settings.map { it.dailyGoalMinutes to it.weeklyGoalMinutes }.distinctUntilChanged()

    private val analytics = combine(c.facts.all, live, goals, tick) { facts, running, (daily, weekly), _ ->
        // Persisted running rows are replaced by the engine's live session so time is never counted twice.
        val finished = facts.filter { it.outcome != null }
        val liveFact = running?.first?.let { SessionFactsRepository.liveFact(it, SystemClock.elapsedRealtime()) }
        ProgressAnalytics(
            finished + listOfNotNull(liveFact),
            AnalyticsSettings(
                zone = ZoneId.systemDefault(),
                nowMs = System.currentTimeMillis(),
                firstDayOfWeek = WeekFields.of(Locale.getDefault()).firstDayOfWeek,
                dailyGoalMs = daily.takeIf { it > 0 }?.let { it * 60_000L },
                weeklyGoalMs = weekly.takeIf { it > 0 }?.let { it * 60_000L },
            ),
        ) to (daily to weekly)
    }.conflate().flowOn(Dispatchers.Default)

    val ui: StateFlow<ProgressUi?> = combine(analytics, query) { (a, g), q ->
        val report = a.report(q.preset, q.custom, q.trendResolution, q.barResolution)
        ProgressUi(q, report, a.calendarMonth(q.calendarMonth ?: YearMonth.from(a.today)), g.first, g.second)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setPreset(p: RangePreset) = query.update { it.copy(preset = p, trendResolution = null, barResolution = null) }
    fun setCustom(range: DateRange) = query.update { it.copy(preset = RangePreset.CUSTOM, custom = range, trendResolution = null, barResolution = null) }
    fun setTrendResolution(r: Resolution) = query.update { it.copy(trendResolution = r) }
    fun setBarResolution(r: Resolution) = query.update { it.copy(barResolution = r) }
    fun showMonth(m: YearMonth) = query.update { it.copy(calendarMonth = m) }

    fun setDailyGoal(minutes: Int) = c.settings.update { it.copy(dailyGoalMinutes = minutes) }
    fun setWeeklyGoal(minutes: Int) = c.settings.update { it.copy(weeklyGoalMinutes = minutes) }

    companion object {
        fun factory(c: AppContainer): ViewModelProvider.Factory = viewModelFactory { initializer { ProgressViewModel(c) } }
    }
}
