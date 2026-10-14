package com.focussounds.ui

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.focussounds.core.ActiveSession
import com.focussounds.core.EndReason
import com.focussounds.core.FocusSound
import com.focussounds.core.Limits
import com.focussounds.core.PauseCause
import com.focussounds.core.Phase
import com.focussounds.core.SessionResult
import com.focussounds.data.ActivityEntity
import com.focussounds.data.HistoryItem
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

private enum class Tab(val title: String) { FOCUS("Focus"), HISTORY("History"), ACTIVITIES("Activities") }

@Composable
fun FocusRoot(vm: FocusViewModel, onStartRequested: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf(Tab.FOCUS) }
    val snackbar = remember { SnackbarHostState() }
    val message by vm.message.collectAsStateWithLifecycle()
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            vm.consumeMessage()
        }
    }
    BackHandler(enabled = tab != Tab.FOCUS) { tab = Tab.FOCUS }

    Scaffold(
        modifier = Modifier.safeDrawingPadding(),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            PrimaryTabRow(selectedTabIndex = tab.ordinal) {
                Tab.entries.forEach { t ->
                    Tab(selected = t == tab, onClick = { tab = t }, text = { Text(t.title) })
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            when (tab) {
                Tab.FOCUS -> FocusTab(vm, onStartRequested)
                Tab.HISTORY -> HistoryTab(vm)
                Tab.ACTIVITIES -> ActivitiesTab(vm)
            }
        }
    }
}

// ---------------------------------------------------------------- Focus

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FocusTab(vm: FocusViewModel, onStartRequested: () -> Unit) {
    val state by vm.engineState.collectAsStateWithLifecycle()
    val activities by vm.activities.collectAsStateWithLifecycle()
    val sel by vm.selection.collectAsStateWithLifecycle()
    var showCustomTimer by remember { mutableStateOf(false) }
    var showAddActivity by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val active = state.active
        if (active != null) {
            ActiveSessionPanel(active, onPause = vm::pause, onResume = vm::resume, onStop = vm::stop)
        } else {
            state.lastResult?.let { ResultCard(it, onDismiss = vm::dismissResult) }

            SectionTitle("Activity")
            val current = vm.effectiveActivity(activities, sel)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                activities.forEach { a ->
                    FilterChip(selected = a.id == current?.id, onClick = { vm.selectActivity(a.id) }, label = { Text(a.name) })
                }
                FilterChip(selected = false, onClick = { showAddActivity = true }, label = { Text("+ New") })
            }

            SectionTitle("Sound")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FocusSound.entries.forEach { s ->
                    FilterChip(selected = s == sel.sound, onClick = { vm.selectSound(s) }, label = { Text(s.displayName) })
                }
            }

            SectionTitle("Timer")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = sel.timerMinutes == null, onClick = { vm.selectTimer(null) }, label = { Text("No timer") })
                Limits.PRESET_MINUTES.forEach { m ->
                    FilterChip(selected = sel.timerMinutes == m, onClick = { vm.selectTimer(m) }, label = { Text("$m min") })
                }
                val custom = sel.timerMinutes?.takeIf { it !in Limits.PRESET_MINUTES }
                FilterChip(
                    selected = custom != null,
                    onClick = { showCustomTimer = true },
                    label = { Text(if (custom != null) "Custom: $custom min" else "Custom…") },
                )
            }

            Button(
                onClick = onStartRequested,
                enabled = state.playerAvailable && current != null,
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                Text(if (state.playerAvailable) "Start" else "Preparing player…", fontSize = 18.sp)
            }
        }

        SectionTitle("Volume")
        Slider(value = state.volume, onValueChange = vm::setVolume)
    }

    if (showCustomTimer) {
        NumberDialog(
            title = "Custom duration (minutes)",
            initial = sel.timerMinutes?.takeIf { it !in Limits.PRESET_MINUTES }?.toString() ?: "",
            onConfirm = { v ->
                vm.selectTimer(v)
                showCustomTimer = false
            },
            onDismiss = { showCustomTimer = false },
        )
    }
    if (showAddActivity) {
        NameDialog(
            title = "New activity",
            initial = "",
            onConfirm = { vm.addActivity(it) { showAddActivity = false } },
            onDismiss = { showAddActivity = false },
        )
    }
}

@Composable
private fun ActiveSessionPanel(s: ActiveSession, onPause: () -> Unit, onResume: () -> Unit, onStop: () -> Unit) {
    // Ticks only while this panel is visible; the engine itself never depends on the UI.
    val now by produceState(SystemClock.elapsedRealtime(), s) {
        while (true) {
            value = SystemClock.elapsedRealtime()
            delay(250)
        }
    }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(s.sound.displayName, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text(s.activityName, style = MaterialTheme.typography.titleMedium)
            val remaining = s.remainingMs(now)
            Text(
                text = formatClock(remaining ?: s.activeMs(now)),
                fontSize = 56.sp,
                fontWeight = FontWeight.Light,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                if (remaining != null) "remaining of ${formatDuration(s.plannedDurationMs!!)}" else "focused (no timer)",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(statusText(s), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (s.phase == Phase.PAUSED) {
                    Button(onClick = onResume, modifier = Modifier.weight(1f)) { Text("Resume") }
                } else {
                    Button(onClick = onPause, modifier = Modifier.weight(1f), enabled = s.phase == Phase.PLAYING) { Text("Pause") }
                }
                OutlinedButton(onClick = onStop, modifier = Modifier.weight(1f)) { Text("Stop") }
            }
        }
    }
}

private fun statusText(s: ActiveSession): String = when (s.phase) {
    Phase.STARTING -> "Starting…"
    Phase.PLAYING -> "Playing"
    Phase.PAUSED -> when (s.pauseCause) {
        PauseCause.USER, null -> "Paused"
        PauseCause.AUDIO_FOCUS_LOSS -> "Paused: another app started playing audio"
        PauseCause.TRANSIENT_INTERRUPTION -> "Paused during a call or alarm; resumes automatically afterwards"
        PauseCause.HEADPHONES_DISCONNECTED -> "Paused: headphones disconnected"
        PauseCause.OTHER -> "Paused by the system"
    }
}

@Composable
private fun ResultCard(r: SessionResult, onDismiss: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val title = when (r.reason) {
                EndReason.COMPLETED -> "Session complete"
                EndReason.STOPPED -> "Session stopped"
                EndReason.INTERRUPTED -> "Session interrupted"
                EndReason.CANCELLED -> "Session cancelled"
            }
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text("${r.activityName} · ${r.sound.displayName} · ${formatDuration(r.activeMs)} focused")
            r.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            TextButton(onClick = onDismiss) { Text("OK") }
        }
    }
}

// ---------------------------------------------------------------- History

@Composable
private fun HistoryTab(vm: FocusViewModel) {
    val items by vm.history.collectAsStateWithLifecycle()
    val startOfToday = remember(items) {
        Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
    val todayMs = items.filter { it.startedAtMs >= startOfToday }.sumOf { it.activeDurationMs }
    val totalMs = items.sumOf { it.activeDurationMs }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item {
            Column(Modifier.padding(vertical = 16.dp)) {
                Text("Today: ${formatDuration(todayMs)} focused", style = MaterialTheme.typography.titleMedium)
                Text("All time: ${formatDuration(totalMs)} in ${items.size} sessions", style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (items.isEmpty()) item { Text("No sessions yet.") }
        items(items, key = { it.id }) { HistoryRow(it) }
    }
}

@Composable
private fun HistoryRow(h: HistoryItem) {
    val df = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    val tf = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Text(
            h.activityName + if (h.activityDeleted) " (deleted activity)" else "",
            style = MaterialTheme.typography.titleSmall,
        )
        Text("${h.soundName} · ${df.format(Date(h.startedAtMs))}" + (h.endedAtMs?.let { " – ${tf.format(Date(it))}" } ?: ""))
        val planned = h.plannedDurationMs?.let { " of ${formatDuration(it)}" } ?: " (no timer)"
        val reason = when {
            h.inProgress -> "In progress"
            else -> when (h.endReason) {
                EndReason.COMPLETED -> "Completed"
                EndReason.STOPPED -> "Stopped"
                EndReason.INTERRUPTED -> "Interrupted"
                EndReason.CANCELLED -> "Cancelled"
                null -> "Unknown"
            }
        }
        Text("${formatDuration(h.activeDurationMs)} focused$planned · $reason", style = MaterialTheme.typography.bodyMedium)
    }
    HorizontalDivider()
}

// ---------------------------------------------------------------- Activities

@Composable
private fun ActivitiesTab(vm: FocusViewModel) {
    val activities by vm.activities.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<ActivityEntity?>(null) }
    var deleting by remember { mutableStateOf<ActivityEntity?>(null) }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item {
            Button(onClick = { adding = true }, modifier = Modifier.padding(vertical = 16.dp)) { Text("Add activity") }
        }
        items(activities, key = { it.id }) { a ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(a.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                if (a.isBuiltIn) {
                    Text("Built-in", style = MaterialTheme.typography.bodySmall)
                } else {
                    TextButton(onClick = { renaming = a }) { Text("Rename") }
                    TextButton(onClick = { deleting = a }) { Text("Delete") }
                }
            }
            HorizontalDivider()
        }
    }

    if (adding) {
        NameDialog("New activity", "", onConfirm = { vm.addActivity(it) { adding = false } }, onDismiss = { adding = false })
    }
    renaming?.let { a ->
        NameDialog("Rename activity", a.name, onConfirm = { vm.renameActivity(a.id, it) { renaming = null } }, onDismiss = { renaming = null })
    }
    deleting?.let { a ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete \"${a.name}\"?") },
            text = { Text("Past sessions stay in your history under this name.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteActivity(a.id)
                    deleting = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

// ---------------------------------------------------------------- Shared

@Composable
private fun SectionTitle(text: String) {
    Spacer(Modifier.height(4.dp))
    Text(text, style = MaterialTheme.typography.titleSmall)
}

@Composable
private fun NameDialog(title: String, initial: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    // Prefilled text starts selected, so typing replaces it instead of appending.
    var field by remember { mutableStateOf(TextFieldValue(initial, selection = TextRange(0, initial.length))) }
    val text = field.text
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = field,
                onValueChange = { v ->
                    field = if (v.text.length <= Limits.MAX_ACTIVITY_NAME_LENGTH) v else field
                },
                singleLine = true,
                label = { Text("Name") },
                modifier = Modifier.focusRequester(focus),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun NumberDialog(title: String, initial: String, onConfirm: (Int) -> Unit, onDismiss: () -> Unit) {
    // Prefilled text starts selected, so typing replaces it instead of appending.
    var field by remember { mutableStateOf(TextFieldValue(initial, selection = TextRange(0, initial.length))) }
    val text = field.text
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val value = text.toIntOrNull()
    val valid = value != null && value in Limits.MIN_TIMED_MINUTES..Limits.MAX_TIMED_MINUTES
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = field,
                onValueChange = { v ->
                    val digits = v.text.filter { it.isDigit() }.take(3)
                    field = if (digits == v.text) v else TextFieldValue(digits, selection = TextRange(digits.length))
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.focusRequester(focus),
                supportingText = { Text("${Limits.MIN_TIMED_MINUTES}–${Limits.MAX_TIMED_MINUTES} minutes") },
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(value!!) }, enabled = valid) { Text("Set") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

fun formatClock(ms: Long): String {
    val total = (ms + 999) / 1000 // round up so a timer never shows 0:00 while still running
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    return when {
        h > 0 -> "$h h ${m} min"
        m > 0 -> "$m min"
        else -> "$totalSec s"
    }
}
