package com.focussounds.ui.screens

import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
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
import com.focussounds.data.SessionDeleteResult
import com.focussounds.ui.components.ActivityBadge
import com.focussounds.ui.components.AppIcons
import com.focussounds.ui.components.ControlShape
import com.focussounds.ui.components.EmptyState
import com.focussounds.ui.components.FocusCard
import com.focussounds.ui.components.PillShape
import com.focussounds.ui.components.ScreenHeader
import com.focussounds.ui.components.ScreenPadding
import com.focussounds.ui.components.SelectChip
import com.focussounds.ui.theme.FocusTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/*
 * History is a full session library. Every persisted session is loaded once as a lightweight
 * projection (the same Flow that feeds Progress, which needs all rows anyway) and rendered with a
 * LazyColumn, so only visible rows are composed; there is no cap on how far back it goes.
 */

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
    val weekMs: Long,
    val weekSessions: Int,
    val allTimeMs: Long,
    val allTimeSessions: Int,
    val activityOptions: List<Pair<String, String>>,
    val filter: HistoryFilterState,
    val hasAny: Boolean,
    /** Activity key -> built-in key, for icons. */
    val builtInKeys: Map<String, String>,
)

class HistoryViewModel(private val c: AppContainer) : ViewModel() {
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
        // Finished sessions only, like the day headers; a running session's live time is on Focus.
        val counted = facts.filter { it.counted && !it.inProgress }
        val weekStart = today.minusDays(6)
        HistoryUi(
            days = matches.groupBy { date(it) }.map { (d, list) -> HistoryDay(d, list.filter { it.counted && !it.inProgress }.sumOf { it.activeMs }, list) },
            matchCount = matches.size,
            matchMs = matches.filter { it.counted }.sumOf { it.activeMs },
            todayMs = counted.filter { date(it) == today }.sumOf { it.activeMs },
            todaySessions = counted.count { date(it) == today },
            weekMs = counted.filter { !date(it).isBefore(weekStart) }.sumOf { it.activeMs },
            weekSessions = counted.count { !date(it).isBefore(weekStart) },
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

    /**
     * Deletes one finished session. The session the engine is running is refused here as well as
     * in the database. History, Progress and the Focus header all observe the sessions table, so
     * they refresh on their own once the row is gone.
     */
    fun deleteSession(id: String) = viewModelScope.launch {
        val engine = c.engine.state.value
        val result = if (engine.active?.id == id) {
            SessionDeleteResult.Running
        } else {
            try {
                c.facts.deleteSession(id)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.e("FocusSounds", "Failed to delete session", t)
                null
            }
        }
        // The Focus screen's "session complete" banner would otherwise describe a deleted session.
        if (result == SessionDeleteResult.Deleted && c.engine.state.value.lastResult?.id == id) c.engine.dismissLastResult()
        c.messages.tryEmit(
            when (result) {
                SessionDeleteResult.Deleted -> "Session deleted"
                SessionDeleteResult.Running -> "A running session can't be deleted. Stop it first."
                SessionDeleteResult.NotFound -> "That session was already deleted"
                null -> "Couldn't delete the session. Please try again."
            },
        )
    }

    companion object {
        fun factory(c: AppContainer): ViewModelProvider.Factory = viewModelFactory { initializer { HistoryViewModel(c) } }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(vm: HistoryViewModel, onStartFocus: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val state = ui ?: return
    val c = FocusTheme.colors
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var pendingDeleteId by rememberSaveable { mutableStateOf<String?>(null) }
    var detailsId by rememberSaveable { mutableStateOf<String?>(null) }
    fun find(id: String?) = id?.let { x -> state.days.firstNotNullOfOrNull { d -> d.sessions.firstOrNull { it.id == x } } }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().testTag("history_list"),
            state = listState,
            contentPadding = PaddingValues(start = ScreenPadding, end = ScreenPadding, top = 8.dp, bottom = 96.dp),
        ) {
            item(key = "title", contentType = "header") {
                ScreenHeader(
                    "History",
                    subtitle = if (state.hasAny) "${sessionsLabel(state.allTimeSessions)} recorded on this device" else "Every session, exactly as recorded",
                )
            }
            if (!state.hasAny) {
                item(key = "empty") {
                    FocusCard(Modifier.padding(top = 16.dp)) {
                        EmptyState(AppIcons.History, "No sessions yet", "Sessions you play appear here with their activity, sound, times and outcome.",
                            action = { com.focussounds.ui.components.PrimaryButton("Start a focus session", onStartFocus, icon = AppIcons.Play) })
                    }
                }
                return@LazyColumn
            }
            item(key = "summary", contentType = "summary") { SummaryStrip(state, Modifier.padding(top = 16.dp)) }
            item(key = "filters", contentType = "filters") { FilterBar(state, vm, Modifier.padding(top = 16.dp)) }
            if (state.filter.isActive) {
                item(key = "match") {
                    Text("${state.matchCount} matching ${if (state.matchCount == 1) "session" else "sessions"} · ${Durations.format(state.matchMs)}",
                        style = MaterialTheme.typography.labelMedium, color = c.textSecondary, modifier = Modifier.padding(top = 12.dp, start = 4.dp))
                }
            }
            if (state.days.isEmpty()) {
                item(key = "nomatch") {
                    FocusCard(Modifier.padding(top = 16.dp)) {
                        EmptyState(AppIcons.Filter, "No matching sessions", "Try a different date range, activity, sound or outcome.",
                            action = { com.focussounds.ui.components.SecondaryButton("Clear filters", vm::clear) })
                    }
                }
            }
            state.days.forEach { day ->
                // Pinned while its sessions scroll by, so the date is always visible in long histories.
                stickyHeader(key = "d${day.date}", contentType = "day") { DayHeader(day) }
                itemsIndexed(day.sessions, key = { _, f -> f.id }, contentType = { _, _ -> "session" }) { i, f ->
                    SessionRow(
                        f,
                        day.date,
                        AppIcons.forBuiltIn(state.builtInKeys[f.activityKey]),
                        first = i == 0,
                        last = i == day.sessions.lastIndex,
                        onOpen = { detailsId = f.id },
                        // A running session has no delete action at all.
                        onDelete = if (f.inProgress) null else ({ pendingDeleteId = f.id }),
                    )
                }
            }
        }

        // A way back up from deep in a long history.
        val showTop by remember { derivedStateOf { listState.firstVisibleItemIndex > 12 } }
        AnimatedVisibility(
            showTop,
            enter = fadeIn(FocusTheme.motion.spec(200)), exit = fadeOut(FocusTheme.motion.spec(150)),
            // Centered, so it never covers a row's delete button at the end of the row.
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
        ) {
            Row(
                Modifier.clip(PillShape).background(c.surface3).border(1.dp, c.outlineStrong, PillShape)
                    .clickable(role = Role.Button) { scope.launch { listState.animateScrollToItem(0) } }
                    .heightIn(min = 48.dp).padding(horizontal = 16.dp).testTag("back_to_top"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(AppIcons.ArrowUp, null, Modifier.size(18.dp), tint = c.textPrimary)
                Spacer(Modifier.width(8.dp))
                Text("Back to top", style = MaterialTheme.typography.labelLarge, color = c.textPrimary)
            }
        }
    }

    find(detailsId)?.let { f ->
        SessionDetailsSheet(
            f,
            AppIcons.forBuiltIn(state.builtInKeys[f.activityKey]),
            onDismiss = { detailsId = null },
            onDelete = if (f.inProgress) null else ({
                detailsId = null
                pendingDeleteId = f.id
            }),
        )
    }
    // Looked up live: if the session vanished (or is somehow running) the dialog simply closes.
    find(pendingDeleteId)?.takeIf { !it.inProgress }?.let { f ->
        ConfirmDialog(
            title = "Delete this session?",
            message = deleteMessage(f),
            confirmLabel = "Delete",
            onConfirm = {
                pendingDeleteId = null
                vm.deleteSession(f.id)
            },
            onDismiss = { pendingDeleteId = null },
        )
    }
}

@Composable
private fun SummaryStrip(s: HistoryUi, modifier: Modifier = Modifier) {
    val c = FocusTheme.colors
    FocusCard(modifier.fillMaxWidth(), padding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            SummaryStat("Today", Durations.format(s.todayMs), sessionsLabel(s.todaySessions), Modifier.weight(1f))
            Box(Modifier.width(1.dp).fillMaxHeight().background(c.outline))
            SummaryStat("Last 7 days", Durations.format(s.weekMs), sessionsLabel(s.weekSessions), Modifier.weight(1f).padding(start = 16.dp))
            Box(Modifier.width(1.dp).fillMaxHeight().background(c.outline))
            SummaryStat("All time", Durations.format(s.allTimeMs), sessionsLabel(s.allTimeSessions), Modifier.weight(1f).padding(start = 16.dp))
        }
    }
}

@Composable
private fun SummaryStat(label: String, value: String, sub: String, modifier: Modifier) {
    val c = FocusTheme.colors
    Column(modifier.semantics(mergeDescendants = true) {}) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = c.textSecondary, maxLines = 1)
        Spacer(Modifier.height(2.dp))
        Text(value, style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"), color = c.textPrimary, maxLines = 2)
        Text(sub, style = MaterialTheme.typography.labelSmall, color = c.textTertiary, maxLines = 1)
    }
}

@Composable
private fun FilterBar(s: HistoryUi, vm: HistoryViewModel, modifier: Modifier = Modifier) {
    val f = s.filter
    Row(modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
private fun FilterMenu(label: String, active: Boolean, icon: ImageVector, options: List<Pair<String, () -> Unit>>) {
    val c = FocusTheme.colors
    var open by remember { mutableStateOf(false) }
    Box {
        SelectChip(label, selected = active, onClick = { open = true }, leading = {
            Icon(icon, null, Modifier.padding(start = 6.dp).size(16.dp), tint = if (active) c.accentContent else c.textSecondary)
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

@Composable
private fun DayHeader(day: HistoryDay) {
    val c = FocusTheme.colors
    val running = day.sessions.count { it.inProgress }
    val finished = day.sessions.count { it.counted && !it.inProgress }
    // Opaque so rows never show through while the header is pinned.
    Row(
        Modifier.fillMaxWidth().background(c.background).padding(start = 4.dp, end = 4.dp, top = 22.dp, bottom = 10.dp)
            .semantics(mergeDescendants = true) { heading() },
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(dayTitle(day.date), style = MaterialTheme.typography.titleMedium, color = c.textPrimary, modifier = Modifier.weight(1f))
        Text(
            "${Durations.format(day.totalMs)} · ${sessionsLabel(finished)}" + if (running > 0) " · $running running" else "",
            style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"), color = c.textSecondary,
        )
    }
}

private val TIME = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

private fun timeRange(f: SessionFact, day: LocalDate): String {
    val zone = ZoneId.systemDefault()
    val start = Instant.ofEpochMilli(f.startedAtMs).atZone(zone)
    val end = f.endedAtMs?.let { Instant.ofEpochMilli(it).atZone(zone) }
    return start.format(TIME) + (end?.let { " – ${it.format(TIME)}" + if (it.toLocalDate() != day) " (+1 day)" else "" } ?: "")
}

@Composable
private fun outcomeColor(o: EndReason?): Color {
    val c = FocusTheme.colors
    return when (o) {
        EndReason.COMPLETED -> c.accentContent
        EndReason.STOPPED -> c.textSecondary
        EndReason.INTERRUPTED -> c.warning
        EndReason.CANCELLED -> c.textTertiary
        null -> c.accentContent
    }
}

/** One session in a day group: rows of a day share one rounded container with hairline dividers. */
@Composable
private fun SessionRow(
    f: SessionFact,
    day: LocalDate,
    icon: ImageVector?,
    first: Boolean,
    last: Boolean,
    onOpen: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    val c = FocusTheme.colors
    val r = 20.dp
    val shape = RoundedCornerShape(topStart = if (first) r else 0.dp, topEnd = if (first) r else 0.dp, bottomStart = if (last) r else 0.dp, bottomEnd = if (last) r else 0.dp)
    val sound = FocusSound.fromId(f.soundId)
    val name = f.activityName + if (f.activityDeleted) " (deleted)" else ""
    Column(
        Modifier.fillMaxWidth().clip(shape).background(c.surface1)
            .drawBehind {
                // Inset divider between rows of the same day (drawn per row, so the list stays lazy).
                if (!first) drawLine(c.outline, Offset(66.dp.toPx(), 0f), Offset(size.width - 16.dp.toPx(), 0f), 1.dp.toPx())
            }
            .clickable(onClickLabel = "Show session details", onClick = onOpen)
            .testTag("session_${f.id}"),
    ) {
        Row(Modifier.padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            ActivityBadge(f.activityName, icon, size = 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleSmall, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (sound != null) {
                        Icon(AppIcons.forSound(sound), null, Modifier.size(13.dp), tint = c.textSecondary)
                        Spacer(Modifier.width(4.dp))
                    }
                    Text("${sound?.displayName ?: f.soundId} · ${timeRange(f, day)}",
                        style = MaterialTheme.typography.bodySmall, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 3.dp)) {
                    val oc = outcomeColor(f.outcome)
                    Box(Modifier.size(6.dp).clip(CircleShape).background(oc))
                    Spacer(Modifier.width(6.dp))
                    Text(outcomeLabel(f.outcome), style = MaterialTheme.typography.labelSmall, color = oc, maxLines = 1)
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                // A running session's saved time lags behind; the live timer is on the Focus screen.
                Text(
                    if (f.inProgress) "Live" else Durations.format(f.activeMs),
                    style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum"),
                    color = if (f.inProgress) c.accentContent else c.textPrimary,
                )
                Text(f.plannedDurationMs?.let { "of ${Durations.format(it)}" } ?: "no timer", style = MaterialTheme.typography.labelSmall, color = c.textTertiary)
            }
            if (onDelete != null) {
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.testTag("delete_session_${f.id}")
                        .semantics { contentDescription = "Delete session: ${f.activityName}, ${timeRange(f, day)}" },
                ) {
                    Icon(AppIcons.Trash, null, Modifier.size(20.dp), tint = c.textSecondary)
                }
            } else {
                // Keeps durations aligned with the rows that have a delete button.
                Spacer(Modifier.width(48.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionDetailsSheet(f: SessionFact, icon: ImageVector?, onDismiss: () -> Unit, onDelete: (() -> Unit)?) {
    val c = FocusTheme.colors
    val zone = ZoneId.systemDefault()
    val day = Instant.ofEpochMilli(f.startedAtMs).atZone(zone).toLocalDate()
    val sound = FocusSound.fromId(f.soundId)
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = c.surface1, contentColor = c.textPrimary, scrimColor = Color.Black.copy(alpha = 0.6f)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp).testTag("session_details")) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ActivityBadge(f.activityName, icon, size = 48.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(f.activityName + if (f.activityDeleted) " (deleted)" else "", style = MaterialTheme.typography.titleLarge, color = c.textPrimary,
                        modifier = Modifier.semantics { heading() })
                    Text(DELETE_DATE.format(day), style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
                }
            }
            Spacer(Modifier.height(20.dp))
            Text(if (f.inProgress) "Live" else Durations.format(f.activeMs), style = MaterialTheme.typography.displaySmall, color = c.textPrimary)
            Text(if (f.inProgress) "running now · the live timer is on the Focus screen" else "of focus (paused time is never counted)",
                style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
            Spacer(Modifier.height(16.dp))
            DetailLine("Time", timeRange(f, day))
            DetailLine("Sound", sound?.displayName ?: f.soundId)
            DetailLine("Timer", f.plannedDurationMs?.let { Durations.format(it) } ?: "No timer")
            DetailLine("Outcome", "${outcomeLabel(f.outcome)} · ${outcomeDescription(f.outcome)}")
            Spacer(Modifier.height(20.dp))
            if (onDelete != null) {
                Row(
                    Modifier.fillMaxWidth().clip(ControlShape).border(1.dp, c.danger.copy(alpha = 0.5f), ControlShape)
                        .background(c.danger.copy(alpha = 0.08f))
                        .clickable(role = Role.Button, onClick = onDelete)
                        .heightIn(min = 52.dp).testTag("details_delete"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Icon(AppIcons.Trash, null, Modifier.size(18.dp), tint = c.danger)
                    Spacer(Modifier.width(8.dp))
                    Text("Delete session", style = MaterialTheme.typography.labelLarge, color = c.danger)
                }
            } else {
                Text("A running session can't be deleted. Stop it first.", style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
            }
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    val c = FocusTheme.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp).semantics(mergeDescendants = true) {}) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = c.textSecondary, modifier = Modifier.width(96.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = c.textPrimary, modifier = Modifier.weight(1f))
    }
}

private val DELETE_DATE = DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)

private fun deleteMessage(f: SessionFact): String {
    val start = Instant.ofEpochMilli(f.startedAtMs).atZone(ZoneId.systemDefault())
    val counted = if (f.counted) "${Durations.format(f.activeMs)} of focus" else "no counted focus time"
    return "${f.activityName} · ${start.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))}, ${start.format(TIME)} · $counted.\n\n" +
        "It will be removed from History, Progress, streaks and goals on this device. " +
        "Your activities and other sessions are not affected. This can't be undone."
}
