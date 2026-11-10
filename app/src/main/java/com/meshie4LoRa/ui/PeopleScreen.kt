package com.meshie4LoRa.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Badge
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meshie4LoRa.data.NodeEntity
import com.meshie4LoRa.data.nodeIdString
import com.meshie4LoRa.mesh.Geo
import com.meshie4LoRa.radio.LinkState
import android.text.format.DateUtils

private data class PersonRow(val node: NodeEntity, val distanceM: Double?, val direction: String?, val uncertaintyM: Double)

@Composable
fun PeopleScreen(vm: MainViewModel, memoryVm: MemoryViewModel, modifier: Modifier = Modifier) {
    val nodes by vm.nodes.collectAsStateWithLifecycle()
    val myNum by vm.myNodeNum.collectAsStateWithLifecycle()
    val feelings by vm.feelings.collectAsStateWithLifecycle()
    var selected by rememberSaveable { mutableStateOf<Long?>(null) }
    var openOnMemory by rememberSaveable { mutableStateOf(false) }
    val openRequest by vm.openPerson.collectAsStateWithLifecycle()
    LaunchedEffect(openRequest) {
        openRequest?.let {
            selected = it.nodeNum
            openOnMemory = it.memory
            vm.consumeOpenPerson()
        }
    }

    val rows = remember(nodes, myNum) {
        val me = nodes.firstOrNull { it.nodeNum == myNum }
        nodes.filter { it.nodeNum != myNum }.map { n ->
            if (me != null && me.hasPosition && n.hasPosition) {
                val d = Geo.distanceMeters(me.latitude!!, me.longitude!!, n.latitude!!, n.longitude!!)
                val b = Geo.bearingDegrees(me.latitude, me.longitude, n.latitude, n.longitude)
                val u = Geo.precisionMeters(me.positionPrecisionBits) + Geo.precisionMeters(n.positionPrecisionBits)
                PersonRow(n, d, Geo.compass(b), u)
            } else PersonRow(n, null, null, 0.0)
        }.sortedWith(compareBy<PersonRow> { it.distanceM == null }.thenBy { it.distanceM }.thenByDescending { it.node.lastHeard })
    }

    val unread by vm.unreadByNode.collectAsStateWithLifecycle()
    val dmPeers by vm.dmPeers.collectAsStateWithLifecycle()
    val factCounts by vm.factCounts.collectAsStateWithLifecycle()
    val knownPeople by vm.knownPeople.collectAsStateWithLifecycle()
    var collapsed by rememberSaveable { mutableStateOf(arrayListOf(Section.STALE.name)) }
    var alphabetical by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    val byDistance = compareBy<PersonRow, Double?>(nullsLast()) { it.distanceM }.thenByDescending { it.node.lastHeard }
    val sections = remember(rows, dmPeers, factCounts, knownPeople, feelings) {
        val staleBefore = System.currentTimeMillis() - STALE_AFTER_MS
        val (dm, rest) = rows.partition { it.node.nodeNum in dmPeers }
        val (known, others) = rest.partition { it.node.nodeNum in knownPeople }
        val (old, fresh) = others.partition { it.node.lastHeard < staleBefore }
        val mostKnown = compareByDescending<PersonRow> { factCounts[it.node.nodeNum] ?: 0 }
            .thenByDescending { !feelings[it.node.nodeNum].isNullOrBlank() }
        mapOf(
            Section.CONVERSATIONS to dm.sortedWith(byDistance),
            Section.KNOWN to known.sortedWith(mostKnown.then(byDistance)),
            Section.EVERYONE to fresh.sortedWith(byDistance),
            Section.STALE to old.sortedWith(byDistance),
        )
    }

    val selectedRow = rows.firstOrNull { it.node.nodeNum == selected }
    if (selectedRow != null) {
        BackHandler { selected = null }
        PersonScreen(vm, memoryVm, selectedRow, onBack = { selected = null; openOnMemory = false }, startOnMemory = openOnMemory, modifier = modifier)
        return
    }

    Column(modifier.fillMaxSize()) {
        StatusBar(vm)
        LazyColumn(Modifier.weight(1f)) {
            sections.forEach { (section, list) ->
                if (list.isEmpty() && section != Section.EVERYONE) return@forEach
                val open = section.name !in collapsed
                val az = section.name in alphabetical
                item(key = "h-${section.name}") {
                    SectionHeader(
                        "${if (open) "▾" else "▸"} ${section.title} (${list.size})",
                        sortLabel = if (az) "A–Z" else section.defaultSort,
                        onClick = { collapsed = ArrayList(if (open) collapsed + section.name else collapsed - section.name) },
                        onSort = { alphabetical = ArrayList(if (az) alphabetical - section.name else alphabetical + section.name) },
                    )
                }
                if (open) {
                    val shown = if (az) list.sortedBy { it.node.displayName.lowercase() } else list
                    items(shown, key = { "${section.name}-${it.node.nodeNum}" }) { row ->
                        PersonListItem(row, unread[row.node.nodeNum], feelings[row.node.nodeNum], factCounts[row.node.nodeNum]) { selected = row.node.nodeNum }
                    }
                }
            }
        }
    }
}

private enum class Section(val title: String, val defaultSort: String) {
    CONVERSATIONS("Conversations", "nearest"),
    KNOWN("People I know about", "most known"),
    EVERYONE("Everyone else", "nearest"),
    STALE("Not heard in 6+ months", "nearest"),
}

private const val STALE_AFTER_MS = 182L * 24 * 60 * 60 * 1000

@Composable
private fun SectionHeader(title: String, sortLabel: String, onClick: () -> Unit, onSort: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title, style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
            )
            TextButton(onClick = onSort) { Text("Sort: $sortLabel", style = MaterialTheme.typography.labelMedium) }
        }
    }
}

@Composable
private fun PersonListItem(row: PersonRow, unread: Int?, feeling: String?, facts: Int?, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(12.dp)) {
        val n = row.node
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${n.displayName}${n.shortName?.let { " ($it)" } ?: ""}${if (n.ignored) " · ignored" else ""}",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f, fill = false),
            )
            unread?.let { count -> Badge(Modifier.padding(start = 8.dp)) { Text("$count") } }
        }
        if (!feeling.isNullOrBlank()) Text("💭 $feeling", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        if ((facts ?: 0) > 0) Text("🧠 $facts fact${if (facts == 1) "" else "s"}", style = MaterialTheme.typography.bodySmall)
        Text(row.details(), style = MaterialTheme.typography.bodySmall)
    }
    HorizontalDivider()
}

private fun PersonRow.details(): String = buildList {
    add(
        distanceM?.let { d ->
            val approx = if (uncertaintyM > 0) " ±${Geo.formatDistance(uncertaintyM)}" else ""
            "${Geo.formatDistance(d)}$approx $direction"
        } ?: "location unknown"
    )
    node.hopsAway?.let { add(if (it == 1) "1 hop" else "$it hops") }
    node.snr?.let { add("SNR %.1f".format(it)) }
    node.rssi?.let { add("RSSI $it") }
    node.batteryLevel?.let { add("🔋$it%") }
    if (node.lastHeard > 0) add("heard ${DateUtils.getRelativeTimeSpanString(node.lastHeard)}")
}.joinToString(" · ")

@Composable
private fun PersonScreen(
    vm: MainViewModel, memoryVm: MemoryViewModel, row: PersonRow, onBack: () -> Unit,
    startOnMemory: Boolean = false, modifier: Modifier = Modifier,
) {
    val node = row.node
    var tab by rememberSaveable(node.nodeNum, startOnMemory) { mutableStateOf(if (startOnMemory) 1 else 0) }
    val nodes by vm.nodes.collectAsStateWithLifecycle()
    val unread by vm.unreadByNode.collectAsStateWithLifecycle()
    val connected = vm.linkState.collectAsStateWithLifecycle().value == LinkState.CONNECTED
    val byNum = remember(nodes) { nodes.associateBy { it.nodeNum } }
    val myNum by vm.myNodeNum.collectAsStateWithLifecycle()
    val conversation by remember(node.nodeNum, myNum) { vm.dmWith(node.nodeNum, myNum) }.collectAsStateWithLifecycle(emptyList())

    // Opening the conversation (or receiving while it's open) marks it read.
    LaunchedEffect(node.nodeNum, unread[node.nodeNum]) {
        if ((unread[node.nodeNum] ?: 0) > 0) vm.markRead(node.nodeNum)
    }
    androidx.compose.runtime.DisposableEffect(node.nodeNum) {
        vm.viewingPerson.value = node.nodeNum
        onDispose { vm.viewingPerson.value = null }
    }

    Column(modifier.fillMaxSize().imePadding()) {
        Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                    Column(Modifier.weight(1f)) {
                        Text(node.displayName, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${nodeIdString(node.nodeNum)} · ${node.hwModel ?: "unknown hw"} · ${row.details()}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 12.dp, end = 8.dp)) {
                    Text("Ignore this user", Modifier.weight(1f))
                    Switch(checked = node.ignored, onCheckedChange = { vm.setIgnored(node.nodeNum, it) })
                }
            }
        }
        PrimaryTabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Chat") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Memory") })
        }
        if (tab == 1) {
            PersonMemoryPanel(memoryVm, node.nodeNum, node.displayName, Modifier.weight(1f))
            return@Column
        }
        if (conversation.isEmpty()) {
            Text(
                "No direct messages yet.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f).fillMaxWidth().padding(16.dp),
            )
        } else {
            MessageList(conversation, byNum, vm::toggleFavorite, Modifier.weight(1f))
        }
        Composer(onSend = { vm.sendText(it, node.nodeNum) }, enabled = connected, label = "DM ${node.displayName}")
    }
}
