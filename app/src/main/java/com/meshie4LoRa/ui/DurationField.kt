package com.meshie4LoRa.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.meshie4LoRa.data.DurationUnit
import com.meshie4LoRa.data.humanDuration

/**
 * "⏱ 2 min" button that opens a small dialog to type a number and pick sec/min/hr, like "Speak up after…" on the
 * Chan page. [onSave] gets seconds; with [allowZero], 0 means off.
 */
@Composable
fun DurationButton(
    seconds: Long, title: String, help: String, onSave: (Long) -> Unit,
    allowZero: Boolean = true, maxSeconds: Long = 7 * 24 * 3600L,
) {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = true }) { Text(if (seconds <= 0) "⏱ off" else "⏱ ${humanDuration(seconds)}") }
    if (!open) return
    var unit by remember { mutableStateOf(DurationUnit.bestFor(seconds)) }
    var text by remember { mutableStateOf((seconds / unit.seconds).toString()) }
    val value = text.trim().toLongOrNull()?.times(unit.seconds)?.takeIf { it in (if (allowZero) 0 else 1)..maxSeconds }
    AlertDialog(
        onDismissRequest = { open = false },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    text, { text = it.filter(Char::isDigit).take(6) }, singleLine = true, suffix = { Text(unit.label) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = value == null,
                    supportingText = { Text(help) },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DurationUnit.entries.forEach { u -> FilterChip(selected = unit == u, onClick = { unit = u }, label = { Text(u.label) }) }
                }
            }
        },
        confirmButton = { Button(onClick = { value?.let(onSave); open = false }, enabled = value != null) { Text("Save") } },
        dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } },
    )
}
