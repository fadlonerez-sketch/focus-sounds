package com.focussounds.ui.screens

import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.focussounds.core.ActiveSession
import com.focussounds.core.Durations
import com.focussounds.core.EndReason
import com.focussounds.core.FocusSound
import com.focussounds.core.Limits
import com.focussounds.core.PauseCause
import com.focussounds.core.Phase
import com.focussounds.data.ActivityEntity
import com.focussounds.ui.FocusViewModel
import com.focussounds.ui.SessionFeedback
import com.focussounds.ui.TodaySummary
import com.focussounds.ui.components.ActivityBadge
import com.focussounds.ui.components.AppIcons
import com.focussounds.ui.components.Eyebrow
import com.focussounds.ui.components.PrimaryButton
import com.focussounds.ui.components.ProgressRing
import com.focussounds.ui.components.ScreenPadding
import com.focussounds.ui.components.SecondaryButton
import com.focussounds.ui.components.SegmentOption
import com.focussounds.ui.components.SegmentedControl
import com.focussounds.ui.components.SelectChip
import com.focussounds.ui.components.tagline
import com.focussounds.ui.theme.FocusTheme
import kotlinx.coroutines.delay
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun FocusScreen(vm: FocusViewModel, onStartRequested: () -> Unit, onOpenProgress: () -> Unit) {
    val engine by vm.engineState.collectAsStateWithLifecycle()
    val countdown by vm.countdown.collectAsStateWithLifecycle()
    val mode = when {
        engine.active != null -> FocusMode.ACTIVE
        countdown != null -> FocusMode.COUNTDOWN
        else -> FocusMode.IDLE
    }
    val motion = FocusTheme.motion
    AnimatedContent(
        mode,
        transitionSpec = { fadeIn(motion.spec(350)) togetherWith fadeOut(motion.spec(250)) },
        label = "focusMode",
        modifier = Modifier.fillMaxSize(),
    ) { m ->
        when (m) {
            FocusMode.ACTIVE -> engine.active?.let { ActiveSessionView(it, engine.volume, vm) } ?: Box(Modifier.fillMaxSize())
            FocusMode.COUNTDOWN -> CountdownView(countdown ?: 0, onCancel = vm::cancelCountdown)
            FocusMode.IDLE -> IdleView(vm, engine.volume, engine.playerAvailable, onStartRequested, onOpenProgress)
        }
    }
}

private enum class FocusMode { IDLE, COUNTDOWN, ACTIVE }

// ---------------------------------------------------------------- idle

@Composable
private fun IdleView(vm: FocusViewModel, volume: Float, playerAvailable: Boolean, onStart: () -> Unit, onOpenProgress: () -> Unit) {
    val c = FocusTheme.colors
    val activities by vm.activities.collectAsStateWithLifecycle()
    val sel by vm.selection.collectAsStateWithLifecycle()
    val feedback by vm.feedback.collectAsStateWithLifecycle()
    val today by vm.today.collectAsStateWithLifecycle()
    var showCustom by remember { mutableStateOf(false) }
    var showAdd by remember { mutableStateOf(false) }
    val current = vm.effectiveActivity(activities, sel)

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = ScreenPadding).testTag("focus_idle"),
    ) {
        Spacer(Modifier.height(12.dp))
        IdleHeader(today, onOpenProgress)
        AnimatedVisibility(feedback != null, enter = FocusTheme.motion.expandIn(), exit = FocusTheme.motion.collapseOut()) {
            feedback?.let { FeedbackBanner(it, vm::dismissFeedback) }
        }
        Spacer(Modifier.height(18.dp))

        Eyebrow("Activity")
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            activities.forEach { a ->
                SelectChip(
                    label = a.name,
                    selected = a.id == current?.id,
                    onClick = { vm.selectActivity(a.id) },
                    leading = { ActivityBadge(a.name, AppIcons.forBuiltIn(a.builtInKey), size = 22.dp) },
                )
            }
            SelectChip("New", selected = false, onClick = { showAdd = true }, leading = {
                Icon(AppIcons.Plus, null, Modifier.size(18.dp), tint = c.accentContent)
            })
        }

        Spacer(Modifier.height(20.dp))
        Eyebrow("Sound")
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FocusSound.entries.forEach { s -> SoundCard(s, s == sel.sound) { vm.selectSound(s) } }
        }

        Spacer(Modifier.height(20.dp))
        Eyebrow("Timer")
        Spacer(Modifier.height(8.dp))
        val custom = sel.timerMinutes?.takeIf { it !in Limits.PRESET_MINUTES }
        SegmentedControl(
            options = listOf(SegmentOption<Int?>(null, "None", AppIcons.Infinity, "No timer")) +
                Limits.PRESET_MINUTES.map { SegmentOption<Int?>(it, "$it min") } +
                SegmentOption<Int?>(-1, if (custom != null) "${custom} min" else "Custom", AppIcons.Edit),
            selected = custom?.let { -1 } ?: sel.timerMinutes,
            onSelect = { if (it == -1) showCustom = true else vm.selectTimer(it) },
            modifier = Modifier.testTag("timer_selector"),
        )

        Spacer(Modifier.height(20.dp))
        VolumeRow(volume, vm::setVolume)

        Spacer(Modifier.height(18.dp))
        PrimaryButton(
            text = if (!playerAvailable) "Preparing player…" else "Start focusing",
            onClick = onStart,
            enabled = playerAvailable && current != null,
            icon = AppIcons.Play,
            modifier = Modifier.fillMaxWidth().testTag("start_button"),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            listOfNotNull(current?.name, sel.sound.displayName, sel.timerMinutes?.let { Durations.format(it * 60_000L) } ?: "no timer").joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = c.textTertiary,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
        Spacer(Modifier.height(24.dp))
    }

    if (showCustom) {
        MinutesDialog(
            "Custom duration",
            sel.timerMinutes?.takeIf { it !in Limits.PRESET_MINUTES },
            Limits.MIN_TIMED_MINUTES..Limits.MAX_TIMED_MINUTES,
            onConfirm = { vm.selectTimer(it); showCustom = false },
            onDismiss = { showCustom = false },
        )
    }
    if (showAdd) NameDialog("New activity", "", onConfirm = { vm.addActivity(it) { showAdd = false } }, onDismiss = { showAdd = false })
}

@Composable
private fun IdleHeader(today: TodaySummary, onOpenProgress: () -> Unit) {
    val c = FocusTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Ready to focus", style = MaterialTheme.typography.headlineSmall, color = c.textPrimary, modifier = Modifier.semantics { heading() })
            Text("Choose an activity, a sound and a timer.", style = MaterialTheme.typography.bodySmall, color = c.textTertiary)
        }
        Row(
            Modifier.clip(RoundedCornerShape(16.dp)).background(c.surface1).border(1.dp, c.outline, RoundedCornerShape(16.dp))
                .clickable(role = Role.Button, onClick = onOpenProgress).padding(horizontal = 10.dp, vertical = 8.dp)
                .semantics(mergeDescendants = true) { contentDescription = "Today ${Durations.format(today.focusMs)}. Open progress." },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val goal = today.dailyGoalMs
            if (goal != null) {
                ProgressRing(today.focusMs.toFloat() / goal, size = 30.dp, stroke = 4.dp)
            } else {
                Icon(AppIcons.Progress, null, Modifier.size(20.dp), tint = c.accentContent)
            }
            Spacer(Modifier.width(8.dp))
            Column {
                Text("Today", style = MaterialTheme.typography.labelSmall, color = c.textTertiary)
                Text(Durations.short(today.focusMs), style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"), color = c.textPrimary)
            }
        }
    }
}

@Composable
private fun SoundCard(sound: FocusSound, selected: Boolean, onClick: () -> Unit) {
    val c = FocusTheme.colors
    val haptics = FocusTheme.haptics
    val shape = RoundedCornerShape(20.dp)
    Column(
        Modifier
            .width(132.dp)
            .clip(shape)
            .background(if (selected) c.accentContainer else c.surface1)
            .border(if (selected) 1.5.dp else 1.dp, if (selected) c.accent else c.outline, shape)
            .selectable(selected, role = Role.RadioButton) {
                haptics.tick()
                onClick()
            }
            .padding(14.dp)
            .semantics { stateDescription = if (selected) "Selected" else "Not selected" }
            .testTag("sound_${sound.id}"),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(if (selected) c.accent else c.surface3), contentAlignment = Alignment.Center) {
                Icon(AppIcons.forSound(sound), null, Modifier.size(22.dp), tint = if (selected) c.onAccent else c.textSecondary)
            }
            Spacer(Modifier.weight(1f))
            if (selected) Icon(AppIcons.Check, null, Modifier.size(18.dp), tint = c.accentContent)
        }
        Spacer(Modifier.height(14.dp))
        Text(sound.displayName, style = MaterialTheme.typography.titleSmall, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(sound.tagline(), style = MaterialTheme.typography.labelSmall, color = c.textTertiary, maxLines = 2, minLines = 2)
    }
}

@Composable
private fun VolumeRow(volume: Float, onChange: (Float) -> Unit) {
    val c = FocusTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Icon(AppIcons.Volume, null, Modifier.size(20.dp), tint = c.textSecondary)
        Spacer(Modifier.width(12.dp))
        Slider(
            value = volume,
            onValueChange = onChange,
            modifier = Modifier.weight(1f).semantics { contentDescription = "Volume" }.testTag("volume_slider"),
            colors = SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = c.accent, inactiveTrackColor = c.surface3),
        )
        Spacer(Modifier.width(12.dp))
        Text("${(volume * 100).toInt()}%", style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"), color = c.textTertiary,
            modifier = Modifier.width(40.dp), textAlign = TextAlign.End)
    }
}

/** Compact, accurate feedback about the session that just ended. */
@Composable
private fun FeedbackBanner(f: SessionFeedback, onDismiss: () -> Unit) {
    val c = FocusTheme.colors
    val r = f.result
    val (icon, tint, title) = when (r.reason) {
        EndReason.COMPLETED -> Triple(AppIcons.Check, c.accentContent, "Session complete")
        EndReason.STOPPED -> Triple(AppIcons.Stop, c.textSecondary, "Session stopped")
        EndReason.INTERRUPTED -> Triple(AppIcons.Info, c.warning, "Session interrupted")
        EndReason.CANCELLED -> Triple(AppIcons.Close, c.textTertiary, "Session cancelled")
    }
    val detail = when (r.reason) {
        EndReason.CANCELLED -> "No focus time was recorded"
        else -> "${Durations.format(r.activeMs)} of ${r.activityName} · ${r.sound.displayName}" +
            (r.detail?.let { " · $it" } ?: "")
    }
    Column(Modifier.padding(top = 14.dp)) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.surface1).border(1.dp, c.outline, RoundedCornerShape(18.dp))
                .padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp)
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
                .testTag("feedback_banner"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(32.dp).clip(CircleShape).background(tint.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(16.dp), tint = tint)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = c.textPrimary)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = c.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                f.milestone?.let {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                        Icon(AppIcons.Goal, null, Modifier.size(14.dp), tint = c.accentContent)
                        Spacer(Modifier.width(6.dp))
                        Text(it, style = MaterialTheme.typography.labelMedium, color = c.accentContent)
                    }
                }
            }
            Box(Modifier.size(44.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onDismiss), contentAlignment = Alignment.Center) {
                Icon(AppIcons.Close, "Dismiss", Modifier.size(18.dp), tint = c.textTertiary)
            }
        }
    }
}

// ---------------------------------------------------------------- countdown

@Composable
private fun CountdownView(seconds: Int, onCancel: () -> Unit) {
    val c = FocusTheme.colors
    Column(Modifier.fillMaxSize().padding(ScreenPadding).testTag("countdown"), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("Get ready", style = MaterialTheme.typography.titleMedium, color = c.textSecondary)
        Spacer(Modifier.height(20.dp))
        ProgressRing(seconds / 10f, size = 180.dp, stroke = 8.dp) {
            val motion = FocusTheme.motion
            AnimatedContent(seconds, transitionSpec = { fadeIn(motion.spec(200)) togetherWith fadeOut(motion.spec(200)) }, label = "count") { s ->
                Text("$s", style = MaterialTheme.typography.displayLarge, color = c.textPrimary, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
        }
        Spacer(Modifier.height(28.dp))
        SecondaryButton("Cancel", onCancel, icon = AppIcons.Close)
    }
}

// ---------------------------------------------------------------- active session

@Composable
private fun ActiveSessionView(s: ActiveSession, volume: Float, vm: FocusViewModel) {
    val c = FocusTheme.colors
    val haptics = FocusTheme.haptics
    // Ticks only while visible; the engine never depends on the UI.
    val now by produceState(SystemClock.elapsedRealtime(), s) {
        while (true) {
            value = SystemClock.elapsedRealtime()
            delay(250)
        }
    }
    val remaining = s.remainingMs(now)
    val active = s.activeMs(now)
    val playing = s.phase == Phase.PLAYING
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = ScreenPadding).testTag("focus_active"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(AppIcons.forSound(s.sound), null, Modifier.size(18.dp), tint = c.accentContent)
            Spacer(Modifier.width(8.dp))
            Text(s.sound.displayName, style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
        }
        Text(s.activityName, style = MaterialTheme.typography.bodyMedium, color = c.textSecondary, maxLines = 2, textAlign = TextAlign.Center)
        Spacer(Modifier.height(18.dp))

        BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            val diameter = minOf(maxWidth, 320.dp)
            Box(Modifier.size(diameter), contentAlignment = Alignment.Center) {
                SessionRing(
                    fraction = s.plannedDurationMs?.let { active.toFloat() / it },
                    playing = playing,
                    modifier = Modifier.fillMaxSize(),
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        Durations.clock(remaining ?: active),
                        style = MaterialTheme.typography.displayLarge,
                        color = if (playing) c.textPrimary else c.textSecondary,
                        modifier = Modifier.testTag("session_time").semantics {
                            contentDescription = (if (remaining != null) "Remaining " else "Focused ") + Durations.format(remaining ?: active)
                        },
                    )
                    Text(
                        if (remaining != null) "remaining of ${Durations.format(s.plannedDurationMs!!)}" else "focused · no timer",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.textTertiary,
                    )
                    if (remaining != null && playing) {
                        val end = LocalTime.now().plusSeconds(remaining / 1000)
                        Text("Ends about ${end.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))}",
                            style = MaterialTheme.typography.labelSmall, color = c.textTertiary)
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        StatusLine(s)
        Spacer(Modifier.height(20.dp))

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            RoundButton(AppIcons.Stop, "Stop session", c.surface2, c.textPrimary, 64.dp, Modifier.testTag("stop_button")) {
                haptics.heavy()
                vm.stop()
            }
            val isPaused = s.phase == Phase.PAUSED
            RoundButton(
                if (isPaused) AppIcons.Play else AppIcons.Pause,
                if (isPaused) "Resume" else "Pause",
                c.accent, c.onAccent, 84.dp, Modifier.testTag("pause_resume_button"),
                enabled = s.phase != Phase.STARTING,
            ) {
                haptics.select()
                if (isPaused) vm.resume() else vm.pause()
            }
            Spacer(Modifier.size(64.dp))
        }
        Spacer(Modifier.height(24.dp))
        VolumeRow(volume, vm::setVolume)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun StatusLine(s: ActiveSession) {
    val c = FocusTheme.colors
    val (text, color) = when (s.phase) {
        Phase.STARTING -> "Starting…" to c.textSecondary
        Phase.PLAYING -> "Playing · keeps going with the screen locked" to c.textSecondary
        Phase.PAUSED -> when (s.pauseCause) {
            PauseCause.USER, null -> "Paused · the timer is stopped" to c.warning
            PauseCause.AUDIO_FOCUS_LOSS -> "Paused: another app started playing audio" to c.warning
            PauseCause.TRANSIENT_INTERRUPTION -> "Paused for a call or alarm · resumes automatically" to c.warning
            PauseCause.HEADPHONES_DISCONNECTED -> "Paused: headphones disconnected" to c.warning
            PauseCause.OTHER -> "Paused by the system" to c.warning
        }
    }
    Text(text, style = MaterialTheme.typography.bodyMedium, color = color, textAlign = TextAlign.Center,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("session_status"))
}

@Composable
private fun RoundButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    bg: androidx.compose.ui.graphics.Color,
    fg: androidx.compose.ui.graphics.Color,
    size: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val c = FocusTheme.colors
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(if (enabled) bg else c.surface3)
            .border(1.dp, if (bg == c.accent) bg else c.outline, CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, Modifier.size(size * 0.38f), tint = if (enabled) fg else c.textTertiary)
    }
}

/**
 * Timer ring with a purely decorative ambient glow. The animation does not measure or react to
 * the audio; it only breathes slowly while a session is playing (Full motion setting).
 */
@Composable
private fun SessionRing(fraction: Float?, playing: Boolean, modifier: Modifier) {
    val c = FocusTheme.colors
    val motion = FocusTheme.motion
    val ambient = motion.ambient && playing
    val transition = rememberInfiniteTransition(label = "ambient")
    val breath by if (ambient) {
        transition.animateFloat(0f, 1f, infiniteRepeatable(tween(4200, easing = LinearEasing), RepeatMode.Reverse), label = "breath")
    } else remember { mutableStateOf(0.5f) }
    val spin by if (motion.enabled && playing && fraction == null) {
        transition.animateFloat(0f, 360f, infiniteRepeatable(tween(if (motion.ambient) 14000 else 28000, easing = LinearEasing)), label = "spin")
    } else remember { mutableStateOf(0f) }

    Canvas(modifier) {
        val w = size.minDimension
        val center = Offset(size.width / 2, size.height / 2)
        val stroke = 10.dp.toPx()
        val radius = w / 2 - stroke - 14.dp.toPx()
        // Soft ambient halo
        val haloAlpha = if (playing) 0.10f + 0.10f * breath else 0.05f
        drawCircle(
            Brush.radialGradient(listOf(c.accent.copy(alpha = haloAlpha), c.accent.copy(alpha = 0f)), center, w / 2),
            radius = w / 2,
            center = center,
        )
        if (ambient) {
            drawCircle(c.accent.copy(alpha = 0.10f * (1 - breath)), radius + 8.dp.toPx() + 10.dp.toPx() * breath, center, style = Stroke(1.5.dp.toPx()))
        }
        val tl = Offset(center.x - radius, center.y - radius)
        val sz = Size(radius * 2, radius * 2)
        drawArc(c.surface3, 0f, 360f, false, tl, sz, style = Stroke(stroke))
        if (fraction != null) {
            val sweep = 360f * fraction.coerceIn(0f, 1f)
            if (sweep > 0f) drawArc(if (playing) c.accent else c.accent.copy(alpha = 0.5f), -90f, sweep, false, tl, sz, style = Stroke(stroke, cap = StrokeCap.Round))
        } else {
            // No timer: a slow orbiting accent arc signals "running", never a progress amount.
            rotate(spin, center) {
                drawArc(
                    Brush.sweepGradient(listOf(c.accent.copy(alpha = 0f), c.accent.copy(alpha = if (playing) 0.9f else 0.35f)), center),
                    -90f, 120f, false, tl, sz, style = Stroke(stroke, cap = StrokeCap.Round),
                )
            }
        }
    }
}

/** Helper for screens that need an activity's icon. */
fun ActivityEntity.icon() = AppIcons.forBuiltIn(builtInKey)
