package com.focussounds.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.focussounds.core.ColorMath
import com.focussounds.core.Durations
import com.focussounds.core.FocusSound
import com.focussounds.core.Limits
import com.focussounds.data.AppSettings
import com.focussounds.data.MotionLevel
import com.focussounds.ui.components.AppIcons
import com.focussounds.ui.components.Eyebrow
import com.focussounds.ui.components.FocusCard
import com.focussounds.ui.components.HairlineDivider
import com.focussounds.ui.components.ScreenPadding
import com.focussounds.ui.components.SectionLabel
import com.focussounds.ui.components.ScreenHeader
import com.focussounds.ui.components.SegmentOption
import com.focussounds.ui.components.SegmentedControl
import com.focussounds.ui.components.SelectChip
import com.focussounds.ui.components.SettingRow
import com.focussounds.ui.components.SwitchRow
import com.focussounds.ui.theme.FocusTheme
import java.time.LocalDate

@Composable
fun SettingsScreen(vm: SettingsViewModel) {
    val c = FocusTheme.colors
    val s by vm.settings.collectAsStateWithLifecycle()
    val activities by vm.activities.collectAsStateWithLifecycle()
    val sessionActive by vm.sessionActive.collectAsStateWithLifecycle()
    val finished by vm.finishedSessions.collectAsStateWithLifecycle()
    var picker by remember { mutableStateOf(false) }
    var goal by remember { mutableStateOf<GoalKind?>(null) }
    var customDefaultTimer by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) vm.exportCsv(uri)
    }

    LazyColumn(
        Modifier.fillMaxSize().testTag("settings_list"),
        contentPadding = PaddingValues(start = ScreenPadding, end = ScreenPadding, top = 8.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { ScreenHeader("Settings", subtitle = "Everything here is stored on this device.") }

        // ------------------------------------------------ Appearance
        item { SectionLabel("Appearance") }
        item {
            FocusCard(Modifier.fillMaxWidth().testTag("appearance_card")) {
                SettingRow(
                    title = "Accent color",
                    description = "${ColorMath.toHex(s.accentArgb)} · used for buttons, selections, charts and progress",
                    onClick = { picker = true },
                    modifier = Modifier.testTag("accent_row"),
                ) {
                    Box(Modifier.size(32.dp).clip(CircleShape).background(Color(s.accentArgb)).border(1.dp, c.outlineStrong, CircleShape))
                }
                Spacer(Modifier.height(6.dp))
                Eyebrow("Quick picks")
                Spacer(Modifier.height(10.dp))
                PresetGrid(s.accentArgb) { vm.setAccent(it) }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    com.focussounds.ui.components.SecondaryButton("Custom color…", { picker = true }, Modifier.weight(1f), icon = AppIcons.Palette)
                    com.focussounds.ui.components.SecondaryButton("Reset to default", vm::resetAccent, Modifier.weight(1f), icon = AppIcons.Reset,
                        enabled = s.accentArgb != AppSettings.DEFAULT_ACCENT)
                }
                Spacer(Modifier.height(14.dp))
                AppearancePreview(Modifier.fillMaxWidth())
            }
        }
        item { SectionLabel("Display & motion") }
        item {
            FocusCard(Modifier.fillMaxWidth().testTag("motion_settings")) {
                SettingRow("Dark theme", description = "Focus Sounds always uses its dark theme, whatever the system setting.", icon = AppIcons.Moon)
                HairlineDivider()
                Spacer(Modifier.height(10.dp))
                Text("Animations", style = MaterialTheme.typography.bodyLarge, color = c.textPrimary)
                Text(
                    when (s.motion) {
                        MotionLevel.FULL -> "Smooth transitions, animated charts and a gentle breathing ring during sessions."
                        MotionLevel.REDUCED -> "Shorter transitions; no ambient breathing effect."
                        MotionLevel.OFF -> "No animations: everything changes instantly."
                    },
                    style = MaterialTheme.typography.bodySmall, color = c.textSecondary,
                )
                Spacer(Modifier.height(8.dp))
                SegmentedControl(MotionLevel.entries.map { SegmentOption(it, it.label) }, s.motion, { m -> vm.update { it.copy(motion = m) } })
                Spacer(Modifier.height(6.dp))
                SwitchRow("Haptic feedback", s.haptics, { on -> vm.update { it.copy(haptics = on) } },
                    description = "Subtle vibration on selections and chart scrubbing", icon = AppIcons.Vibrate)
            }
        }

        // ------------------------------------------------ Goals
        item { SectionLabel("Goals") }
        item {
            FocusCard(Modifier.fillMaxWidth().testTag("goals_settings")) {
                Text("Only actual playing time counts toward goals.", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                Spacer(Modifier.height(4.dp))
                SettingRow("Daily goal", description = goalText(s.dailyGoalMinutes, "per day"), icon = AppIcons.Goal, onClick = { goal = GoalKind.DAILY }) {
                    Icon(AppIcons.ChevronRight, null, tint = c.textTertiary)
                }
                SettingRow("Weekly goal", description = goalText(s.weeklyGoalMinutes, "per week"), icon = AppIcons.Calendar, onClick = { goal = GoalKind.WEEKLY }) {
                    Icon(AppIcons.ChevronRight, null, tint = c.textTertiary)
                }
                SwitchRow("Goal milestones", s.goalCelebrations, { on -> vm.update { it.copy(goalCelebrations = on) } },
                    description = "A short note when a session reaches your daily or weekly goal", icon = AppIcons.Star)
            }
        }

        // ------------------------------------------------ Focus behavior
        item { SectionLabel("Focus") }
        item {
            FocusCard(Modifier.fillMaxWidth().testTag("focus_settings")) {
                SwitchRow("Remember last selection", s.rememberLastSelection, { on -> vm.update { it.copy(rememberLastSelection = on) } },
                    description = if (s.rememberLastSelection) "The app opens with the activity, sound and timer you used last." else "The app opens with the defaults below.",
                    icon = AppIcons.Reset)
                if (!s.rememberLastSelection) {
                    Spacer(Modifier.height(6.dp))
                    Text("Default timer", style = MaterialTheme.typography.labelMedium, color = c.textSecondary)
                    Spacer(Modifier.height(6.dp))
                    val custom = s.defaultTimerMinutes.takeIf { it != 0 && it !in Limits.PRESET_MINUTES }
                    SegmentedControl(
                        listOf(SegmentOption(0, "None", AppIcons.Infinity)) + Limits.PRESET_MINUTES.map { SegmentOption(it, "$it min") } +
                            SegmentOption(-1, custom?.let { "$it min" } ?: "Custom"),
                        if (custom != null) -1 else s.defaultTimerMinutes,
                        { m -> if (m == -1) customDefaultTimer = true else vm.update { it.copy(defaultTimerMinutes = m) } },
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("Default sound", style = MaterialTheme.typography.labelMedium, color = c.textSecondary)
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FocusSound.entries.forEach { snd ->
                            SelectChip(snd.displayName, snd.id == s.defaultSoundId, { vm.update { it.copy(defaultSoundId = snd.id) } }, leading = {
                                Icon(AppIcons.forSound(snd), null, Modifier.size(16.dp), tint = if (snd.id == s.defaultSoundId) c.accentContent else c.textSecondary)
                            })
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    DefaultActivityPicker(activities.map { it.id to it.name }, s.defaultActivityId) { id -> vm.update { it.copy(defaultActivityId = id) } }
                    Spacer(Modifier.height(8.dp))
                }
                HairlineDivider()
                Spacer(Modifier.height(10.dp))
                Text("Start countdown", style = MaterialTheme.typography.bodyLarge, color = c.textPrimary)
                Text("A short pause to settle in before the sound starts.", style = MaterialTheme.typography.bodySmall, color = c.textTertiary)
                Spacer(Modifier.height(8.dp))
                SegmentedControl(AppSettings.COUNTDOWN_CHOICES.map { SegmentOption(it, if (it == 0) "Off" else "$it s") }, s.countdownSeconds,
                    { sec -> vm.update { it.copy(countdownSeconds = sec) } })
                Spacer(Modifier.height(6.dp))
                SwitchRow("Completion chime", s.completionChime, { on -> vm.update { it.copy(completionChime = on) } },
                    description = "A soft chime when a timer finishes (follows notification volume and Do Not Disturb)", icon = AppIcons.Bell)
                SwitchRow("Completion vibration", s.completionVibration, { on -> vm.update { it.copy(completionVibration = on) } },
                    description = "A gentle double pulse when a timer finishes", icon = AppIcons.Vibrate)
            }
        }

        // ------------------------------------------------ Data & privacy
        item { SectionLabel("Data & privacy") }
        item {
            FocusCard(Modifier.fillMaxWidth().testTag("data_settings")) {
                Row(Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp)).background(c.surface2).padding(12.dp)) {
                    Icon(AppIcons.Shield, null, Modifier.size(20.dp), tint = c.accentContent)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "Your sessions, activities and settings are stored only on this phone. No account, no cloud sync, no analytics, and the app has no internet or microphone permission. History is excluded from cloud backup.",
                        style = MaterialTheme.typography.bodySmall, color = c.textSecondary,
                    )
                }
                Spacer(Modifier.height(6.dp))
                SettingRow("Export history (CSV)", description = "$finished finished ${if (finished == 1) "session" else "sessions"} · saved where you choose",
                    icon = AppIcons.Export, onClick = { export.launch("focus-sounds-history-${LocalDate.now()}.csv") },
                    modifier = Modifier.testTag("export_csv"))
                SettingRow(
                    "Clear history",
                    description = when {
                        sessionActive -> "Stop the running session first"
                        finished == 0 -> "There is no history to clear"
                        else -> "Permanently delete all $finished recorded sessions"
                    },
                    icon = AppIcons.Trash,
                    onClick = if (!sessionActive && finished > 0) ({ confirmClear = true }) else null,
                    modifier = Modifier.testTag("clear_history"),
                )
            }
        }

        // ------------------------------------------------ About
        item { SectionLabel("About") }
        item {
            FocusCard(Modifier.fillMaxWidth()) {
                SettingRow("Focus Sounds", description = "Version ${vm.versionName} · works fully offline", icon = AppIcons.Info)
                SettingRow("Sounds", description = "Brown and White Noise are generated by the app's own build scripts. " +
                    "Ocean, Stream and Forest are CC0 recordings by craiggroshek, turbostream and klankbeeld (freesound.org).", icon = AppIcons.Volume)
            }
        }
    }

    if (picker) {
        ColorPickerDialog(s.accentArgb, onApply = { vm.setAccent(it); picker = false }, onDismiss = { picker = false })
    }
    goal?.let { kind ->
        if (kind == GoalKind.DAILY) {
            GoalDialog("Daily focus goal", s.dailyGoalMinutes, listOf(30, 60, 120, 180), 24 * 60,
                onConfirm = { m -> vm.update { it.copy(dailyGoalMinutes = m) }; goal = null }, onDismiss = { goal = null })
        } else {
            GoalDialog("Weekly focus goal", s.weeklyGoalMinutes, listOf(300, 600, 900, 1200), 7 * 24 * 60,
                onConfirm = { m -> vm.update { it.copy(weeklyGoalMinutes = m) }; goal = null }, onDismiss = { goal = null })
        }
    }
    if (customDefaultTimer) {
        MinutesDialog("Default timer", s.defaultTimerMinutes.takeIf { it !in Limits.PRESET_MINUTES && it != 0 },
            Limits.MIN_TIMED_MINUTES..Limits.MAX_TIMED_MINUTES,
            onConfirm = { m -> vm.update { it.copy(defaultTimerMinutes = m) }; customDefaultTimer = false },
            onDismiss = { customDefaultTimer = false })
    }
    if (confirmClear) {
        ConfirmDialog(
            title = "Delete all history?",
            message = "This permanently deletes $finished recorded ${if (finished == 1) "session" else "sessions"} from this device. Progress, streaks and charts will start over. Activities and settings are kept. Consider exporting a CSV first.",
            confirmLabel = "Delete history",
            onConfirm = { vm.clearHistory(); confirmClear = false },
            onDismiss = { confirmClear = false },
        )
    }
}

private fun goalText(minutes: Int, unit: String) = if (minutes == 0) "Off" else "${Durations.format(minutes * 60_000L)} $unit"

@Composable
private fun DefaultActivityPicker(options: List<Pair<Long, String>>, selectedId: Long, onSelect: (Long) -> Unit) {
    val c = FocusTheme.colors
    var open by remember { mutableStateOf(false) }
    val label = options.firstOrNull { it.first == selectedId }?.second ?: options.firstOrNull()?.second ?: "—"
    Box {
        SettingRow("Default activity", description = label, icon = AppIcons.Activities, onClick = { open = true },
            modifier = Modifier.semantics { contentDescription = "Default activity: $label" }) {
            Icon(AppIcons.ChevronDown, null, tint = c.textTertiary)
        }
        DropdownMenu(open, { open = false }, containerColor = c.surface2) {
            options.forEach { (id, name) ->
                DropdownMenuItem(text = { Text(name, color = c.textPrimary) }, onClick = { onSelect(id); open = false })
            }
        }
    }
}
