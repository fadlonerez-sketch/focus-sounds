package com.focussounds.core.analytics

import com.focussounds.core.Durations
import com.focussounds.core.EndReason
import com.focussounds.core.FocusSound
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/*
 * Progress analytics. Pure Kotlin, deterministic, computed off the main thread.
 *
 * Rules (also documented in README "Progress rules"):
 * - Focus time = persisted active (actually playing) time. Paused time was never recorded as active.
 * - Day attribution: a session belongs to the local calendar day on which it STARTED. The database
 *   does not store when inside a session the pauses happened, so splitting a session across
 *   midnight would invent precision the data does not have.
 * - A session "counts" (sessions, averages, streaks) when it has active time > 0 and was not
 *   cancelled. Interrupted sessions count with the time recorded up to their last checkpoint.
 *   The running session (if any) is included with its live active time.
 * - A focus day has at least FOCUS_DAY_MIN_MS of focus. Current streak = consecutive focus days
 *   ending today, or ending yesterday while today has no focus yet (today is not over).
 * - Days before the first recorded session are "not tracked", never "zero focus".
 * - Comparisons use the immediately preceding period of equal length, cut at the same time of
 *   day when the current period ends now; percentages only appear when the previous period was
 *   fully tracked and had at least MIN_BASELINE_MS of focus.
 */

const val FOCUS_DAY_MIN_MS = 60_000L
const val MIN_BASELINE_MS = 5 * 60_000L

data class SessionFact(
    val id: String,
    /** Stable grouping key: the activity id, or the recorded name once the activity was deleted. */
    val activityKey: String,
    /** Current name if the activity still exists, else the name recorded with the session. */
    val activityName: String,
    val activityDeleted: Boolean,
    val soundId: String,
    val startedAtMs: Long,
    val endedAtMs: Long?,
    val plannedDurationMs: Long?,
    val activeMs: Long,
    /** Null while the session is still running. */
    val outcome: EndReason?,
) {
    val inProgress: Boolean get() = outcome == null
    val counted: Boolean get() = outcome != EndReason.CANCELLED && activeMs > 0

    companion object {
        fun activityKeyFor(activityId: Long?, recordedName: String): String =
            if (activityId != null) "id:$activityId" else "name:${recordedName.trim().lowercase()}"
    }
}

data class AnalyticsSettings(
    val zone: ZoneId,
    val nowMs: Long,
    val firstDayOfWeek: DayOfWeek,
    val dailyGoalMs: Long?,
    val weeklyGoalMs: Long?,
)

enum class RangePreset(val label: String) {
    TODAY("Today"),
    LAST_7_DAYS("7 days"),
    LAST_30_DAYS("30 days"),
    LAST_90_DAYS("90 days"),
    LAST_12_MONTHS("12 months"),
    ALL_TIME("All time"),
    CUSTOM("Custom"),
}

enum class Resolution(val label: String) { HOUR("Hourly"), DAY("Daily"), WEEK("Weekly"), MONTH("Monthly"), YEAR("Yearly") }

data class DateRange(val start: LocalDate, val end: LocalDate) {
    init {
        require(!end.isBefore(start)) { "end before start" }
    }

    val days: Long get() = ChronoUnit.DAYS.between(start, end) + 1
    operator fun contains(d: LocalDate) = !d.isBefore(start) && !d.isAfter(end)
    fun dates(): Sequence<LocalDate> = generateSequence(start) { it.plusDays(1) }.takeWhile { !it.isAfter(end) }
}

data class DayStat(val activeMs: Long, val sessions: Int)

data class Bucket(
    val start: LocalDate,
    /** Last day of the full bucket (may extend past the range; see [daysInRange]). */
    val end: LocalDate,
    val hour: Int?,
    val activeMs: Long,
    val sessions: Int,
    val daysInRange: Int,
    val totalDays: Int,
    val containsToday: Boolean,
    val isFuture: Boolean,
) {
    /** Only part of this week/month/year lies inside the selected range. */
    val partial: Boolean get() = daysInRange < totalDays
}

data class Overview(
    val totalMs: Long,
    val sessions: Int,
    val averageSessionMs: Long?,
    /** Average per tracked day in the range; null before anything was recorded. */
    val dailyAverageMs: Long?,
    val averagedOverDays: Long,
    val focusDays: Int,
)

data class Comparison(
    val previousRange: DateRange,
    /** The previous period was cut at the same time of day because the current one ends now. */
    val cutAtSameTime: Boolean,
    val currentMs: Long,
    val previousMs: Long,
    val currentSessions: Int,
    val previousSessions: Int,
    /** Tracking had not started (fully) during the previous period: no fair comparison. */
    val previousNotFullyTracked: Boolean,
    /** Null when a percentage would be misleading (see rules). */
    val changeFraction: Double?,
    val label: String,
) {
    val deltaMs: Long get() = currentMs - previousMs
}

data class ActivitySlice(
    val key: String,
    val name: String,
    val deleted: Boolean,
    val activeMs: Long,
    val sessions: Int,
    val fraction: Double,
)

data class ActivityDistribution(
    /** Largest first. When there are many activities the smallest are moved to [other]. */
    val slices: List<ActivitySlice>,
    val other: List<ActivitySlice>,
    val totalMs: Long,
) {
    val otherMs: Long get() = other.sumOf { it.activeMs }
    val otherFraction: Double get() = if (totalMs == 0L) 0.0 else otherMs.toDouble() / totalMs
}

data class SoundUsage(val sound: FocusSound, val activeMs: Long, val sessions: Int, val fraction: Double)

data class HistogramBin(val label: String, val fromMinutes: Int, val toMinutes: Int?, val sessions: Int)

data class OutcomeCount(val outcome: EndReason?, val sessions: Int, val activeMs: Long)

data class Streaks(val current: Int, val longest: Int, val longestEnd: LocalDate?, val todayCounts: Boolean)

data class GoalProgress(val goalMs: Long, val achievedMs: Long, val period: DateRange) {
    val fraction: Double get() = if (goalMs <= 0) 0.0 else achievedMs.toDouble() / goalMs
    val met: Boolean get() = achievedMs >= goalMs
    val remainingMs: Long get() = (goalMs - achievedMs).coerceAtLeast(0)
}

data class DayGoal(val date: LocalDate, val activeMs: Long, val goalMs: Long) {
    val met: Boolean get() = activeMs >= goalMs
    val fraction: Double get() = if (goalMs <= 0) 0.0 else activeMs.toDouble() / goalMs
}

data class DayRecord(val date: LocalDate, val activeMs: Long, val sessions: Int)

data class SessionRecord(val fact: SessionFact, val date: LocalDate)

enum class InsightKind { COMPARISON, TOP_ACTIVITY, TOP_SOUND, GOAL, STREAK, LONGEST_SESSION, RECORD_DAY }

data class Insight(val kind: InsightKind, val title: String, val detail: String)

data class ProgressReport(
    val preset: RangePreset,
    val range: DateRange,
    val today: LocalDate,
    val hasAnyData: Boolean,
    val hasDataInRange: Boolean,
    val includesRunningSession: Boolean,
    val overview: Overview,
    val comparison: Comparison?,
    val trendResolution: Resolution,
    val trendResolutions: List<Resolution>,
    val trend: List<Bucket>,
    val barResolution: Resolution?,
    val barResolutions: List<Resolution>,
    val bars: List<Bucket>,
    val barGoalMs: Long?,
    val activities: ActivityDistribution,
    val sounds: List<SoundUsage>,
    val sessionLengths: List<HistogramBin>,
    val outcomes: List<OutcomeCount>,
    val startHours: List<Int>,
    val bestDay: DayRecord?,
    val longestSession: SessionRecord?,
    val longestCompleted: SessionRecord?,
    val streaks: Streaks,
    val dailyGoal: GoalProgress?,
    val weeklyGoal: GoalProgress?,
    /** One entry per tracked day in the range (only when a daily goal is set). */
    val goalDays: List<DayGoal>,
    val insights: List<Insight>,
)

enum class CellState { NOT_IN_MONTH, FUTURE, NOT_TRACKED, TRACKED }

data class CalendarCell(
    val date: LocalDate,
    val state: CellState,
    val activeMs: Long,
    val sessions: Int,
    /** 0 = no focus, 1..5 = intensity (see [CALENDAR_LEVELS_MS]). */
    val level: Int,
    val goalMet: Boolean,
    val isToday: Boolean,
)

/** Lower bounds (inclusive) of intensity levels 2..5; level 1 is anything above zero. */
val CALENDAR_LEVELS_MS = listOf(15 * 60_000L, 30 * 60_000L, 60 * 60_000L, 120 * 60_000L)

data class CalendarMonth(
    val month: YearMonth,
    val weeks: List<List<CalendarCell>>,
    val canGoBack: Boolean,
    val canGoForward: Boolean,
    val totalMs: Long,
    val focusDays: Int,
)

fun calendarLevel(activeMs: Long): Int = when {
    activeMs <= 0 -> 0
    activeMs < CALENDAR_LEVELS_MS[0] -> 1
    activeMs < CALENDAR_LEVELS_MS[1] -> 2
    activeMs < CALENDAR_LEVELS_MS[2] -> 3
    activeMs < CALENDAR_LEVELS_MS[3] -> 4
    else -> 5
}

class ProgressAnalytics(facts: List<SessionFact>, private val s: AnalyticsSettings) {

    private class Item(val fact: SessionFact, val date: LocalDate, val hour: Int)

    val today: LocalDate = Instant.ofEpochMilli(s.nowMs).atZone(s.zone).toLocalDate()
    private val nowHour: Int = Instant.ofEpochMilli(s.nowMs).atZone(s.zone).hour

    /** Deduplicated by id (a finished row wins over a running copy), oldest first. */
    private val items: List<Item> = facts
        .groupBy { it.id }
        .map { (_, copies) -> copies.firstOrNull { it.outcome != null } ?: copies.first() }
        .map { f ->
            val z = Instant.ofEpochMilli(f.startedAtMs).atZone(s.zone)
            Item(f, z.toLocalDate(), z.hour)
        }
        .sortedBy { it.fact.startedAtMs }

    private val counted: List<Item> = items.filter { it.fact.counted }

    /** First day anything was recorded. Days before it are "not tracked". */
    val firstTrackedDate: LocalDate? = items.firstOrNull()?.date

    /** All-time focus per day (counted sessions only). */
    val dayStats: Map<LocalDate, DayStat> = counted.groupBy { it.date }
        .mapValues { (_, list) -> DayStat(list.sumOf { it.fact.activeMs }, list.size) }

    val streaks: Streaks by lazy { computeStreaks() }

    fun weekStart(d: LocalDate): LocalDate = d.with(TemporalAdjusters.previousOrSame(s.firstDayOfWeek))

    fun resolveRange(preset: RangePreset, custom: DateRange? = null): DateRange {
        fun back(days: Long) = DateRange(today.minusDays(days - 1), today)
        return when (preset) {
            RangePreset.TODAY -> DateRange(today, today)
            RangePreset.LAST_7_DAYS -> back(7)
            RangePreset.LAST_30_DAYS -> back(30)
            RangePreset.LAST_90_DAYS -> back(90)
            RangePreset.LAST_12_MONTHS -> DateRange(today.withDayOfMonth(1).minusMonths(11), today)
            RangePreset.ALL_TIME -> DateRange(minOf(firstTrackedDate ?: today, today), today)
            RangePreset.CUSTOM -> {
                val c = custom ?: return back(7)
                val end = minOf(c.end, today)
                DateRange(minOf(c.start, end), end)
            }
        }
    }

    fun trendResolutions(r: DateRange): List<Resolution> = buildList {
        val d = r.days
        if (d == 1L) add(Resolution.HOUR)
        if (d in 2..366) add(Resolution.DAY)
        if (d >= 14) add(Resolution.WEEK)
        if (d >= 60) add(Resolution.MONTH)
        if (d >= 730) add(Resolution.YEAR)
    }

    fun defaultTrendResolution(r: DateRange): Resolution = when {
        r.days == 1L -> Resolution.HOUR
        r.days <= 31 -> Resolution.DAY
        r.days <= 120 -> Resolution.WEEK
        r.days <= 1095 -> Resolution.MONTH
        else -> Resolution.YEAR
    }

    fun barResolutions(r: DateRange): List<Resolution> = buildList {
        val d = r.days
        if (d in 2..92) add(Resolution.DAY)
        if (d in 14..731) add(Resolution.WEEK)
        if (d >= 60) add(Resolution.MONTH)
    }

    fun defaultBarResolution(r: DateRange): Resolution? = barResolutions(r).let { allowed ->
        val preferred = when {
            r.days <= 31 -> Resolution.DAY
            r.days <= 120 -> Resolution.WEEK
            else -> Resolution.MONTH
        }
        if (preferred in allowed) preferred else allowed.firstOrNull()
    }

    fun buckets(r: DateRange, res: Resolution): List<Bucket> {
        if (res == Resolution.HOUR) {
            val day = r.start
            val byHour = counted.filter { it.date == day }.groupBy { it.hour }
            return (0..23).map { h ->
                val list = byHour[h].orEmpty()
                Bucket(day, day, h, list.sumOf { it.fact.activeMs }, list.size, 1, 1,
                    containsToday = day == today && h == nowHour,
                    isFuture = day.isAfter(today) || (day == today && h > nowHour))
            }
        }
        val out = mutableListOf<Bucket>()
        var bStart = when (res) {
            Resolution.DAY -> r.start
            Resolution.WEEK -> weekStart(r.start)
            Resolution.MONTH -> r.start.withDayOfMonth(1)
            Resolution.YEAR -> r.start.withDayOfYear(1)
            Resolution.HOUR -> error("handled")
        }
        while (!bStart.isAfter(r.end)) {
            val bEnd = when (res) {
                Resolution.DAY -> bStart
                Resolution.WEEK -> bStart.plusDays(6)
                Resolution.MONTH -> bStart.plusMonths(1).minusDays(1)
                Resolution.YEAR -> bStart.plusYears(1).minusDays(1)
                Resolution.HOUR -> error("handled")
            }
            val from = maxOf(bStart, r.start)
            val to = minOf(bEnd, r.end)
            var ms = 0L
            var n = 0
            var d = from
            while (!d.isAfter(to)) {
                dayStats[d]?.let { ms += it.activeMs; n += it.sessions }
                d = d.plusDays(1)
            }
            out += Bucket(
                start = bStart, end = bEnd, hour = null, activeMs = ms, sessions = n,
                daysInRange = (ChronoUnit.DAYS.between(from, to) + 1).toInt(),
                totalDays = (ChronoUnit.DAYS.between(bStart, bEnd) + 1).toInt(),
                containsToday = today in DateRange(bStart, bEnd),
                isFuture = bStart.isAfter(today),
            )
            bStart = bEnd.plusDays(1)
        }
        return out
    }

    fun report(
        preset: RangePreset,
        custom: DateRange? = null,
        trendResolution: Resolution? = null,
        barResolution: Resolution? = null,
    ): ProgressReport {
        val range = resolveRange(preset, custom)
        val inRange = counted.filter { it.date in range }
        val allInRange = items.filter { it.date in range }
        val total = inRange.sumOf { it.fact.activeMs }

        val trendRes = trendResolution?.takeIf { it in trendResolutions(range) } ?: defaultTrendResolution(range)
        val barRes = barResolution?.takeIf { it in barResolutions(range) } ?: defaultBarResolution(range)

        val overview = overview(range, inRange)
        val comparison = comparison(preset, range, total, inRange.size)
        val activities = activityDistribution(inRange)
        val sounds = soundUsage(inRange)
        val bestDay = dayStats.filterKeys { it in range }.maxWithOrNull(compareBy<Map.Entry<LocalDate, DayStat>> { it.value.activeMs }.thenByDescending { it.key })
            ?.let { DayRecord(it.key, it.value.activeMs, it.value.sessions) }
        val longest = inRange.maxWithOrNull(compareBy<Item> { it.fact.activeMs }.thenByDescending { it.fact.startedAtMs })
            ?.let { SessionRecord(it.fact, it.date) }
        val longestCompleted = inRange.filter { it.fact.outcome == EndReason.COMPLETED }
            .maxWithOrNull(compareBy<Item> { it.fact.activeMs }.thenByDescending { it.fact.startedAtMs })
            ?.let { SessionRecord(it.fact, it.date) }
        val dailyGoal = s.dailyGoalMs?.takeIf { it > 0 }?.let { GoalProgress(it, dayStats[today]?.activeMs ?: 0, DateRange(today, today)) }
        val weeklyGoal = s.weeklyGoalMs?.takeIf { it > 0 }?.let {
            val wr = DateRange(weekStart(today), today)
            GoalProgress(it, wr.dates().sumOf { d -> dayStats[d]?.activeMs ?: 0 }, wr)
        }
        val goalDays = s.dailyGoalMs?.takeIf { it > 0 && firstTrackedDate != null }?.let { goal ->
            val from = maxOf(range.start, firstTrackedDate!!)
            val to = minOf(range.end, today)
            if (from.isAfter(to)) emptyList()
            else DateRange(from, to).dates().map { DayGoal(it, dayStats[it]?.activeMs ?: 0, goal) }.toList()
        }.orEmpty()

        val report = ProgressReport(
            preset = preset,
            range = range,
            today = today,
            hasAnyData = counted.isNotEmpty(),
            hasDataInRange = inRange.isNotEmpty(),
            includesRunningSession = inRange.any { it.fact.inProgress },
            overview = overview,
            comparison = comparison,
            trendResolution = trendRes,
            trendResolutions = trendResolutions(range),
            trend = buckets(range, trendRes),
            barResolution = barRes,
            barResolutions = barResolutions(range),
            bars = barRes?.let { buckets(range, it) }.orEmpty(),
            barGoalMs = when (barRes) {
                Resolution.DAY -> s.dailyGoalMs?.takeIf { it > 0 }
                Resolution.WEEK -> s.weeklyGoalMs?.takeIf { it > 0 }
                else -> null
            },
            activities = activities,
            sounds = sounds,
            sessionLengths = histogram(inRange),
            outcomes = outcomes(allInRange),
            startHours = (0..23).map { h -> inRange.count { it.hour == h } },
            bestDay = bestDay,
            longestSession = longest,
            longestCompleted = longestCompleted,
            streaks = streaks,
            dailyGoal = dailyGoal,
            weeklyGoal = weeklyGoal,
            goalDays = goalDays,
            insights = emptyList(),
        )
        return report.copy(insights = insights(report))
    }

    private fun overview(range: DateRange, inRange: List<Item>): Overview {
        val total = inRange.sumOf { it.fact.activeMs }
        val first = firstTrackedDate
        val avgDays = if (first == null) 0L else {
            val from = maxOf(range.start, first)
            val to = minOf(range.end, today)
            if (from.isAfter(to)) 0L else ChronoUnit.DAYS.between(from, to) + 1
        }
        return Overview(
            totalMs = total,
            sessions = inRange.size,
            averageSessionMs = if (inRange.isEmpty()) null else total / inRange.size,
            dailyAverageMs = if (avgDays == 0L) null else total / avgDays,
            averagedOverDays = avgDays,
            focusDays = range.dates().count { (dayStats[it]?.activeMs ?: 0) >= FOCUS_DAY_MIN_MS },
        )
    }

    private fun comparison(preset: RangePreset, range: DateRange, currentMs: Long, currentSessions: Int): Comparison? {
        if (preset == RangePreset.ALL_TIME) return null
        val byMonths = preset == RangePreset.LAST_12_MONTHS
        val shiftDays = range.days
        fun shift(d: LocalDate): LocalDate = if (byMonths) d.minusMonths(12) else d.minusDays(shiftDays)
        val prev = DateRange(shift(range.start), shift(range.end))
        val endsNow = range.end == today
        val cutoffMs: Long = if (endsNow) {
            val now = Instant.ofEpochMilli(s.nowMs).atZone(s.zone)
            (if (byMonths) now.minusMonths(12) else now.minusDays(shiftDays)).toInstant().toEpochMilli()
        } else {
            prev.end.plusDays(1).atStartOfDay(s.zone).toInstant().toEpochMilli()
        }
        val prevItems = counted.filter { it.date in prev && it.fact.startedAtMs < cutoffMs }
        val prevMs = prevItems.sumOf { it.fact.activeMs }
        val first = firstTrackedDate
        val notFullyTracked = first == null || first.isAfter(prev.start)
        val change = if (!notFullyTracked && prevMs >= MIN_BASELINE_MS) (currentMs - prevMs).toDouble() / prevMs else null
        val label = when (preset) {
            RangePreset.TODAY -> "yesterday at this time"
            RangePreset.LAST_12_MONTHS -> "the previous 12 months"
            RangePreset.CUSTOM -> "the preceding ${range.days} days"
            else -> "the previous ${range.days} days"
        }
        return Comparison(prev, endsNow, currentMs, prevMs, currentSessions, prevItems.size, notFullyTracked, change, label)
    }

    private fun activityDistribution(inRange: List<Item>): ActivityDistribution {
        val total = inRange.sumOf { it.fact.activeMs }
        val slices = inRange.groupBy { it.fact.activityKey }.map { (key, list) ->
            val latest = list.maxBy { it.fact.startedAtMs }.fact
            val ms = list.sumOf { it.fact.activeMs }
            ActivitySlice(key, latest.activityName, latest.activityDeleted, ms, list.size,
                if (total == 0L) 0.0 else ms.toDouble() / total)
        }.sortedWith(compareByDescending<ActivitySlice> { it.activeMs }.thenBy { it.name.lowercase() })
        return if (slices.size > MAX_SLICES) {
            ActivityDistribution(slices.take(MAX_SLICES - 1), slices.drop(MAX_SLICES - 1), total)
        } else {
            ActivityDistribution(slices, emptyList(), total)
        }
    }

    private fun soundUsage(inRange: List<Item>): List<SoundUsage> {
        val total = inRange.sumOf { it.fact.activeMs }
        val by = inRange.groupBy { it.fact.soundId }
        return FocusSound.entries.map { sound ->
            val list = by[sound.id].orEmpty()
            val ms = list.sumOf { it.fact.activeMs }
            SoundUsage(sound, ms, list.size, if (total == 0L) 0.0 else ms.toDouble() / total)
        }
    }

    private fun histogram(inRange: List<Item>): List<HistogramBin> = HISTOGRAM_EDGES.mapIndexed { i, from ->
        val to = HISTOGRAM_EDGES.getOrNull(i + 1)
        val label = if (to == null) "$from+" else "$from–$to"
        HistogramBin(label, from, to, inRange.count {
            val min = it.fact.activeMs / 60_000.0
            min >= from && (to == null || min < to)
        })
    }

    private fun outcomes(all: List<Item>): List<OutcomeCount> {
        val order = listOf(EndReason.COMPLETED, EndReason.STOPPED, EndReason.INTERRUPTED, EndReason.CANCELLED, null)
        return order.map { o ->
            val list = all.filter { it.fact.outcome == o }
            OutcomeCount(o, list.size, list.sumOf { it.fact.activeMs })
        }.filter { it.outcome != null || it.sessions > 0 }
    }

    private fun computeStreaks(): Streaks {
        val days = dayStats.filter { (d, v) -> v.activeMs >= FOCUS_DAY_MIN_MS && !d.isAfter(today) }.keys.sorted()
        var longest = 0
        var longestEnd: LocalDate? = null
        var run = 0
        var prev: LocalDate? = null
        for (d in days) {
            run = if (prev != null && prev.plusDays(1) == d) run + 1 else 1
            if (run >= longest) {
                longest = run
                longestEnd = d
            }
            prev = d
        }
        val set = days.toHashSet()
        val todayCounts = today in set
        var cur = 0
        var d = if (todayCounts) today else today.minusDays(1)
        while (d in set) {
            cur++
            d = d.minusDays(1)
        }
        return Streaks(cur, longest, longestEnd, todayCounts)
    }

    fun calendarMonth(month: YearMonth, dailyGoalMs: Long? = s.dailyGoalMs): CalendarMonth {
        val first = month.atDay(1)
        val last = month.atEndOfMonth()
        val gridStart = weekStart(first)
        val gridEnd = weekStart(last).plusDays(6)
        val tracked = firstTrackedDate
        val cells = DateRange(gridStart, gridEnd).dates().map { d ->
            val stat = dayStats[d]
            val state = when {
                YearMonth.from(d) != month -> CellState.NOT_IN_MONTH
                d.isAfter(today) -> CellState.FUTURE
                tracked == null || d.isBefore(tracked) -> CellState.NOT_TRACKED
                else -> CellState.TRACKED
            }
            val ms = if (state == CellState.TRACKED) stat?.activeMs ?: 0 else 0
            CalendarCell(
                date = d,
                state = state,
                activeMs = ms,
                sessions = if (state == CellState.TRACKED) stat?.sessions ?: 0 else 0,
                level = calendarLevel(ms),
                goalMet = dailyGoalMs != null && dailyGoalMs > 0 && state == CellState.TRACKED && ms >= dailyGoalMs,
                isToday = d == today,
            )
        }.toList()
        val inMonth = cells.filter { it.state == CellState.TRACKED }
        return CalendarMonth(
            month = month,
            weeks = cells.chunked(7),
            canGoBack = tracked != null && YearMonth.from(tracked) < month,
            canGoForward = month < YearMonth.from(today),
            totalMs = inMonth.sumOf { it.activeMs },
            focusDays = inMonth.count { it.activeMs >= FOCUS_DAY_MIN_MS },
        )
    }

    private fun insights(r: ProgressReport): List<Insight> {
        val out = mutableListOf<Insight>()
        val total = r.overview.totalMs
        r.comparison?.let { c ->
            val pct = c.changeFraction
            when {
                pct != null && c.deltaMs != 0L -> {
                    val dir = if (c.deltaMs > 0) "up" else "down"
                    val sign = if (c.deltaMs > 0) "+" else "−"
                    out += Insight(InsightKind.COMPARISON, "Focus time is $dir ${Durations.percent(kotlin.math.abs(pct))}",
                        "${Durations.format(c.currentMs)} vs ${Durations.format(c.previousMs)} ($sign${Durations.format(kotlin.math.abs(c.deltaMs))}) compared with ${c.label}.")
                }
                pct != null -> out += Insight(InsightKind.COMPARISON, "Same focus time as before",
                    "${Durations.format(c.currentMs)}, unchanged compared with ${c.label}.")
                !c.previousNotFullyTracked && c.previousMs == 0L && c.currentMs > 0 ->
                    out += Insight(InsightKind.COMPARISON, "Back to focusing",
                        "${Durations.format(c.currentMs)} so far; there was no focus time in ${c.label}.")
            }
        }
        if (total > 0) {
            val top = r.activities.slices.firstOrNull()
            if (top != null) {
                val title = if (r.activities.slices.size + r.activities.other.size == 1) "All focus went to ${top.name}" else "Most focus went to ${top.name}"
                out += Insight(InsightKind.TOP_ACTIVITY, title,
                    "${Durations.format(top.activeMs)} · ${Durations.percent(top.fraction)} of focus time in this period.")
            }
            r.sounds.maxByOrNull { it.activeMs }?.takeIf { it.activeMs > 0 }?.let { su ->
                out += Insight(InsightKind.TOP_SOUND, "${su.sound.displayName} was your most-played sound",
                    "${Durations.format(su.activeMs)} of active playback across ${su.sessions} ${plural(su.sessions, "session")} (${Durations.percent(su.fraction)}).")
            }
        }
        if (r.goalDays.isNotEmpty() && r.range.days > 1) {
            val met = r.goalDays.count { it.met }
            out += Insight(InsightKind.GOAL, "Daily goal met on $met of ${r.goalDays.size} ${plural(r.goalDays.size, "day")}",
                "Goal: ${Durations.format(r.goalDays.first().goalMs)} per day. Days before you started using the app are not counted.")
        }
        if (r.streaks.current >= 2) {
            val detail = if (r.streaks.current >= r.streaks.longest) "This is your longest streak so far." else "Your longest is ${r.streaks.longest} days."
            val todayNote = if (!r.streaks.todayCounts) " Focus at least 1 min today to keep it going." else ""
            out += Insight(InsightKind.STREAK, "${r.streaks.current}-day focus streak", detail + todayNote)
        }
        r.longestCompleted?.let { l ->
            out += Insight(InsightKind.LONGEST_SESSION, "Longest completed session: ${Durations.format(l.fact.activeMs)}",
                "${l.fact.activityName} with ${FocusSound.fromId(l.fact.soundId)?.displayName ?: l.fact.soundId} on ${DAY_FORMAT.format(l.date)}.")
        }
        val bestEver = dayStats.maxWithOrNull(compareBy<Map.Entry<LocalDate, DayStat>> { it.value.activeMs }.thenByDescending { it.key })
        val trackedDays = firstTrackedDate?.let { ChronoUnit.DAYS.between(it, today) + 1 } ?: 0
        if (r.bestDay != null && bestEver != null && bestEver.key == r.bestDay.date && trackedDays >= 7 && r.range.days > 1) {
            out += Insight(InsightKind.RECORD_DAY, "Best day on record: ${DAY_FORMAT.format(r.bestDay.date)}",
                "${Durations.format(r.bestDay.activeMs)} of focus across ${r.bestDay.sessions} ${plural(r.bestDay.sessions, "session")}.")
        }
        return out
    }

    companion object {
        const val MAX_SLICES = 6
        val HISTOGRAM_EDGES = listOf(0, 5, 15, 25, 45, 60, 90)
        private val DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.ENGLISH)

        fun plural(n: Int, word: String) = if (n == 1) word else "${word}s"
    }
}

/**
 * Filters for the History screen. Every non-null criterion must match.
 */
data class HistoryFilter(
    val range: DateRange? = null,
    val activityKeys: Set<String> = emptySet(),
    val soundIds: Set<String> = emptySet(),
    /** Use null inside the set to match sessions still in progress. */
    val outcomes: Set<EndReason?> = emptySet(),
) {
    val isActive: Boolean get() = range != null || activityKeys.isNotEmpty() || soundIds.isNotEmpty() || outcomes.isNotEmpty()

    fun matches(f: SessionFact, zone: ZoneId): Boolean {
        if (range != null && Instant.ofEpochMilli(f.startedAtMs).atZone(zone).toLocalDate() !in range) return false
        if (activityKeys.isNotEmpty() && f.activityKey !in activityKeys) return false
        if (soundIds.isNotEmpty() && f.soundId !in soundIds) return false
        if (outcomes.isNotEmpty() && f.outcome !in outcomes) return false
        return true
    }
}
