package com.meshie4LoRa.ui

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meshie4LoRa.data.ChangedBy
import com.meshie4LoRa.data.MemoryEntity
import com.meshie4LoRa.data.MemorySource

@Composable
fun MemoryToasts(vm: MemoryViewModel) = Toasts(vm.toast, vm::clearToast)

/** Memory tab of a person's page: feeling (+ lock), summary, facts, clearing. */
@Composable
fun PersonMemoryPanel(vm: MemoryViewModel, nodeNum: Long, name: String, modifier: Modifier = Modifier) {
    MemoryToasts(vm)
    val rel by remember(nodeNum) { vm.relationship(nodeNum) }.collectAsState(initial = null)
    val facts by remember(nodeNum) { vm.facts(nodeNum) }.collectAsState(initial = emptyList())
    val log by remember(nodeNum) { vm.feelingLog(nodeNum) }.collectAsState(initial = emptyList())
    var feeling by remember(rel?.feeling) { mutableStateOf(rel?.feeling.orEmpty()) }
    var summary by remember(rel?.summary) { mutableStateOf(rel?.summary.orEmpty()) }
    var newFact by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    val locked = rel?.feelingLocked == true

    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CooldownCard(vm, nodeNum, name)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("How the character feels about $name", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    feeling, { feeling = it }, Modifier.fillMaxWidth(),
                    placeholder = { Text("e.g. head over heels in love, a bit annoyed, sad they left") },
                    supportingText = { Text("${feeling.length} / 100") }, isError = feeling.length > 100,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Lock feeling")
                        Text("When locked, the model can't change it.", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = locked, onCheckedChange = { vm.setLocked(nodeNum, it) })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.setFeeling(nodeNum, feeling, locked) }, enabled = feeling != rel?.feeling.orEmpty() && feeling.length <= 100) {
                        Text("Save")
                    }
                    OutlinedButton(onClick = { vm.setFeeling(nodeNum, feeling, true) }, enabled = feeling.length <= 100 && !(locked && feeling == rel?.feeling)) {
                        Text("Save & lock")
                    }
                }
                rel?.takeIf { it.feelingChangedBy == ChangedBy.MODEL && it.feelingReason.isNotBlank() }?.let {
                    Text("Model's reason: ${it.feelingReason}", style = MaterialTheme.typography.bodySmall)
                }
                if (log.isNotEmpty()) {
                    HorizontalDivider()
                    Text("History", style = MaterialTheme.typography.labelLarge)
                    log.take(8).forEach { e ->
                        Text(
                            "${DateUtils.getRelativeTimeSpanString(e.at)} · ${if (e.changedBy == ChangedBy.USER) "you" else "model"}: " +
                                "\"${e.oldFeeling.ifBlank { "—" }}\" → \"${e.newFeeling.ifBlank { "—" }}\"" +
                                if (e.changedBy == ChangedBy.MODEL && e.reason.isNotBlank()) " (${e.reason})" else "",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("What the character remembers", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(summary, { summary = it }, Modifier.fillMaxWidth(), placeholder = { Text("Running summary of your chats") }, minLines = 2)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.setSummary(nodeNum, summary) }, enabled = summary != rel?.summary.orEmpty()) { Text("Save") }
                    TextButton(onClick = { vm.updateNow(nodeNum) }) { Text("Update from chat now") }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Facts (${facts.size})", style = MaterialTheme.typography.titleMedium)
                facts.forEach { f -> FactRow(f, onPin = { vm.togglePin(f) }, onDelete = { vm.delete(f.id) }) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(newFact, { newFact = it }, Modifier.weight(1f), placeholder = { Text("Add a fact") }, singleLine = true)
                    TextButton(onClick = { vm.addFact(nodeNum, newFact); newFact = "" }, enabled = newFact.isNotBlank()) { Text("Add") }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { confirm = "Clear the model's automatic facts and summary about $name? Manual and pinned facts stay." to { vm.clearAutoFor(nodeNum) } }) {
                Text("Clear auto memories")
            }
            TextButton(onClick = { confirm = "Forget everything about $name, including the feeling and its lock?" to { vm.forget(nodeNum) } }) {
                Text("Forget everything", color = MaterialTheme.colorScheme.error)
            }
        }
    }
    ConfirmDialog(confirm) { confirm = null }
}

/** Minimum time between replies to one person; mainly for other bots on the mesh. */
@Composable
private fun CooldownCard(vm: MemoryViewModel, nodeNum: Long, name: String) {
    val saved by remember(nodeNum) { vm.cooldown(nodeNum) }.collectAsState(initial = null)
    val current = saved ?: 0L
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Reply cooldown", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                DurationButton(
                    current, "Reply cooldown", "How long to ignore $name after answering. 0 turns it off.",
                    onSave = { vm.setCooldown(nodeNum, it) },
                )
            }
            Text(
                "After answering $name, the character ignores $name's messages for this long. Use it for other bots, " +
                    "so three or more of them can't chat in an endless loop.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
fun FactRow(f: MemoryEntity, onPin: () -> Unit, onDelete: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text((if (f.pinned) "📌 " else "") + f.text)
            Text(
                "${if (f.source == MemorySource.AUTO) "learned" else "added by you"} ${DateUtils.getRelativeTimeSpanString(f.updatedAt)}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        TextButton(onClick = onPin) { Text(if (f.pinned) "Unpin" else "Pin") }
        TextButton(onClick = onDelete) { Text("Delete") }
    }
}

@Composable
fun ConfirmDialog(confirm: Pair<String, () -> Unit>?, onDismiss: () -> Unit) {
    confirm ?: return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Are you sure?") },
        text = { Text(confirm.first) },
        confirmButton = { TextButton(onClick = { confirm.second(); onDismiss() }) { Text("Yes", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Chan page card: memories about the character itself + global bulk actions. */
@Composable
fun CharacterMemoryCard(vm: MemoryViewModel) {
    MemoryToasts(vm)
    val self by vm.selfMemories.collectAsStateWithLifecycle()
    val total by vm.memoryCount.collectAsStateWithLifecycle()
    var newFact by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Memory", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${self.size} about the character, ${total - self.size} about people (those are on each person's page). " +
                            "Shared by all characters.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide" else "Show") }
            }
            if (!expanded) return@Column
            Text("Things the character remembers about itself and the world", style = MaterialTheme.typography.labelLarge)
            self.forEach { f -> FactRow(f, onPin = { vm.togglePin(f) }, onDelete = { vm.delete(f.id) }) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(newFact, { newFact = it }, Modifier.weight(1f), placeholder = { Text("e.g. I won the karaoke contest last night") }, singleLine = true)
                TextButton(onClick = { vm.addFact(null, newFact); newFact = "" }, enabled = newFact.isNotBlank()) { Text("Add") }
            }
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            Text("Clear", style = MaterialTheme.typography.labelLarge)
            Text(
                "Every option also restarts the conversation for the model: chat from before now is hidden from it, " +
                    "your earlier manual messages stop being style examples, and the stats count from zero. " +
                    "Your chat log stays visible to you. Persona, rules, examples and event notes are never touched.",
                style = MaterialTheme.typography.bodySmall,
            )
            ClearOption(
                "All auto memories",
                "Deletes facts the model wrote down itself, every summary, and feelings you haven't locked. " +
                    "Keeps facts you added, pinned facts and locked feelings.",
            ) { confirm = "Clear all automatic facts, summaries and unlocked feelings? Manual, pinned and locked items stay." to { vm.clearAllAuto() } }
            ClearOption(
                "Everything not pinned",
                "Like above, but also deletes facts you added yourself unless they're pinned. Keeps pinned facts and locked feelings.",
            ) { confirm = "Clear everything that isn't pinned (manual facts too), plus summaries and unlocked feelings?" to { vm.clearAllUnpinned() } }
            ClearOption(
                "Wipe everything",
                "Deletes every fact (pinned too), summary, feeling, lock and the feeling history, for everyone. Favorites stay.",
                danger = true,
            ) { confirm = "Wipe ALL memories, summaries, feelings and locks for everyone?" to { vm.wipeAll() } }
            ClearOption(
                "Full reset (forget everything)",
                "Wipe everything, plus clears favorites and position history and reloads the model so nothing is cached. " +
                    "Use it to start the persona completely fresh.",
                danger = true,
            ) {
                confirm = ("Full reset: wipes all memories, summaries and feelings, hides all earlier chat and your past " +
                    "messages from the model, clears favorites and position history, and reloads the model. Only the persona, " +
                    "rules, examples and event notes you wrote stay. The chat log stays visible to you.") to { vm.fullReset() }
            }
        }
    }
    ConfirmDialog(confirm) { confirm = null }
}

@Composable
private fun ClearOption(title: String, explanation: String, danger: Boolean = false, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        TextButton(onClick = onClick, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 0.dp)) {
            Text(title, color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
        }
        Text(explanation, style = MaterialTheme.typography.bodySmall)
    }
}
