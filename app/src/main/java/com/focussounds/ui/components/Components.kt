package com.focussounds.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focussounds.ui.theme.FocusTheme

val ScreenPadding = 20.dp
val CardShape = RoundedCornerShape(24.dp)
val ControlShape = RoundedCornerShape(16.dp)
val PillShape = RoundedCornerShape(50)

/** Opacity of "glass" surfaces that sit over the landscape: dark enough for text, light enough to feel layered. */
const val GlassAlpha = 0.84f

fun lerpColor(a: Color, b: Color, t: Float): Color = androidx.compose.ui.graphics.lerp(a, b, t)

/**
 * The standard raised card: a softly top-lit surface with a hairline whose upper edge catches the
 * light, and a generous radius. [glass] lets the backdrop show through slightly (Focus only);
 * [accentWash] tints the top of the card with the accent for the one card that leads a screen.
 */
@Composable
fun FocusCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    selected: Boolean = false,
    padding: PaddingValues = PaddingValues(20.dp),
    glass: Boolean = false,
    accentWash: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = FocusTheme.colors
    val motion = FocusTheme.motion
    val border by animateColorAsState(if (selected) c.accent else c.outline, motion.spec(200), label = "cardBorder")
    val bg by animateColorAsState(if (selected) c.accentContainer else c.surface1, motion.spec(200), label = "cardBg")
    val alpha = if (glass) GlassAlpha else 1f
    val stroke = if (selected) Brush.verticalGradient(listOf(border, border)) else Brush.verticalGradient(listOf(c.outlineStrong, border.copy(alpha = 0.7f)))
    Column(
        modifier
            .clip(CardShape)
            .background(
                Brush.verticalGradient(
                    listOf((if (accentWash) lerpColor(bg, c.accentContainer, 0.75f) else lerpColor(bg, c.surface2, 0.55f)).copy(alpha = alpha), bg.copy(alpha = alpha)),
                ),
            )
            .border(BorderStroke(if (selected) 1.5.dp else 1.dp, stroke), CardShape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(padding),
        content = content,
    )
}

/** Large page title with an optional subtitle and trailing action; the same on every screen. */
@Composable
fun ScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    subtitleColor: Color = FocusTheme.colors.textSecondary,
    trailing: @Composable (RowScope.() -> Unit)? = null,
) {
    val c = FocusTheme.colors
    Row(modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineMedium, color = c.textPrimary, modifier = Modifier.semantics { heading() })
            if (subtitle != null) {
                Spacer(Modifier.size(2.dp))
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = subtitleColor)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

/** Label above a group of rows or cards (outside the card), like an inset grouped list. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Eyebrow(text, modifier.padding(start = 4.dp, top = 12.dp, bottom = 0.dp).semantics { heading() }, color = FocusTheme.colors.textSecondary)
}

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: @Composable (RowScope.() -> Unit)? = null,
) {
    val c = FocusTheme.colors
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = c.textTertiary)
        }
        trailing?.invoke(this)
    }
}

/** Small uppercase label above a group of controls. */
@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier, color: Color = FocusTheme.colors.textTertiary) {
    Text(
        text.uppercase(),
        modifier = modifier,
        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.4.sp, fontWeight = FontWeight.SemiBold),
        color = color,
    )
}

data class SegmentOption<T>(val value: T, val label: String, val icon: ImageVector? = null, val description: String? = null)

/**
 * Pill segmented control. Scrolls horizontally when options don't fit (large fonts / narrow screens).
 */
@Composable
fun <T> SegmentedControl(
    options: List<SegmentOption<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    fill: Boolean = true,
    glass: Boolean = false,
) {
    val c = FocusTheme.colors
    val haptics = FocusTheme.haptics
    val scroll = rememberScrollState()
    Row(
        modifier
            .clip(ControlShape)
            .background(c.surface1.copy(alpha = if (glass) GlassAlpha else 1f))
            .border(1.dp, c.outline, ControlShape)
            .then(if (fill) Modifier.fillMaxWidth() else Modifier)
            .horizontalScroll(scroll)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEach { o ->
            val isSel = o.value == selected
            val bg by animateColorAsState(if (isSel) c.accent else Color.Transparent, FocusTheme.motion.spec(180), label = "segBg")
            val fg by animateColorAsState(if (isSel) c.onAccent else c.textSecondary, FocusTheme.motion.spec(180), label = "segFg")
            Row(
                Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(bg)
                    .selectable(isSel, role = Role.RadioButton) {
                        if (!isSel) haptics.tick()
                        onSelect(o.value)
                    }
                    .heightIn(min = 42.dp)
                    .padding(horizontal = 13.dp, vertical = 9.dp)
                    .semantics { if (o.description != null) contentDescription = o.description },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                if (o.icon != null) {
                    Icon(o.icon, null, Modifier.size(16.dp), tint = fg)
                    if (o.label.isNotEmpty()) Spacer(Modifier.width(6.dp))
                }
                if (o.label.isNotEmpty()) Text(o.label, style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1)
            }
        }
    }
}

/** Selectable chip with optional icon/badge, for activities and filters. */
@Composable
fun SelectChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leading: @Composable (() -> Unit)? = null,
    glass: Boolean = false,
) {
    val c = FocusTheme.colors
    val motion = FocusTheme.motion
    val idle = c.surface1.copy(alpha = if (glass) GlassAlpha else 1f)
    val bg by animateColorAsState(if (selected) c.accentContainer else idle, motion.spec(180), label = "chipBg")
    val border by animateColorAsState(if (selected) c.accent else c.outline, motion.spec(180), label = "chipBorder")
    val fg by animateColorAsState(if (selected) c.accentContent else c.textSecondary, motion.spec(180), label = "chipFg")
    Row(
        modifier
            .clip(PillShape)
            .background(bg)
            .border(if (selected) 1.5.dp else 1.dp, border, PillShape)
            .selectable(selected, role = Role.RadioButton, onClick = onClick)
            .heightIn(min = 44.dp)
            .padding(start = if (leading != null) 8.dp else 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(8.dp))
        }
        Text(label, style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Icon in a softly tinted rounded square; built-ins use their icon, custom activities a monogram. */
@Composable
fun ActivityBadge(name: String, icon: ImageVector?, size: Dp = 32.dp, tint: Color = FocusTheme.colors.accentContent, background: Color = FocusTheme.colors.accentContainer) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * 0.32f)).background(background),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(size * 0.58f), tint = tint)
        } else {
            Text(
                name.trim().firstOrNull()?.uppercase() ?: "•",
                color = tint,
                fontWeight = FontWeight.SemiBold,
                fontSize = (size.value * 0.44f).sp,
            )
        }
    }
}

@Composable
fun MetricTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    icon: ImageVector? = null,
) {
    val c = FocusTheme.colors
    FocusCard(modifier, padding = PaddingValues(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, null, Modifier.size(16.dp), tint = c.accentContent)
                Spacer(Modifier.width(6.dp))
            }
            Text(label, style = MaterialTheme.typography.labelMedium, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.size(8.dp))
        Text(value, style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"), color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (detail != null) {
            Text(detail, style = MaterialTheme.typography.bodySmall, color = c.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Circular progress with an optional centered label. Fractions above 1 render as a full ring. */
@Composable
fun ProgressRing(
    fraction: Float,
    modifier: Modifier = Modifier,
    size: Dp = 64.dp,
    stroke: Dp = 7.dp,
    color: Color = FocusTheme.colors.accent,
    track: Color = FocusTheme.colors.surface3,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val animated by animateFloatAsState(fraction.coerceIn(0f, 1f), FocusTheme.motion.spec(700), label = "ring")
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val sw = stroke.toPx()
            val inset = sw / 2
            val arcSize = androidx.compose.ui.geometry.Size(this.size.width - sw, this.size.height - sw)
            val topLeft = androidx.compose.ui.geometry.Offset(inset, inset)
            drawArc(track, 0f, 360f, false, topLeft, arcSize, style = Stroke(sw))
            if (animated > 0f) drawArc(color, -90f, 360f * animated, false, topLeft, arcSize, style = Stroke(sw, cap = StrokeCap.Round))
        }
        content()
    }
}

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    action: @Composable (() -> Unit)? = null,
) {
    val c = FocusTheme.colors
    Column(modifier.fillMaxWidth().padding(vertical = 24.dp, horizontal = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(56.dp).clip(CircleShape).background(c.accentContainer), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(26.dp), tint = c.accentContent)
        }
        Spacer(Modifier.size(14.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
        Spacer(Modifier.size(4.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium, color = c.textSecondary, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.size(14.dp))
            action()
        }
    }
}

/** Primary call to action: full-width accent pill. */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    val c = FocusTheme.colors
    val bg by animateColorAsState(if (enabled) c.accent else c.surface3, FocusTheme.motion.spec(200), label = "btnBg")
    val fg = if (enabled) c.onAccent else c.textTertiary
    // A faint top highlight gives the fill some depth without a heavy gradient.
    val top = if (enabled) lerpColor(bg, Color.White, 0.12f) else bg
    Row(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Brush.verticalGradient(listOf(top, bg)))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .heightIn(min = 58.dp)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(20.dp), tint = fg)
            Spacer(Modifier.width(10.dp))
        }
        Text(text, style = MaterialTheme.typography.titleMedium, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    contentColor: Color = FocusTheme.colors.textPrimary,
) {
    val c = FocusTheme.colors
    Row(
        modifier
            .clip(ControlShape)
            .background(c.surface2)
            .border(1.dp, c.outlineStrong.copy(alpha = 0.7f), ControlShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        val fg = if (enabled) contentColor else c.textTertiary
        if (icon != null) {
            Icon(icon, null, Modifier.size(18.dp), tint = fg)
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Settings row: title + description + trailing control. */
@Composable
fun SettingRow(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    icon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val c = FocusTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = 56.dp)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(c.accentContainer), contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(20.dp), tint = c.accentContent)
            }
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = c.textPrimary)
            if (description != null) Text(description, style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    icon: ImageVector? = null,
) {
    val haptics = FocusTheme.haptics
    val c = FocusTheme.colors
    SettingRow(
        title = title,
        description = description,
        icon = icon,
        modifier = modifier.toggleable(checked, role = Role.Switch) {
            haptics.select()
            onCheckedChange(it)
        },
    ) {
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = c.onAccent,
                checkedTrackColor = c.accent,
                uncheckedThumbColor = c.textTertiary,
                uncheckedTrackColor = c.surface3,
                uncheckedBorderColor = c.outlineStrong,
            ),
        )
    }
}

/** Compact status pill (e.g. outcome labels). Always pairs color with text. */
@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 9.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(12.dp), tint = color)
            Spacer(Modifier.width(4.dp))
        }
        Text(text, style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1)
    }
}

@Composable
fun HairlineDivider(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().size(height = 1.dp, width = 0.dp).background(FocusTheme.colors.outline))
}

/** Small colored key used in chart legends: color + shape (not color alone). */
@Composable
fun LegendSwatch(color: Color, modifier: Modifier = Modifier, square: Boolean = false) {
    Box(modifier.size(10.dp).clip(if (square) RoundedCornerShape(3.dp) else CircleShape).background(color))
}
