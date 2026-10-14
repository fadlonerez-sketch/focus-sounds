package com.focussounds.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.focussounds.core.ColorMath
import com.focussounds.data.AppSettings
import com.focussounds.ui.components.AppIcons
import com.focussounds.ui.components.Eyebrow
import com.focussounds.ui.components.PrimaryButton
import com.focussounds.ui.components.ProgressRing
import com.focussounds.ui.components.SecondaryButton
import com.focussounds.ui.components.SelectChip
import com.focussounds.ui.theme.FocusTheme
import com.focussounds.ui.theme.LocalFocusColors
import com.focussounds.ui.theme.previewColors

data class AccentPreset(val name: String, val argb: Int)

val ACCENT_PRESETS = listOf(
    AccentPreset("Periwinkle", AppSettings.DEFAULT_ACCENT),
    AccentPreset("Teal", 0xFF2DD4BF.toInt()),
    AccentPreset("Mint", 0xFF4ADE80.toInt()),
    AccentPreset("Sky", 0xFF38BDF8.toInt()),
    AccentPreset("Lavender", 0xFFC084FC.toInt()),
    AccentPreset("Rose", 0xFFF472B6.toInt()),
    AccentPreset("Coral", 0xFFFF7A6B.toInt()),
    AccentPreset("Amber", 0xFFF5B041.toInt()),
    AccentPreset("Sand", 0xFFD6C3A1.toInt()),
    AccentPreset("Ice", 0xFFA7C4D9.toInt()),
)

/**
 * Full-screen accent picker: HSV pad + hue bar, HEX entry, presets, and a live preview of real
 * components in the candidate color. Nothing changes in the app until Apply.
 */
@Composable
fun ColorPickerDialog(current: Int, onApply: (Int) -> Unit, onDismiss: () -> Unit) {
    val start = remember { ColorMath.toHsv(current) }
    var hue by remember { mutableFloatStateOf(start[0]) }
    var sat by remember { mutableFloatStateOf(start[1]) }
    var value by remember { mutableFloatStateOf(start[2]) }
    val candidate = ColorMath.fromHsv(hue, sat, value)
    var hex by remember { mutableStateOf(TextFieldValue(ColorMath.toHex(current))) }
    var hexError by remember { mutableStateOf<String?>(null) }

    fun setFromArgb(argb: Int, updateHex: Boolean = true) {
        val hsv = ColorMath.toHsv(argb)
        if (hsv[1] > 0f && hsv[2] > 0f) hue = hsv[0]
        sat = hsv[1]
        value = hsv[2]
        if (updateHex) {
            val h = ColorMath.toHex(argb)
            hex = TextFieldValue(h, TextRange(h.length))
            hexError = null
        }
    }
    fun syncHex() {
        val h = ColorMath.toHex(ColorMath.fromHsv(hue, sat, value))
        hex = TextFieldValue(h, TextRange(h.length))
        hexError = null
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val c = FocusTheme.colors
        Column(Modifier.fillMaxSize().background(c.background).safeDrawingPadding().testTag("color_picker")) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss) { Icon(AppIcons.Close, "Cancel", tint = c.textPrimary) }
                Text("Accent color", style = MaterialTheme.typography.titleLarge, color = c.textPrimary, modifier = Modifier.weight(1f))
            }
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                // Live preview of real components in the candidate color.
                val preview = remember(candidate) { previewColors(candidate) }
                CompositionLocalProvider(LocalFocusColors provides preview) { AppearancePreview(Modifier.fillMaxWidth().testTag("picker_preview")) }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Swatch(current, 36)
                    Text("  Current  →  ", style = MaterialTheme.typography.labelMedium, color = c.textTertiary)
                    Swatch(candidate, 36)
                    Text("  New", style = MaterialTheme.typography.labelMedium, color = c.textTertiary)
                }

                SaturationValuePad(hue, sat, value, Modifier.fillMaxWidth().aspectRatio(1.6f)) { s, v ->
                    sat = s
                    value = v
                    syncHex()
                }
                HueBar(hue, Modifier.fillMaxWidth().height(28.dp)) {
                    hue = it
                    syncHex()
                }

                OutlinedTextField(
                    value = hex,
                    onValueChange = { v ->
                        val cleaned = v.text.uppercase().filter { it == '#' || it.isDigit() || it in 'A'..'F' }.take(7)
                        hex = v.copy(text = cleaned)
                        val parsed = ColorMath.parseHex(cleaned)
                        hexError = if (parsed == null) "Use 6 hex digits, e.g. #8B8CF8" else null
                        if (parsed != null) setFromArgb(parsed, updateHex = false)
                    },
                    label = { Text("HEX") },
                    singleLine = true,
                    isError = hexError != null,
                    supportingText = { Text(hexError ?: "Type a color like #2DD4BF") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                    colors = fieldColors(),
                    modifier = Modifier.fillMaxWidth().testTag("hex_field"),
                )

                Eyebrow("Presets")
                PresetGrid(candidate) { setFromArgb(it) }

                val palette = remember(candidate) { previewColors(candidate) }
                if (palette.accent != Color(candidate)) {
                    Row(Modifier.clip(RoundedCornerShape(14.dp)).background(c.surface2).padding(12.dp)) {
                        Icon(AppIcons.Info, null, Modifier.size(18.dp), tint = c.textSecondary)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "This color is very dark on the dark theme, so buttons and charts use a slightly lighter shade of the same hue to stay visible.",
                            style = MaterialTheme.typography.bodySmall, color = c.textSecondary,
                        )
                    }
                }
                SecondaryButton("Reset to default", { setFromArgb(AppSettings.DEFAULT_ACCENT) }, icon = AppIcons.Reset)
                Spacer(Modifier.height(8.dp))
            }
            Row(Modifier.fillMaxWidth().background(c.surface1).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SecondaryButton("Cancel", onDismiss, Modifier.weight(1f))
                PrimaryButton("Apply", { onApply(candidate) }, Modifier.weight(1f).testTag("apply_color"), enabled = hexError == null)
            }
        }
    }
}

@Composable
private fun Swatch(argb: Int, sizeDp: Int) {
    Box(Modifier.size(sizeDp.dp).clip(CircleShape).background(Color(argb)).border(1.dp, FocusTheme.colors.outlineStrong, CircleShape))
}

@Composable
fun PresetGrid(selected: Int, onPick: (Int) -> Unit) {
    val c = FocusTheme.colors
    val haptics = FocusTheme.haptics
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ACCENT_PRESETS.chunked(5).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                row.forEach { p ->
                    val isSel = p.argb == selected
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(60.dp)) {
                        Box(
                            Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(Color(p.argb))
                                .border(if (isSel) 3.dp else 1.dp, if (isSel) c.textPrimary else c.outlineStrong, CircleShape)
                                .clickable(role = Role.RadioButton) {
                                    haptics.tick()
                                    onPick(p.argb)
                                }
                                .semantics {
                                    contentDescription = "${p.name} accent"
                                    stateDescription = if (isSel) "Selected" else "Not selected"
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (isSel) Icon(AppIcons.Check, null, Modifier.size(20.dp), tint = Color(ColorMath.bestOnColor(p.argb)))
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(p.name, style = MaterialTheme.typography.labelSmall, color = if (isSel) c.textPrimary else c.textTertiary, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun SaturationValuePad(hue: Float, sat: Float, value: Float, modifier: Modifier, onChange: (Float, Float) -> Unit) {
    val hueColor = Color(ColorMath.fromHsv(hue, 1f, 1f))
    Canvas(
        modifier
            .semantics { contentDescription = "Saturation ${(sat * 100).toInt()} percent, brightness ${(value * 100).toInt()} percent" }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    fun update(p: Offset) = onChange((p.x / size.width).coerceIn(0f, 1f), 1f - (p.y / size.height).coerceIn(0f, 1f))
                    update(down.position)
                    drag(down.id) { change ->
                        change.consume()
                        update(change.position)
                    }
                }
            },
    ) {
        val shape = Path().apply { addRoundRect(RoundRect(0f, 0f, size.width, size.height, CornerRadius(18.dp.toPx()))) }
        clipPath(shape) {
            drawRect(hueColor)
            drawRect(Brush.horizontalGradient(listOf(Color.White, Color.White.copy(alpha = 0f))))
            drawRect(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0f), Color.Black)))
        }
        val p = Offset(sat * size.width, (1 - value) * size.height)
        drawCircle(Color.Black.copy(alpha = 0.35f), 15.dp.toPx(), p)
        drawCircle(Color.White, 13.dp.toPx(), p, style = Stroke(3.dp.toPx()))
        drawCircle(Color(ColorMath.fromHsv(hue, sat, value)), 10.dp.toPx(), p)
    }
}

@Composable
private fun HueBar(hue: Float, modifier: Modifier, onChange: (Float) -> Unit) {
    val hues = remember { (0..6).map { Color(ColorMath.fromHsv(it * 60f, 1f, 1f)) } }
    Canvas(
        modifier
            .semantics { contentDescription = "Hue ${hue.toInt()} degrees" }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    fun update(p: Offset) = onChange((p.x / size.width).coerceIn(0f, 0.9999f) * 360f)
                    update(down.position)
                    drag(down.id) { change ->
                        change.consume()
                        update(change.position)
                    }
                }
            },
    ) {
        drawRoundRect(Brush.horizontalGradient(hues), cornerRadius = CornerRadius(14.dp.toPx()))
        val x = hue / 360f * size.width
        drawCircle(Color.White, size.height / 2 - 1.dp.toPx(), Offset(x, size.height / 2), style = Stroke(3.dp.toPx()))
    }
}

/** A small composition of real components, drawn in whatever accent is provided. */
@Composable
fun AppearancePreview(modifier: Modifier = Modifier) {
    val c = FocusTheme.colors
    // A flat inset panel (not a card inside a card) showing the accent on real components.
    Column(
        modifier
            .clip(RoundedCornerShape(18.dp))
            .background(c.surface2)
            .padding(16.dp)
            .clearAndSetSemantics { contentDescription = "Preview of the accent color on app components" }
            .pointerInput(Unit) {
                // The sample controls are illustrations only: swallow presses so they never react,
                // but let movement through so the Settings list still scrolls when dragged here.
                awaitPointerEventScope {
                    while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { if (it.changedToDown()) it.consume() }
                }
            },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProgressRing(0.62f, size = 64.dp, stroke = 7.dp) {
                Text("62%", style = MaterialTheme.typography.labelMedium, color = c.textPrimary)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Preview", style = MaterialTheme.typography.labelMedium, color = c.textSecondary)
                Text("1 h 15 min today", style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
                Text("▲ 18% vs yesterday", style = MaterialTheme.typography.labelMedium, color = c.accentContent)
            }
        }
        Spacer(Modifier.height(12.dp))
        Canvas(Modifier.fillMaxWidth().height(44.dp)) {
            val ys = listOf(0.7f, 0.55f, 0.6f, 0.35f, 0.45f, 0.2f, 0.3f)
            val pts = ys.mapIndexed { i, y -> Offset(size.width * i / (ys.size - 1), size.height * y) }
            val path = com.focussounds.ui.charts.monotonePath(pts)
            drawPath(path, c.accent, style = Stroke(2.5.dp.toPx()))
            pts.forEach { drawCircle(c.accent, 3.dp.toPx(), it) }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            SelectChip("Selected", selected = true, onClick = {})
            SelectChip("Other", selected = false, onClick = {})
        }
        Slider(0.6f, onValueChange = {}, colors = SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = c.accent, inactiveTrackColor = c.surface3))
        PrimaryButton("Start focusing", {}, Modifier.fillMaxWidth(), icon = AppIcons.Play)
    }
}
