package com.focussounds.ui.charts

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.dp
import com.focussounds.core.Durations
import com.focussounds.ui.theme.Motion
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sqrt

/** One value on a time axis. */
data class ChartDatum(
    /** Short axis label ("Mon", "12", "Oct"). */
    val axisLabel: String,
    /** Full label for the selection tooltip ("Tue, Oct 13"). */
    val title: String,
    val value: Double,
    val detail: String,
    /** e.g. "Partial week: 3 of 7 days in range". */
    val note: String? = null,
    /** Future slots are drawn as placeholders, never as zero focus. */
    val future: Boolean = false,
    /** Partial buckets are drawn lighter so they are not mistaken for full ones. */
    val partial: Boolean = false,
)

enum class AxisKind { MINUTES, COUNT }

/** Y-axis scale with "nice" round ticks that always start at zero (no truncated axes). */
data class AxisScale(val max: Double, val ticks: List<Double>, val kind: AxisKind) {
    fun label(v: Double): String = when (kind) {
        AxisKind.MINUTES -> Durations.axis(v.toLong())
        AxisKind.COUNT -> v.toLong().toString()
    }

    companion object {
        private val MINUTE_STEPS = listOf(1.0, 2.0, 5.0, 10.0, 15.0, 20.0, 30.0, 60.0, 90.0, 120.0, 180.0, 240.0, 300.0, 360.0, 480.0, 600.0, 720.0, 1200.0, 1800.0, 2400.0, 3000.0, 6000.0, 12000.0, 30000.0, 60000.0)
        private val COUNT_STEPS = listOf(1.0, 2.0, 5.0, 10.0, 20.0, 25.0, 50.0, 100.0, 200.0, 500.0, 1000.0, 2000.0, 5000.0)

        /** Values are minutes for [AxisKind.MINUTES]. */
        fun of(maxValue: Double, kind: AxisKind, maxTicks: Int = 4): AxisScale {
            val steps = if (kind == AxisKind.MINUTES) MINUTE_STEPS else COUNT_STEPS
            val m = max(maxValue, if (kind == AxisKind.MINUTES) 15.0 else 2.0)
            val step = steps.firstOrNull { m / it <= maxTicks } ?: (m / maxTicks)
            val top = ceil(m / step) * step
            val ticks = generateSequence(0.0) { it + step }.takeWhile { it <= top + 1e-9 }.toList()
            return AxisScale(top, ticks, kind)
        }
    }
}

/**
 * Animates a list of values: from the previous values when the shape is compatible (same count),
 * otherwise growing from the baseline. Honors the user's motion setting.
 */
@Composable
fun animatedValues(target: List<Float>, motion: Motion): List<Float> {
    val progress = remember { Animatable(if (motion.enabled) 0f else 1f) }
    var from by remember { mutableStateOf(List(target.size) { 0f }) }
    var to by remember { mutableStateOf(target) }
    // Last values actually drawn (plain holder: written during composition, so not snapshot state).
    val shown = remember { ShownValues(List(target.size) { 0f }) }
    LaunchedEffect(target) {
        from = if (shown.values.size == target.size) shown.values else List(target.size) { 0f }
        to = target
        if (!motion.enabled) {
            progress.snapTo(1f)
        } else {
            progress.snapTo(0f)
            progress.animateTo(1f, motion.spec(650))
        }
    }
    val t = progress.value
    val values = to.mapIndexed { i, v -> from.getOrElse(i) { 0f } + (v - from.getOrElse(i) { 0f }) * t }
    shown.values = values
    return values
}

/** Fritsch–Carlson monotone cubic through points: smooth, but never overshoots (no dips below zero). */
fun monotonePath(points: List<Offset>, path: Path = Path()): Path {
    path.reset()
    if (points.isEmpty()) return path
    path.moveTo(points[0].x, points[0].y)
    if (points.size == 1) return path
    val n = points.size
    val d = FloatArray(n - 1) { (points[it + 1].y - points[it].y) / (points[it + 1].x - points[it].x) }
    val m = FloatArray(n)
    m[0] = d[0]
    m[n - 1] = d[n - 2]
    for (k in 1 until n - 1) m[k] = if (d[k - 1] * d[k] <= 0f) 0f else (d[k - 1] + d[k]) / 2f
    for (k in 0 until n - 1) {
        if (d[k] == 0f) {
            m[k] = 0f
            m[k + 1] = 0f
        } else {
            val a = m[k] / d[k]
            val b = m[k + 1] / d[k]
            val s = a * a + b * b
            if (s > 9f) {
                val t = 3f / sqrt(s)
                m[k] = t * a * d[k]
                m[k + 1] = t * b * d[k]
            }
        }
    }
    for (k in 0 until n - 1) {
        val p0 = points[k]
        val p1 = points[k + 1]
        val h = (p1.x - p0.x) / 3f
        path.cubicTo(p0.x + h, p0.y + m[k] * h, p1.x - h, p1.y - m[k + 1] * h, p1.x, p1.y)
    }
    return path
}

/** Rectangle with only the top corners rounded (bar shape). */
fun topRoundedBar(left: Float, top: Float, right: Float, bottom: Float, radius: Float): Path = Path().apply {
    val r = minOf(radius, (right - left) / 2f, (bottom - top).coerceAtLeast(0f))
    addRoundRect(RoundRect(left, top, right, bottom, CornerRadius(r), CornerRadius(r), CornerRadius.Zero, CornerRadius.Zero))
}

/** Draws a two-line tooltip bubble anchored above [anchor], kept inside the canvas. */
fun DrawScope.tooltip(
    measurer: TextMeasurer,
    anchor: Offset,
    title: String,
    body: String,
    note: String?,
    titleStyle: TextStyle,
    bodyStyle: TextStyle,
    noteStyle: TextStyle,
    background: Color,
    border: Color,
) {
    val pad = 10.dp.toPx()
    val t = measurer.measure(title, titleStyle)
    val b = measurer.measure(body, bodyStyle)
    val nLayout = note?.let { measurer.measure(it, noteStyle) }
    val w = maxOf(t.size.width, b.size.width, nLayout?.size?.width ?: 0) + pad * 2
    val h = t.size.height + b.size.height + (nLayout?.size?.height ?: 0) + pad * 2 + 2.dp.toPx()
    var x = anchor.x - w / 2
    x = x.coerceIn(0f, size.width - w)
    var y = anchor.y - h - 12.dp.toPx()
    if (y < 0) y = (anchor.y + 14.dp.toPx()).coerceAtMost(size.height - h)
    val r = CornerRadius(12.dp.toPx())
    drawRoundRect(border, Offset(x - 1, y - 1), Size(w + 2, h + 2), r)
    drawRoundRect(background, Offset(x, y), Size(w, h), r)
    drawText(t, topLeft = Offset(x + pad, y + pad))
    drawText(b, topLeft = Offset(x + pad, y + pad + t.size.height + 2.dp.toPx()))
    nLayout?.let { drawText(it, topLeft = Offset(x + pad, y + pad + t.size.height + b.size.height + 2.dp.toPx())) }
}

private class ShownValues(var values: List<Float>)
