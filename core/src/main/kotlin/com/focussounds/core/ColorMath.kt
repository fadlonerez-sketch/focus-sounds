package com.focussounds.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Color utilities on opaque ARGB ints (0xFFRRGGBB). Pure Kotlin so theme rules are unit-testable.
 * Contrast follows WCAG 2.x relative luminance.
 */
object ColorMath {
    private val HEX6 = Regex("^#?([0-9a-fA-F]{6})$")
    private val HEX3 = Regex("^#?([0-9a-fA-F]{3})$")

    /** Parses "#RRGGBB", "RRGGBB", "#RGB" or "RGB" (whitespace ignored). Returns null if invalid. */
    fun parseHex(input: String): Int? {
        val s = input.trim()
        HEX6.matchEntire(s)?.let { return (0xFF000000 or it.groupValues[1].toLong(16)).toInt() }
        HEX3.matchEntire(s)?.let { m ->
            val (r, g, b) = m.groupValues[1].map { c -> c.digitToInt(16) * 17 }
            return rgb(r, g, b)
        }
        return null
    }

    fun toHex(argb: Int): String = String.format(java.util.Locale.ROOT, "#%06X", argb and 0xFFFFFF)

    fun red(c: Int) = (c shr 16) and 0xFF
    fun green(c: Int) = (c shr 8) and 0xFF
    fun blue(c: Int) = c and 0xFF

    fun rgb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    /** Returns [hue 0..360), saturation 0..1, value 0..1. */
    fun toHsv(c: Int): FloatArray {
        val r = red(c) / 255f
        val g = green(c) / 255f
        val b = blue(c) / 255f
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val d = mx - mn
        val h = when {
            d == 0f -> 0f
            mx == r -> 60f * (((g - b) / d) % 6f)
            mx == g -> 60f * (((b - r) / d) + 2f)
            else -> 60f * (((r - g) / d) + 4f)
        }.let { if (it < 0) it + 360f else it }
        val s = if (mx == 0f) 0f else d / mx
        return floatArrayOf(h, s, mx)
    }

    fun fromHsv(hue: Float, saturation: Float, value: Float): Int {
        val h = ((hue % 360f) + 360f) % 360f
        val s = saturation.coerceIn(0f, 1f)
        val v = value.coerceIn(0f, 1f)
        val c = v * s
        val x = c * (1 - abs((h / 60f) % 2f - 1))
        val m = v - c
        val (r, g, b) = when ((h / 60f).toInt()) {
            0 -> Triple(c, x, 0f)
            1 -> Triple(x, c, 0f)
            2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c)
            4 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        return rgb(((r + m) * 255).roundToInt(), ((g + m) * 255).roundToInt(), ((b + m) * 255).roundToInt())
    }

    fun relativeLuminance(c: Int): Double {
        fun ch(v: Int): Double {
            val s = v / 255.0
            return if (s <= 0.04045) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * ch(red(c)) + 0.7152 * ch(green(c)) + 0.0722 * ch(blue(c))
    }

    fun contrastRatio(a: Int, b: Int): Double {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    /** Linear blend: [amount] of [fg] over [bg]. */
    fun blend(fg: Int, bg: Int, amount: Float): Int {
        val t = amount.coerceIn(0f, 1f)
        fun mix(a: Int, b: Int) = (a * t + b * (1 - t)).roundToInt()
        return rgb(mix(red(fg), red(bg)), mix(green(fg), green(bg)), mix(blue(fg), blue(bg)))
    }

    /**
     * Returns [color] unchanged if it already reaches [minRatio] against the dark [background];
     * otherwise the smallest blend toward white that does (hue is kept).
     */
    fun ensureContrastOnDark(color: Int, background: Int, minRatio: Double): Int {
        if (contrastRatio(color, background) >= minRatio) return color
        var lo = 0f
        var hi = 1f
        repeat(20) {
            val mid = (lo + hi) / 2
            if (contrastRatio(blend(WHITE, color, mid), background) >= minRatio) hi = mid else lo = mid
        }
        return blend(WHITE, color, hi)
    }

    /**
     * White or black, whichever reads better on [background]. One of the two always reaches at
     * least 4.58:1 (WCAG AA for text) against any opaque color.
     */
    fun bestOnColor(background: Int): Int =
        if (contrastRatio(WHITE, background) >= contrastRatio(BLACK, background)) WHITE else BLACK

    const val WHITE = 0xFFFFFFFF.toInt()
    const val INK = 0xFF0B0C0F.toInt()
    const val BLACK = 0xFF000000.toInt()
}

/**
 * The colors derived from the user's chosen accent. The chosen color is used as-is whenever it is
 * legible; it is only lightened (keeping its hue) when it would be unreadable on the dark theme.
 */
data class AccentPalette(
    val chosen: Int,
    /** Buttons, selected chips, slider tracks, chart series: >= 3:1 against the background. */
    val fill: Int,
    /** Accent-colored text and icons: >= 4.5:1 against the raised surfaces. */
    val content: Int,
    /** Text/icons placed on [fill]. */
    val onFill: Int,
    /** Subtle tinted surface for selected cards. */
    val container: Int,
) {
    val fillAdjusted: Boolean get() = fill != chosen
    val contentAdjusted: Boolean get() = content != chosen

    companion object {
        fun derive(chosen: Int, background: Int, raisedSurface: Int): AccentPalette {
            val fill = ColorMath.ensureContrastOnDark(chosen, background, 3.0)
            val content = ColorMath.ensureContrastOnDark(chosen, raisedSurface, 4.5)
            return AccentPalette(
                chosen = chosen,
                fill = fill,
                content = content,
                onFill = ColorMath.bestOnColor(fill),
                container = ColorMath.blend(fill, background, 0.16f),
            )
        }
    }
}

/**
 * Distinct, harmonious colors for chart categories, starting with the accent itself. Hues are spread
 * by the golden angle; every color keeps >= 3:1 contrast on [surface].
 */
fun categoricalColors(accent: Int, count: Int, surface: Int): List<Int> {
    if (count <= 0) return emptyList()
    val (h, s, _) = ColorMath.toHsv(accent).let { Triple(it[0], it[1], it[2]) }
    val sat = s.coerceIn(0.42f, 0.68f)
    return List(count) { i ->
        if (i == 0) {
            ColorMath.ensureContrastOnDark(accent, surface, 3.0)
        } else {
            val hue = h + i * 137.508f
            val value = if (i % 2 == 0) 0.92f else 0.8f
            ColorMath.ensureContrastOnDark(ColorMath.fromHsv(hue, sat, value), surface, 3.0)
        }
    }
}
