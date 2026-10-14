package com.focussounds.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focussounds.core.AccentPalette
import com.focussounds.core.ColorMath
import com.focussounds.core.categoricalColors
import com.focussounds.data.MotionLevel

/** Fixed dark neutrals. The app is always dark; only the accent is user-controlled. */
object Neutrals {
    val Background = Color(0xFF0A0B0E)
    val Surface1 = Color(0xFF111318)
    val Surface2 = Color(0xFF171A20)
    val Surface3 = Color(0xFF1E2128)
    val Outline = Color(0xFF262A32)
    val OutlineStrong = Color(0xFF353A44)
    val TextPrimary = Color(0xFFECEEF2)
    val TextSecondary = Color(0xFFA2A8B4)
    val TextTertiary = Color(0xFF7D8491)
    val Warning = Color(0xFFE9B84A)
    val Danger = Color(0xFFF2777A)
    val Neutral = Color(0xFF8C95A5)
}

@Immutable
data class FocusColors(
    val background: Color,
    val surface1: Color,
    val surface2: Color,
    val surface3: Color,
    val outline: Color,
    val outlineStrong: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    /** The color the user chose (shown in the picker). */
    val accentChosen: Color,
    /** Fills: buttons, selection, chart series. Contrast-safe on the background. */
    val accent: Color,
    /** Accent-colored text and icons. Contrast-safe on raised surfaces. */
    val accentContent: Color,
    val onAccent: Color,
    val accentContainer: Color,
    val warning: Color,
    val danger: Color,
    val neutral: Color,
    /** Categorical series colors starting with the accent. */
    val series: List<Color>,
) {
    fun seriesColor(i: Int): Color = series[i % series.size]

    companion object {
        fun from(accentArgb: Int): FocusColors {
            val p = AccentPalette.derive(accentArgb, Neutrals.Background.toArgb(), Neutrals.Surface3.toArgb())
            return FocusColors(
                background = Neutrals.Background,
                surface1 = Neutrals.Surface1,
                surface2 = Neutrals.Surface2,
                surface3 = Neutrals.Surface3,
                outline = Neutrals.Outline,
                outlineStrong = Neutrals.OutlineStrong,
                textPrimary = Neutrals.TextPrimary,
                textSecondary = Neutrals.TextSecondary,
                textTertiary = Neutrals.TextTertiary,
                accentChosen = Color(p.chosen),
                accent = Color(p.fill),
                accentContent = Color(p.content),
                onAccent = Color(p.onFill),
                accentContainer = Color(p.container),
                warning = Neutrals.Warning,
                danger = Neutrals.Danger,
                neutral = Neutrals.Neutral,
                series = categoricalColors(p.fill, 8, Neutrals.Surface1.toArgb()).map { Color(it) },
            )
        }
    }
}

/** Animation intensity chosen by the user. */
@Immutable
data class Motion(val level: MotionLevel) {
    val enabled: Boolean get() = level != MotionLevel.OFF
    /** Continuous ambient effects (breathing rings) only at Full. */
    val ambient: Boolean get() = level == MotionLevel.FULL

    fun ms(base: Int): Int = when (level) {
        MotionLevel.FULL -> base
        MotionLevel.REDUCED -> base / 2
        MotionLevel.OFF -> 0
    }

    fun <T> spec(base: Int = 350): FiniteAnimationSpec<T> = if (level == MotionLevel.OFF) snap() else tween(ms(base))

    /** Expand/collapse transitions; none at all when animations are off. */
    fun expandIn(): EnterTransition = if (enabled) expandVertically(tween(ms(300))) + fadeIn(tween(ms(300))) else EnterTransition.None
    fun collapseOut(): ExitTransition = if (enabled) shrinkVertically(tween(ms(250))) + fadeOut(tween(ms(200))) else ExitTransition.None
}

/** Haptics that respect the user's setting. */
class Haptics(private val enabled: Boolean, private val feedback: HapticFeedback) {
    fun tick() = perform(HapticFeedbackType.SegmentTick)
    fun select() = perform(HapticFeedbackType.ToggleOn)
    fun confirm() = perform(HapticFeedbackType.Confirm)
    fun heavy() = perform(HapticFeedbackType.LongPress)
    private fun perform(t: HapticFeedbackType) {
        if (enabled) feedback.performHapticFeedback(t)
    }
}

val LocalFocusColors = staticCompositionLocalOf { FocusColors.from(com.focussounds.data.AppSettings.DEFAULT_ACCENT) }
val LocalMotion = staticCompositionLocalOf { Motion(MotionLevel.FULL) }
val LocalHaptics = staticCompositionLocalOf<Haptics> { error("Haptics not provided") }

object FocusTheme {
    val colors: FocusColors @Composable get() = LocalFocusColors.current
    val motion: Motion @Composable get() = LocalMotion.current
    val haptics: Haptics @Composable get() = LocalHaptics.current
}

/** Tabular figures keep timers and numbers from jittering as digits change. */
val Tabular = TextStyle(fontFeatureSettings = "tnum")

private val AppTypography = Typography().let { t ->
    Typography(
        displayLarge = t.displayLarge.copy(fontWeight = FontWeight.Light, fontSize = 72.sp, lineHeight = 76.sp, letterSpacing = (-1.5).sp, fontFeatureSettings = "tnum"),
        displayMedium = t.displayMedium.copy(fontWeight = FontWeight.Light, fontSize = 48.sp, lineHeight = 54.sp, letterSpacing = (-1).sp, fontFeatureSettings = "tnum"),
        displaySmall = t.displaySmall.copy(fontWeight = FontWeight.Normal, fontSize = 34.sp, lineHeight = 40.sp, letterSpacing = (-0.5).sp, fontFeatureSettings = "tnum"),
        headlineSmall = t.headlineSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 22.sp, letterSpacing = (-0.2).sp),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
        titleSmall = t.titleSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, letterSpacing = 0.1.sp),
        bodyLarge = t.bodyLarge.copy(fontSize = 16.sp),
        bodyMedium = t.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
        bodySmall = t.bodySmall.copy(fontSize = 12.sp, lineHeight = 16.sp),
        labelLarge = t.labelLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
        labelMedium = t.labelMedium.copy(fontWeight = FontWeight.Medium, fontSize = 12.sp, letterSpacing = 0.3.sp),
        labelSmall = t.labelSmall.copy(fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 0.4.sp),
    )
}

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun FocusTheme(
    accentArgb: Int,
    motionLevel: MotionLevel,
    hapticsEnabled: Boolean,
    content: @Composable () -> Unit,
) {
    val motion = remember(motionLevel) { Motion(motionLevel) }
    val target = remember(accentArgb) { FocusColors.from(accentArgb) }
    val spec = motion.spec<Color>(450)
    // Smoothly recolor the whole app when a new accent is applied.
    val accent by animateColorAsState(target.accent, spec, label = "accent")
    val accentContent by animateColorAsState(target.accentContent, spec, label = "accentContent")
    val accentContainer by animateColorAsState(target.accentContainer, spec, label = "accentContainer")
    val onAccent by animateColorAsState(target.onAccent, spec, label = "onAccent")
    val colors = target.copy(accent = accent, accentContent = accentContent, accentContainer = accentContainer, onAccent = onAccent)

    val scheme = darkColorScheme(
        primary = colors.accent,
        onPrimary = colors.onAccent,
        primaryContainer = colors.accentContainer,
        onPrimaryContainer = colors.accentContent,
        secondary = colors.accentContent,
        onSecondary = colors.onAccent,
        secondaryContainer = colors.accentContainer,
        onSecondaryContainer = colors.accentContent,
        tertiary = colors.accentContent,
        background = colors.background,
        onBackground = colors.textPrimary,
        surface = colors.background,
        onSurface = colors.textPrimary,
        surfaceVariant = colors.surface2,
        onSurfaceVariant = colors.textSecondary,
        surfaceContainerLowest = colors.background,
        surfaceContainerLow = colors.surface1,
        surfaceContainer = colors.surface1,
        surfaceContainerHigh = colors.surface2,
        surfaceContainerHighest = colors.surface3,
        surfaceBright = colors.surface3,
        surfaceDim = colors.background,
        inverseSurface = colors.textPrimary,
        inverseOnSurface = colors.background,
        outline = colors.outlineStrong,
        outlineVariant = colors.outline,
        error = colors.danger,
        onError = Color.Black,
        scrim = Color.Black,
    )
    MaterialTheme(colorScheme = scheme, typography = AppTypography, shapes = AppShapes) {
        val feedback = LocalHapticFeedback.current
        val haptics = remember(hapticsEnabled, feedback) { Haptics(hapticsEnabled, feedback) }
        CompositionLocalProvider(
            LocalFocusColors provides colors,
            LocalMotion provides motion,
            LocalHaptics provides haptics,
            content = content,
        )
    }
}

/** For previews of a candidate accent inside the color picker. */
fun previewColors(argb: Int): FocusColors = FocusColors.from(argb)

fun Color.hex(): String = ColorMath.toHex(toArgb())
