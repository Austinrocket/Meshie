package com.meshie4LoRa.ui

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import com.meshie4LoRa.data.SLEEP_FOREVER
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meshie4LoRa.brain.BotMode
import com.meshie4LoRa.data.BROADCAST_NODE
import com.meshie4LoRa.data.DecisionEntity
import com.meshie4LoRa.data.DecisionOutcome
import com.meshie4LoRa.data.MessageEntity
import com.meshie4LoRa.data.MessageOrigin
import com.meshie4LoRa.data.NodeEntity
import com.meshie4LoRa.data.nodeIdString
import com.meshie4LoRa.radio.LinkState
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

const val MAX_TEXT_BYTES = 200

@Composable
fun FeedScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val messages by vm.messages.collectAsStateWithLifecycle()
    val decisions by vm.decisions.collectAsStateWithLifecycle()
    val nodes by vm.nodes.collectAsStateWithLifecycle()
    val byNum = remember(nodes) { nodes.associateBy { it.nodeNum } }
    val connected = vm.linkState.collectAsStateWithLifecycle().value == LinkState.CONNECTED
    val me by vm.activeCharacterName.collectAsStateWithLifecycle()
    // Channel messages plus every brain decision (channel and DM), newest first.
    val items = remember(messages, decisions) {
        (messages.filter { !it.isDirect }.map { FeedItem.Msg(it) } + decisions.map { FeedItem.Decision(it) })
            .sortedByDescending { it.at }
    }
    val factCounts by vm.factCounts.collectAsStateWithLifecycle()
    // Non-null while picking messages for "Respond to multiple".
    var selecting by remember { mutableStateOf<Set<Long>?>(null) }

    Column(modifier.fillMaxSize().imePadding()) {
        StatusBar(vm)
        BotControlBar(vm)
        val listState = rememberLazyListState()
        // reverseLayout: index 0 is the newest item at the bottom.
        LaunchedEffect(items.firstOrNull()?.key) { if (items.isNotEmpty()) listState.animateScrollToItem(0) }
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            state = listState,
            reverseLayout = true,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(items, key = { it.key }) { item ->
                when (item) {
                    is FeedItem.Msg -> FeedMessage(
                        vm, item.m, byNum, factCounts[item.m.fromNode] ?: 0, selecting,
                        onSelect = { picked -> selecting = picked },
                    )
                    is FeedItem.Decision -> DecisionRow(vm, item.d, byNum)
                }
            }
        }
        selecting?.let { picked ->
            Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Tap messages to include (${picked.size})", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                    TextButton(onClick = { selecting = null }) { Text("Cancel") }
                    Button(
                        onClick = {
                            vm.respondTo(messages.filter { it.id in picked }, "Reply to ${picked.size} message${if (picked.size == 1) "" else "s"}")
                            selecting = null
                        },
                        enabled = picked.isNotEmpty(),
                    ) { Text("Respond") }
                }
            }
        }
        if (vm.botMode.collectAsStateWithLifecycle().value == BotMode.MANUAL) {
            Button(
                onClick = vm::proposeNextThought, enabled = connected,
                modifier = Modifier.fillMaxWidth().padding(8.dp),
            ) { Text("💭 Thought") }
        } else {
            Composer(onSend = { vm.sendText(it) }, enabled = connected, label = "Send as $me")
        }
    }
    ProposalDialog(vm)
}

/** A channel message; tap opens a menu (or toggles it while picking messages), long-press stars it. */
@Composable
private fun FeedMessage(
    vm: MainViewModel, m: MessageEntity, byNum: Map<Long, NodeEntity>, memories: Int,
    selecting: Set<Long>?, onSelect: (Set<Long>?) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val mine = m.origin != MessageOrigin.MESH
    val name = if (mine) "Me" else byNum[m.fromNode]?.displayName ?: nodeIdString(m.fromNode)
    Box {
        MessageRow(
            m, byNum[m.fromNode], byNum[m.toNode], vm::toggleFavorite,
            selected = selecting?.let { m.id in it },
            onTap = { selecting?.let { onSelect(if (m.id in it) it - m.id else it + m.id) } ?: run { menu = true } },
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            Text(name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            HorizontalDivider()
            if (!mine) {
                DropdownMenuItem(text = { Text("🧠 Show memories ($memories)") }, onClick = { menu = false; vm.openPerson(m.fromNode, true) })
                DropdownMenuItem(text = { Text("💬 Respond") }, onClick = { menu = false; vm.respondTo(listOf(m), "Reply to $name") })
            }
            DropdownMenuItem(text = { Text("☑ Respond to multiple…") }, onClick = { menu = false; onSelect(setOf(m.id)) })
            if (!mine) DropdownMenuItem(text = { Text("📣 Call out $name") }, onClick = { menu = false; vm.callOut(m.fromNode, name) })
            DropdownMenuItem(
                text = { Text(if (m.favorite) "⭐ Remove from favorites" else "☆ Add to favorites") },
                onClick = { menu = false; vm.toggleFavorite(m) },
            )
        }
    }
}

/** Editable draft for a reply or callout asked for from the Feed menu. Works in every bot mode. */
@Composable
private fun ProposalDialog(vm: MainViewModel) {
    val p = vm.proposal.collectAsStateWithLifecycle().value ?: return
    var text by remember(p) { mutableStateOf((p as? MainViewModel.Proposal.Ready)?.text.orEmpty()) }
    val bytes = text.toByteArray().size
    AlertDialog(
        onDismissRequest = vm::dismissProposal,
        title = { Text(p.title) },
        text = {
            when (p) {
                is MainViewModel.Proposal.Thinking -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("Thinking…")
                }
                is MainViewModel.Proposal.Failed -> Text("Couldn't come up with one: ${p.why}", color = MaterialTheme.colorScheme.error)
                is MainViewModel.Proposal.Ready -> OutlinedTextField(
                    text, { text = it }, Modifier.fillMaxWidth(),
                    supportingText = { Text("$bytes / $MAX_TEXT_BYTES bytes · goes to the channel") }, isError = bytes > MAX_TEXT_BYTES,
                )
            }
        },
        confirmButton = {
            when (p) {
                is MainViewModel.Proposal.Ready ->
                    Button(onClick = { vm.sendProposal(text) }, enabled = text.isNotBlank() && bytes <= MAX_TEXT_BYTES) { Text("Send") }
                is MainViewModel.Proposal.Failed -> Button(onClick = vm::proposeAgain) { Text("Try again") }
                is MainViewModel.Proposal.Thinking -> Unit
            }
        },
        dismissButton = {
            Row {
                if (p is MainViewModel.Proposal.Ready) TextButton(onClick = vm::proposeAgain) { Text("Another") }
                TextButton(onClick = vm::dismissProposal) { Text(if (p is MainViewModel.Proposal.Thinking) "Cancel" else "Discard") }
            }
        },
    )
}

private sealed interface FeedItem {
    val at: Long
    val key: String

    data class Msg(val m: MessageEntity) : FeedItem {
        override val at get() = m.rxTime
        override val key get() = "m${m.id}"
    }

    data class Decision(val d: DecisionEntity) : FeedItem {
        override val at get() = d.at
        override val key get() = "d${d.id}"
    }
}

@Composable
private fun BotControlBar(vm: MainViewModel) {
    val mode by vm.botMode.collectAsStateWithLifecycle()
    val sleepUntil by vm.sleepUntil.collectAsStateWithLifecycle()
    val status by vm.brainStatus.collectAsStateWithLifecycle()
    val modelReady by vm.modelReady.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(sleepUntil) {
        while (true) {
            now = System.currentTimeMillis()
            delay(30_000)
        }
    }
    val asleep = sleepUntil > now
    val off = sleepUntil == SLEEP_FOREVER

    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            BotMode.entries.forEach { m ->
                FilterChip(selected = mode == m, onClick = { vm.setBotMode(m) }, label = { Text(m.label) })
            }
        }
        val line = when {
            off -> "🔌 Off · switch it on from the Chan page."
            asleep -> "💤 Asleep until ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(sleepUntil))}. Still listening and logging."
            !modelReady -> "⚠ No model loaded. Pick one in Settings → Models."
            status != null -> "🤖 $status"
            else -> "${mode.description}."
        }
        Text(line, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun SleepDialog(onDismiss: () -> Unit, onSleep: (minutes: Int) -> Unit) {
    var amount by rememberSaveable { mutableStateOf("1") }
    var hours by rememberSaveable { mutableStateOf(true) }
    val minutes = amount.trim().toDoubleOrNull()?.let { (if (hours) it * 60 else it).toInt() }?.takeIf { it in 1..MAX_SLEEP_MINUTES }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sleep for…") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = amount, onValueChange = { amount = it }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    isError = minutes == null,
                    supportingText = { Text(minutes?.let { "Wakes at ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(System.currentTimeMillis() + it * 60_000L))}" } ?: "Up to 7 days") },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = !hours, onClick = { hours = false }, label = { Text("minutes") })
                    FilterChip(selected = hours, onClick = { hours = true }, label = { Text("hours") })
                }
                Text("To switch the bot off with no timer, use the switch instead.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { Button(onClick = { minutes?.let(onSleep) }, enabled = minutes != null) { Text("Sleep") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private const val MAX_SLEEP_MINUTES = 7 * 24 * 60

@Composable
private fun DecisionRow(vm: MainViewModel, d: DecisionEntity, byNum: Map<Long, NodeEntity>) {
    val where = if (d.peer == BROADCAST_NODE) "" else " (DM ${byNum[d.peer]?.displayName ?: nodeIdString(d.peer)})"
    when (d.outcome) {
        DecisionOutcome.DRAFT -> {
            var text by remember(d.id) { mutableStateOf(d.reply.orEmpty()) }
            val bytes = text.toByteArray().size
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            ) {
                Column(Modifier.padding(10.dp)) {
                    Text("Draft$where · ${d.reason}", style = MaterialTheme.typography.labelMedium)
                    OutlinedTextField(
                        value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth(),
                        supportingText = { Text("$bytes / $MAX_TEXT_BYTES bytes") }, isError = bytes > MAX_TEXT_BYTES,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.sendDraft(d.id, text) }, enabled = text.isNotBlank() && bytes <= MAX_TEXT_BYTES) { Text("Send") }
                        TextButton(onClick = { vm.discardDraft(d.id) }) { Text("Discard") }
                    }
                }
            }
        }
        DecisionOutcome.SENT -> Unit // the sent message itself shows up in the feed
        else -> {
            val icon = when (d.outcome) {
                DecisionOutcome.OBSERVED -> "👀"
                DecisionOutcome.DISCARDED -> "🗑"
                DecisionOutcome.ERROR -> "⚠"
                else -> "🤐"
            }
            val text = buildString {
                append("$icon ")
                if (d.idle) append("idle · ")
                append(d.reason).append(where)
                d.reply?.let {
                    append(
                        when (d.outcome) {
                            DecisionOutcome.OBSERVED -> " → would say: \"$it\""
                            DecisionOutcome.SKIPPED -> "\nModel's words: \"$it\""
                            else -> " → \"$it\""
                        }
                    )
                }
                append(" · %.1f s".format(d.latencyMs / 1000.0))
            }
            Text(
                (if (d.favorite) "⭐ " else "") + text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            if (d.outcome == DecisionOutcome.OBSERVED && !d.reply.isNullOrBlank()) {
                Row(Modifier.padding(start = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { vm.sendDraft(d.id, d.reply) }) { Text("Send now") }
                    TextButton(onClick = { vm.toggleFavorite(d) }) { Text(if (d.favorite) "⭐ Unstar" else "☆ Star") }
                }
            }
        }
    }
}

@Composable
fun MessageList(messages: List<MessageEntity>, byNum: Map<Long, NodeEntity>, onFavorite: (MessageEntity) -> Unit, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    LaunchedEffect(messages.firstOrNull()?.id) { if (messages.isNotEmpty()) listState.animateScrollToItem(0) }
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        state = listState,
        reverseLayout = true,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(messages, key = { it.id }) { MessageRow(it, byNum[it.fromNode], byNum[it.toNode], onFavorite) }
    }
}

@Composable
fun StatusBar(vm: MainViewModel) {
    val link by vm.linkState.collectAsStateWithLifecycle()
    val ready by vm.configComplete.collectAsStateWithLifecycle()
    val name by vm.deviceName.collectAsStateWithLifecycle()
    val myNode by vm.myNode.collectAsStateWithLifecycle()
    val channels by vm.channels.collectAsStateWithLifecycle()
    val opChannel by vm.operatingChannel.collectAsStateWithLifecycle()
    val chName = channels.firstOrNull { it.index == opChannel }?.name ?: "ch $opChannel"
    val status = when {
        link == LinkState.CONNECTED && ready -> "Connected"
        link == LinkState.CONNECTED -> "Syncing…"
        else -> link.name.lowercase().replaceFirstChar(Char::uppercase)
    }
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Text(
            "${myNode?.longName ?: name ?: "No radio"} · $status · #$chName",
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

/** Long-press stars/unstars a message (favorites feed "From favorites" on the Chan page). [selected] is non-null while picking messages. */
@Composable
private fun MessageRow(
    m: MessageEntity, from: NodeEntity?, to: NodeEntity?, onFavorite: (MessageEntity) -> Unit,
    selected: Boolean? = null, onTap: (() -> Unit)? = null,
) {
    val mine = m.origin != MessageOrigin.MESH
    val colors = when {
        selected == true -> CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
        mine -> CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        else -> CardDefaults.cardColors()
    }
    Card(
        colors = colors,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp).combinedClickable(
            onClick = { onTap?.invoke() },
            onLongClick = { onFavorite(m) },
        ),
    ) {
        Column(Modifier.padding(10.dp)) {
            val sender = if (mine) "Me (${m.origin.name.lowercase()})" else from?.displayName ?: nodeIdString(m.fromNode)
            val target = if (m.isDirect) " → ${to?.displayName ?: nodeIdString(m.toNode)}" else ""
            val check = when (selected) { true -> "☑ "; false -> "☐ "; null -> "" }
            Text(check + (if (m.favorite) "⭐ " else "") + "$sender$target", style = MaterialTheme.typography.labelMedium)
            Text(m.text, style = MaterialTheme.typography.bodyLarge)
            val meta = buildList {
                add(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(m.rxTime)))
                m.hopsAway?.let { add("$it hops") }
                m.rxSnr?.let { add("SNR %.1f".format(it)) }
                m.rxRssi?.let { add("RSSI $it") }
                if (m.viaMqtt) add("MQTT")
            }.joinToString(" · ")
            Text(meta, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
fun Composer(onSend: (String) -> Unit, enabled: Boolean, label: String) {
    var text by rememberSaveable { mutableStateOf("") }
    val bytes = text.toByteArray(Charsets.UTF_8).size
    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.weight(1f),
            label = { Text(label) },
            supportingText = { Text("$bytes / $MAX_TEXT_BYTES bytes") },
            isError = bytes > MAX_TEXT_BYTES,
        )
        Button(
            onClick = { onSend(text.trim()); text = "" },
            enabled = enabled && text.isNotBlank() && bytes <= MAX_TEXT_BYTES,
            modifier = Modifier.padding(start = 8.dp),
        ) { Text("Send") }
    }
}
