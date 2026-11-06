package com.focussounds.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.focussounds.core.Durations
import com.focussounds.core.EndReason
import com.focussounds.core.analytics.ActivityDistribution
import com.focussounds.core.analytics.Comparison
import com.focussounds.core.analytics.DateRange
import com.focussounds.core.analytics.InsightKind
import com.focussounds.core.analytics.ProgressReport
import com.focussounds.core.analytics.RangePreset
import com.focussounds.core.analytics.Resolution
import com.focussounds.ui.charts.AxisKind
import com.focussounds.ui.charts.BarChart
import com.focussounds.ui.charts.CalendarHeatmap
import com.focussounds.ui.charts.ChartDatum
import com.focussounds.ui.charts.DonutChart
import com.focussounds.ui.charts.DonutSlice
import com.focussounds.ui.charts.GoalDot
import com.focussounds.ui.charts.GoalStrip
import com.focussounds.ui.charts.StackSegment
import com.focussounds.ui.charts.StackedBar
import com.focussounds.ui.charts.TrendChart
import com.focussounds.ui.charts.UsageBars
import com.focussounds.ui.charts.UsageRow
import com.focussounds.ui.components.AppIcons
import com.focussounds.ui.components.EmptyState
import com.focussounds.ui.components.Eyebrow
import com.focussounds.ui.components.FocusCard
import com.focussounds.ui.components.LegendSwatch
import com.focussounds.ui.components.MetricTile
import com.focussounds.ui.components.Pill
import com.focussounds.ui.components.PrimaryButton
import com.focussounds.ui.components.ProgressRing
import com.focussounds.ui.components.ScreenPadding
import com.focussounds.ui.components.PillShape
import com.focussounds.ui.components.SectionLabel
import com.focussounds.ui.components.ScreenHeader
import com.focussounds.ui.components.SecondaryButton
import com.focussounds.ui.components.SectionHeader
import com.focussounds.ui.components.SegmentOption
import com.focussounds.ui.components.SegmentedControl
import com.focussounds.ui.theme.FocusTheme
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.TextStyle

@Composable
fun ProgressScreen(vm: ProgressViewModel, onStartFocus: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val state = ui
    var showCustom by remember { mutableStateOf(false) }
    var goalEditor by remember { mutableStateOf<GoalKind?>(null) }

    if (state == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Calculating…", color = FocusTheme.colors.textTertiary)
        }
        return
    }
    val r = state.report

    LazyColumn(
        Modifier.fillMaxSize().testTag("progress_list"),
        contentPadding = PaddingValues(start = ScreenPadding, end = ScreenPadding, top = 8.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(key = "header") { ProgressHeader(r) }
        item(key = "ranges") {
            SegmentedControl(
                options = RangePreset.entries.map { p ->
                    if (p == RangePreset.CUSTOM) SegmentOption(p, "", AppIcons.Calendar, "Custom date range")
                    else SegmentOption(p, p.short, description = p.label)
                },
                selected = state.query.preset,
                onSelect = { if (it == RangePreset.CUSTOM) showCustom = true else vm.setPreset(it) },
                modifier = Modifier.testTag("range_selector"),
            )
        }
        if (!r.hasAnyData) {
            item(key = "empty") {
                FocusCard {
                    EmptyState(
                        icon = AppIcons.Progress,
                        title = "Your progress will appear here",
                        message = "Every focus session you play is recorded on this device. Charts, streaks and insights appear after your first session.",
                        action = { PrimaryButton("Start a focus session", onStartFocus, icon = AppIcons.Play) },
                    )
                }
            }
            item(key = "goals") { GoalsCard(r, state.dailyGoalMinutes, state.weeklyGoalMinutes) { goalEditor = it } }
            item(key = "rules") { RulesCard() }
            return@LazyColumn
        }
        item(key = "hero") { HeroCard(r) }
        item(key = "goals") { GoalsCard(r, state.dailyGoalMinutes, state.weeklyGoalMinutes) { goalEditor = it } }
        item(key = "metrics") { MetricsGrid(r) }
        if (r.insights.isNotEmpty()) item(key = "insights") { InsightsCard(r) }
        item(key = "trends_label") { SectionLabel("Trends") }
        item(key = "trend") { TrendCard(r, vm::setTrendResolution) }
        if (r.barResolution != null) item(key = "bars") { BarsCard(r, vm::setBarResolution) }
        item(key = "breakdown_label") { SectionLabel("Breakdown") }
        item(key = "activities") { ActivitiesCard(r.activities) }
        item(key = "calendar") { CalendarCard(state, vm) }
        item(key = "sounds") { SoundsCard(r) }
        item(key = "details") { DetailsCard(r) }
        item(key = "rules") { RulesCard() }
    }

    if (showCustom) CustomRangeDialog(state.query.custom, r.today, onDismiss = { showCustom = false }) {
        showCustom = false
        vm.setCustom(it)
    }
    goalEditor?.let { kind ->
        if (kind == GoalKind.DAILY) {
            GoalDialog("Daily focus goal", state.dailyGoalMinutes, listOf(30, 60, 120, 180), 24 * 60,
                onConfirm = { vm.setDailyGoal(it); goalEditor = null }, onDismiss = { goalEditor = null })
        } else {
            GoalDialog("Weekly focus goal", state.weeklyGoalMinutes, listOf(300, 600, 900, 1200), 7 * 24 * 60,
                onConfirm = { vm.setWeeklyGoal(it); goalEditor = null }, onDismiss = { goalEditor = null })
        }
    }
}

enum class GoalKind { DAILY, WEEKLY }

@Composable
private fun ProgressHeader(r: ProgressReport) {
    val c = FocusTheme.colors
    Column {
        ScreenHeader("Progress", subtitle = rangeLabel(r.range, r.today))
        if (r.includesRunningSession) {
            // On an opaque base: the header sits over the sky light.
            Box(Modifier.padding(top = 6.dp).clip(PillShape).background(c.surface1)) { Pill("Live session included", c.accentContent) }
        }
    }
}

@Composable
private fun HeroCard(r: ProgressReport) {
    val c = FocusTheme.colors
    val o = r.overview
    FocusCard(Modifier.fillMaxWidth().testTag("hero_card"), padding = PaddingValues(22.dp), accentWash = true) {
        Eyebrow("Focus time · ${if (r.preset == RangePreset.CUSTOM) "${r.range.days} days" else r.preset.label}")
        Spacer(Modifier.height(6.dp))
        Text(Durations.format(o.totalMs), style = MaterialTheme.typography.displaySmall, color = c.textPrimary, modifier = Modifier.testTag("hero_total"))
        Spacer(Modifier.height(8.dp))
        r.comparison?.let { ComparisonLine(it) }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth()) {
            HeroStat("Sessions", o.sessions.toString(), Modifier.weight(1f))
            HeroStat("Avg session", o.averageSessionMs?.let { Durations.format(it) } ?: "—", Modifier.weight(1f))
            HeroStat(
                "Per day",
                o.dailyAverageMs?.let { Durations.format(it) } ?: "—",
                Modifier.weight(1f),
                sub = if (o.averagedOverDays > 0) "over ${o.averagedOverDays} ${if (o.averagedOverDays == 1L) "day" else "days"}" else null,
            )
        }
    }
}

@Composable
private fun HeroStat(label: String, value: String, modifier: Modifier, sub: String? = null) {
    val c = FocusTheme.colors
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = c.textSecondary, maxLines = 1)
        Text(value, style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"), color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (sub != null) Text(sub, style = MaterialTheme.typography.labelSmall, color = c.textSecondary, maxLines = 1)
    }
}

@Composable
private fun ComparisonLine(cmp: Comparison) {
    val c = FocusTheme.colors
    val pct = cmp.changeFraction
    Column {
        when {
            cmp.previousNotFullyTracked -> Text("Not enough history yet to compare with ${cmp.label}.", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
            pct != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                val up = cmp.deltaMs > 0
                val same = cmp.deltaMs == 0L
                Pill(
                    when {
                        same -> "No change"
                        up -> "▲ ${Durations.percent(pct)}"
                        else -> "▼ ${Durations.percent(-pct)}"
                    },
                    if (up) c.accentContent else c.neutral,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "${if (up) "+" else if (same) "" else "−"}${Durations.format(kotlin.math.abs(cmp.deltaMs))} vs ${cmp.label}",
                    style = MaterialTheme.typography.bodySmall, color = c.textSecondary,
                )
            }
            cmp.previousMs == 0L -> Text("No focus time in ${cmp.label}.", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
            else -> Text("${cmp.label.replaceFirstChar { it.uppercase() }}: ${Durations.format(cmp.previousMs)} (too little for a meaningful percentage).",
                style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
        }
        if (cmp.cutAtSameTime && !cmp.previousNotFullyTracked) {
            Text("Compared up to the same time of day.", style = MaterialTheme.typography.labelSmall, color = c.textSecondary)
        }
    }
}

@Composable
private fun GoalsCard(r: ProgressReport, dailyMin: Int, weeklyMin: Int, onEdit: (GoalKind) -> Unit) {
    val c = FocusTheme.colors
    // Observed through the configuration so weekday initials follow a locale change.
    val locale = LocalConfiguration.current.locales[0]
    FocusCard(Modifier.fillMaxWidth().testTag("goals_card")) {
        SectionHeader("Goals", subtitle = if (dailyMin == 0 && weeklyMin == 0) "Optional targets for daily or weekly focus" else null)
        Spacer(Modifier.height(12.dp))
        if (dailyMin == 0 && weeklyMin == 0) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton("Set daily goal", { onEdit(GoalKind.DAILY) }, Modifier.weight(1f), icon = AppIcons.Goal)
                SecondaryButton("Set weekly goal", { onEdit(GoalKind.WEEKLY) }, Modifier.weight(1f), icon = AppIcons.Calendar)
            }
            return@FocusCard
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GoalBlock("Today", r.dailyGoal?.let { it.achievedMs to it.goalMs }, Modifier.weight(1f)) { onEdit(GoalKind.DAILY) }
            GoalBlock("This week", r.weeklyGoal?.let { it.achievedMs to it.goalMs }, Modifier.weight(1f)) { onEdit(GoalKind.WEEKLY) }
        }
        val days = r.goalDays.takeLast(14)
        if (days.size >= 3) {
            Spacer(Modifier.height(16.dp))
            Text("Daily goal · last ${days.size} tracked days", style = MaterialTheme.typography.labelMedium, color = c.textTertiary)
            Spacer(Modifier.height(8.dp))
            GoalStrip(days.map { d ->
                GoalDot(
                    d.date.dayOfWeek.getDisplayName(TextStyle.NARROW, locale),
                    d.fraction.toFloat(),
                    d.met,
                    "${dayTitle(d.date, r.today)}: ${Durations.format(d.activeMs)}, ${if (d.met) "goal met" else "goal not met"}",
                )
            })
        }
    }
}

@Composable
private fun GoalBlock(label: String, progress: Pair<Long, Long>?, modifier: Modifier, onEdit: () -> Unit) {
    val c = FocusTheme.colors
    Row(
        modifier.clip(RoundedCornerShape(16.dp)).background(c.surface2).clickable(role = Role.Button, onClick = onEdit).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (progress == null) {
            Column {
                Text(label, style = MaterialTheme.typography.labelMedium, color = c.textTertiary)
                Text("No goal", style = MaterialTheme.typography.titleSmall, color = c.textSecondary)
                Text("Tap to set", style = MaterialTheme.typography.labelSmall, color = c.accentContent)
            }
            return@Row
        }
        val (done, goal) = progress
        val met = done >= goal
        ProgressRing(done.toFloat() / goal, size = 52.dp, stroke = 6.dp) {
            if (met) Icon(AppIcons.Check, "Goal met", Modifier.size(20.dp), tint = c.accentContent)
            else Text(Durations.percent(done.toDouble() / goal), style = MaterialTheme.typography.labelSmall, color = c.textPrimary)
        }
        Spacer(Modifier.width(10.dp))
        Column {
            Text(label, style = MaterialTheme.typography.labelMedium, color = c.textTertiary)
            Text(Durations.short(done), style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum"), color = c.textPrimary, maxLines = 1)
            Text("of ${Durations.short(goal)}", style = MaterialTheme.typography.labelSmall, color = c.textTertiary, maxLines = 1)
            Text(
                if (met) "Reached" else "${Durations.short(goal - done)} left",
                style = MaterialTheme.typography.labelSmall, color = if (met) c.accentContent else c.textSecondary, maxLines = 1,
            )
        }
    }
}

@Composable
private fun MetricsGrid(r: ProgressReport) {
    val s = r.streaks
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricTile(
                "Current streak", "${s.current} ${if (s.current == 1) "day" else "days"}", Modifier.weight(1f),
                detail = when {
                    s.current == 0 -> "Focus 1 min today to start one"
                    !s.todayCounts -> "Focus today to keep it"
                    else -> "Longest: ${s.longest} ${if (s.longest == 1) "day" else "days"}"
                },
                icon = AppIcons.Flame,
            )
            MetricTile(
                "Focus days", "${r.overview.focusDays} of ${minOf(r.range.days, java.time.temporal.ChronoUnit.DAYS.between(r.range.start, r.today) + 1)}",
                Modifier.weight(1f), detail = "Days with at least 1 min", icon = AppIcons.Calendar,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricTile(
                "Best day", r.bestDay?.let { Durations.format(it.activeMs) } ?: "—", Modifier.weight(1f),
                detail = r.bestDay?.let { dayTitle(it.date, r.today) } ?: "No focus in this period", icon = AppIcons.Star,
            )
            MetricTile(
                "Longest session", r.longestSession?.let { Durations.format(it.fact.activeMs) } ?: "—", Modifier.weight(1f),
                detail = r.longestSession?.let { "${it.fact.activityName} · ${dayTitle(it.date, r.today)}" } ?: "No sessions in this period",
                icon = AppIcons.Timer,
            )
        }
    }
}

@Composable
private fun InsightsCard(r: ProgressReport) {
    val c = FocusTheme.colors
    FocusCard(Modifier.fillMaxWidth().testTag("insights_card")) {
        SectionHeader("Highlights", subtitle = "Calculated from your recorded sessions")
        Spacer(Modifier.height(8.dp))
        r.insights.forEachIndexed { i, ins ->
            if (i > 0) Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
                val icon = when (ins.kind) {
                    InsightKind.COMPARISON -> AppIcons.Progress
                    InsightKind.TOP_ACTIVITY -> AppIcons.Activities
                    InsightKind.TOP_SOUND -> AppIcons.Volume
                    InsightKind.GOAL -> AppIcons.Goal
                    InsightKind.STREAK -> AppIcons.Flame
                    InsightKind.LONGEST_SESSION -> AppIcons.Timer
                    InsightKind.RECORD_DAY -> AppIcons.Star
                }
                Box(Modifier.size(34.dp).clip(RoundedCornerShape(11.dp)).background(c.accentContainer), contentAlignment = Alignment.Center) {
                    Icon(icon, null, Modifier.size(18.dp), tint = c.accentContent)
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(ins.title, style = MaterialTheme.typography.titleSmall, color = c.textPrimary)
                    Text(ins.detail, style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                }
            }
        }
    }
}

@Composable
private fun TrendCard(r: ProgressReport, onResolution: (Resolution) -> Unit) {
    val c = FocusTheme.colors
    val data = remember(r.trend, r.trendResolution) { r.trend.map { it.toDatum(r.trendResolution, r.range, r.today) } }
    FocusCard(Modifier.fillMaxWidth().testTag("trend_card")) {
        SectionHeader(
            "Focus trend",
            subtitle = if (r.trendResolution == Resolution.HOUR) "By session start hour · tap or drag to inspect" else "${r.trendResolution.label} · tap or drag to inspect",
        )
        if (r.trendResolutions.size > 1) {
            Spacer(Modifier.height(12.dp))
            SegmentedControl(r.trendResolutions.map { SegmentOption(it, it.label) }, r.trendResolution, onResolution, fill = false)
        }
        Spacer(Modifier.height(14.dp))
        if (data.all { it.value == 0.0 }) {
            NoDataInPeriod()
        } else {
            TrendChart(data, summary = chartSummary("Focus trend", data))
            if (data.any { it.partial }) {
                Spacer(Modifier.height(6.dp))
                Text("Hollow points mark periods still in progress or only partly in range.", style = MaterialTheme.typography.labelSmall, color = c.textTertiary)
            }
        }
    }
}

@Composable
private fun BarsCard(r: ProgressReport, onResolution: (Resolution) -> Unit) {
    val c = FocusTheme.colors
    val res = r.barResolution ?: return
    val data = remember(r.bars, res) { r.bars.map { it.toDatum(res, r.range, r.today) } }
    val goalMin = r.barGoalMs?.let { it / 60_000.0 }
    val title = when (res) {
        Resolution.DAY -> "Daily focus"
        Resolution.WEEK -> "Weekly focus"
        else -> "Monthly focus"
    }
    FocusCard(Modifier.fillMaxWidth().testTag("bars_card")) {
        SectionHeader(
            title,
            subtitle = if (goalMin != null) "Dashed line: ${Durations.short(r.barGoalMs!!)} goal · solid bars reached it" else "Tap a bar for details",
        )
        if (r.barResolutions.size > 1) {
            Spacer(Modifier.height(12.dp))
            SegmentedControl(r.barResolutions.map { SegmentOption(it, it.label) }, res, onResolution, fill = false)
        }
        Spacer(Modifier.height(14.dp))
        if (data.all { it.value == 0.0 }) {
            NoDataInPeriod()
        } else {
            BarChart(
                data,
                goal = goalMin,
                summary = chartSummary(title, data),
                highlightMax = goalMin == null,
            )
            if (goalMin != null) {
                val goalMs = r.barGoalMs!!
                val text = if (res == Resolution.DAY) {
                    // Tracked days only; today counts once it is met (it is not over yet).
                    val days = r.goalDays.filter { it.date != r.today || it.met }
                    "Goal reached on ${days.count { it.met }} of ${days.size} tracked ${if (days.size == 1) "day" else "days"}."
                } else {
                    val full = r.bars.filter { !it.partial && !it.containsToday }
                    "Goal reached in ${full.count { it.activeMs >= goalMs }} of ${full.size} complete ${if (full.size == 1) "week" else "weeks"}."
                }
                Spacer(Modifier.height(8.dp))
                Text(text, style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
            }
        }
    }
}

@Composable
private fun ActivitiesCard(d: ActivityDistribution) {
    val c = FocusTheme.colors
    var selected by rememberSaveable(d.slices.map { it.key }) { mutableIntStateOf(-1) }
    var showOther by rememberSaveable { mutableStateOf(false) }
    val slices = d.slices.mapIndexed { i, s -> DonutSlice(s.name, s.activeMs.toDouble(), c.seriesColor(i), Durations.format(s.activeMs)) } +
        if (d.other.isNotEmpty()) listOf(DonutSlice("Other (${d.other.size})", d.otherMs.toDouble(), c.neutral, Durations.format(d.otherMs))) else emptyList()
    val fractions = d.slices.map { it.fraction } + if (d.other.isNotEmpty()) listOf(d.otherFraction) else emptyList()

    FocusCard(Modifier.fillMaxWidth().testTag("activities_card")) {
        SectionHeader("Where your focus went", subtitle = "Active time by activity")
        Spacer(Modifier.height(14.dp))
        if (d.totalMs == 0L) {
            NoDataInPeriod()
            return@FocusCard
        }
        val sel = slices.getOrNull(selected)
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            DonutChart(
                slices = slices,
                selected = selected,
                onSelect = { selected = it },
                centerTitle = sel?.label ?: "Total",
                centerValue = sel?.detail ?: Durations.format(d.totalMs),
                centerDetail = sel?.let { Durations.percent(fractions[selected]) } ?: "${d.slices.size + d.other.size} ${if (d.slices.size + d.other.size == 1) "activity" else "activities"}",
                summary = "Activity distribution: " + d.slices.joinToString { "${it.name} ${Durations.percent(it.fraction)}" },
            )
        }
        Spacer(Modifier.height(14.dp))
        slices.forEachIndexed { i, s ->
            LegendRow(
                color = s.color,
                label = s.label,
                value = s.detail,
                percent = Durations.percent(fractions[i]),
                sub = d.slices.getOrNull(i)?.let { "${sessionsLabel(it.sessions)}${if (it.deleted) " · deleted activity" else ""}" }
                    ?: "Tap to ${if (showOther) "hide" else "show"} the grouped activities",
                selected = i == selected,
            ) {
                if (i >= d.slices.size) showOther = !showOther
                selected = if (selected == i) -1 else i
            }
        }
        AnimatedVisibility(showOther && d.other.isNotEmpty(), enter = FocusTheme.motion.expandIn(), exit = FocusTheme.motion.collapseOut()) {
            Column(Modifier.padding(start = 20.dp)) {
                d.other.forEach { o ->
                    LegendRow(c.neutral.copy(alpha = 0.6f), o.name, Durations.format(o.activeMs), Durations.percent(o.fraction),
                        sessionsLabel(o.sessions) + if (o.deleted) " · deleted activity" else "", selected = false, onClick = null)
                }
            }
        }
    }
}

@Composable
private fun LegendRow(color: Color, label: String, value: String, percent: String, sub: String?, selected: Boolean, onClick: (() -> Unit)?) {
    val c = FocusTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) c.surface3 else Color.Transparent)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .heightIn(min = 48.dp)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LegendSwatch(color)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = c.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (sub != null) Text(sub, style = MaterialTheme.typography.labelSmall, color = c.textTertiary, maxLines = 1)
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(value, style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"), color = c.textPrimary)
            Text(percent, style = MaterialTheme.typography.labelSmall, color = c.textTertiary)
        }
    }
}

@Composable
private fun CalendarCard(state: ProgressUi, vm: ProgressViewModel) {
    val c = FocusTheme.colors
    var selectedDay by rememberSaveable { mutableStateOf<Long?>(null) }
    val selectedDate = selectedDay?.let { LocalDate.ofEpochDay(it) }
    val cal = state.calendar
    FocusCard(Modifier.fillMaxWidth().testTag("calendar_card")) {
        SectionHeader("Focus calendar", subtitle = "Daily focus intensity · tap a day")
        Spacer(Modifier.height(8.dp))
        CalendarHeatmap(
            month = cal,
            selected = selectedDate,
            onSelect = { selectedDay = it?.toEpochDay() },
            onPrev = { selectedDay = null; vm.showMonth(cal.month.minusMonths(1)) },
            onNext = { selectedDay = null; vm.showMonth(cal.month.plusMonths(1)) },
        )
        val cell = selectedDate?.let { d -> cal.weeks.flatten().firstOrNull { it.date == d } }
        if (cell != null) {
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surface2).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(dayTitle(cell.date, state.report.today), style = MaterialTheme.typography.titleSmall, color = c.textPrimary)
                    Text("${Durations.format(cell.activeMs)} · ${sessionsLabel(cell.sessions)}", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                }
                if (cell.goalMet) Pill("Goal met", c.accentContent, icon = AppIcons.Check)
            }
        }
    }
}

@Composable
private fun SoundsCard(r: ProgressReport) {
    val c = FocusTheme.colors
    FocusCard(Modifier.fillMaxWidth().testTag("sounds_card")) {
        SectionHeader("Sound usage", subtitle = "Active playback time per sound")
        Spacer(Modifier.height(14.dp))
        UsageBars(r.sounds.map { s ->
            UsageRow(
                label = s.sound.displayName,
                icon = AppIcons.forSound(s.sound),
                valueLabel = Durations.format(s.activeMs),
                detail = if (s.sessions == 0) "Not used in this period" else "${sessionsLabel(s.sessions)} · ${Durations.percent(s.fraction)} of playback",
                fraction = s.fraction.toFloat(),
                color = c.accent,
            )
        })
    }
}

@Composable
private fun DetailsCard(r: ProgressReport) {
    val c = FocusTheme.colors
    var open by rememberSaveable { mutableStateOf(false) }
    val motion = FocusTheme.motion
    FocusCard(Modifier.fillMaxWidth().testTag("details_card")) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button) { open = !open }.heightIn(min = 44.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("More breakdowns", style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
                Text("Session lengths, start times and outcomes", style = MaterialTheme.typography.bodySmall, color = c.textTertiary)
            }
            Icon(if (open) AppIcons.ChevronDown else AppIcons.ChevronRight, if (open) "Collapse" else "Expand", tint = c.textSecondary)
        }
        AnimatedVisibility(open, enter = motion.expandIn(), exit = motion.collapseOut()) {
            Column {
                Spacer(Modifier.height(18.dp))
                Text("Session length", style = MaterialTheme.typography.titleSmall, color = c.textPrimary)
                Text("Sessions by active minutes", style = MaterialTheme.typography.labelSmall, color = c.textTertiary)
                Spacer(Modifier.height(10.dp))
                val lengths = r.sessionLengths.map { b ->
                    ChartDatum(b.label, "${b.label} min", b.sessions.toDouble(), sessionsLabel(b.sessions))
                }
                if (lengths.all { it.value == 0.0 }) NoDataInPeriod() else BarChart(lengths, axis = AxisKind.COUNT, height = 170.dp, summary = chartSummary("Session lengths", lengths))

                Spacer(Modifier.height(22.dp))
                Text("When sessions start", style = MaterialTheme.typography.titleSmall, color = c.textPrimary)
                Text("Number of sessions by start hour", style = MaterialTheme.typography.labelSmall, color = c.textTertiary)
                Spacer(Modifier.height(10.dp))
                val hours = r.startHours.mapIndexed { h, n ->
                    ChartDatum("%02d".format(h), "%02d:00–%02d:00".format(h, (h + 1) % 24), n.toDouble(), "${sessionsLabel(n)} started")
                }
                if (hours.all { it.value == 0.0 }) NoDataInPeriod() else BarChart(hours, axis = AxisKind.COUNT, height = 150.dp, summary = chartSummary("Start hours", hours), highlightMax = true)

                Spacer(Modifier.height(22.dp))
                Text("Session outcomes", style = MaterialTheme.typography.titleSmall, color = c.textPrimary)
                Text("How sessions in this period ended", style = MaterialTheme.typography.labelSmall, color = c.textTertiary)
                Spacer(Modifier.height(10.dp))
                StackedBar(r.outcomes.map { o ->
                    StackSegment(
                        outcomeLabel(o.outcome),
                        o.sessions,
                        when (o.outcome) {
                            EndReason.COMPLETED -> c.accent
                            EndReason.STOPPED -> c.neutral
                            EndReason.INTERRUPTED -> c.warning
                            EndReason.CANCELLED -> c.textTertiary.copy(alpha = 0.6f)
                            null -> c.accentContent.copy(alpha = 0.5f)
                        },
                        Durations.format(o.activeMs),
                    )
                })
            }
        }
    }
}

@Composable
private fun RulesCard() {
    val c = FocusTheme.colors
    var open by rememberSaveable { mutableStateOf(false) }
    FocusCard(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button) { open = !open }.heightIn(min = 44.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(AppIcons.Info, null, Modifier.size(20.dp), tint = c.accentContent)
            Spacer(Modifier.width(12.dp))
            Text("How Progress is calculated", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = c.textPrimary)
            Icon(if (open) AppIcons.ChevronDown else AppIcons.ChevronRight, null, tint = c.textSecondary)
        }
        AnimatedVisibility(open, enter = FocusTheme.motion.expandIn(), exit = FocusTheme.motion.collapseOut()) {
            Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    "Focus time is the time a sound was actually playing. Paused time, calls and interruptions are never counted.",
                    "Each session counts on the day it started, even if it ran past midnight. The app does not store when pauses happened, so it does not guess how to split a session.",
                    "Interrupted sessions count with the time saved up to their last checkpoint. Cancelled sessions (stopped before any playback) are not counted.",
                    "A focus day has at least 1 minute of focus. Your streak counts consecutive focus days; it stays alive until today ends.",
                    "Comparisons use the period just before, of the same length. When the period ends now, the previous one is cut at the same time of day. Percentages are hidden when the previous period had under 5 minutes or was before you started.",
                    "Days before your first session are shown as not tracked, never as zero focus. Future days are never counted.",
                    "Everything is calculated on this device from your own history.",
                ).forEach { line ->
                    Row {
                        Text("•  ", color = c.accentContent, style = MaterialTheme.typography.bodySmall)
                        Text(line, style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                    }
                }
            }
        }
    }
}

@Composable
private fun NoDataInPeriod() {
    val c = FocusTheme.colors
    Box(Modifier.fillMaxWidth().heightIn(min = 120.dp).clip(RoundedCornerShape(14.dp)).background(c.surface2), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(16.dp)) {
            Icon(AppIcons.Moon, null, Modifier.size(22.dp), tint = c.textTertiary)
            Spacer(Modifier.height(6.dp))
            Text("No focus recorded in this period", style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
            Text("Choose a longer range or start a session.", style = MaterialTheme.typography.labelSmall, color = c.textTertiary)
        }
    }
}

private fun chartSummary(name: String, data: List<ChartDatum>): String {
    val best = data.maxByOrNull { it.value }
    return "$name: ${data.size} points. Highest ${best?.title}: ${best?.detail}."
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomRangeDialog(current: DateRange?, today: LocalDate, onDismiss: () -> Unit, onConfirm: (DateRange) -> Unit) {
    val c = FocusTheme.colors
    fun toUtcMillis(d: LocalDate) = d.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    fun fromUtcMillis(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()
    val todayMs = toUtcMillis(today)
    val state = rememberDateRangePickerState(
        initialSelectedStartDateMillis = current?.start?.let(::toUtcMillis),
        initialSelectedEndDateMillis = current?.end?.let(::toUtcMillis),
        yearRange = 2020..today.year,
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= todayMs
            override fun isSelectableYear(year: Int) = year <= today.year
        },
    )
    val colors = DatePickerDefaults.colors(
        containerColor = c.surface2,
        titleContentColor = c.textSecondary,
        headlineContentColor = c.textPrimary,
        weekdayContentColor = c.textTertiary,
        dayContentColor = c.textPrimary,
        disabledDayContentColor = c.textTertiary.copy(alpha = 0.4f),
        selectedDayContainerColor = c.accent,
        selectedDayContentColor = c.onAccent,
        todayDateBorderColor = c.accentContent,
        todayContentColor = c.accentContent,
        dayInSelectionRangeContainerColor = c.accentContainer,
        dayInSelectionRangeContentColor = c.textPrimary,
    )
    val start = state.selectedStartDateMillis
    val end = state.selectedEndDateMillis ?: start
    DatePickerDialog(
        onDismissRequest = onDismiss,
        colors = colors,
        confirmButton = {
            TextButton(enabled = start != null, onClick = { onConfirm(DateRange(fromUtcMillis(start!!), fromUtcMillis(end!!))) }) {
                Text("Apply", color = if (start != null) c.accentContent else c.textTertiary)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = c.textSecondary) } },
    ) {
        DateRangePicker(state = state, colors = colors, modifier = Modifier.weight(1f), title = {
            Text("Custom range", Modifier.padding(start = 24.dp, top = 16.dp), color = c.textSecondary)
        })
    }
}
