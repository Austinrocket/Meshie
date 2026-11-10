package com.meshie4LoRa.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meshie4LoRa.brain.BotBrain
import com.meshie4LoRa.brain.DataField
import com.meshie4LoRa.data.CharacterEntity
import com.meshie4LoRa.mesh.MAX_LONG_NAME_BYTES
import com.meshie4LoRa.mesh.MAX_SHORT_NAME_BYTES
import kotlin.math.roundToInt

/** Context settings (Settings tab → Context): how the model sees the mesh and how it behaves. */
@Composable
fun ContextSettings(vm: CharacterViewModel, modifier: Modifier = Modifier) {
    CharacterToasts(vm)
    Column(modifier.verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        RateLimitCard(vm)
        BehaviorCard(vm)
        TemplatesCard(vm)
        DataAccessCard(vm)
    }
}

/** The persona's live material on the Chan page: what's happening now, what it remembers, and the exact prompts. */
@Composable
fun ChanSettings(vm: CharacterViewModel, memoryVm: MemoryViewModel) {
    CharacterToasts(vm)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        EventNotesCard(vm)
        CharacterMemoryCard(memoryVm)
        PromptPreviewCard(vm)
    }
}

@Composable
private fun CharacterToasts(vm: CharacterViewModel) = Toasts(vm.toast, vm::clearToast)

@Composable
internal fun CharacterEditor(
    initial: CharacterEntity, onSave: (CharacterEntity) -> Unit, onCancel: () -> Unit, modifier: Modifier,
    onExport: (CharacterEntity) -> Unit = {},
    /** Optional "let the brain write it" slot; it hands back a generated persona to fill the form with. */
    writer: (@Composable (apply: (com.meshie4LoRa.brain.GeneratedPersona) -> Unit) -> Unit)? = null,
) {
    var c by remember(initial.id) { mutableStateOf(initial) }
    val longBytes = c.longName.trim().toByteArray().size
    val shortBytes = c.shortName.trim().toByteArray().size
    val valid = longBytes in 1..MAX_LONG_NAME_BYTES && shortBytes in 1..MAX_SHORT_NAME_BYTES && c.persona.isNotBlank()

    Column(modifier.fillMaxSize().imePadding()) {
        Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
            Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onCancel) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                Text(if (initial.id == 0L) "New persona" else "Edit persona", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Button(onClick = { onSave(c) }, enabled = valid, modifier = Modifier.padding(end = 8.dp)) { Text("Save") }
            }
        }
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            writer?.invoke { g ->
                c = c.copy(
                    longName = g.name, shortName = g.shortName, pronouns = g.pronouns, persona = g.persona,
                    examples = g.examples.joinToString("\n"),
                    avatar = com.meshie4LoRa.ui.stats.Avatar.PRESETS.firstOrNull { it.first == g.look }?.second?.toJson() ?: c.avatar,
                )
            }
            OutlinedTextField(
                c.longName, { c = c.copy(longName = it) }, Modifier.fillMaxWidth(),
                label = { Text("Long name (shown on the mesh)") }, singleLine = true,
                supportingText = { Text("$longBytes / $MAX_LONG_NAME_BYTES bytes") }, isError = longBytes !in 1..MAX_LONG_NAME_BYTES,
            )
            OutlinedTextField(
                c.shortName, { c = c.copy(shortName = it) }, Modifier.fillMaxWidth(),
                label = { Text("Short name") }, singleLine = true,
                supportingText = { Text("$shortBytes / $MAX_SHORT_NAME_BYTES bytes") }, isError = shortBytes !in 1..MAX_SHORT_NAME_BYTES,
            )
            OutlinedTextField(
                c.nicknames, { c = c.copy(nicknames = it) }, Modifier.fillMaxWidth(),
                label = { Text("Nicknames (comma separated, count as a mention)") }, singleLine = true,
            )
            Text("Pronouns", style = MaterialTheme.typography.labelLarge)
            @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                com.meshie4LoRa.data.Pronouns.entries.forEach { p ->
                    FilterChip(selected = c.pronouns == p, onClick = { c = c.copy(pronouns = p) }, label = { Text(p.label) })
                }
            }
            OutlinedTextField(c.persona, { c = c.copy(persona = it) }, Modifier.fillMaxWidth(), label = { Text("Persona") }, minLines = 4)
            OutlinedTextField(c.rules, { c = c.copy(rules = it) }, Modifier.fillMaxWidth(), label = { Text("Style rules (one per line)") }, minLines = 2)
            OutlinedTextField(c.examples, { c = c.copy(examples = it) }, Modifier.fillMaxWidth(), label = { Text("Example lines (one per line)") }, minLines = 2)
            Text("Eagerness to join conversations: ${c.eagerness}", style = MaterialTheme.typography.labelLarge)
            Slider(value = c.eagerness.toFloat(), onValueChange = { c = c.copy(eagerness = it.roundToInt()) }, valueRange = 0f..100f)
            OutlinedTextField(
                c.idlePrompt, { c = c.copy(idlePrompt = it) }, Modifier.fillMaxWidth(),
                label = { Text("Idle chatter ideas (optional)") }, minLines = 2,
            )
            OutlinedTextField(
                c.bannedPhrases, { c = c.copy(bannedPhrases = it) }, Modifier.fillMaxWidth(),
                label = { Text("Never say (one per line)") }, minLines = 2,
                supportingText = { Text("Replies containing these are thrown away. Not shown to the model, so it isn't tempted by them.") },
            )
            if (initial.id != 0L) {
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                Text("Share", style = MaterialTheme.typography.labelLarge)
                Text(
                    "Saves a file with this persona as shown above, its look, and what it remembers about itself. " +
                        "Nothing about people on your mesh is included. Friends can load it with Import on the new persona page.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(onClick = { onExport(c) }) { Text("📤 Export this persona") }
            }
        }
    }
}

/** Hard cap on how often we send, for busy channels and multi-bot meshes. */
@Composable
private fun RateLimitCard(vm: CharacterViewModel) {
    val saved by vm.behavior.collectAsStateWithLifecycle()
    var count by remember(saved.rateLimitCount) { mutableStateOf(saved.rateLimitCount) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Rate limit", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (count > 0) DurationButton(
                    saved.rateLimitWindowSec.toLong(), "Rate limit window", "At most $count message${if (count == 1) "" else "s"} in this long.",
                    onSave = { vm.setBehavior(saved.copy(rateLimitWindowSec = it.toInt())) }, allowZero = false, maxSeconds = 24 * 3600L,
                )
            }
            Text(
                if (count == 0) "Off: the character can send as often as it likes."
                else "At most $count message${if (count == 1) "" else "s"} per ${com.meshie4LoRa.data.humanDuration(saved.rateLimitWindowSec.toLong())}.",
                style = MaterialTheme.typography.labelLarge,
            )
            Slider(
                count.toFloat(), { count = it.roundToInt() }, valueRange = 0f..30f,
                onValueChangeFinished = { if (count != saved.rateLimitCount) vm.setBehavior(saved.copy(rateLimitCount = count)) },
            )
            Text(
                "Counts everything you send, but only holds back automatic messages. Held-back replies show in the Feed " +
                    "with Send now, and your own messages always go out.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun BehaviorCard(vm: CharacterViewModel) {
    val saved by vm.behavior.collectAsStateWithLifecycle()
    var b by remember(saved) { mutableStateOf(saved) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Behavior", style = MaterialTheme.typography.titleMedium)
                    Text("Reply timing, chat history, persona strength. Idle chatter timing is on the Chan page.", style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide" else "Show") }
            }
            if (!expanded) return@Column
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Wait before reacting", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                DurationButton(
                    b.debounceSec.toLong(), "Wait before reacting", "Time to let people finish a burst of messages. 0 answers right away.",
                    onSave = { b = b.copy(debounceSec = it.toInt()); vm.setBehavior(b) }, maxSeconds = 24 * 3600L,
                )
            }
            Text("Gives people time to finish a burst of messages before the character answers.", style = MaterialTheme.typography.bodySmall)
            Text(
                if (b.personaStrength <= 1.01f) "Persona strength: normal" else "Persona strength: %.1f×".format(b.personaStrength),
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                "Pushes every message harder toward the persona by comparing with what the model would say without it. " +
                    "Above normal, each message takes about twice as long. Try 1.5–2; very high values can get weird.",
                style = MaterialTheme.typography.bodySmall,
            )
            Slider(b.personaStrength, { b = b.copy(personaStrength = (it * 4).roundToInt() / 4f) }, valueRange = 1f..3f)
            Text("Messages of context: ${b.historySize}", style = MaterialTheme.typography.labelLarge)
            Slider(b.historySize.toFloat(), { b = b.copy(historySize = it.roundToInt()) }, valueRange = 0f..30f)
            Text("Only messages from the last ${b.historyMinutes} min", style = MaterialTheme.typography.labelLarge)
            Slider(b.historyMinutes.toFloat(), { b = b.copy(historyMinutes = it.roundToInt()) }, valueRange = 2f..120f)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Second-pass review", style = MaterialTheme.typography.labelLarge)
                    Text("The model checks each reply before it's sent. Slower, fewer weird replies.", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = b.reviewPass, onCheckedChange = { b = b.copy(reviewPass = it) })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Learn from my messages", style = MaterialTheme.typography.labelLarge)
                    Text("Your last ${com.meshie4LoRa.brain.CharacterRepository.MANUAL_EXAMPLES} manual messages become style examples.", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = b.learnFromManual, onCheckedChange = { b = b.copy(learnFromManual = it) })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("React when someone comes nearby", style = MaterialTheme.typography.labelLarge)
                    Text(
                        "Posts once when someone moves to within ${BotBrain.ARRIVAL_RADIUS_M.toInt()} m (at most every 2 h per person). " +
                            "Needs \"Use my radio's GPS\" in Model data access.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = b.greetArrivals, onCheckedChange = { b = b.copy(greetArrivals = it) })
            }
            Text(
                if (b.loopGuard == 0) "Bot-loop guard: off" else "Bot-loop guard: stop after ${b.loopGuard} replies in a row to one node",
                style = MaterialTheme.typography.labelLarge,
            )
            Slider(b.loopGuard.toFloat(), { b = b.copy(loopGuard = it.roundToInt()) }, valueRange = 0f..10f)
            Button(onClick = { vm.setBehavior(b) }, enabled = b != saved) { Text("Save") }
        }
    }
}

@Composable
private fun TemplatesCard(vm: CharacterViewModel) {
    val templates by vm.templates.collectAsStateWithLifecycle()
    val phrases by vm.phrases.collectAsStateWithLifecycle()
    var expanded by rememberSaveable { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Prompt templates", style = MaterialTheme.typography.titleMedium)
                    Text("Every word the model sees. {key} inserts a value; {#key}…{/key} only shows if it has one.", style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide" else "Show") }
            }
            if (!expanded) return@Column
            com.meshie4LoRa.brain.PromptTemplate.entries.forEach { t ->
                val saved = templates[t] ?: t.default
                var text by remember(t, saved) { mutableStateOf(saved) }
                HorizontalDivider()
                Text(t.title, style = MaterialTheme.typography.labelLarge)
                Text(t.help, style = MaterialTheme.typography.bodySmall)
                Text("Values: " + (t.placeholders + listOf("they", "them", "their", "themself")).joinToString(" ") { "{$it}" }, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                OutlinedTextField(
                    text, { text = it }, Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), minLines = 3,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.setTemplate(t, text) }, enabled = text != saved) { Text("Save") }
                    TextButton(onClick = { vm.setTemplate(t, null) }, enabled = saved != t.default) { Text("Reset to default") }
                }
            }
            HorizontalDivider()
            Text("Phrases", style = MaterialTheme.typography.titleSmall)
            Text("Small fixed bits the app puts around data. Leave one empty to drop that line entirely.", style = MaterialTheme.typography.bodySmall)
            com.meshie4LoRa.brain.PromptPhrase.entries.forEach { ph ->
                val saved = phrases[ph] ?: ph.default
                var text by remember(ph, saved) { mutableStateOf(saved) }
                Text(ph.title + if (ph.placeholders.isEmpty()) "" else "  ·  " + ph.placeholders.joinToString(" ") { "{$it}" }, style = MaterialTheme.typography.labelMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        text, { text = it }, Modifier.weight(1f),
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    )
                    TextButton(onClick = { vm.setPhrase(ph, text) }, enabled = text != saved) { Text("Save") }
                    TextButton(onClick = { vm.setPhrase(ph, null) }, enabled = saved != ph.default) { Text("Reset") }
                }
            }
        }
    }
}

@Composable
private fun EventNotesCard(vm: CharacterViewModel) {
    val saved by vm.eventNotes.collectAsStateWithLifecycle()
    var text by rememberSaveable(saved) { mutableStateOf(saved) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Event notes", style = MaterialTheme.typography.titleMedium)
            Text("What's going on today. Shared by all characters.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), placeholder = { Text("e.g. We're at Riverside Jazz Fest, main stage at 8 pm") }, minLines = 2)
            Button(onClick = { vm.setEventNotes(text.trim()) }, enabled = text.trim() != saved) { Text("Save") }
        }
    }
}

@Composable
private fun DataAccessCard(vm: CharacterViewModel) {
    val enabled by vm.dataFields.collectAsStateWithLifecycle()
    var expanded by rememberSaveable { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Model data access", style = MaterialTheme.typography.titleMedium)
                    Text("${enabled.size} of ${DataField.entries.size} mesh fields visible to the model", style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide" else "Show") }
            }
            if (!expanded) return@Column
            DataField.entries.groupBy { it.group }.forEach { (group, fields) ->
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                Text(group, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                fields.forEach { f ->
                    val blocked = f in DataField.needsOwnLocation && DataField.OWN_LOCATION !in enabled
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(f.label, color = if (blocked) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface)
                            if (blocked) Text("Needs \"Use my radio's GPS\"", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                            if (f.note.isNotEmpty()) Text(f.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                        Switch(checked = f in enabled && !blocked, enabled = !blocked, onCheckedChange = { vm.setField(f, it) })
                    }
                }
            }
        }
    }
}

@Composable
private fun PromptPreviewCard(vm: CharacterViewModel) {
    val nodes by vm.nodes.collectAsStateWithLifecycle()
    val myNum by vm.myNodeNum.collectAsStateWithLifecycle()
    val preview by vm.preview.collectAsStateWithLifecycle()
    val sample by vm.sample.collectAsStateWithLifecycle()
    var target by rememberSaveable { mutableStateOf<Long?>(null) }
    var kind by rememberSaveable { mutableStateOf(com.meshie4LoRa.brain.PromptKind.REPLY) }
    var raw by rememberSaveable { mutableStateOf(false) }
    val people = remember(nodes, myNum) { nodes.filter { it.nodeNum != myNum && it.lastHeard > 0 }.sortedByDescending { it.lastHeard }.take(8) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Prompt preview", style = MaterialTheme.typography.titleMedium)
            Text(
                "Exactly what the model gets for each kind of call. Everything here comes from your persona, templates, " +
                    "phrases, data access settings, memories and the chat; turn on Raw to see the model's own wrapper too.",
                style = MaterialTheme.typography.bodySmall,
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(com.meshie4LoRa.brain.PromptKind.entries) { k ->
                    FilterChip(selected = kind == k, onClick = { kind = k; vm.buildPreview(target, k) }, label = { Text(k.label) })
                }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item { FilterChip(selected = target == null, onClick = { target = null }, label = { Text("Channel") }) }
                items(people, key = { it.nodeNum }) { n ->
                    FilterChip(selected = target == n.nodeNum, onClick = { target = n.nodeNum }, label = { Text(n.displayName) })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.buildPreview(target, kind) }) { Text("Show prompt") }
                OutlinedButton(onClick = { vm.generateSample(target) }) { Text("Sample reply") }
            }
            sample?.let {
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.small) {
                    Text(it, Modifier.padding(8.dp))
                }
                Text("Sample replies are never transmitted.", style = MaterialTheme.typography.bodySmall)
            }
            preview?.let { p ->
                Text("${p.kind.label} · ~${p.approxTokens} tokens", style = MaterialTheme.typography.labelMedium)
                if (p.messages.isEmpty()) Text("Nothing to send yet (e.g. no chat with that person).", style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Raw (exactly what the model reads)", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    Switch(checked = raw && p.raw != null, enabled = p.raw != null, onCheckedChange = { raw = it })
                }
                if (p.raw == null) Text("Load a model to see the raw text with its chat template.", style = MaterialTheme.typography.bodySmall)
                SelectionContainer {
                    Column {
                        if (raw && p.raw != null) {
                            Text(p.raw, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                        } else p.messages.forEachIndexed { i, m ->
                            if (i > 0) HorizontalDivider(Modifier.padding(vertical = 6.dp))
                            Text(m.role.uppercase(), style = MaterialTheme.typography.labelLarge)
                            Text(m.content, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}
