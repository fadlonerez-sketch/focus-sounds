package com.focussounds.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp
import com.focussounds.core.BuiltInActivity
import com.focussounds.core.FocusSound

/**
 * The app's own line-icon family (24-unit grid, round 1.8 strokes) so every screen shares one
 * visual language. Tinted by Icon() like any vector.
 */
object AppIcons {
    private fun c(cx: Float, cy: Float, r: Float) = "M${cx - r},$cy a$r,$r 0 1,0 ${2 * r},0 a$r,$r 0 1,0 ${-2 * r},0"
    private fun rect(x: Float, y: Float, w: Float, h: Float, r: Float = 2f) =
        "M${x + r},$y H${x + w - r} Q${x + w},$y ${x + w},${y + r} V${y + h - r} Q${x + w},${y + h} ${x + w - r},${y + h} " +
            "H${x + r} Q$x,${y + h} $x,${y + h - r} V${y + r} Q$x,$y ${x + r},$y Z"

    private fun icon(name: String, vararg paths: String, fill: Set<Int> = emptySet(), width: Float = 1.8f): ImageVector {
        val b = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        paths.forEachIndexed { i, d ->
            b.addPath(
                pathData = addPathNodes(d),
                fill = if (i in fill) SolidColor(Color.Black) else null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = width,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        return b.build()
    }

    // Navigation
    val Focus = icon("focus", c(12f, 12f, 8.5f), c(12f, 12f, 4.5f), c(12f, 12f, 0.8f), fill = setOf(2))
    val Progress = icon("progress", "M4 19.5 H20", "M5 15 L9.5 10.5 L13 13.5 L19 7.5", "M15.5 7.5 H19 V11")
    val History = icon("history", c(12f, 12f, 8.5f), "M12 7.5 V12 L15 14")
    val Activities = icon("activities", rect(4f, 4f, 7f, 7f), rect(13f, 4f, 7f, 7f), rect(4f, 13f, 7f, 7f), rect(13f, 13f, 7f, 7f))
    val Settings = icon("settings",
        "M4 7 H12", "M16 7 H20", c(14f, 7f, 2f),
        "M4 12 H7", "M11 12 H20", c(9f, 12f, 2f),
        "M4 17 H14", "M18 17 H20", c(16f, 17f, 2f))

    // Controls
    val Play = icon("play", "M8 5.5 L18.5 12 L8 18.5 Z", fill = setOf(0))
    val Pause = icon("pause", "M8.5 6 V18", "M15.5 6 V18", width = 3f)
    val Stop = icon("stop", rect(6.5f, 6.5f, 11f, 11f, 2.5f), fill = setOf(0))
    val Plus = icon("plus", "M12 5 V19", "M5 12 H19")
    val Check = icon("check", "M5 12.5 L10 17.5 L19 7.5")
    val Close = icon("close", "M6.5 6.5 L17.5 17.5", "M17.5 6.5 L6.5 17.5")
    val ChevronLeft = icon("chevron_left", "M14.5 5.5 L8 12 L14.5 18.5")
    val ChevronRight = icon("chevron_right", "M9.5 5.5 L16 12 L9.5 18.5")
    val ChevronDown = icon("chevron_down", "M5.5 9.5 L12 16 L18.5 9.5")
    val Edit = icon("edit", "M14.5 5.5 L18.5 9.5 L9 19 L4.5 19.5 L5 15 Z", "M12.5 7.5 L16.5 11.5")
    val Trash = icon("trash", "M5 7 H19", "M9.5 7 V4.5 H14.5 V7", "M7 7 L8 19.5 H16 L17 7", "M10.5 10.5 V16", "M13.5 10.5 V16")
    val Volume = icon("volume", "M4 9.5 H7.5 L12 5.5 V18.5 L7.5 14.5 H4 Z", "M15.5 9 Q17.5 12 15.5 15", "M18 6.5 Q21.8 12 18 17.5")
    val VolumeOff = icon("volume_off", "M4 9.5 H7.5 L12 5.5 V18.5 L7.5 14.5 H4 Z", "M15.5 9.5 L20.5 14.5", "M20.5 9.5 L15.5 14.5")
    val ArrowUp = icon("arrow_up", "M12 19 V5", "M6 11 L12 5 L18 11")
    val Timer = icon("timer", c(12f, 13.5f, 7f), "M12 13.5 V10", "M10 3.5 H14", "M12 3.5 V6.5", "M18 6.5 L19.5 5")
    val Infinity = icon("infinity", "M12 12 C10 9 5.5 8.5 5.5 12 C5.5 15.5 10 15 12 12 C14 9 18.5 8.5 18.5 12 C18.5 15.5 14 15 12 12 Z")
    val Flame = icon("flame", "M12 21 C8 21 5.5 18.2 5.5 15 C5.5 11 9 9.5 9.5 4.5 C12 6.5 13.5 9 13 11.5 C14.5 11 15.5 9.5 15.5 8.5 C17.5 10.5 18.5 12.7 18.5 15 C18.5 18.2 16 21 12 21 Z")
    val Goal = icon("goal", "M6 21 V4", "M6 4.5 H17 L14.5 8.5 L17 12.5 H6")
    val Calendar = icon("calendar", rect(4f, 5.5f, 16f, 14.5f), "M4 10 H20", "M8.5 3.5 V7", "M15.5 3.5 V7")
    val Lock = icon("lock", rect(5.5f, 10.5f, 13f, 9.5f), "M8.5 10.5 V7.5 C8.5 3.5 15.5 3.5 15.5 7.5 V10.5")
    val Export = icon("export", "M12 4 V15", "M7.5 10.5 L12 15 L16.5 10.5", "M5 19.5 H19")
    val Palette = icon("palette",
        "M12 4 C7 4 4 7.6 4 12 C4 16.4 7.6 20 12 20 C13.2 20 13.8 19.2 13.5 18 C13.2 16.8 13.8 15.5 15.2 15.5 H17 C18.7 15.5 20 14.2 20 12.4 C20 7.7 16.4 4 12 4 Z",
        c(8.5f, 11f, 1f), c(12f, 8f, 1f), c(15.5f, 11f, 1f), fill = setOf(1, 2, 3))
    val Sparkle = icon("sparkle", "M12 4 L13.6 10.4 L20 12 L13.6 13.6 L12 20 L10.4 13.6 L4 12 L10.4 10.4 Z")
    val Star = icon("star", "M12 4.5 L14.2 9.3 L19.4 9.8 L15.5 13.3 L16.6 18.4 L12 15.8 L7.4 18.4 L8.5 13.3 L4.6 9.8 L9.8 9.3 Z")
    val Info = icon("info", c(12f, 12f, 8.5f), "M12 11 V16.5", "M12 7.8 V8")
    val Shield = icon("shield", "M12 3.5 L19 6.5 V11.5 C19 15.8 16 19.3 12 20.5 C8 19.3 5 15.8 5 11.5 V6.5 Z")
    val Reset = icon("reset", "M5 12 A7 7 0 1 0 7.1 7", "M7 3.5 V7.5 H11")
    val Filter = icon("filter", "M4 6 H20 L14 13 V19 L10 17 V13 Z")
    val Vibrate = icon("vibrate", rect(8f, 4f, 8f, 16f), "M4.5 9 V15", "M19.5 9 V15")
    val Bell = icon("bell", "M6 16.5 V11 C6 7.7 8.7 5 12 5 C15.3 5 18 7.7 18 11 V16.5 L19.5 18 H4.5 Z", "M10 20.5 H14")
    val Moon = icon("moon", "M19 14.5 C17.9 15 16.7 15.2 15.5 15.2 C11.2 15.2 7.8 11.8 7.8 7.5 C7.8 6.3 8 5.1 8.5 4 C5.6 5.2 3.5 8.1 3.5 11.5 C3.5 16.2 7.3 20 12 20 C15.4 20 18.3 17.9 19 14.5 Z")

    // Sounds
    val BrownNoise = icon("brown_noise", "M3 12 C6 5.5 9 5.5 12 12 C15 18.5 18 18.5 21 12")
    val WhiteNoise = icon("white_noise", "M3 12 L5 8.5 L7 15 L9 6.5 L11 17 L13 8 L15 15.5 L17 7.5 L19 16 L21 12")
    val Ocean = icon("ocean",
        "M3 9.5 C5.5 7 8 7 10.5 9.5 C13 12 15.5 12 18 9.5 C19 8.5 20 8.1 21 8.1",
        "M3 15.5 C5.5 13 8 13 10.5 15.5 C13 18 15.5 18 18 15.5 C19 14.5 20 14.1 21 14.1")
    val Stream = icon("stream", "M12 3.5 C12 3.5 6 10.5 6 14.5 C6 17.8 8.7 20.5 12 20.5 C15.3 20.5 18 17.8 18 14.5 C18 10.5 12 3.5 12 3.5 Z", "M9.3 15 C9.3 16.5 10.4 17.6 12 17.9")
    val Forest = icon("forest", "M12 3 L6.5 11 H9.5 L5 17 H19 L14.5 11 H17.5 Z", "M12 17 V21")

    // Activities
    val Studying = icon("studying", "M2.5 9.5 L12 5 L21.5 9.5 L12 14 Z", "M6.5 11.5 V16 C6.5 17.5 9 19 12 19 C15 19 17.5 17.5 17.5 16 V11.5", "M21.5 9.5 V14")
    val Programming = icon("programming", "M8.5 7 L3.5 12 L8.5 17", "M15.5 7 L20.5 12 L15.5 17", "M13.5 5 L10.5 19")
    val Reading = icon("reading", "M12 6.5 C10 5 6.5 4.5 3.5 5 V18.5 C6.5 18 10 18.5 12 20 C14 18.5 17.5 18 20.5 18.5 V5 C17.5 4.5 14 5 12 6.5 Z", "M12 6.5 V20")
    val Writing = icon("writing", "M4 20 L5 15.5 L15.5 5 C16.3 4.2 17.7 4.2 18.5 5 L19 5.5 C19.8 6.3 19.8 7.7 19 8.5 L8.5 19 Z", "M13.5 7 L17 10.5")
    val Project = icon("project", rect(3.5f, 7.5f, 17f, 12f), "M9 7.5 V5.5 H15 V7.5", "M3.5 12.5 H20.5")
    val Meditation = icon("meditation",
        "M12 19 C8 17 6.5 13.5 7.5 9 C10 10 12 13 12 19 Z",
        "M12 19 C16 17 17.5 13.5 16.5 9 C14 10 12 13 12 19 Z",
        "M12 19 C10.2 15.5 10.5 9.5 12 6.5 C13.5 9.5 13.8 15.5 12 19",
        "M4 19 C7 20.5 17 20.5 20 19")

    fun forSound(s: FocusSound): ImageVector = when (s) {
        FocusSound.BROWN_NOISE -> BrownNoise
        FocusSound.WHITE_NOISE -> WhiteNoise
        FocusSound.OCEAN -> Ocean
        FocusSound.STREAM -> Stream
        FocusSound.FOREST -> Forest
    }

    /** Built-in activities have their own icon; custom ones use a monogram (see ActivityBadge). */
    fun forBuiltIn(key: String?): ImageVector? = when (key) {
        BuiltInActivity.STUDYING.key -> Studying
        BuiltInActivity.PROGRAMMING.key -> Programming
        BuiltInActivity.READING.key -> Reading
        BuiltInActivity.WRITING.key -> Writing
        BuiltInActivity.PROJECT.key -> Project
        BuiltInActivity.MEDITATION.key -> Meditation
        else -> null
    }
}

/** One-line descriptions shown on the sound cards. */
fun FocusSound.tagline(): String = when (this) {
    FocusSound.BROWN_NOISE -> "Deep, soft rumble"
    FocusSound.WHITE_NOISE -> "Bright, even hiss"
    FocusSound.OCEAN -> "Calm waves on a shore"
    FocusSound.STREAM -> "A steady woodland brook"
    FocusSound.FOREST -> "Wind through pines"
}
