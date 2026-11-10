package com.meshie4LoRa.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.material3.AlertDialog
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Local-only chat with the loaded model; nothing is transmitted. */
@Composable
fun TestChatScreen(vm: ModelsViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val lines by vm.chat.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val system by vm.systemPrompt.collectAsStateWithLifecycle()
    var input by rememberSaveable { mutableStateOf("") }
    var editSystem by rememberSaveable { mutableStateOf(false) }
    var sending by remember { mutableStateOf<String?>(null) }
    // Leaving the screen any other way (switching tabs) also drops the chat; config changes keep it.
    val activity = androidx.activity.compose.LocalActivity.current
    DisposableEffect(Unit) { onDispose { if (activity?.isChangingConfigurations != true) vm.clearChat() } }

    Column(modifier.fillMaxSize().imePadding()) {
        Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
            Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                Column(Modifier.weight(1f)) {
                    Text("Test chat", style = MaterialTheme.typography.titleMedium)
                    Text("Private unless you tap Send to channel · cleared when you leave", style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = { editSystem = !editSystem }) { Text("Prompt") }
                TextButton(onClick = vm::clearChat) { Text("Clear") }
            }
        }
        if (editSystem) {
            OutlinedTextField(
                value = system,
                onValueChange = { vm.systemPrompt.value = it },
                label = { Text("System prompt") },
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                minLines = 3,
            )
        }
        val listState = rememberLazyListState()
        LaunchedEffect(lines.size, lines.lastOrNull()?.text?.length) { if (lines.isNotEmpty()) listState.scrollToItem(0) }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            state = listState,
            reverseLayout = true,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            itemsIndexed(lines.asReversed()) { _, line ->
                val colors = when (line.role) {
                    "user" -> CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                    "error" -> CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                    else -> CardDefaults.cardColors()
                }
                Card(colors = colors, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp)) {
                        Text(line.role, style = MaterialTheme.typography.labelMedium)
                        Text(line.text.ifEmpty { "…" }, style = MaterialTheme.typography.bodyLarge)
                        line.stats?.let { s ->
                            val bytes = line.text.trim().toByteArray().size
                            Text(
                                "%d tok @ %.1f tok/s · prompt %d tok @ %.1f tok/s · %d bytes".format(
                                    s.genTokens, s.genTps, s.promptTokens, s.promptTps, bytes,
                                ),
                                style = MaterialTheme.typography.labelSmall,
                            )
                            if (line.role == "assistant" && line.text.isNotBlank()) {
                                TextButton(onClick = { sending = line.text.trim() }) { Text("📤 Send to channel") }
                            }
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { Text("Say something") },
                modifier = Modifier.weight(1f),
            )
            if (busy) {
                Button(onClick = vm::stop, modifier = Modifier.padding(start = 8.dp)) { Text("Stop") }
            } else {
                Button(
                    onClick = { vm.sendChat(input.trim()); input = "" },
                    enabled = input.isNotBlank(),
                    modifier = Modifier.padding(start = 8.dp),
                ) { Text("Send") }
            }
        }
    }
    sending?.let { initial ->
        var text by remember(initial) { mutableStateOf(initial) }
        val bytes = text.toByteArray().size
        AlertDialog(
            onDismissRequest = { sending = null },
            title = { Text("Send to the channel?") },
            text = {
                OutlinedTextField(
                    text, { text = it }, Modifier.fillMaxWidth(),
                    supportingText = { Text("$bytes / $MAX_TEXT_BYTES bytes") }, isError = bytes > MAX_TEXT_BYTES,
                )
            },
            confirmButton = {
                Button(onClick = { vm.sendToChannel(text); sending = null }, enabled = text.isNotBlank() && bytes <= MAX_TEXT_BYTES) { Text("Send") }
            },
            dismissButton = { TextButton(onClick = { sending = null }) { Text("Cancel") } },
        )
    }
}
