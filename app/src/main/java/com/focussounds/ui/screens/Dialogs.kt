package com.focussounds.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.focussounds.core.Durations
import com.focussounds.core.Limits
import com.focussounds.ui.components.SegmentOption
import com.focussounds.ui.components.SegmentedControl
import com.focussounds.ui.theme.FocusTheme

@Composable
fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = FocusTheme.colors.accent,
    unfocusedBorderColor = FocusTheme.colors.outlineStrong,
    focusedLabelColor = FocusTheme.colors.accentContent,
    cursorColor = FocusTheme.colors.accent,
    focusedTextColor = FocusTheme.colors.textPrimary,
    unfocusedTextColor = FocusTheme.colors.textPrimary,
)

@Composable
private fun FocusDialog(
    title: String,
    onDismiss: () -> Unit,
    confirmLabel: String,
    confirmEnabled: Boolean,
    onConfirm: () -> Unit,
    dismissLabel: String = "Cancel",
    destructive: Boolean = false,
    body: @Composable () -> Unit,
) {
    val c = FocusTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface2,
        titleContentColor = c.textPrimary,
        textContentColor = c.textSecondary,
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = body,
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = confirmEnabled) {
                Text(confirmLabel, color = when {
                    !confirmEnabled -> c.textTertiary
                    destructive -> c.danger
                    else -> c.accentContent
                })
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(dismissLabel, color = c.textSecondary) } },
    )
}

/** Text field whose prefilled text starts selected, so typing replaces it. */
@Composable
private fun rememberSelectedField(initial: String) =
    remember { mutableStateOf(TextFieldValue(initial, selection = TextRange(0, initial.length))) }

@Composable
fun NameDialog(title: String, initial: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var field by rememberSelectedField(initial)
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    FocusDialog(title, onDismiss, "Save", field.text.isNotBlank(), { onConfirm(field.text) }) {
        OutlinedTextField(
            value = field,
            onValueChange = { v -> if (v.text.length <= Limits.MAX_ACTIVITY_NAME_LENGTH) field = v },
            singleLine = true,
            label = { Text("Name") },
            supportingText = { Text("${field.text.length}/${Limits.MAX_ACTIVITY_NAME_LENGTH}") },
            colors = fieldColors(),
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
        )
    }
}

@Composable
fun MinutesDialog(
    title: String,
    initialMinutes: Int?,
    range: IntRange,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
    help: String? = null,
) {
    val initial = initialMinutes?.toString() ?: ""
    var field by rememberSelectedField(initial)
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val value = field.text.toIntOrNull()
    val valid = value != null && value in range
    FocusDialog(title, onDismiss, "Set", valid, { onConfirm(value!!) }) {
        Column {
            if (help != null) Text(help, style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(
                value = field,
                onValueChange = { v ->
                    val digits = v.text.filter { it.isDigit() }.take(4)
                    field = if (digits == v.text) v else TextFieldValue(digits, selection = TextRange(digits.length))
                },
                singleLine = true,
                label = { Text("Minutes") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                supportingText = {
                    Text(if (value != null && valid) Durations.format(value * 60_000L) else "${range.first}–${range.last} minutes")
                },
                isError = field.text.isNotEmpty() && !valid,
                colors = fieldColors(),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        }
    }
}

/** Daily or weekly goal: quick presets plus a custom value; "Off" removes the goal. */
@Composable
fun GoalDialog(
    title: String,
    currentMinutes: Int,
    presets: List<Int>,
    maxMinutes: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var minutes by remember { mutableStateOf(currentMinutes) }
    var custom by remember { mutableStateOf(currentMinutes != 0 && currentMinutes !in presets) }
    var field by rememberSelectedField(if (custom) currentMinutes.toString() else "")
    val customValue = field.text.toIntOrNull()
    val result = if (custom) customValue else minutes
    val valid = result != null && result in 0..maxMinutes
    FocusDialog(title, onDismiss, "Save", valid, { onConfirm(result!!) }) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SegmentedControl(
                options = listOf(SegmentOption(0, "Off")) + presets.map { SegmentOption(it, Durations.short(it * 60_000L)) } +
                    SegmentOption(-1, "Custom"),
                selected = if (custom) -1 else minutes,
                onSelect = {
                    if (it == -1) {
                        custom = true
                    } else {
                        custom = false
                        minutes = it
                    }
                },
            )
            if (custom) {
                OutlinedTextField(
                    value = field,
                    onValueChange = { v ->
                        val digits = v.text.filter { it.isDigit() }.take(5)
                        field = if (digits == v.text) v else TextFieldValue(digits, selection = TextRange(digits.length))
                    },
                    singleLine = true,
                    label = { Text("Minutes") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    supportingText = { Text(customValue?.takeIf { valid }?.let { Durations.format(it * 60_000L) } ?: "1–$maxMinutes minutes") },
                    isError = field.text.isNotEmpty() && !valid,
                    colors = fieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Text(
                if (result == 0) "No goal: progress is still recorded." else "Only actual playing time counts toward goals; paused time never does.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = true,
) {
    FocusDialog(title, onDismiss, confirmLabel, true, onConfirm, destructive = destructive) {
        Text(message, style = MaterialTheme.typography.bodyMedium)
    }
}
