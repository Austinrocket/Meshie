package com.meshie4LoRa.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meshie4LoRa.data.LlmSettings
import com.meshie4LoRa.llm.EngineState
import com.meshie4LoRa.llm.ModelStatus
import com.meshie4LoRa.llm.ModelUi
import com.meshie4LoRa.llm.RamFit
import kotlin.math.roundToInt

private fun gb(bytes: Long?) = bytes?.let { "%.2f GB".format(it / 1e9) } ?: "? GB"

@Composable
fun ModelsScreen(vm: ModelsViewModel, modifier: Modifier = Modifier) {
    var showChat by rememberSaveable { mutableStateOf(false) }
    Toasts(vm.toast, vm::clearToast, long = true)

    if (showChat) {
        val leave = { showChat = false; vm.clearChat() }
        BackHandler { leave() }
        TestChatScreen(vm, onBack = leave, modifier = modifier)
        return
    }

    val models by vm.models.collectAsStateWithLifecycle()
    val activeId by vm.activeModelId.collectAsStateWithLifecycle()
    val engine by vm.engineState.collectAsStateWithLifecycle()
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::importFile) }

    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { ActiveModelCard(vm, engine, models.firstOrNull { it.model.id == activeId }, onTestChat = { showChat = true }) }
        item { SettingsCard(vm) }
        item { Text("Models", style = MaterialTheme.typography.titleLarge) }
        items(models, key = { it.model.id }) { ModelRow(vm, it, isActive = it.model.id == activeId) }
        item { AddModelCard(vm, onImport = { importer.launch(arrayOf("*/*")) }) }
    }
}

@Composable
private fun ActiveModelCard(vm: ModelsViewModel, engine: EngineState, active: ModelUi?, onTestChat: () -> Unit) {
    val bench by vm.bench.collectAsStateWithLifecycle()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Active model", style = MaterialTheme.typography.titleMedium)
            Text(active?.model?.name ?: "None selected — download a model below and tap Use.")
            when (engine) {
                EngineState.Unloaded -> Text("Not loaded", style = MaterialTheme.typography.bodySmall)
                is EngineState.Loading -> {
                    Text("Loading…", style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                is EngineState.Failed -> Text(engine.message, color = MaterialTheme.colorScheme.error)
                is EngineState.Ready -> Text(
                    "${engine.info.description} · ${"%.2f".format(engine.info.params / 1e9)}B params · " +
                        "ctx ${engine.info.ctx}/${engine.info.ctxTrain}" +
                        if (engine.info.hasTemplate) "" else " · no chat template (using ChatML)",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            val ready = engine is EngineState.Ready
            @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onTestChat, enabled = ready) { Text("Test chat") }
                OutlinedButton(onClick = vm::benchmark, enabled = ready) { Text("Benchmark") }
                if (ready) TextButton(onClick = vm::unload) { Text("Unload") }
                else if (active != null && active.status == ModelStatus.Downloaded && engine !is EngineState.Loading) {
                    TextButton(onClick = { vm.activate(active.model.id) }) { Text("Load") }
                }
            }
            bench?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun SettingsCard(vm: ModelsViewModel) {
    val saved by vm.llmSettings.collectAsStateWithLifecycle()
    var expanded by rememberSaveable { mutableStateOf(false) }
    var s by remember(saved) { mutableStateOf(saved) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Generation settings", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide" else "Show") }
            }
            if (!expanded) return@Column
            Text("Context size", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(2048, 4096, 8192).forEach { n ->
                    FilterChip(selected = s.nCtx == n, onClick = { s = s.copy(nCtx = n) }, label = { Text("$n") })
                }
            }
            LabeledSlider("CPU threads", s.nThreads.toFloat(), 1f..8f, 6, "${s.nThreads}") { s = s.copy(nThreads = it.roundToInt()) }
            LabeledSlider("Max reply tokens", s.maxTokens.toFloat(), 32f..512f, 0, "${s.maxTokens}") { s = s.copy(maxTokens = it.roundToInt()) }
            LabeledSlider("Temperature", s.temperature, 0f..1.5f, 0, "%.2f".format(s.temperature)) { s = s.copy(temperature = it) }
            LabeledSlider("Top-p", s.topP, 0.5f..1f, 0, "%.2f".format(s.topP)) { s = s.copy(topP = it) }
            LabeledSlider("Top-k (0 = off)", s.topK.toFloat(), 0f..100f, 0, "${s.topK}") { s = s.copy(topK = it.roundToInt()) }
            LabeledSlider("Min-p", s.minP, 0f..0.3f, 0, "%.2f".format(s.minP)) { s = s.copy(minP = it) }
            LabeledSlider("Repeat penalty", s.repeatPenalty, 1f..1.5f, 0, "%.2f".format(s.repeatPenalty)) { s = s.copy(repeatPenalty = it) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Fast weights (repack)", style = MaterialTheme.typography.labelLarge)
                    Text("Much faster at reading prompts. Uses extra RAM, so only models up to ~1.1 GB use it.", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = s.repackWeights, onCheckedChange = { s = s.copy(repackWeights = it) })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.saveSettings(s) }, enabled = s != saved) { Text("Save") }
                TextButton(onClick = { s = LlmSettings() }) { Text("Defaults") }
            }
            Text("Changing context size, threads or fast weights reloads the model.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int, display: String, onChange: (Float) -> Unit) {
    Column {
        Text("$label: $display", style = MaterialTheme.typography.labelLarge)
        Slider(value = value, onValueChange = onChange, valueRange = range, steps = steps)
    }
}

@Composable
private fun ModelRow(vm: ModelsViewModel, ui: ModelUi, isActive: Boolean) {
    val m = ui.model
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(m.name + if (isActive) "  ✓ active" else "", style = MaterialTheme.typography.titleMedium)
            val fit = when (ui.fit) {
                RamFit.GOOD -> "fits well"
                RamFit.TIGHT -> "tight on RAM"
                RamFit.RISKY -> "may not fit in RAM"
            }
            Text("${gb(m.sizeBytes)} · $fit${if (m.verified) " · verified" else ""}", style = MaterialTheme.typography.bodySmall)
            if (m.description.isNotBlank()) Text(m.description, style = MaterialTheme.typography.bodySmall)
            ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            when (val st = ui.status) {
                is ModelStatus.Downloading -> {
                    val frac = if (st.total > 0) st.bytes.toFloat() / st.total else 0f
                    LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${gb(st.bytes)} / ${gb(st.total)}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        if (st.waiting != null) TextButton(onClick = { vm.download(m.id) }) { Text("Restart") }
                        TextButton(onClick = { vm.cancelDownload(m.id) }) { Text("Cancel") }
                    }
                    st.waiting?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
                ModelStatus.Verifying -> {
                    Text("Verifying checksum…", style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                ModelStatus.Downloaded -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!isActive) Button(onClick = { vm.activate(m.id) }) { Text("Use") }
                    OutlinedButton(onClick = { vm.delete(m.id) }) { Text("Delete") }
                }
                ModelStatus.NotDownloaded -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (m.url != null) Button(onClick = { vm.download(m.id) }) { Text("Download") }
                    if (m.source != com.meshie4LoRa.data.ModelSource.CATALOG) OutlinedButton(onClick = { vm.delete(m.id) }) { Text("Remove") }
                }
            }
        }
    }
}

@Composable
private fun AddModelCard(vm: ModelsViewModel, onImport: () -> Unit) {
    var url by rememberSaveable { mutableStateOf("") }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Add another model", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("Hugging Face .gguf link") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.addFromUrl(url); url = "" }, enabled = url.isNotBlank()) { Text("Add & download") }
                OutlinedButton(onClick = onImport) { Text("Import .gguf file") }
            }
        }
    }
}
