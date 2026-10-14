package com.focussounds.core.analytics

import com.focussounds.core.EndReason
import com.focussounds.core.FocusSound
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId

private const val MIN = 60_000L

class ProgressAnalyticsTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private val today = LocalDate.of(2026, 10, 14) // a Wednesday
    private val now = LocalDateTime.of(today, LocalTime.of(15, 30))
    private var ids = 0

    private fun ms(date: LocalDate, time: LocalTime) = date.atTime(time).atZone(zone).toInstant().toEpochMilli()

    private fun fact(
        date: LocalDate,
        time: LocalTime = LocalTime.of(10, 0),
        activeMin: Double,
        activityId: Long? = 1,
        activityName: String = "Studying",
        sound: FocusSound = FocusSound.BROWN_NOISE,
        outcome: EndReason? = EndReason.STOPPED,
        wallMin: Double = activeMin,
        planned: Long? = null,
        id: String = "s${++ids}",
    ): SessionFact {
        val start = ms(date, time)
        return SessionFact(
            id = id,
            activityKey = SessionFact.activityKeyFor(activityId, activityName),
            activityName = activityName,
            activityDeleted = activityId == null,
            soundId = sound.id,
            startedAtMs = start,
            endedAtMs = if (outcome == null) null else start + (wallMin * MIN).toLong(),
            plannedDurationMs = planned,
            activeMs = (activeMin * MIN).toLong(),
            outcome = outcome,
        )
    }

    private fun analytics(
        facts: List<SessionFact>,
        dailyGoalMin: Long? = null,
        weeklyGoalMin: Long? = null,
        at: LocalDateTime = now,
    ) = ProgressAnalytics(
        facts,
        AnalyticsSettings(zone, at.atZone(zone).toInstant().toEpochMilli(), DayOfWeek.MONDAY,
            dailyGoalMin?.let { it * MIN }, weeklyGoalMin?.let { it * MIN }),
    )

    // ---------------------------------------------------------------- basics

    @Test
    fun emptyHistoryProducesHonestEmptyReport() {
        val a = analytics(emptyList(), dailyGoalMin = 60)
        for (p in RangePreset.entries) {
            val r = a.report(p)
            assertFalse(r.hasAnyData)
            assertFalse(r.hasDataInRange)
            assertEquals(0, r.overview.totalMs)
            assertEquals(0, r.overview.sessions)
            assertNull(r.overview.averageSessionMs)
            assertNull("no average before anything was recorded", r.overview.dailyAverageMs)
            assertTrue(r.trend.all { it.activeMs == 0L })
            assertNull(r.comparison?.changeFraction)
            assertEquals(0, r.streaks.current)
            assertTrue("no insights without data", r.insights.isEmpty())
            assertTrue("goal days only exist once tracking started", r.goalDays.isEmpty())
        }
    }

    @Test
    fun aggregatesActiveTimeNotWallClockTime() {
        // 60 min on the wall clock, 40 min actually playing (20 min paused).
        val a = analytics(listOf(fact(today, activeMin = 40.0, wallMin = 60.0)))
        val r = a.report(RangePreset.TODAY)
        assertEquals(40 * MIN, r.overview.totalMs)
        assertEquals(40 * MIN, r.overview.averageSessionMs)
        assertEquals(40 * MIN, r.sounds.first { it.sound == FocusSound.BROWN_NOISE }.activeMs)
    }

    @Test
    fun presetRangesResolveToCalendarDates() {
        val a = analytics(listOf(fact(LocalDate.of(2025, 3, 2), activeMin = 10.0)))
        assertEquals(DateRange(today, today), a.resolveRange(RangePreset.TODAY))
        assertEquals(DateRange(today.minusDays(6), today), a.resolveRange(RangePreset.LAST_7_DAYS))
        assertEquals(DateRange(today.minusDays(29), today), a.resolveRange(RangePreset.LAST_30_DAYS))
        assertEquals(DateRange(today.minusDays(89), today), a.resolveRange(RangePreset.LAST_90_DAYS))
        assertEquals(DateRange(LocalDate.of(2025, 11, 1), today), a.resolveRange(RangePreset.LAST_12_MONTHS))
        assertEquals(DateRange(LocalDate.of(2025, 3, 2), today), a.resolveRange(RangePreset.ALL_TIME))
        // Custom ranges never extend into the future.
        assertEquals(DateRange(today.minusDays(3), today),
            a.resolveRange(RangePreset.CUSTOM, DateRange(today.minusDays(3), today.plusDays(10))))
    }

    // ---------------------------------------------------------------- buckets

    @Test
    fun dailyBucketsIncludeZeroFocusDays() {
        val a = analytics(listOf(fact(today, activeMin = 30.0), fact(today.minusDays(3), activeMin = 20.0)))
        val r = a.report(RangePreset.LAST_7_DAYS)
        assertEquals(Resolution.DAY, r.trendResolution)
        assertEquals(7, r.trend.size)
        assertEquals(listOf(0L, 0L, 0L, 20 * MIN, 0L, 0L, 30 * MIN), r.trend.map { it.activeMs })
        assertTrue(r.trend.last().containsToday)
    }

    @Test
    fun weeklyBucketsAreClippedToTheRangeAndFlaggedPartial() {
        val start = today.minusDays(29) // Tue 2026-09-15
        val a = analytics(listOf(
            fact(start.minusDays(1), activeMin = 99.0), // Mon before the range: same week, must be excluded
            fact(start, activeMin = 10.0),
            fact(today, activeMin = 5.0),
        ))
        val r = a.report(RangePreset.LAST_30_DAYS, trendResolution = Resolution.WEEK)
        assertEquals(Resolution.WEEK, r.trendResolution)
        val first = r.trend.first()
        assertEquals(DayOfWeek.MONDAY, first.start.dayOfWeek)
        assertEquals(10 * MIN, first.activeMs)
        assertTrue(first.partial)
        assertEquals(6, first.daysInRange)
        val last = r.trend.last()
        assertTrue(last.partial && last.containsToday)
        assertEquals(15 * MIN, r.trend.sumOf { it.activeMs })
    }

    @Test
    fun monthlyAndYearlyBucketsSumCorrectly() {
        val facts = listOf(
            fact(LocalDate.of(2024, 1, 10), activeMin = 60.0),
            fact(LocalDate.of(2025, 12, 31), activeMin = 30.0),
            fact(LocalDate.of(2026, 1, 1), activeMin = 15.0),
            fact(today, activeMin = 5.0),
        )
        val a = analytics(facts)
        val yearly = a.report(RangePreset.ALL_TIME, trendResolution = Resolution.YEAR).trend
        assertEquals(listOf(60 * MIN, 30 * MIN, 20 * MIN), yearly.map { it.activeMs })
        val monthly = a.report(RangePreset.LAST_12_MONTHS, trendResolution = Resolution.MONTH).trend
        assertEquals(12, monthly.size)
        assertEquals(30 * MIN, monthly.first { it.start == LocalDate.of(2025, 12, 1) }.activeMs)
        assertEquals(15 * MIN, monthly.first { it.start == LocalDate.of(2026, 1, 1) }.activeMs)
    }

    @Test
    fun hourlyBucketsForTodayMarkFutureHours() {
        val a = analytics(listOf(fact(today, LocalTime.of(9, 15), activeMin = 25.0), fact(today, LocalTime.of(9, 50), activeMin = 10.0)))
        val r = a.report(RangePreset.TODAY)
        assertEquals(Resolution.HOUR, r.trendResolution)
        assertEquals(24, r.trend.size)
        assertEquals(35 * MIN, r.trend[9].activeMs)
        assertEquals(2, r.trend[9].sessions)
        assertFalse(r.trend[15].isFuture)
        assertTrue(r.trend[16].isFuture)
        assertTrue("no bar chart for a single day", r.bars.isEmpty())
    }

    @Test
    fun unsupportedResolutionFallsBackToDefault() {
        val a = analytics(listOf(fact(today, activeMin = 5.0)))
        assertEquals(Resolution.DAY, a.report(RangePreset.LAST_7_DAYS, trendResolution = Resolution.YEAR).trendResolution)
        assertEquals(Resolution.DAY, a.report(RangePreset.LAST_7_DAYS).barResolution)
        assertEquals(Resolution.MONTH, a.report(RangePreset.LAST_12_MONTHS).barResolution)
    }

    // ---------------------------------------------------------------- comparisons

    @Test
    fun previousPeriodIsCutAtTheSameTimeOfDay() {
        val yesterday = today.minusDays(1)
        val a = analytics(listOf(
            fact(LocalDate.of(2026, 1, 1), activeMin = 1.0), // tracking began long ago
            fact(yesterday, LocalTime.of(10, 0), activeMin = 30.0),
            fact(yesterday, LocalTime.of(18, 0), activeMin = 60.0), // after 15:30: not comparable yet
            fact(today, LocalTime.of(9, 0), activeMin = 45.0),
        ))
        val c = a.report(RangePreset.TODAY).comparison!!
        assertTrue(c.cutAtSameTime)
        assertEquals(30 * MIN, c.previousMs)
        assertEquals(45 * MIN, c.currentMs)
        assertEquals(0.5, c.changeFraction!!, 1e-9)
        assertEquals(DateRange(yesterday, yesterday), c.previousRange)
    }

    @Test
    fun sevenDayComparisonUsesThePrecedingSevenDays() {
        val a = analytics(listOf(
            fact(today.minusDays(30), activeMin = 1.0),
            fact(today.minusDays(7), activeMin = 100.0), // previous period (day 7 before today)
            fact(today.minusDays(13), activeMin = 20.0), // previous period start
            fact(today.minusDays(14), activeMin = 500.0), // outside both
            fact(today.minusDays(2), activeMin = 60.0),
        ))
        val c = a.report(RangePreset.LAST_7_DAYS).comparison!!
        assertEquals(DateRange(today.minusDays(13), today.minusDays(7)), c.previousRange)
        assertEquals(120 * MIN, c.previousMs)
        assertEquals(60 * MIN, c.currentMs)
        assertEquals(-0.5, c.changeFraction!!, 1e-9)
    }

    @Test
    fun noPercentageForZeroOrTinyBaselines() {
        val zero = analytics(listOf(fact(today.minusDays(40), activeMin = 10.0), fact(today, activeMin = 30.0)))
            .report(RangePreset.LAST_7_DAYS).comparison!!
        assertEquals(0, zero.previousMs)
        assertNull(zero.changeFraction)
        assertFalse(zero.previousNotFullyTracked)

        val tiny = analytics(listOf(fact(today.minusDays(40), activeMin = 10.0), fact(today.minusDays(8), activeMin = 4.0), fact(today, activeMin = 30.0)))
            .report(RangePreset.LAST_7_DAYS).comparison!!
        assertNull("4 min baseline is below the 5 min minimum", tiny.changeFraction)
    }

    @Test
    fun noComparisonWhenPreviousPeriodWasNotTracked() {
        // The user started 3 days ago: the previous 7 days were (mostly) before tracking began.
        val a = analytics(listOf(fact(today.minusDays(3), activeMin = 30.0), fact(today, activeMin = 30.0)))
        val c = a.report(RangePreset.LAST_7_DAYS).comparison!!
        assertTrue(c.previousNotFullyTracked)
        assertNull(c.changeFraction)
        assertTrue(a.report(RangePreset.LAST_7_DAYS).insights.none { it.kind == InsightKind.COMPARISON })
        assertNull(a.report(RangePreset.ALL_TIME).comparison)
    }

    // ---------------------------------------------------------------- data integrity

    @Test
    fun sessionCrossingMidnightIsAttributedToItsStartDay() {
        val day1 = today.minusDays(2)
        val a = analytics(listOf(fact(day1, LocalTime.of(23, 30), activeMin = 70.0, wallMin = 75.0)))
        assertEquals(70 * MIN, a.dayStats[day1]!!.activeMs)
        assertNull(a.dayStats[day1.plusDays(1)])
        val r = a.report(RangePreset.LAST_7_DAYS)
        assertEquals(70 * MIN, r.trend.first { it.start == day1 }.activeMs)
        assertEquals(0, r.trend.first { it.start == day1.plusDays(1) }.activeMs)
    }

    @Test
    fun interruptedCountsCancelledDoesNotAndOutcomesAreReported() {
        val a = analytics(listOf(
            fact(today, activeMin = 25.0, outcome = EndReason.COMPLETED, planned = 25 * MIN),
            fact(today, activeMin = 12.0, outcome = EndReason.STOPPED),
            fact(today, activeMin = 7.0, outcome = EndReason.INTERRUPTED),
            fact(today, activeMin = 0.0, outcome = EndReason.CANCELLED),
        ))
        val r = a.report(RangePreset.TODAY)
        assertEquals(44 * MIN, r.overview.totalMs)
        assertEquals(3, r.overview.sessions)
        val byOutcome = r.outcomes.associateBy { it.outcome }
        assertEquals(1, byOutcome[EndReason.COMPLETED]!!.sessions)
        assertEquals(1, byOutcome[EndReason.STOPPED]!!.sessions)
        assertEquals(1, byOutcome[EndReason.INTERRUPTED]!!.sessions)
        assertEquals(1, byOutcome[EndReason.CANCELLED]!!.sessions)
        assertEquals(25 * MIN, r.longestCompleted!!.fact.activeMs)
    }

    @Test
    fun duplicateRowsForTheSameSessionAreCountedOnce() {
        val finished = fact(today, activeMin = 30.0, id = "dup", outcome = EndReason.STOPPED)
        val runningCopy = finished.copy(outcome = null, endedAtMs = null, activeMs = 29 * MIN)
        val r = analytics(listOf(runningCopy, finished, finished)).report(RangePreset.TODAY)
        assertEquals(1, r.overview.sessions)
        assertEquals("finished copy wins", 30 * MIN, r.overview.totalMs)
    }

    @Test
    fun runningSessionIsIncludedWithItsLiveActiveTime() {
        val running = fact(today, LocalTime.of(15, 0), activeMin = 20.0, outcome = null)
        val starting = fact(today, LocalTime.of(15, 29), activeMin = 0.0, outcome = null)
        val r = analytics(listOf(running)).report(RangePreset.TODAY)
        assertTrue(r.includesRunningSession)
        assertEquals(20 * MIN, r.overview.totalMs)
        assertEquals(1, r.outcomes.first { it.outcome == null }.sessions)
        val s = analytics(listOf(starting)).streaks
        assertEquals("a session with no active playback never counts", 0, s.current)
    }

    @Test
    fun renamedActivitiesStayOneGroupAndDeletedOnesKeepTheirName() {
        val facts = listOf(
            fact(today, activeMin = 30.0, activityId = 7, activityName = "Music practice"),
            fact(today.minusDays(1), activeMin = 20.0, activityId = 7, activityName = "Music practice"),
            fact(today, activeMin = 10.0, activityId = null, activityName = "Chess"),
        )
        val d = analytics(facts).report(RangePreset.LAST_7_DAYS).activities
        assertEquals(2, d.slices.size)
        assertEquals("Music practice", d.slices[0].name)
        assertEquals(50 * MIN, d.slices[0].activeMs)
        assertEquals(2, d.slices[0].sessions)
        assertEquals("Chess", d.slices[1].name)
        assertTrue(d.slices[1].deleted)
        assertEquals(1.0, d.slices.sumOf { it.fraction }, 1e-9)
    }

    @Test
    fun manySmallActivitiesAreGroupedIntoOtherWithoutLosingData() {
        val facts = (1..8).map { i -> fact(today, activeMin = (10 * i).toDouble(), activityId = i.toLong(), activityName = "A$i") }
        val d = analytics(facts).report(RangePreset.TODAY).activities
        assertEquals(5, d.slices.size)
        assertEquals(listOf("A8", "A7", "A6", "A5", "A4"), d.slices.map { it.name })
        assertEquals(listOf("A3", "A2", "A1"), d.other.map { it.name })
        assertEquals(60 * MIN, d.otherMs)
        assertEquals(360 * MIN, d.totalMs)
        assertEquals(d.totalMs, d.slices.sumOf { it.activeMs } + d.otherMs)
    }

    @Test
    fun soundUsageCoversAllFiveSoundsByActiveTime() {
        val r = analytics(listOf(
            fact(today, activeMin = 30.0, sound = FocusSound.OCEAN, wallMin = 90.0),
            fact(today, activeMin = 10.0, sound = FocusSound.OCEAN),
            fact(today, activeMin = 20.0, sound = FocusSound.FOREST),
        )).report(RangePreset.TODAY)
        assertEquals(FocusSound.entries, r.sounds.map { it.sound })
        val ocean = r.sounds.first { it.sound == FocusSound.OCEAN }
        assertEquals(40 * MIN, ocean.activeMs)
        assertEquals(2, ocean.sessions)
        assertEquals(2.0 / 3, ocean.fraction, 1e-9)
        assertEquals(0, r.sounds.first { it.sound == FocusSound.STREAM }.activeMs)
        assertTrue(r.insights.any { it.kind == InsightKind.TOP_SOUND && it.title.startsWith("Ocean") })
    }

    // ---------------------------------------------------------------- streaks & goals

    @Test
    fun streaksUseConsecutiveFocusDays() {
        val facts = listOf(today, today.minusDays(1), today.minusDays(2)).map { fact(it, activeMin = 5.0) } +
            (10..14).map { fact(today.minusDays(it.toLong()), activeMin = 20.0) } +
            fact(today.minusDays(3), activeMin = 0.5) // 30 s: below the 1-minute focus-day threshold
        val s = analytics(facts).streaks
        assertEquals(3, s.current)
        assertEquals(5, s.longest)
        assertEquals(today.minusDays(10), s.longestEnd)
        assertTrue(s.todayCounts)
    }

    @Test
    fun currentStreakSurvivesUntilTodayEnds() {
        val facts = listOf(today.minusDays(1), today.minusDays(2)).map { fact(it, activeMin = 5.0) }
        val s = analytics(facts).streaks
        assertEquals(2, s.current)
        assertFalse(s.todayCounts)
        val broken = analytics(listOf(fact(today.minusDays(2), activeMin = 5.0))).streaks
        assertEquals(0, broken.current)
        assertEquals(1, broken.longest)
    }

    @Test
    fun goalProgressForTodayAndThisWeek() {
        val facts = listOf(
            fact(today, activeMin = 45.0),
            fact(today.minusDays(1), activeMin = 90.0), // Tue
            fact(today.minusDays(2), activeMin = 30.0), // Mon (week start)
            fact(today.minusDays(3), activeMin = 600.0), // Sun: previous week
        )
        val r = analytics(facts, dailyGoalMin = 60, weeklyGoalMin = 300).report(RangePreset.LAST_7_DAYS)
        val d = r.dailyGoal!!
        assertEquals(45 * MIN, d.achievedMs)
        assertFalse(d.met)
        assertEquals(15 * MIN, d.remainingMs)
        val w = r.weeklyGoal!!
        assertEquals(165 * MIN, w.achievedMs)
        assertEquals(today.minusDays(2), w.period.start)
        // Goal days only cover tracked days (tracking began 3 days ago, Sunday).
        assertEquals(4, r.goalDays.size)
        assertEquals(2, r.goalDays.count { it.met })
        assertEquals(60 * MIN, r.barGoalMs)
    }

    @Test
    fun noGoalsWhenNotConfigured() {
        val r = analytics(listOf(fact(today, activeMin = 30.0))).report(RangePreset.LAST_7_DAYS)
        assertNull(r.dailyGoal)
        assertNull(r.weeklyGoal)
        assertNull(r.barGoalMs)
        assertTrue(r.goalDays.isEmpty())
    }

    // ---------------------------------------------------------------- calendar

    @Test
    fun calendarDistinguishesFutureUntrackedZeroAndIntensity() {
        val facts = listOf(
            fact(LocalDate.of(2026, 10, 5), activeMin = 10.0),
            fact(LocalDate.of(2026, 10, 6), activeMin = 20.0),
            fact(LocalDate.of(2026, 10, 7), activeMin = 45.0),
            fact(LocalDate.of(2026, 10, 8), activeMin = 90.0),
            fact(LocalDate.of(2026, 10, 9), activeMin = 150.0),
        )
        val cal = analytics(facts, dailyGoalMin = 60).calendarMonth(YearMonth.of(2026, 10))
        val cells = cal.weeks.flatten().associateBy { it.date }
        assertEquals(CellState.NOT_TRACKED, cells[LocalDate.of(2026, 10, 1)]!!.state)
        assertEquals(CellState.NOT_IN_MONTH, cells[LocalDate.of(2026, 9, 28)]!!.state)
        assertEquals(CellState.FUTURE, cells[LocalDate.of(2026, 10, 20)]!!.state)
        assertEquals(CellState.TRACKED, cells[LocalDate.of(2026, 10, 12)]!!.state)
        assertEquals(0, cells[LocalDate.of(2026, 10, 12)]!!.level)
        assertEquals(listOf(1, 2, 3, 4, 5), (5..9).map { cells[LocalDate.of(2026, 10, it)]!!.level })
        assertTrue(cells[LocalDate.of(2026, 10, 8)]!!.goalMet)
        assertFalse(cells[LocalDate.of(2026, 10, 7)]!!.goalMet)
        assertTrue(cells[today]!!.isToday)
        assertFalse("cannot navigate into the future", cal.canGoForward)
        assertFalse("nothing tracked before October", cal.canGoBack)
        assertEquals(315 * MIN, cal.totalMs)
        assertTrue(cal.weeks.all { it.size == 7 && it.first().date.dayOfWeek == DayOfWeek.MONDAY })
    }

    @Test
    fun daylightSavingDaysAreStillSingleCalendarDays() {
        // Europe/Berlin falls back on 2026-10-25 (a 25-hour day).
        val dst = LocalDate.of(2026, 10, 25)
        val at = LocalDateTime.of(LocalDate.of(2026, 10, 27), LocalTime.of(12, 0))
        val a = analytics(listOf(fact(dst, LocalTime.of(23, 50), activeMin = 30.0), fact(dst.minusDays(5), activeMin = 1.0)), at = at)
        val r = a.report(RangePreset.LAST_7_DAYS)
        assertEquals(7, r.trend.size)
        assertEquals(30 * MIN, r.trend.first { it.start == dst }.activeMs)
    }

    // ---------------------------------------------------------------- other charts & insights

    @Test
    fun sessionLengthHistogramUsesActiveMinutes() {
        val r = analytics(listOf(
            fact(today, activeMin = 3.0), fact(today, activeMin = 25.0), fact(today, activeMin = 50.0, wallMin = 120.0),
            fact(today, activeMin = 120.0),
        )).report(RangePreset.TODAY)
        val bins = r.sessionLengths.associate { it.label to it.sessions }
        assertEquals(1, bins["0–5"])
        assertEquals(1, bins["25–45"])
        assertEquals(1, bins["45–60"])
        assertEquals(1, bins["90+"])
        assertEquals(4, r.sessionLengths.sumOf { it.sessions })
    }

    @Test
    fun insightsAreBackedByData() {
        val facts = (0..20).map { fact(today.minusDays(it.toLong()), activeMin = 30.0, activityName = "Studying") } +
            fact(today.minusDays(2), activeMin = 120.0, activityId = 2, activityName = "Programming", outcome = EndReason.COMPLETED)
        val r = analytics(facts, dailyGoalMin = 60).report(RangePreset.LAST_7_DAYS)
        val kinds = r.insights.map { it.kind }
        assertTrue(InsightKind.TOP_ACTIVITY in kinds)
        assertTrue(InsightKind.STREAK in kinds)
        assertTrue(InsightKind.GOAL in kinds)
        assertTrue(InsightKind.LONGEST_SESSION in kinds)
        assertTrue(InsightKind.RECORD_DAY in kinds)
        val goal = r.insights.first { it.kind == InsightKind.GOAL }
        assertEquals("Daily goal met on 1 of 7 days", goal.title)
        val streak = r.insights.first { it.kind == InsightKind.STREAK }
        assertEquals("21-day focus streak", streak.title)
        assertNotNull(r.bestDay)
        assertEquals(today.minusDays(2), r.bestDay!!.date)
    }

    @Test
    fun historyFilterMatchesEveryCriterion() {
        val f = fact(today, activeMin = 10.0, sound = FocusSound.OCEAN, outcome = EndReason.COMPLETED)
        assertTrue(HistoryFilter().matches(f, zone))
        assertTrue(HistoryFilter(range = DateRange(today, today), soundIds = setOf("ocean")).matches(f, zone))
        assertFalse(HistoryFilter(range = DateRange(today.minusDays(3), today.minusDays(1))).matches(f, zone))
        assertFalse(HistoryFilter(activityKeys = setOf("id:99")).matches(f, zone))
        assertFalse(HistoryFilter(outcomes = setOf(EndReason.STOPPED)).matches(f, zone))
        assertTrue(HistoryFilter(outcomes = setOf(EndReason.COMPLETED, null)).matches(f, zone))
        assertTrue(HistoryFilter(outcomes = setOf(null)).matches(f.copy(outcome = null), zone))
    }
}
