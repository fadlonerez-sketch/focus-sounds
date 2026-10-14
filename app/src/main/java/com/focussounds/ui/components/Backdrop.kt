package com.focussounds.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import com.focussounds.ui.theme.BackdropColors
import com.focussounds.ui.theme.FocusTheme
import kotlin.math.PI
import kotlin.math.sin

/** What the backdrop shows behind the current screen. */
enum class BackdropScene { NONE, FOCUS_IDLE, FOCUS_ACTIVE, PROGRESS }

/**
 * An abstract night landscape: an accent-tinted sky with stars, a glow rising
 * behind four layered ridges with lit crests, and mist between the layers. Progress gets only the
 * sky light, so the two screens feel related while charts stay on calm, flat darkness.
 *
 * Purely decorative: no semantics, no input handling. It is drawn with cached paths into its own
 * layer and only redraws when the accent or scene changes (one-shot transitions, no loops), so a
 * ticking timer on top of it never re-runs this drawing.
 */
@Composable
fun AmbientBackdrop(scene: BackdropScene, modifier: Modifier = Modifier) {
    val colors by rememberUpdatedState(FocusTheme.colors.backdrop)
    val spec = FocusTheme.motion.spec<Float>(800)
    val focus = scene == BackdropScene.FOCUS_IDLE || scene == BackdropScene.FOCUS_ACTIVE
    val active = scene == BackdropScene.FOCUS_ACTIVE
    val landscape by animateFloatAsState(if (focus) 1f else 0f, spec, label = "landscape")
    val horizonGlow by animateFloatAsState(if (active) 1f else if (focus) 0.8f else 0f, spec, label = "horizonGlow")
    val skyLight by animateFloatAsState(
        when (scene) {
            BackdropScene.PROGRESS -> 1f
            BackdropScene.FOCUS_IDLE -> 0.5f
            BackdropScene.FOCUS_ACTIVE -> 0.25f
            BackdropScene.NONE -> 0f
        },
        spec, label = "skyLight",
    )
    // During a session the mist thickens around the ridges.
    val mist by animateFloatAsState(if (active) 1f else 0f, spec, label = "mist")

    Spacer(
        modifier
            .fillMaxSize()
            .testTag("app_backdrop")
            .graphicsLayer {}
            .drawWithCache {
                val geometry = NightGeometry(size, density)
                onDrawBehind {
                    if (landscape <= 0f && skyLight <= 0f) return@onDrawBehind
                    drawNight(geometry, colors, landscape, horizonGlow, skyLight, mist)
                }
            },
    )
}

private class Star(val center: Offset, val radius: Float, val alpha: Float, val halo: Boolean)

/** Size-dependent shapes, rebuilt only when the screen size changes. */
private class NightGeometry(size: Size, val density: Float) {
    val w = size.width
    val h = size.height
    // Low on the screen, so the layered ridges fill the open space under the controls.
    val horizon = h * 0.66f
    val farBase = horizon - h * 0.01f
    val far = ridge(farBase, h * 0.085f, seed = 0.6f, roughness = 1.0f)
    val farCrest = ridge(farBase, h * 0.085f, seed = 0.6f, roughness = 1.0f, closed = false)
    val midBase = horizon + h * 0.065f
    val mid = ridge(midBase, h * 0.06f, seed = 2.1f, roughness = 1.35f)
    val midCrest = ridge(midBase, h * 0.06f, seed = 2.1f, roughness = 1.35f, closed = false)
    val nearBase = horizon + h * 0.15f
    val near = ridge(nearBase, h * 0.05f, seed = 4.4f, roughness = 0.8f)
    val nearCrest = ridge(nearBase, h * 0.05f, seed = 4.4f, roughness = 0.8f, closed = false)
    val front = ridge(horizon + h * 0.24f, h * 0.03f, seed = 7.3f, roughness = 0.6f)
    val stars: List<Star> = run {
        // Deterministic positions: the same sky every time, no randomness between frames.
        var x = 0x2545F491L
        fun next(): Float {
            x = x * 6364136223846793005L + 1442695040888963407L
            return ((x ushr 33) and 0xFFFFFF).toFloat() / 0xFFFFFF
        }
        List(72) { i ->
            val p = Offset(next() * w, next() * horizon * 0.82f)
            val bright = i % 11 == 0
            Star(p, (if (bright) 1.3f + next() * 0.5f else 0.55f + next() * 0.65f) * density, 0.35f + next() * 0.55f, bright)
        }
    }

    /** A smooth ridge line from left to right, made of three layered sine waves. */
    private fun ridge(base: Float, amplitude: Float, seed: Float, roughness: Float, closed: Boolean = true): Path {
        val steps = 32
        val pts = List(steps + 1) { i ->
            val t = i / steps.toFloat()
            val y = base - amplitude * (
                0.55f * sin(2 * PI * (t * 1.1f * roughness + seed * 0.37f)).toFloat() +
                    0.3f * sin(2 * PI * (t * 2.3f * roughness + seed * 0.71f)).toFloat() +
                    0.15f * sin(2 * PI * (t * 5.2f * roughness + seed * 1.3f)).toFloat()
                )
            Offset(t * w, y)
        }
        return Path().apply {
            moveTo(-2f, pts.first().y)
            for (i in 1 until pts.size) {
                val prev = pts[i - 1]
                val cur = pts[i]
                quadraticTo(prev.x, prev.y, (prev.x + cur.x) / 2, (prev.y + cur.y) / 2)
            }
            lineTo(w + 2f, pts.last().y)
            if (closed) {
                lineTo(w + 2f, h + 2f)
                lineTo(-2f, h + 2f)
                close()
            }
        }
    }
}

private fun DrawScope.drawNight(
    g: NightGeometry,
    c: BackdropColors,
    landscape: Float,
    horizonGlow: Float,
    skyLight: Float,
    mist: Float,
) {
    val w = g.w
    val h = g.h
    // Sky: deep at the top, warming toward the horizon.
    if (landscape > 0f) {
        drawRect(
            Brush.verticalGradient(0f to c.skyTop, 0.35f to c.skyTop, g.horizon / h to c.skyHorizon, 1f to c.skyHorizon, startY = 0f, endY = h),
            alpha = landscape,
        )
    }
    // Soft light from the upper corner: shared by Focus and Progress.
    if (skyLight > 0f) {
        val corner = Offset(w * 0.9f, -h * 0.05f)
        drawCircle(
            Brush.radialGradient(listOf(c.glow.copy(alpha = 0.8f), c.skyHorizon.copy(alpha = 0.35f), Color.Transparent), corner, w * 1.1f),
            radius = w * 1.1f, center = corner, alpha = skyLight,
        )
        val left = Offset(w * 0.02f, h * 0.04f)
        drawCircle(
            Brush.radialGradient(listOf(c.skyHorizon.copy(alpha = 0.7f), Color.Transparent), left, w * 0.75f),
            radius = w * 0.75f, center = left, alpha = skyLight * 0.7f,
        )
    }
    if (landscape <= 0f) return

    g.stars.forEach { s ->
        if (s.halo) {
            drawCircle(Brush.radialGradient(listOf(c.star.copy(alpha = 0.22f), Color.Transparent), s.center, s.radius * 5f), s.radius * 5f, s.center, alpha = landscape)
        }
        drawCircle(c.star, s.radius, s.center, alpha = s.alpha * landscape)
    }

    // Glow rising behind the ridges (a wide, flattened radial light).
    if (horizonGlow > 0f) {
        val center = Offset(w * 0.5f, g.horizon)
        scale(scaleX = 1f, scaleY = 0.55f, pivot = center) {
            drawCircle(
                Brush.radialGradient(listOf(c.glow, c.glow.copy(alpha = 0.7f), c.skyHorizon.copy(alpha = 0.4f), Color.Transparent), center, w * 1.05f),
                radius = w * 1.05f, center = center, alpha = horizonGlow * landscape,
            )
        }
    }

    val crest = Stroke(1.5f * g.density, cap = StrokeCap.Round)
    drawPath(g.far, Brush.verticalGradient(listOf(c.farRidge, c.midRidge), startY = g.farBase - h * 0.09f, endY = g.horizon + h * 0.14f), alpha = landscape)
    drawPath(g.farCrest, c.rim, alpha = (0.55f + 0.35f * horizonGlow) * landscape, style = crest)
    mistBand(c.fog, g.horizon + h * (0.04f + 0.012f * mist), h * (0.075f + 0.025f * mist), (0.65f + 0.25f * mist) * landscape)

    drawPath(g.mid, Brush.verticalGradient(listOf(c.midRidge, c.nearRidge), startY = g.midBase - h * 0.06f, endY = g.midBase + h * 0.12f), alpha = landscape)
    drawPath(g.midCrest, c.rim, alpha = 0.35f * landscape, style = Stroke(1.2f * g.density, cap = StrokeCap.Round))
    mistBand(c.fog, g.horizon + h * (0.13f + 0.01f * mist), h * 0.06f, (0.4f + 0.25f * mist) * landscape)

    drawPath(g.near, Brush.verticalGradient(listOf(c.nearRidge, c.skyTop), startY = g.nearBase - h * 0.05f, endY = h), alpha = landscape)
    drawPath(g.nearCrest, c.rim, alpha = 0.18f * landscape, style = Stroke(1f * g.density, cap = StrokeCap.Round))
    drawPath(g.front, c.skyTop, alpha = landscape)
}

/** A horizontal band of mist that fades out above and below its center line. */
private fun DrawScope.mistBand(color: Color, centerY: Float, thickness: Float, alpha: Float) {
    if (alpha <= 0f) return
    drawRect(
        Brush.verticalGradient(
            0f to Color.Transparent, 0.5f to color, 1f to Color.Transparent,
            startY = centerY - thickness, endY = centerY + thickness,
        ),
        topLeft = Offset(0f, centerY - thickness),
        size = Size(size.width, thickness * 2),
        alpha = alpha,
    )
}
