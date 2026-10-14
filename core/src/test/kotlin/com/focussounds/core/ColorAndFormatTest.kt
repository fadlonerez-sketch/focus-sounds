package com.focussounds.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorAndFormatTest {
    private val background = 0xFF0B0C0F.toInt()
    private val raised = 0xFF20242C.toInt()

    @Test
    fun parsesValidHexForms() {
        assertEquals(0xFF7C83FD.toInt(), ColorMath.parseHex("#7C83FD"))
        assertEquals(0xFF7C83FD.toInt(), ColorMath.parseHex("7c83fd"))
        assertEquals(0xFF7C83FD.toInt(), ColorMath.parseHex("  #7c83Fd "))
        assertEquals(0xFFFFAA00.toInt(), ColorMath.parseHex("#FA0"))
        assertEquals("#7C83FD", ColorMath.toHex(0xFF7C83FD.toInt()))
    }

    @Test
    fun rejectsInvalidHex() {
        for (bad in listOf("", "#", "#12345", "#1234567", "GGGGGG", "#12 345", "rgb(1,2,3)", "#FFFFFFFF", "0x123456")) {
            assertNull("'$bad' must be rejected", ColorMath.parseHex(bad))
        }
    }

    @Test
    fun hsvRoundTripsForPrimaryAndArbitraryColors() {
        for (c in listOf(0xFFFF0000, 0xFF00FF00, 0xFF0000FF, 0xFF7C83FD, 0xFF2DD4BF, 0xFF123456, 0xFFFFFFFF, 0xFF000000).map { it.toInt() }) {
            val (h, s, v) = ColorMath.toHsv(c).toList()
            val back = ColorMath.fromHsv(h, s, v)
            listOf(ColorMath::red, ColorMath::green, ColorMath::blue).forEach { ch ->
                assertTrue("${ColorMath.toHex(c)} -> ${ColorMath.toHex(back)}", kotlin.math.abs(ch(c) - ch(back)) <= 1)
            }
        }
    }

    @Test
    fun contrastRatioMatchesWcagReferenceValues() {
        assertEquals(21.0, ColorMath.contrastRatio(0xFFFFFFFF.toInt(), 0xFF000000.toInt()), 0.01)
        assertEquals(1.0, ColorMath.contrastRatio(0xFF777777.toInt(), 0xFF777777.toInt()), 0.001)
        assertEquals(4.48, ColorMath.contrastRatio(0xFF777777.toInt(), 0xFFFFFFFF.toInt()), 0.02)
    }

    @Test
    fun legibleAccentsAreUsedExactlyAsChosen() {
        val p = AccentPalette.derive(0xFF7C83FD.toInt(), background, raised)
        assertEquals(0xFF7C83FD.toInt(), p.fill)
        assertEquals(0xFF7C83FD.toInt(), p.content)
        assertFalse(p.fillAdjusted)
    }

    @Test
    fun everyAccentGetsContrastSafeDerivedColors() {
        // Sweep the whole hue wheel at several brightness levels, including near-black accents.
        for (hue in 0 until 360 step 15) for (sat in listOf(0f, 0.5f, 1f)) for (value in listOf(0.05f, 0.3f, 0.6f, 1f)) {
            val chosen = ColorMath.fromHsv(hue.toFloat(), sat, value)
            val p = AccentPalette.derive(chosen, background, raised)
            val name = ColorMath.toHex(chosen)
            assertTrue("$name fill vs background", ColorMath.contrastRatio(p.fill, background) >= 3.0)
            assertTrue("$name content vs raised surface", ColorMath.contrastRatio(p.content, raised) >= 4.5)
            assertTrue("$name text on fill", ColorMath.contrastRatio(p.onFill, p.fill) >= 4.5)
            if (p.fillAdjusted) {
                // Adjustment keeps the hue (only lightens toward white) for chromatic colors.
                val before = ColorMath.toHsv(chosen)
                val after = ColorMath.toHsv(p.fill)
                if (before[1] > 0.2f && before[2] > 0.2f) assertTrue(name, hueDistance(before[0], after[0]) < 6f)
            }
        }
    }

    @Test
    fun categoricalColorsAreDistinctAndReadable() {
        val colors = categoricalColors(0xFF2DD4BF.toInt(), 8, raised)
        assertEquals(8, colors.size)
        assertEquals(8, colors.toSet().size)
        assertEquals(0xFF2DD4BF.toInt(), colors[0])
        colors.forEach { assertTrue(ColorMath.contrastRatio(it, raised) >= 3.0) }
    }

    private fun hueDistance(a: Float, b: Float): Float {
        val d = kotlin.math.abs(a - b) % 360f
        return if (d > 180f) 360f - d else d
    }

    @Test
    fun durationsFormatConsistently() {
        assertEquals("0 min", Durations.format(0))
        assertEquals("45 s", Durations.format(45_000))
        assertEquals("25 min", Durations.format(25 * 60_000L + 30_000))
        assertEquals("1 h", Durations.format(3_600_000))
        assertEquals("1 h 05 min", Durations.format(65 * 60_000L))
        assertEquals("128 h 40 min", Durations.format((128 * 60 + 40) * 60_000L))
        assertEquals("1h 5m", Durations.short(65 * 60_000L))
        assertEquals("1.5h", Durations.axis(90))
        assertEquals("2h", Durations.axis(120))
        assertEquals("15m", Durations.axis(15))
        assertEquals("25:00", Durations.clock(25 * 60_000L))
        assertEquals("00:01", Durations.clock(1))
        assertEquals("1:00:00", Durations.clock(3_600_000))
        assertEquals("<1%", Durations.percent(0.001))
        assertEquals(">99%", Durations.percent(0.999))
        assertEquals("42%", Durations.percent(0.42))
    }

    @Test
    fun csvEscapesAndNeutralizesFormulas() {
        val csv = Csv.build(listOf("a", "b"), listOf(listOf("Deep, work", "say \"hi\""), listOf("=SUM(A1)", null), listOf("line\nbreak", "ok")))
        val lines = csv.split("\r\n")
        assertEquals("a,b", lines[0])
        assertEquals("\"Deep, work\",\"say \"\"hi\"\"\"", lines[1])
        assertEquals("'=SUM(A1),", lines[2])
        assertTrue(csv.contains("\"line\nbreak\",ok"))
    }

    // ---------------------------------------------------------------- night backdrop

    private val textPrimary = 0xFFECEEF2.toInt()
    private val textSecondary = 0xFFA2A8B4.toInt()
    private val backdropCap = BackdropPalette.maxLuminanceFor(textSecondary, 4.5)

    private fun backdropFor(chosen: Int): BackdropPalette =
        BackdropPalette.derive(AccentPalette.derive(chosen, background, raised).fill, background, backdropCap)

    @Test
    fun backdropKeepsTextOnItReadableForEveryAccent() {
        for (hue in 0 until 360 step 15) for (sat in listOf(0f, 0.5f, 1f)) for (value in listOf(0.05f, 0.3f, 0.6f, 1f)) {
            val chosen = ColorMath.fromHsv(hue.toFloat(), sat, value)
            for (c in backdropFor(chosen).capped) {
                val where = "accent ${ColorMath.toHex(chosen)}, backdrop ${ColorMath.toHex(c)}"
                assertTrue(where, ColorMath.relativeLuminance(c) <= backdropCap)
                assertTrue("secondary text on $where", ColorMath.contrastRatio(textSecondary, c) >= 4.5)
                assertTrue("primary text on $where", ColorMath.contrastRatio(textPrimary, c) >= 8.0)
            }
        }
    }

    @Test
    fun backdropIsClearlyVisible() {
        // The landscape must read as a picture, not as a faint tint of the background.
        val p = backdropFor(0xFF8B8CF8.toInt())
        assertTrue("glow is several times brighter than the background", ColorMath.relativeLuminance(p.glow) > ColorMath.relativeLuminance(background) * 8)
        assertTrue("far ridge stands out from the mid ridge", ColorMath.relativeLuminance(p.farRidge) > ColorMath.relativeLuminance(p.midRidge) * 1.6)
        assertTrue("mid ridge stands out from the near ridge", ColorMath.relativeLuminance(p.midRidge) > ColorMath.relativeLuminance(p.nearRidge) * 1.6)
        val (_, glowSat, _) = ColorMath.toHsv(p.glow).toList()
        assertTrue("the glow is richly colored (saturation $glowSat)", glowSat > 0.5f)
    }

    @Test
    fun backdropFollowsTheAccentHue() {
        val bright = 0xFFFFFFFF.toInt()
        for (accent in listOf(0xFF8B8CF8, 0xFF2DD4BF, 0xFFFF7A6B, 0xFFF5C542, 0xFF4ADE80, 0xFFE879F9).map { it.toInt() }) {
            val p = backdropFor(accent)
            val hue = ColorMath.toHsv(p.glow)[0]
            val target = ColorMath.toHsv(accent)[0]
            val diff = kotlin.math.abs(((hue - target + 540f) % 360f) - 180f)
            assertTrue("glow ${ColorMath.toHex(p.glow)} keeps the hue of ${ColorMath.toHex(accent)} ($hue vs $target)", diff <= 20f)
            assertTrue("layers recede: far ridge lighter than near ridge",
                ColorMath.relativeLuminance(p.farRidge) > ColorMath.relativeLuminance(p.midRidge) &&
                    ColorMath.relativeLuminance(p.midRidge) > ColorMath.relativeLuminance(p.nearRidge))
            assertTrue("stars stay dim", ColorMath.contrastRatio(p.star, bright) > 4.5)
        }
        assertNotEquals("different accents give different backdrops", backdropFor(0xFF2DD4BF.toInt()).glow, backdropFor(0xFFFF7A6B.toInt()).glow)
    }

    @Test
    fun withLuminanceNeverExceedsTheTarget() {
        for (c in listOf(0xFFFF0000, 0xFF00FF00, 0xFF0000FF, 0xFF7C83FD, 0xFFFFFFFF, 0xFF000000, 0xFF010203).map { it.toInt() }) {
            for (target in listOf(0.002, 0.005, 0.011, 0.05)) {
                val r = ColorMath.withLuminance(c, target, 0xFF0B0C0F.toInt())
                assertTrue("${ColorMath.toHex(c)} at $target -> ${ColorMath.toHex(r)}", ColorMath.relativeLuminance(r) <= target)
            }
        }
    }
}
