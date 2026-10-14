package com.focussounds.core

import java.util.Locale
import kotlin.math.roundToLong

/** Human-readable durations used everywhere in the app, so every screen formats time the same way. */
object Durations {
    /** "0 min", "45 s", "25 min", "1 h", "1 h 05 min", "128 h 40 min". */
    fun format(ms: Long): String {
        val totalSec = (ms.coerceAtLeast(0)) / 1000
        if (totalSec == 0L) return "0 min"
        if (totalSec < 60) return "$totalSec s"
        val totalMin = totalSec / 60
        val h = totalMin / 60
        val m = totalMin % 60
        return when {
            h == 0L -> "$m min"
            m == 0L -> "$h h"
            else -> String.format(Locale.ROOT, "%d h %02d min", h, m)
        }
    }

    /** Compact form for tight labels: "0m", "45s", "25m", "1h", "1h 5m". */
    fun short(ms: Long): String {
        val totalSec = (ms.coerceAtLeast(0)) / 1000
        if (totalSec == 0L) return "0m"
        if (totalSec < 60) return "${totalSec}s"
        val totalMin = totalSec / 60
        val h = totalMin / 60
        val m = totalMin % 60
        return when {
            h == 0L -> "${m}m"
            m == 0L -> "${h}h"
            else -> "${h}h ${m}m"
        }
    }

    /** Axis tick labels for minute values: "0", "15m", "1h", "1.5h", "12h". */
    fun axis(minutes: Long): String = when {
        minutes == 0L -> "0"
        minutes < 60 -> "${minutes}m"
        minutes % 60 == 0L -> "${minutes / 60}h"
        else -> String.format(Locale.ROOT, "%.1fh", minutes / 60.0)
    }

    /** Countdown/stopwatch display, rounded up so a running timer never shows 00:00. */
    fun clock(ms: Long): String {
        val total = (ms.coerceAtLeast(0) + 999) / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%02d:%02d", m, s)
    }

    /** Whole percent, never showing "0%" for a non-zero share or "100%" for a partial one. */
    fun percent(fraction: Double): String {
        if (fraction <= 0.0) return "0%"
        if (fraction >= 1.0) return "100%"
        val p = (fraction * 100).roundToLong()
        return when {
            p == 0L -> "<1%"
            p == 100L -> ">99%"
            else -> "$p%"
        }
    }
}

/**
 * Builds RFC 4180 CSV. Cells that a spreadsheet would evaluate as a formula (=, +, -, @, tab, CR)
 * are prefixed with an apostrophe because activity names are user-provided.
 */
object Csv {
    fun build(header: List<String>, rows: List<List<String?>>): String = buildString {
        append(header.joinToString(",") { cell(it) }).append("\r\n")
        rows.forEach { r -> append(r.joinToString(",") { cell(it) }).append("\r\n") }
    }

    fun cell(value: String?): String {
        if (value == null) return ""
        var v = value
        if (v.isNotEmpty() && v[0] in "=+-@\t\r") v = "'$v"
        return if (v.any { it == ',' || it == '"' || it == '\n' || it == '\r' } || v != v.trim()) {
            "\"" + v.replace("\"", "\"\"") + "\""
        } else {
            v
        }
    }
}
