package com.meshie4LoRa.ui.stats

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meshie4LoRa.data.BROADCAST_NODE
import com.meshie4LoRa.data.MessageEntity
import com.meshie4LoRa.data.MessageOrigin
import com.meshie4LoRa.data.Pronouns
import com.meshie4LoRa.data.nodeIdString
import java.text.DateFormat
import java.util.Date

/** Base stats that have their own detail page. Messages heard / stayed quiet are counts only. */
enum class StatKind { SAID_AUTO, SAID_MANUAL, TALKED, KNOWN, FACTS, FEELINGS }

private val stopWords = setOf(
    "the", "a", "an", "and", "or", "to", "of", "in", "on", "is", "it", "i", "im", "i'm", "you", "u", "me", "my", "so", "that",
    "this", "for", "with", "at", "be", "are", "was", "just", "like", "what", "rn", "lol", "do", "not", "no", "but", "if", "we",
)

@Composable
fun StatDetailScreen(
    kind: StatKind,
    vm: StatsViewModel,
    name: String,
    pronouns: Pronouns,
    onOpenPerson: (nodeNum: Long, memory: Boolean) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)
    val nodes by vm.nodesByNum.collectAsStateWithLifecycle()
    fun nameOf(num: Long?) = num?.let { nodes[it]?.displayName ?: nodeIdString(it) } ?: name
    val title = when (kind) {
        StatKind.SAID_AUTO -> "Said on ${pronouns.possessive} own"
        StatKind.SAID_MANUAL -> "Said by you as $name"
        StatKind.TALKED -> "People talked with"
        StatKind.KNOWN -> "People ${pronouns.subject} ${pronouns.verb("knows", "know")}"
        StatKind.FACTS -> "Facts remembered"
        StatKind.FEELINGS -> "Feelings held"
    }
    Column(modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
            Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
        }
        when (kind) {
            StatKind.SAID_AUTO, StatKind.SAID_MANUAL -> {
                val origin = if (kind == StatKind.SAID_AUTO) MessageOrigin.BOT_AUTO else MessageOrigin.BOT_MANUAL
                val messages by remember(origin) { vm.messagesBy(origin) }.collectAsStateWithLifecycle(emptyList())
                MessageStats(messages, ::nameOf, onOpenPerson, vm::toggleFavorite)
            }
            StatKind.TALKED -> {
                val people by vm.talkedList.collectAsStateWithLifecycle(emptyList())
                val total = people.sumOf { it.count }.coerceAtLeast(1)
                LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { Text("${people.size} people since the last full reset. Tap someone to open your chat with them.", style = MaterialTheme.typography.bodySmall) }
                    itemsIndexed(people) { i, p ->
                        Row(Modifier.fillMaxWidth().clickable { onOpenPerson(p.nodeNum, false) }.padding(vertical = 6.dp)) {
                            Text("#${i + 1}  ${nameOf(p.nodeNum)}", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                            Text("${p.count} msgs \u00b7 %.0f%%".format(100.0 * p.count / total), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            StatKind.KNOWN, StatKind.FACTS, StatKind.FEELINGS -> {
                val memories by vm.memoriesList.collectAsStateWithLifecycle(emptyList())
                val rels by vm.relationshipsList.collectAsStateWithLifecycle(emptyList())
                val me by vm.myNodeNum.collectAsStateWithLifecycle()
                LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    when (kind) {
                        StatKind.FACTS -> {
                            val byPerson = memories.filter { it.nodeNum != me }.groupBy { it.nodeNum }
                            item { Text("${memories.size} facts. Tap one to open that person's memories.", style = MaterialTheme.typography.bodySmall) }
                            byPerson.forEach { (node, facts) ->
                                item(key = "h$node") {
                                    Text(
                                        if (node == null) "About $name ${pronouns.reflexive}" else nameOf(node),
                                        style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp),
                                    )
                                }
                                items(facts, key = { it.id }) { f ->
                                    Text(
                                        (if (f.pinned) "\ud83d\udccc " else "\u2022 ") + f.text + if (f.source.name == "MANUAL") "  (added by you)" else "",
                                        Modifier.fillMaxWidth().clickable(enabled = node != null) { node?.let { onOpenPerson(it, true) } }.padding(vertical = 4.dp),
                                    )
                                }
                            }
                        }
                        StatKind.FEELINGS -> {
                            val held = rels.filter { it.feeling.isNotBlank() && it.nodeNum != me }
                            item { Text("How $name feels about ${held.size} people. Tap to open their memories.", style = MaterialTheme.typography.bodySmall) }
                            items(held, key = { it.nodeNum }) { r ->
                                Card(Modifier.fillMaxWidth().clickable { onOpenPerson(r.nodeNum, true) }) {
                                    Column(Modifier.padding(10.dp)) {
                                        Text(nameOf(r.nodeNum) + if (r.feelingLocked) "  \ud83d\udd12" else "", fontWeight = FontWeight.SemiBold)
                                        Text(r.feeling, style = MaterialTheme.typography.bodyLarge)
                                        val by = when (r.feelingChangedBy?.name) { "USER" -> "set by you"; "MODEL" -> "decided by $name"; else -> null }
                                        listOfNotNull(by, r.feelingReason.takeIf { it.isNotBlank() }).joinToString(" \u00b7 ").takeIf { it.isNotBlank() }
                                            ?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                                    }
                                }
                            }
                        }
                        else -> {
                            val factCounts = memories.mapNotNull { it.nodeNum }.groupingBy { it }.eachCount()
                            val relBy = rels.associateBy { it.nodeNum }
                            val known = (factCounts.keys + rels.filter { it.feeling.isNotBlank() || it.summary.isNotBlank() }.map { it.nodeNum })
                                .filter { it != me }.distinct().sortedByDescending { factCounts[it] ?: 0 }
                            item { Text("Everyone $name has notes on. Tap to open their memories.", style = MaterialTheme.typography.bodySmall) }
                            items(known, key = { it }) { n ->
                                val r = relBy[n]
                                Card(Modifier.fillMaxWidth().clickable { onOpenPerson(n, true) }) {
                                    Column(Modifier.padding(10.dp)) {
                                        Text("${nameOf(n)}  \u00b7  ${factCounts[n] ?: 0} facts", fontWeight = FontWeight.SemiBold)
                                        r?.summary?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                                        r?.feeling?.takeIf { it.isNotBlank() }?.let { Text("feels: $it", style = MaterialTheme.typography.bodySmall) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageStats(
    messages: List<MessageEntity>,
    nameOf: (Long?) -> String,
    onOpenPerson: (Long, Boolean) -> Unit,
    onStar: (MessageEntity) -> Unit,
) {
    // Only content changes (not a star) should redo the summary.
    val summary = remember(messages.size, messages.firstOrNull()?.id) {
        if (messages.isEmpty()) null else {
            val hours = messages.groupingBy { java.time.Instant.ofEpochMilli(it.rxTime).atZone(java.time.ZoneId.systemDefault()).hour }.eachCount()
            val busiest = hours.maxByOrNull { it.value }?.key
            val words = messages.flatMap { it.text.lowercase().split(Regex("[^\\p{L}\\p{N}']+")) }
                .filter { it.length > 2 && it !in stopWords }.groupingBy { it }.eachCount()
                .entries.sortedByDescending { it.value }.take(6).joinToString(", ") { "${it.key} (${it.value})" }
            val dms = messages.count { it.toNode != BROADCAST_NODE }
            "${messages.size} messages \u00b7 avg ${messages.map { it.text.length }.average().toInt()} chars \u00b7 $dms in DMs\n" +
                (busiest?.let { "Busiest hour: %02d:00\n".format(it) } ?: "") +
                (if (words.isNotEmpty()) "Favorite words: $words" else "")
        }
    }
    // Stars flip immediately; the database catches up in the background.
    val starred = remember { androidx.compose.runtime.mutableStateMapOf<Long, Boolean>() }
    LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Text(summary ?: "Nothing yet since the last full reset.", style = MaterialTheme.typography.bodyMedium)
            Text("Tap a DM to open the chat. \u2b50 stars it as a favorite.", style = MaterialTheme.typography.bodySmall)
            HorizontalDivider(Modifier.padding(top = 6.dp))
        }
        items(messages, key = { it.id }) { m ->
            val dm = m.toNode != BROADCAST_NODE
            Row(
                Modifier.fillMaxWidth().clickable(enabled = dm) { onOpenPerson(m.toNode, false) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(m.text)
                    Text(
                        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(m.rxTime)) +
                            if (dm) " \u00b7 DM to ${nameOf(m.toNode)}" else " \u00b7 channel",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                val fav = starred[m.id] ?: m.favorite
                TextButton(onClick = { starred[m.id] = !fav; onStar(m.copy(favorite = fav)) }) { Text(if (fav) "\u2b50" else "\u2606") }
            }
        }
    }
}
