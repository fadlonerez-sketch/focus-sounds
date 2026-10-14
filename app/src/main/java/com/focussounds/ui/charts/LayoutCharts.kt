package com.focussounds.ui.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.focussounds.core.Durations
import com.focussounds.core.analytics.CalendarCell
import com.focussounds.core.analytics.CalendarMonth
import com.focussounds.core.analytics.CellState
import com.focussounds.ui.components.AppIcons
import com.focussounds.ui.components.LegendSwatch
import com.focussounds.ui.theme.FocusTheme
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private val LEVEL_ALPHA = listOf(0f, 0.22f, 0.4f, 0.6f, 0.8f, 1f)
private val MONTH_FORMAT = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())

/**
 * Month calendar heatmap: one large, tappable cell per day. Future days and days before tracking
 * began are drawn differently from tracked zero-focus days.
 */
@Composable
fun CalendarHeatmap(
    month: CalendarMonth,
    selected: LocalDate?,
    onSelect: (LocalDate?) -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = FocusTheme.colors
    val haptics = FocusTheme.haptics
    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onPrev, enabled = month.canGoBack) {
                Icon(AppIcons.ChevronLeft, "Previous month", tint = if (month.canGoBack) c.textPrimary else c.textTertiary.copy(alpha = 0.4f))
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(MONTH_FORMAT.format(month.month), style = MaterialTheme.typography.titleSmall, color = c.textPrimary)
                Text("${Durations.format(month.totalMs)} · ${month.focusDays} focus ${if (month.focusDays == 1) "day" else "days"}",
                    style = MaterialTheme.typography.labelSmall, color = c.textTertiary)
            }
            IconButton(onClick = onNext, enabled = month.canGoForward) {
                Icon(AppIcons.ChevronRight, "Next month", tint = if (month.canGoForward) c.textPrimary else c.textTertiary.copy(alpha = 0.4f))
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth()) {
            month.weeks.first().forEach { cell ->
                Text(
                    cell.date.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = c.textTertiary,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        month.weeks.forEach { week ->
            Row(Modifier.fillMaxWidth()) {
                week.forEach { cell ->
                    Box(Modifier.weight(1f).aspectRatio(1f).padding(2.5.dp)) {
                        if (cell.state != CellState.NOT_IN_MONTH) {
                            DayCell(cell, cell.date == selected) {
                                haptics.tick()
                                onSelect(if (cell.date == selected) null else cell.date)
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        HeatLegend()
    }
}

@Composable
private fun DayCell(cell: CalendarCell, isSelected: Boolean, onClick: () -> Unit) {
    val c = FocusTheme.colors
    val shape = RoundedCornerShape(10.dp)
    val tracked = cell.state == CellState.TRACKED
    val fill = when {
        !tracked -> Color.Transparent
        cell.level == 0 -> c.surface2
        else -> c.accent.copy(alpha = LEVEL_ALPHA[cell.level])
    }
    val textColor = when {
        cell.state == CellState.FUTURE -> c.textTertiary.copy(alpha = 0.45f)
        cell.state == CellState.NOT_TRACKED -> c.textTertiary.copy(alpha = 0.7f)
        cell.level >= 4 -> c.onAccent
        cell.level == 0 -> c.textTertiary
        else -> c.textPrimary
    }
    val desc = when (cell.state) {
        CellState.FUTURE -> "${cell.date}: upcoming"
        CellState.NOT_TRACKED -> "${cell.date}: before tracking started"
        else -> "${cell.date}: ${Durations.format(cell.activeMs)}, ${cell.sessions} sessions" + if (cell.goalMet) ", goal met" else ""
    }
    Box(
        Modifier
            .fillMaxSize()
            .clip(shape)
            .background(fill)
            .then(
                when {
                    isSelected -> Modifier.border(2.dp, c.textPrimary, shape)
                    cell.isToday -> Modifier.border(1.5.dp, c.accentContent, shape)
                    cell.state == CellState.NOT_TRACKED -> Modifier.drawBehind {
                        drawRoundRect(c.outline, cornerRadius = CornerRadius(10.dp.toPx()),
                            style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx()))))
                    }
                    else -> Modifier
                },
            )
            .clickable(enabled = tracked, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = desc },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(cell.date.dayOfMonth.toString(), style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"), color = textColor,
                fontWeight = if (cell.isToday) FontWeight.Bold else FontWeight.Medium)
            if (cell.goalMet) {
                Box(Modifier.padding(top = 2.dp).size(4.dp).clip(CircleShape).background(if (cell.level >= 4) c.onAccent else c.accentContent))
            }
        }
    }
}

@Composable
private fun HeatLegend() {
    val c = FocusTheme.colors
    val labels = listOf("0", "<15m", "15–30m", "30–60m", "1–2h", "2h+")
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
            labels.forEachIndexed { i, label ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(3.dp))
                            .background(if (i == 0) c.surface2 else c.accent.copy(alpha = LEVEL_ALPHA[i])),
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(label, style = MaterialTheme.typography.labelSmall, color = c.textTertiary, maxLines = 1)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(5.dp).clip(CircleShape).background(c.accentContent))
            Text(" Daily goal met    ", style = MaterialTheme.typography.labelSmall, color = c.textTertiary)
            Box(
                Modifier.size(10.dp).drawBehind {
                    drawRoundRect(c.outlineStrong, cornerRadius = CornerRadius(3.dp.toPx()),
                        style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 2.dp.toPx()))))
                },
            )
            Text(" Before tracking started", style = MaterialTheme.typography.labelSmall, color = c.textTertiary)
        }
    }
}

data class UsageRow(
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector?,
    val valueLabel: String,
    val detail: String,
    val fraction: Float,
    val color: Color,
)

/** Horizontal usage bars (e.g. sounds). Bar length is relative to the largest row. */
@Composable
fun UsageBars(rows: List<UsageRow>, modifier: Modifier = Modifier) {
    val c = FocusTheme.colors
    val max = rows.maxOfOrNull { it.fraction }?.takeIf { it > 0f } ?: 1f
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        rows.forEach { r ->
            val f = animatedFraction(r.fraction / max)
            Column(Modifier.semantics(mergeDescendants = true) { contentDescription = "${r.label}: ${r.valueLabel}, ${r.detail}" }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (r.icon != null) {
                        Icon(r.icon, null, Modifier.size(18.dp), tint = if (r.fraction > 0) c.accentContent else c.textTertiary)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(r.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(r.valueLabel, style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"), color = c.textPrimary)
                }
                Spacer(Modifier.height(6.dp))
                Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(c.surface3)) {
                    if (f > 0f) Box(Modifier.fillMaxHeight().fillMaxWidth(f).clip(RoundedCornerShape(4.dp)).background(r.color))
                }
                Spacer(Modifier.height(4.dp))
                Text(r.detail, style = MaterialTheme.typography.labelSmall, color = c.textTertiary)
            }
        }
    }
}

data class StackSegment(val label: String, val count: Int, val color: Color, val detail: String)

/** Single stacked bar with a labeled legend underneath (labels + counts, never color alone). */
@Composable
fun StackedBar(segments: List<StackSegment>, modifier: Modifier = Modifier) {
    val c = FocusTheme.colors
    val total = segments.sumOf { it.count }.coerceAtLeast(1)
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp)).background(c.surface3)) {
            segments.filter { it.count > 0 }.forEachIndexed { i, s ->
                if (i > 0) Spacer(Modifier.width(2.dp))
                Box(Modifier.weight(s.count.toFloat()).fillMaxHeight().background(s.color))
            }
        }
        Spacer(Modifier.height(12.dp))
        segments.forEach { s ->
            Row(Modifier.fillMaxWidth().heightIn(min = 30.dp), verticalAlignment = Alignment.CenterVertically) {
                LegendSwatch(s.color, square = true)
                Spacer(Modifier.width(10.dp))
                Text(s.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = c.textPrimary)
                Text(s.detail, style = MaterialTheme.typography.labelSmall, color = c.textTertiary)
                Spacer(Modifier.width(10.dp))
                Text("${s.count}", style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"), color = c.textPrimary)
                Text("  ${Durations.percent(s.count.toDouble() / total)}", style = MaterialTheme.typography.labelSmall, color = c.textTertiary)
            }
        }
    }
}

data class GoalDot(val dayLabel: String, val fraction: Float, val met: Boolean, val description: String)

/** Row of small rings, one per day: filled with a check when the goal was met. */
@Composable
fun GoalStrip(days: List<GoalDot>, modifier: Modifier = Modifier) {
    val c = FocusTheme.colors
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        days.forEach { d ->
            Column(
                Modifier.weight(1f).semantics(mergeDescendants = true) { contentDescription = d.description },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val f = animatedFraction(d.fraction.coerceIn(0f, 1f))
                Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
                    Canvas(Modifier.size(22.dp)) {
                        val sw = 3.dp.toPx()
                        val inset = sw / 2
                        val sz = androidx.compose.ui.geometry.Size(size.width - sw, size.height - sw)
                        val tl = androidx.compose.ui.geometry.Offset(inset, inset)
                        if (d.met) {
                            drawCircle(c.accent)
                        } else {
                            drawArc(c.surface3, 0f, 360f, false, tl, sz, style = Stroke(sw))
                            if (f > 0f) drawArc(c.accent, -90f, 360f * f, false, tl, sz, style = Stroke(sw, cap = androidx.compose.ui.graphics.StrokeCap.Round))
                        }
                    }
                    if (d.met) Icon(AppIcons.Check, null, Modifier.size(13.dp), tint = c.onAccent)
                }
                Spacer(Modifier.height(4.dp))
                Text(d.dayLabel, style = MaterialTheme.typography.labelSmall, color = c.textTertiary, maxLines = 1)
            }
        }
    }
}
