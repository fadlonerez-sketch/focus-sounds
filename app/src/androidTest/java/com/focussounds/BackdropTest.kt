package com.focussounds

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.focussounds.core.ColorMath
import com.focussounds.data.MotionLevel
import com.focussounds.ui.components.AmbientBackdrop
import com.focussounds.ui.components.BackdropScene
import com.focussounds.ui.theme.FocusTheme
import com.focussounds.ui.theme.Neutrals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/** Renders the real backdrop composable and checks its pixels. */
@RunWith(AndroidJUnit4::class)
class BackdropTest {
    @get:Rule
    val rule = createComposeRule()

    private var accent by mutableStateOf(RED)
    private var scene by mutableStateOf(BackdropScene.FOCUS_IDLE)

    private fun show() = rule.setContent {
        FocusTheme(accentArgb = accent, motionLevel = MotionLevel.OFF, hapticsEnabled = false) {
            Box(Modifier.size(360.dp, 720.dp).background(FocusTheme.colors.background)) { AmbientBackdrop(scene) }
        }
    }

    private fun capture(): Bitmap {
        rule.waitForIdle()
        return rule.onNodeWithTag("app_backdrop").captureToImage().asAndroidBitmap()
    }

    private fun Bitmap.at(fx: Float, fy: Float): Int = getPixel((width * fx).toInt(), (height * fy).toInt())

    /** The glow, sky and mist of the Focus scene, avoiding the tiny star dots. */
    private val focusSamples = listOf(0.5f to 0.52f, 0.3f to 0.45f, 0.7f to 0.45f, 0.5f to 0.25f, 0.5f to 0.62f, 0.5f to 0.72f, 0.5f to 0.9f)

    @Test
    fun focusBackdropFollowsTheAccentImmediatelyAndKeepsTextReadable() {
        show()
        val red = capture().at(0.5f, 0.52f)
        assertTrue("red accent tints the glow: ${ColorMath.toHex(red)}", ColorMath.red(red) > ColorMath.green(red) + 4 && ColorMath.red(red) > ColorMath.blue(red) + 4)

        accent = TEAL
        val tealBitmap = capture()
        val teal = tealBitmap.at(0.5f, 0.52f)
        assertTrue("teal accent tints the glow: ${ColorMath.toHex(teal)}", ColorMath.green(teal) > ColorMath.red(teal) + 4 && ColorMath.blue(teal) > ColorMath.red(teal) + 4)
        assertNotEquals(red, teal)

        for ((x, y) in focusSamples) {
            val p = tealBitmap.at(x, y) or 0xFF000000.toInt()
            assertTrue("secondary text stays >= 4.5:1 at ($x, $y) on ${ColorMath.toHex(p)}",
                ColorMath.contrastRatio(Neutrals.TextSecondary.toArgb(), p) >= 4.45) // small tolerance for 8-bit rendering
        }
    }

    @Test
    fun landscapeIsClearlyVisible() {
        show()
        val b = capture()
        val bg = ColorMath.relativeLuminance(Neutrals.Background.toArgb())
        val glow = ColorMath.relativeLuminance(b.at(0.5f, 0.52f))
        assertTrue("the glow is far brighter than the plain background ($glow vs $bg)", glow > bg * 5)
        // Ridge layers are distinguishable from each other near the bottom of the landscape.
        val lums = (0..20).map { ColorMath.relativeLuminance(b.at(0.5f, 0.55f + it * 0.02f)) }
        assertTrue("several distinct landscape tones: $lums", lums.map { (it * 2000).toInt() }.toSet().size >= 4)
    }

    @Test
    fun scenesDifferAsDesigned() {
        scene = BackdropScene.NONE
        show()
        val bg = Neutrals.Background.toArgb()
        val none = capture()
        for ((x, y) in focusSamples + listOf(0.9f to 0.03f)) assertNear("no backdrop outside Focus/Progress", bg, none.at(x, y))

        scene = BackdropScene.PROGRESS
        val progress = capture()
        assertTrue("Progress has the sky light in the upper corner",
            ColorMath.relativeLuminance(progress.at(0.9f, 0.03f)) > ColorMath.relativeLuminance(bg) * 1.5)
        assertNear("Progress has no landscape below", bg, progress.at(0.5f, 0.9f))

        scene = BackdropScene.FOCUS_IDLE
        val idle = capture().at(0.5f, 0.52f)
        scene = BackdropScene.FOCUS_ACTIVE
        val active = capture().at(0.5f, 0.52f)
        assertTrue("an active session brightens the horizon glow (${ColorMath.toHex(idle)} -> ${ColorMath.toHex(active)})",
            ColorMath.relativeLuminance(active) > ColorMath.relativeLuminance(idle))
    }

    private fun assertNear(what: String, expected: Int, actual: Int) {
        val d = maxOf(abs(ColorMath.red(expected) - ColorMath.red(actual)), abs(ColorMath.green(expected) - ColorMath.green(actual)),
            abs(ColorMath.blue(expected) - ColorMath.blue(actual)))
        assertEquals("$what: ${ColorMath.toHex(expected)} vs ${ColorMath.toHex(actual)}", true, d <= 2)
    }

    private companion object {
        val RED = 0xFFE5484D.toInt()
        val TEAL = 0xFF2DD4BF.toInt()
    }
}
