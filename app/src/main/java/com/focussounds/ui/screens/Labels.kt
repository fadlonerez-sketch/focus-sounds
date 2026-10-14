package com.focussounds.ui.screens

import com.focussounds.core.Durations
import com.focussounds.core.EndReason
import com.focussounds.core.analytics.Bucket
import com.focussounds.core.analytics.DateRange
import com.focussounds.core.analytics.ProgressAnalytics
import com.focussounds.core.analytics.Resolution
import com.focussounds.ui.charts.ChartDatum
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private val locale: Locale get() = Locale.getDefault()
private val DAY_TITLE get() = DateTimeFormatter.ofPattern("EEE, MMM d", locale)
private val DAY_SHORT get() = DateTimeFormatter.ofPattern("MMM d", locale)
private val DAY_YEAR get() = DateTimeFormatter.ofPattern("MMM d, yyyy", locale)
private val MONTH_TITLE get() = DateTimeFormatter.ofPattern("MMMM yyyy", locale)
private val MONTH_SHORT get() = DateTimeFormatter.ofPattern("MMM", locale)
private val MONTH_SHORT_YEAR get() = DateTimeFormatter.ofPattern("MMM ''yy", locale)

fun dayTitle(d: LocalDate, today: LocalDate = LocalDate.now()): String = when (d) {
    today -> "Today"
    today.minusDays(1) -> "Yesterday"
    else -> if (d.year == today.year) DAY_TITLE.format(d) else DAY_YEAR.format(d)
}

fun rangeLabel(r: DateRange, today: LocalDate): String = when {
    r.days == 1L -> dayTitle(r.start, today) + if (r.start == today) ", " + DAY_SHORT.format(r.start) else ""
    r.start.year == r.end.year && r.end.year == today.year -> "${DAY_SHORT.format(r.start)} – ${DAY_SHORT.format(r.end)}"
    else -> "${DAY_YEAR.format(r.start)} – ${DAY_YEAR.format(r.end)}"
}

fun sessionsLabel(n: Int) = "$n ${ProgressAnalytics.plural(n, "session")}"

/** Converts an analytics bucket into a chart point with honest labels and notes. */
fun Bucket.toDatum(res: Resolution, range: DateRange, today: LocalDate): ChartDatum {
    val minutes = activeMs / 60_000.0
    val detail = "${Durations.format(activeMs)} · ${sessionsLabel(sessions)}"
    val spansYears = range.start.year != range.end.year
    return when (res) {
        Resolution.HOUR -> ChartDatum(
            axisLabel = "%02d".format(hour),
            title = "%02d:00–%02d:00".format(hour, (hour!! + 1) % 24),
            value = minutes,
            detail = "${Durations.format(activeMs)} · ${sessions} started",
            note = if (containsToday) "Hour in progress" else null,
            future = isFuture,
            partial = containsToday,
        )
        Resolution.DAY -> ChartDatum(
            axisLabel = if (range.days <= 7) start.dayOfWeek.getDisplayName(TextStyle.SHORT, locale) else start.dayOfMonth.toString(),
            title = dayTitle(start, today),
            value = minutes,
            detail = detail,
            note = if (start == today) "Today so far" else null,
            partial = start == today,
        )
        Resolution.WEEK -> ChartDatum(
            axisLabel = DAY_SHORT.format(start),
            title = "Week of ${DAY_SHORT.format(start)}",
            value = minutes,
            detail = detail,
            note = when {
                containsToday -> "Week in progress"
                partial -> "Partial week: $daysInRange of $totalDays days in range"
                else -> null
            },
            partial = partial || containsToday,
        )
        Resolution.MONTH -> ChartDatum(
            axisLabel = if (spansYears) MONTH_SHORT_YEAR.format(start) else MONTH_SHORT.format(start),
            title = MONTH_TITLE.format(start),
            value = minutes,
            detail = detail,
            note = when {
                containsToday -> "Month in progress"
                partial -> "Partial month: $daysInRange of $totalDays days in range"
                else -> null
            },
            partial = partial || containsToday,
        )
        Resolution.YEAR -> ChartDatum(
            axisLabel = start.year.toString(),
            title = start.year.toString(),
            value = minutes,
            detail = detail,
            note = when {
                containsToday -> "Year in progress"
                partial -> "Partial year: $daysInRange of $totalDays days in range"
                else -> null
            },
            partial = partial || containsToday,
        )
    }
}

fun outcomeLabel(o: EndReason?): String = when (o) {
    EndReason.COMPLETED -> "Completed"
    EndReason.STOPPED -> "Stopped"
    EndReason.INTERRUPTED -> "Interrupted"
    EndReason.CANCELLED -> "Cancelled"
    null -> "In progress"
}

fun outcomeDescription(o: EndReason?): String = when (o) {
    EndReason.COMPLETED -> "Timer reached its end"
    EndReason.STOPPED -> "Stopped by you"
    EndReason.INTERRUPTED -> "Ended unexpectedly; time saved up to the last checkpoint"
    EndReason.CANCELLED -> "Stopped before any focus time"
    null -> "Running now"
}
