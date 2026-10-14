package com.focussounds.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.focussounds.AppContainer
import com.focussounds.core.Durations
import com.focussounds.core.EndReason
import com.focussounds.core.FocusSound
import com.focussounds.core.analytics.DateRange
import com.focussounds.core.analytics.HistoryFilter
import com.focussounds.core.analytics.SessionFact
import com.focussounds.ui.components.ActivityBadge
import com.focussounds.ui.components.AppIcons
import com.focussounds.ui.components.EmptyState
import com.focussounds.ui.components.FocusCard
import com.focussounds.ui.components.Pill
import com.focussounds.ui.components.ScreenPadding
import com.focussounds.ui.components.SelectChip
import com.focussounds.ui.theme.FocusTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

enum class DateFilter(val label: String) { ALL("Any date"), TODAY("Today"), WEEK("Last 7 days"), MONTH("Last 30 days") }

data class HistoryFilterState(
    val date: DateFilter = DateFilter.ALL,
    val activityKey: String? = null,
    val soundId: String? = null,
    /** "all", or an outcome key, or "running". */
    val outcome: String? = null,
) {
    val isActive get() = date != DateFilter.ALL || activityKey != null || soundId != null || outcome != null
}

data class HistoryDay(val date: LocalDate, val totalMs: Long, val sessions: List<SessionFact>)

data class HistoryUi(
    val days: List<HistoryDay>,
    val matchCount: Int,
    val matchMs: Long,
    val todayMs: Long,
    val todaySessions: Int,
    val allTimeMs: Long,
    val allTimeSessions: Int,
    val activityOptions: List<Pair<String, String>>,
    val filter: HistoryFilterState,
    val hasAny: Boolean,
    /** Activity key -> built-in key, for icons. */
    val builtInKeys: Map<String, String>,
)

class HistoryViewModel(c: AppContainer) : ViewModel() {
    private val filter = MutableStateFlow(HistoryFilterState())

    val ui: StateFlow<HistoryUi?> = combine(c.facts.all, c.activities.activities, filter) { facts, activities, f ->
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        fun date(x: SessionFact) = Instant.ofEpochMilli(x.startedAtMs).atZone(zone).toLocalDate()
        val core = HistoryFilter(
            range = when (f.date) {
                DateFilter.ALL -> null
                DateFilter.TODAY -> DateRange(today, today)
                DateFilter.WEEK -> DateRange(today.minusDays(6), today)
                DateFilter.MONTH -> DateRange(today.minusDays(29), today)
            },
            activityKeys = setOfNotNull(f.activityKey),
            soundIds = setOfNotNull(f.soundId),
            outcomes = when (f.outcome) {
                null -> emptySet()
                "running" -> setOf(null)
                else -> setOf(EndReason.fromKey(f.outcome))
            },
        )
        val matches = facts.filter { core.matches(it, zone) }.sortedByDescending { it.startedAtMs }
        val counted = facts.filter { it.counted }
        HistoryUi(
            days = matches.groupBy { date(it) }.map { (d, list) -> HistoryDay(d, list.filter { it.counted && !it.inProgress }.sumOf { it.activeMs }, list) },
            matchCount = matches.size,
            matchMs = matches.filter { it.counted }.sumOf { it.activeMs },
            todayMs = counted.filter { date(it) == today }.sumOf { it.activeMs },
            todaySessions = counted.count { date(it) == today },
            allTimeMs = counted.sumOf { it.activeMs },
            allTimeSessions = counted.size,
            activityOptions = facts.groupBy { it.activityKey }.map { (k, l) ->
                k to (l.maxBy { it.startedAtMs }.let { it.activityName + if (it.activityDeleted) " (deleted)" else "" })
            }.sortedBy { it.second.lowercase() },
            filter = f,
            hasAny = facts.isNotEmpty(),
            builtInKeys = activities.mapNotNull { a -> a.builtInKey?.let { SessionFact.activityKeyFor(a.id, a.name) to it } }.toMap(),
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setDate(d: DateFilter) = filter.update { it.copy(date = d) }
    fun setActivity(k: String?) = filter.update { it.copy(activityKey = k) }
    fun setSound(s: String?) = filter.update { it.copy(soundId = s) }
    fun setOutcome(o: String?) = filter.update { it.copy(outcome = o) }
    fun clear() = filter.update { HistoryFilterState() }

    companion object {
        fun factory(c: AppContainer): ViewModelProvider.Factory = viewModelFactory { initializer { HistoryViewModel(c) } }
    }
}

@Composable
fun HistoryScreen(vm: HistoryViewModel, onStartFocus: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val state = ui ?: return
    val c = FocusTheme.colors
    LazyColumn(
        Modifier.fillMaxSize().testTag("history_list"),
        contentPadding = PaddingValues(start = ScreenPadding, end = ScreenPadding, top = 8.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "title") {
            Column(Modifier.padding(top = 8.dp)) {
                Text("History", style = MaterialTheme.typography.headlineSmall, color = c.textPrimary, modifier = Modifier.semantics { heading() })
                Text("Every session, exactly as recorded", style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
            }
        }
        if (!state.hasAny) {
            item(key = "empty") {
                FocusCard {
                    EmptyState(AppIcons.History, "No sessions yet", "Sessions you play appear here with their activity, sound, times and outcome.",
                        action = { com.focussounds.ui.components.PrimaryButton("Start a focus session", onStartFocus, icon = AppIcons.Play) })
                }
            }
            return@LazyColumn
        }
        item(key = "summary") { SummaryCard(state) }
        item(key = "filters") { FilterBar(state, vm) }
        if (state.filter.isActive) {
            item(key = "match") {
                Text("${state.matchCount} matching ${if (state.matchCount == 1) "session" else "sessions"} · ${Durations.format(state.matchMs)}",
                    style = MaterialTheme.typography.labelMedium, color = c.textTertiary)
            }
        }
        if (state.days.isEmpty()) {
            item(key = "nomatch") {
                FocusCard {
                    EmptyState(AppIcons.Filter, "No matching sessions", "Try a different date range, activity, sound or outcome.",
                        action = { com.focussounds.ui.components.SecondaryButton("Clear filters", vm::clear) })
                }
            }
        }
        state.days.forEach { day ->
            item(key = "d${day.date}") {
                Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(dayTitle(day.date), style = MaterialTheme.typography.titleSmall, color = c.textPrimary, modifier = Modifier.weight(1f).semantics { heading() })
                    val running = day.sessions.count { it.inProgress }
                    val finished = day.sessions.count { it.counted && !it.inProgress }
                    Text(
                        "${Durations.format(day.totalMs)} · ${sessionsLabel(finished)}" + if (running > 0) " · $running running" else "",
                        style = MaterialTheme.typography.labelMedium, color = c.textTertiary,
                    )
                }
            }
            items(day.sessions, key = { it.id }) { SessionCard(it, day.date, AppIcons.forBuiltIn(state.builtInKeys[it.activityKey])) }
        }
    }
}

@Composable
private fun SummaryCard(s: HistoryUi) {
    val c = FocusTheme.colors
    FocusCard(Modifier.fillMaxWidth()) {
        Row {
            Column(Modifier.weight(1f)) {
                Text("Today", style = MaterialTheme.typography.labelMedium, color = c.textTertiary)
                Text(Durations.format(s.todayMs), style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"), color = c.textPrimary)
                Text(sessionsLabel(s.todaySessions), style = MaterialTheme.typography.labelSmall, color = c.textTertiary)
            }
            Column(Modifier.weight(1f)) {
                Text("All time", style = MaterialTheme.typography.labelMedium, color = c.textTertiary)
                Text(Durations.format(s.allTimeMs), style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"), color = c.textPrimary)
                Text(sessionsLabel(s.allTimeSessions), style = MaterialTheme.typography.labelSmall, color = c.textTertiary)
            }
        }
    }
}

@Composable
private fun FilterBar(s: HistoryUi, vm: HistoryViewModel) {
    val f = s.filter
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterMenu(
            label = f.date.label, active = f.date != DateFilter.ALL, icon = AppIcons.Calendar,
            options = DateFilter.entries.map { it.label to { vm.setDate(it) } },
        )
        FilterMenu(
            label = f.activityKey?.let { k -> s.activityOptions.firstOrNull { it.first == k }?.second } ?: "Any activity",
            active = f.activityKey != null, icon = AppIcons.Activities,
            options = listOf<Pair<String, () -> Unit>>("Any activity" to { vm.setActivity(null) }) + s.activityOptions.map { (k, n) -> n to { vm.setActivity(k) } },
        )
        FilterMenu(
            label = f.soundId?.let { FocusSound.fromId(it)?.displayName } ?: "Any sound",
            active = f.soundId != null, icon = AppIcons.Volume,
            options = listOf<Pair<String, () -> Unit>>("Any sound" to { vm.setSound(null) }) + FocusSound.entries.map { it.displayName to { vm.setSound(it.id) } },
        )
        FilterMenu(
            label = when (f.outcome) {
                null -> "Any outcome"
                "running" -> "In progress"
                else -> outcomeLabel(EndReason.fromKey(f.outcome))
            },
            active = f.outcome != null, icon = AppIcons.Check,
            options = listOf<Pair<String, () -> Unit>>("Any outcome" to { vm.setOutcome(null) }) +
                EndReason.entries.map { outcomeLabel(it) to { vm.setOutcome(it.key) } } + ("In progress" to { vm.setOutcome("running") }),
        )
        if (f.isActive) SelectChip("Clear", selected = false, onClick = vm::clear, leading = { Icon(AppIcons.Close, null, Modifier.size(16.dp), tint = FocusTheme.colors.textSecondary) })
    }
}

@Composable
private fun FilterMenu(label: String, active: Boolean, icon: androidx.compose.ui.graphics.vector.ImageVector, options: List<Pair<String, () -> Unit>>) {
    val c = FocusTheme.colors
    var open by remember { mutableStateOf(false) }
    Box {
        SelectChip(label, selected = active, onClick = { open = true }, leading = {
            Icon(icon, null, Modifier.size(16.dp), tint = if (active) c.accentContent else c.textSecondary)
        })
        DropdownMenu(open, onDismissRequest = { open = false }, containerColor = c.surface2) {
            options.forEach { (text, action) ->
                DropdownMenuItem(text = { Text(text, color = c.textPrimary) }, onClick = {
                    action()
                    open = false
                })
            }
        }
    }
}

private val TIME = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

@Composable
private fun SessionCard(f: SessionFact, day: LocalDate, icon: androidx.compose.ui.graphics.vector.ImageVector?) {
    val c = FocusTheme.colors
    val zone = ZoneId.systemDefault()
    val start = Instant.ofEpochMilli(f.startedAtMs).atZone(zone)
    val end = f.endedAtMs?.let { Instant.ofEpochMilli(it).atZone(zone) }
    val crossesMidnight = end != null && end.toLocalDate() != day
    val sound = FocusSound.fromId(f.soundId)
    val outcomeColor = when (f.outcome) {
        EndReason.COMPLETED -> c.accentContent
        EndReason.STOPPED -> c.textSecondary
        EndReason.INTERRUPTED -> c.warning
        EndReason.CANCELLED -> c.textTertiary
        null -> c.accentContent
    }
    FocusCard(Modifier.fillMaxWidth(), padding = PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ActivityBadge(f.activityName, icon, size = 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(f.activityName + if (f.activityDeleted) " (deleted)" else "", style = MaterialTheme.typography.titleSmall, color = c.textPrimary,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (sound != null) Icon(AppIcons.forSound(sound), null, Modifier.size(13.dp), tint = c.textTertiary)
                    Text(
                        " ${sound?.displayName ?: f.soundId} · ${start.format(TIME)}" +
                            (end?.let { " – ${it.format(TIME)}" + if (crossesMidnight) " (+1 day)" else "" } ?: ""),
                        style = MaterialTheme.typography.bodySmall, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(horizontalAlignment = Alignment.End) {
                // A running session's saved time lags behind; the live timer is on the Focus screen.
                Text(
                    if (f.inProgress) "Live" else Durations.format(f.activeMs),
                    style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum"),
                    color = if (f.inProgress) c.accentContent else c.textPrimary,
                )
                Text(f.plannedDurationMs?.let { "of ${Durations.format(it)}" } ?: "no timer", style = MaterialTheme.typography.labelSmall, color = c.textTertiary)
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Pill(outcomeLabel(f.outcome), outcomeColor)
            Spacer(Modifier.width(8.dp))
            Text(outcomeDescription(f.outcome), style = MaterialTheme.typography.labelSmall, color = c.textTertiary, maxLines = 2)
        }
    }
}
