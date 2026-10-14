package com.focussounds.ui.charts

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focussounds.ui.theme.FocusTheme
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

private const val NONE = -1

private data class Plot(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
}

/**
 * Area/line chart of a value over time with a scrubbable selection.
 * Values are minutes for [AxisKind.MINUTES].
 */
@Composable
fun TrendChart(
    data: List<ChartDatum>,
    modifier: Modifier = Modifier,
    height: Dp = 220.dp,
    axis: AxisKind = AxisKind.MINUTES,
    summary: String,
) {
    val c = FocusTheme.colors
    val motion = FocusTheme.motion
    val haptics = FocusTheme.haptics
    val measurer = rememberTextMeasurer()
    var selected by remember(data.size, data.firstOrNull()?.title) { mutableIntStateOf(NONE) }
    val animated = animatedValues(data.map { it.value.toFloat() }, motion)
    val scale = remember(data) { AxisScale.of(data.filterNot { it.future }.maxOfOrNull { it.value } ?: 0.0, axis) }
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = c.textTertiary, fontFeatureSettings = "tnum")
    val tipTitle = MaterialTheme.typography.labelMedium.copy(color = c.textSecondary)
    val tipBody = MaterialTheme.typography.titleSmall.copy(color = c.textPrimary, fontFeatureSettings = "tnum")
    val tipNote = MaterialTheme.typography.labelSmall.copy(color = c.warning)

    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
            .semantics { contentDescription = summary }
            .pointerInput(data) {
                fun pick(x: Float) {
                    if (data.isEmpty()) return
                    val yLabelW = 40.dp.toPx()
                    val w = size.width - yLabelW
                    val i = if (data.size == 1) 0 else ((x - yLabelW) / w * (data.size - 1)).roundToInt().coerceIn(0, data.size - 1)
                    val idx = if (data[i].future) NONE else i
                    if (idx != selected) {
                        selected = idx
                        if (idx != NONE) haptics.tick()
                    }
                }
                detectTapGestures { pick(it.x) }
            }
            .pointerInput(data) {
                detectHorizontalDragGestures(onDragEnd = {}) { change, _ ->
                    val yLabelW = 40.dp.toPx()
                    val w = size.width - yLabelW
                    val i = if (data.size <= 1) 0 else ((change.position.x - yLabelW) / w * (data.size - 1)).roundToInt().coerceIn(0, data.size - 1)
                    val idx = if (data.getOrNull(i)?.future == true) NONE else i
                    if (idx != selected) {
                        selected = idx
                        if (idx != NONE) haptics.tick()
                    }
                }
            },
    ) {
        if (data.isEmpty()) return@Canvas
        val plot = Plot(40.dp.toPx(), 12.dp.toPx(), size.width - 6.dp.toPx(), size.height - 24.dp.toPx())
        fun y(v: Float) = plot.bottom - (v / scale.max.toFloat()) * plot.height
        fun x(i: Int) = if (data.size == 1) plot.left + plot.width / 2 else plot.left + plot.width * i / (data.size - 1)

        // Grid + y labels
        scale.ticks.forEach { t ->
            val yy = y(t.toFloat())
            drawLine(c.outline, Offset(plot.left, yy), Offset(plot.right, yy), 1.dp.toPx(),
                pathEffect = if (t == 0.0) null else PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())))
            val l = measurer.measure(scale.label(t), labelStyle)
            drawText(l, topLeft = Offset(plot.left - l.size.width - 8.dp.toPx(), yy - l.size.height / 2))
        }
        // X labels with a stride that avoids overlap
        val widest = data.maxOf { measurer.measure(it.axisLabel, labelStyle).size.width }.toFloat()
        val spacing = if (data.size <= 1) plot.width else plot.width / (data.size - 1)
        val stride = max(1, ceil((widest + 10.dp.toPx()) / spacing).toInt())
        // Every stride-th label, plus the last one when it does not crowd its neighbor.
        val labelled = (data.indices step stride).toMutableList()
        if (data.lastIndex !in labelled && data.lastIndex - labelled.last() >= max(1, stride * 2 / 3)) labelled += data.lastIndex
        labelled.forEach { i ->
            val d = data[i]
            val l = measurer.measure(d.axisLabel, labelStyle.copy(color = if (d.future) c.textTertiary.copy(alpha = 0.5f) else c.textTertiary))
            val lx = (x(i) - l.size.width / 2).coerceIn(plot.left - 4.dp.toPx(), size.width - l.size.width.toFloat())
            drawText(l, topLeft = Offset(lx, plot.bottom + 6.dp.toPx()))
        }

        val pts = data.indices.filter { !data[it].future }.map { Offset(x(it), y(animated[it])) }
        if (pts.isNotEmpty()) {
            val line = monotonePath(pts)
            val area = Path().apply {
                addPath(line)
                lineTo(pts.last().x, plot.bottom)
                lineTo(pts.first().x, plot.bottom)
                close()
            }
            drawPath(area, Brush.verticalGradient(listOf(c.accent.copy(alpha = 0.32f), c.accent.copy(alpha = 0.02f)), plot.top, plot.bottom))
            drawPath(line, c.accent, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            if (data.size <= 31) {
                data.indices.filter { !data[it].future }.forEach { i ->
                    val p = Offset(x(i), y(animated[i]))
                    if (data[i].partial) {
                        drawCircle(c.surface1, 4.dp.toPx(), p)
                        drawCircle(c.accent, 4.dp.toPx(), p, style = Stroke(1.6.dp.toPx()))
                    } else {
                        drawCircle(c.accent, 3.dp.toPx(), p)
                    }
                }
            }
        }
        if (selected in data.indices) {
            val d = data[selected]
            val p = Offset(x(selected), y(animated[selected]))
            drawLine(c.textTertiary.copy(alpha = 0.6f), Offset(p.x, plot.top), Offset(p.x, plot.bottom), 1.dp.toPx())
            drawCircle(c.accent.copy(alpha = 0.25f), 11.dp.toPx(), p)
            drawCircle(c.accent, 5.5.dp.toPx(), p)
            drawCircle(c.surface1, 2.5.dp.toPx(), p)
            tooltip(measurer, p, d.title, d.detail, d.note, tipTitle, tipBody, tipNote, c.surface3, c.outlineStrong)
        }
    }
}

/**
 * Vertical bar chart with optional goal line. Bars meeting the goal are solid; others lighter.
 * Values are minutes for [AxisKind.MINUTES]; [goal] uses the same unit.
 */
@Composable
fun BarChart(
    data: List<ChartDatum>,
    modifier: Modifier = Modifier,
    height: Dp = 200.dp,
    axis: AxisKind = AxisKind.MINUTES,
    goal: Double? = null,
    goalLabel: String? = null,
    summary: String,
    highlightMax: Boolean = false,
) {
    val c = FocusTheme.colors
    val motion = FocusTheme.motion
    val haptics = FocusTheme.haptics
    val measurer = rememberTextMeasurer()
    var selected by remember(data.size, data.firstOrNull()?.title) { mutableIntStateOf(NONE) }
    val animated = animatedValues(data.map { it.value.toFloat() }, motion)
    val scale = remember(data, goal) { AxisScale.of(max(data.maxOfOrNull { it.value } ?: 0.0, goal ?: 0.0), axis) }
    val maxIndex = remember(data) { if (highlightMax) data.indices.maxByOrNull { data[it].value }?.takeIf { data[it].value > 0 } ?: NONE else NONE }
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = c.textTertiary, fontFeatureSettings = "tnum")
    val tipTitle = MaterialTheme.typography.labelMedium.copy(color = c.textSecondary)
    val tipBody = MaterialTheme.typography.titleSmall.copy(color = c.textPrimary, fontFeatureSettings = "tnum")
    val tipNote = MaterialTheme.typography.labelSmall.copy(color = c.warning)
    val goalStyle = MaterialTheme.typography.labelSmall.copy(color = c.accentContent, fontWeight = FontWeight.SemiBold)

    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
            .semantics { contentDescription = summary }
            .pointerInput(data) {
                detectTapGestures { pos ->
                    if (data.isEmpty()) return@detectTapGestures
                    val left = 40.dp.toPx()
                    val slot = (size.width - left) / data.size
                    val i = ((pos.x - left) / slot).toInt()
                    val idx = if (i in data.indices && !data[i].future) i else NONE
                    selected = if (idx == selected) NONE else idx
                    if (selected != NONE) haptics.tick()
                }
            },
    ) {
        if (data.isEmpty()) return@Canvas
        val plot = Plot(40.dp.toPx(), 14.dp.toPx(), size.width, size.height - 24.dp.toPx())
        fun y(v: Float) = plot.bottom - (v / scale.max.toFloat()) * plot.height
        scale.ticks.forEach { t ->
            val yy = y(t.toFloat())
            drawLine(c.outline, Offset(plot.left, yy), Offset(plot.right, yy), 1.dp.toPx(),
                pathEffect = if (t == 0.0) null else PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())))
            val l = measurer.measure(scale.label(t), labelStyle)
            drawText(l, topLeft = Offset(plot.left - l.size.width - 8.dp.toPx(), yy - l.size.height / 2))
        }
        val slot = plot.width / data.size
        val barW = (slot * 0.62f).coerceAtMost(36.dp.toPx())
        val widest = data.maxOf { measurer.measure(it.axisLabel, labelStyle).size.width }.toFloat()
        val stride = max(1, ceil((widest + 8.dp.toPx()) / slot).toInt())
        data.forEachIndexed { i, d ->
            val cx = plot.left + slot * (i + 0.5f)
            val v = animated[i]
            val meets = goal != null && d.value >= goal
            val base = when {
                d.future -> c.surface3
                selected != NONE && selected != i -> c.accent.copy(alpha = 0.3f)
                goal != null && !meets -> c.accent.copy(alpha = 0.5f)
                d.partial -> c.accent.copy(alpha = 0.55f)
                else -> c.accent
            }
            if (d.future) {
                drawRoundRect(c.surface3, Offset(cx - barW / 2, plot.bottom - 3.dp.toPx()), Size(barW, 3.dp.toPx()), CornerRadius(2.dp.toPx()))
            } else if (v > 0f) {
                val top = y(v).coerceAtMost(plot.bottom - 2.dp.toPx())
                drawPath(topRoundedBar(cx - barW / 2, top, cx + barW / 2, plot.bottom, 6.dp.toPx()), base)
                if (i == maxIndex && selected == NONE) drawCircle(c.accentContent, 2.5.dp.toPx(), Offset(cx, top - 7.dp.toPx()))
            }
            if (i % stride == 0) {
                val l = measurer.measure(d.axisLabel, labelStyle.copy(color = if (i == selected) c.textPrimary else c.textTertiary))
                drawText(l, topLeft = Offset(cx - l.size.width / 2, plot.bottom + 6.dp.toPx()))
            }
        }
        if (goal != null && goal > 0) {
            val gy = y(goal.toFloat())
            drawLine(c.accentContent, Offset(plot.left, gy), Offset(plot.right, gy), 1.5.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx())))
            goalLabel?.let {
                val l = measurer.measure(it, goalStyle)
                drawRoundRect(c.background.copy(alpha = 0.85f), Offset(plot.right - l.size.width - 10.dp.toPx(), gy - l.size.height - 4.dp.toPx()),
                    Size(l.size.width + 8.dp.toPx(), l.size.height.toFloat()), CornerRadius(4.dp.toPx()))
                drawText(l, topLeft = Offset(plot.right - l.size.width - 6.dp.toPx(), gy - l.size.height - 4.dp.toPx()))
            }
        }
        if (selected in data.indices) {
            val d = data[selected]
            val cx = plot.left + slot * (selected + 0.5f)
            val top = y(animated[selected])
            tooltip(measurer, Offset(cx, top), d.title, d.detail, d.note, tipTitle, tipBody, tipNote, c.surface3, c.outlineStrong)
        }
    }
}

data class DonutSlice(val label: String, val value: Double, val color: Color, val detail: String)

/** Donut with tappable segments. Selection is hoisted so the legend can drive it too. */
@Composable
fun DonutChart(
    slices: List<DonutSlice>,
    selected: Int,
    onSelect: (Int) -> Unit,
    centerTitle: String,
    centerValue: String,
    centerDetail: String?,
    summary: String,
    modifier: Modifier = Modifier,
    diameter: Dp = 210.dp,
) {
    val c = FocusTheme.colors
    val motion = FocusTheme.motion
    val haptics = FocusTheme.haptics
    val sweep = remember { Animatable(if (motion.enabled) 0f else 1f) }
    LaunchedEffect(slices.map { it.value }) {
        if (motion.enabled) {
            sweep.snapTo(0f)
            sweep.animateTo(1f, motion.spec(800))
        } else {
            sweep.snapTo(1f)
        }
    }
    val total = slices.sumOf { it.value }
    Box(modifier.size(diameter).semantics { contentDescription = summary }, contentAlignment = Alignment.Center) {
        Canvas(
            Modifier
                .size(diameter)
                .pointerInput(slices) {
                    detectTapGestures { pos ->
                        val cx = size.width / 2f
                        val cy = size.height / 2f
                        val r = hypot(pos.x - cx, pos.y - cy)
                        val outer = size.width / 2f
                        if (r < outer * 0.55f || r > outer) {
                            onSelect(NONE)
                            return@detectTapGestures
                        }
                        var ang = Math.toDegrees(atan2((pos.y - cy).toDouble(), (pos.x - cx).toDouble())) + 90.0
                        if (ang < 0) ang += 360.0
                        var acc = 0.0
                        val idx = slices.indexOfFirst { s ->
                            acc += s.value / total * 360.0
                            ang <= acc
                        }
                        val next = if (idx == selected) NONE else idx
                        onSelect(next)
                        if (next != NONE) haptics.tick()
                    }
                },
        ) {
            if (total <= 0) return@Canvas
            val stroke = 26.dp.toPx()
            val gap = if (slices.size > 1) 1.8f else 0f
            var start = -90f
            slices.forEachIndexed { i, s ->
                val full = (s.value / total * 360.0).toFloat()
                val sw = (full * sweep.value - gap).coerceAtLeast(0.5f)
                val isSel = i == selected
                val w = if (isSel) stroke + 8.dp.toPx() else stroke
                val inset = stroke / 2 + 4.dp.toPx()
                val color = if (selected != NONE && !isSel) s.color.copy(alpha = 0.35f) else s.color
                drawArc(color, start + gap / 2, sw, false, Offset(inset, inset), Size(size.width - inset * 2, size.height - inset * 2), style = Stroke(w, cap = StrokeCap.Butt))
                start += full * sweep.value
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 40.dp)) {
            Text(centerTitle, style = MaterialTheme.typography.labelMedium, color = c.textTertiary, textAlign = TextAlign.Center, maxLines = 2)
            Text(centerValue, style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"), color = c.textPrimary, textAlign = TextAlign.Center, maxLines = 1)
            if (centerDetail != null) Text(centerDetail, style = MaterialTheme.typography.labelMedium, color = c.accentContent, textAlign = TextAlign.Center)
        }
    }
}

/** Animated fraction for simple Compose-drawn bars. */
@Composable
fun animatedFraction(target: Float): Float {
    val motion = FocusTheme.motion
    val v by animateFloatAsState(target, motion.spec(700), label = "fraction")
    return v
}

/** Tick label style shared by Compose-drawn charts. */
@Composable
fun chartLabelStyle(): TextStyle = MaterialTheme.typography.labelSmall.copy(color = FocusTheme.colors.textTertiary, fontSize = 11.sp, fontFeatureSettings = "tnum")
