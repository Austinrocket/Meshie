package com.meshie4LoRa.ui.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meshie4LoRa.ui.MAX_TEXT_BYTES
import com.meshie4LoRa.ui.CharacterEditor
import com.meshie4LoRa.ui.CharacterViewModel
import com.meshie4LoRa.ui.MemoryViewModel
import com.meshie4LoRa.data.CharacterEntity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import kotlinx.coroutines.launch
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.saveable.rememberSaveable
import com.meshie4LoRa.ui.stats.MoodAnalyzer.Mood
import kotlinx.coroutines.delay
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints

private val moodColors = mapOf(
    Mood.HYPE to Color(0xFFFF7A00), Mood.LOVE to Color(0xFFFF4F7B), Mood.FLIRTY to Color(0xFFD63FA0),
    Mood.CHAOS to Color(0xFF7A3FE4), Mood.SALTY to Color(0xFFE53935), Mood.SAD to Color(0xFF3F86E4),
    Mood.CURIOUS to Color(0xFF00A3A3), Mood.CONFUSED to Color(0xFF8D6E63), Mood.CHILL to Color(0xFF3FAF7B),
    Mood.PARTY to Color(0xFFFFB300),
)

/** Home page: swipe between personas (Pokédex entries), stats, thoughts, favorites and all bot settings. */
@Composable
fun StatsScreen(
    vm: StatsViewModel,
    characterVm: CharacterViewModel,
    memoryVm: MemoryViewModel,
    onOpenPerson: (nodeNum: Long, memory: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onTour: () -> Unit = {},
) {
    val dex by vm.dex.collectAsStateWithLifecycle()
    val characters by characterVm.characters.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<CharacterEntity?>(null) }
    var deleting by remember { mutableStateOf<CharacterEntity?>(null) }
    var styling by remember { mutableStateOf<CharacterEntity?>(null) }
    var exporting by remember { mutableStateOf<CharacterEntity?>(null) }
    val exporter = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> exporting?.let { ch -> uri?.let { characterVm.exportPersona(ch, it) } }; exporting = null }
    val importer = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(characterVm::importPersona) }
    styling?.let { ch ->
        BackHandler { styling = null }
        AvatarCreator(
            avatarOf(ch, characters.indexOfFirst { it.id == ch.id }), ch.longName.ifBlank { "New persona" },
            onSave = { characterVm.save(ch.copy(avatar = it.toJson())); styling = null },
            onCancel = { styling = null }, modifier = modifier,
        )
        return
    }
    var detail by rememberSaveable { mutableStateOf<StatKind?>(null) }
    var brainstorming by rememberSaveable { mutableStateOf(false) }
    var wordBank by rememberSaveable { mutableStateOf(false) }
    if (wordBank) {
        val saved by vm.moodWords.collectAsStateWithLifecycle()
        MoodWordBankDialog(saved, onSave = { vm.setMoodWords(it); wordBank = false }, onDismiss = { wordBank = false })
    }
    if (brainstorming) {
        BackHandler { brainstorming = false }
        BrainstormScreen(vm, dex.name.ifBlank { "your persona" }, onBack = { brainstorming = false }, modifier = modifier)
        return
    }
    detail?.let { kind ->
        StatDetailScreen(kind, vm, dex.name.ifBlank { "your persona" }, dex.pronouns, onOpenPerson, onBack = { detail = null }, modifier = modifier)
        return
    }
    editing?.let { draft ->
        BackHandler { editing = null }
        com.meshie4LoRa.ui.Toasts(characterVm.toast, characterVm::clearToast)
        CharacterEditor(
            draft, onSave = { characterVm.save(it); editing = null }, onCancel = { editing = null }, modifier = modifier,
            onExport = { ch ->
                exporting = ch
                exporter.launch(ch.longName.replace(Regex("[^A-Za-z0-9_-]+"), "_").trim('_').ifEmpty { "persona" } + ".meshchan.json")
            },
            writer = if (draft.id == 0L) { apply -> com.meshie4LoRa.ui.PersonaWriterCard(characterVm, apply) } else null,
        )
        return
    }
    val pager = rememberPagerState { characters.size + 1 }
    LaunchedEffect(characters.isNotEmpty()) {
        characters.indexOfFirst { it.active }.takeIf { it >= 0 }?.let { pager.scrollToPage(it) }
    }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        com.meshie4LoRa.ui.BatteryOptimizationBanner()
        HorizontalPager(pager, pageSpacing = 12.dp, verticalAlignment = Alignment.Top) { page ->
            val ch = characters.getOrNull(page)
            if (ch == null) NewPersonaCard(onCreate = { editing = characterVm.newCharacter() }, onImport = { importer.launch(arrayOf("application/json", "*/*")) })
            else DexCard(ch, page, if (ch.active) dex else null)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            repeat(pager.pageCount) { i ->
                Box(
                    Modifier.padding(3.dp).size(if (i == pager.currentPage) 10.dp else 7.dp)
                        .background(
                            if (i == pager.currentPage) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.outlineVariant,
                            CircleShape,
                        ),
                )
            }
        }
        characters.getOrNull(pager.currentPage)?.let { viewed ->
            @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.Center,
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                if (viewed.active) Text("✓ Active persona", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                else Button(onClick = { characterVm.activate(viewed.id) }) { Text("Make active") }
                OutlinedButton(onClick = { editing = viewed }) { Text("Edit") }
                OutlinedButton(onClick = { styling = viewed }) { Text("Look") }
                if (!viewed.active) TextButton(onClick = { deleting = viewed }) { Text("Delete") }
            }
        }

        BrainstormButton(dex.name.ifBlank { "your persona" }) { brainstorming = true }
        IdleAndThoughtCard(vm)
        FavoritesCard(vm)

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Base stats", style = MaterialTheme.typography.titleMedium)
                Text("Since the last full reset. Tap a stat to see everything behind it.", style = MaterialTheme.typography.bodySmall)
                val p = dex.pronouns
                val rows = listOf(
                    Triple("Said on ${p.possessive} own", dex.sentAuto, StatKind.SAID_AUTO),
                    Triple("Said by you", dex.sentManual, StatKind.SAID_MANUAL),
                    Triple("People talked with", dex.talkedTo, StatKind.TALKED),
                    Triple("People ${p.subject} ${p.verb("knows", "know")}", dex.peopleKnown, StatKind.KNOWN),
                    Triple("Facts remembered", dex.facts, StatKind.FACTS),
                    Triple("Feelings held", dex.feelings, StatKind.FEELINGS),
                )
                val max = (rows.maxOfOrNull { it.second } ?: 1).coerceAtLeast(1)
                rows.forEach { (label, value, kind) ->
                    Box(Modifier.fillMaxWidth().clickable { detail = kind }.padding(vertical = 2.dp)) { StatBar("$label ›", value, max) }
                }
                Text("Messages heard: ${dex.heard}   ·   Chose to stay quiet: ${dex.skipped}", style = MaterialTheme.typography.bodySmall)
                dex.longest?.let {
                    Text("Longest rant", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
                    Text("\u201c$it\u201d", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Mood points (all time)", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { wordBank = true }) { Text("Word bank") }
                }
                val total = dex.moodTotals.values.sum()
                if (total <= 0.0) Text("No mood yet.", style = MaterialTheme.typography.bodySmall)
                dex.moodTotals.entries.filter { it.value > 0.01 }.sortedByDescending { it.value }.forEach { (m, v) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${m.emoji} ${m.label}", Modifier.width(110.dp), style = MaterialTheme.typography.bodySmall)
                        LinearProgressIndicator(
                            progress = { (v / total).toFloat() }, color = moodColors.getValue(m),
                            modifier = Modifier.weight(1f).height(8.dp),
                        )
                        Text(" %.0f".format(v), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, modifier = Modifier.width(48.dp))
                    }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("People ${dex.pronouns.subject} ${dex.pronouns.verb("knows", "know")} best", style = MaterialTheme.typography.titleMedium)
                if (dex.besties.isEmpty()) Text("Nobody yet. Memories appear after conversations.", style = MaterialTheme.typography.bodySmall)
                dex.besties.forEach { b ->
                    Column(
                        Modifier.fillMaxWidth().clickable { onOpenPerson(b.nodeNum, true) }.padding(vertical = 4.dp),
                    ) {
                        Text(
                            (b.node?.displayName ?: "!%08x".format(b.nodeNum)) + "  ·  ${b.facts} fact${if (b.facts == 1) "" else "s"}  ›",
                            style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        if (b.feeling.isNotBlank()) Text("feels: ${b.feeling}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        com.meshie4LoRa.ui.ChanSettings(characterVm, memoryVm)
        TextButton(onClick = onTour, modifier = Modifier.fillMaxWidth()) { Text("🎓 Take the welcome tour again") }
    }
    deleting?.let { ch ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete ${ch.longName}?") },
            text = { Text("This persona's settings are deleted. Shared memories stay.") },
            confirmButton = { TextButton(onClick = { characterVm.delete(ch.id); deleting = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

/** Lets the operator add their own keywords to each mood, on top of the built-in ones. */
@Composable
private fun MoodWordBankDialog(saved: Map<Mood, String>, onSave: (Map<Mood, String>) -> Unit, onDismiss: () -> Unit) {
    val edits = remember(saved) { androidx.compose.runtime.mutableStateMapOf<Mood, String>().apply { putAll(saved) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Mood word bank") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "When your persona's messages or feelings use one of these words, that mood gets a point. " +
                        "Add your own, separated by commas. Emoji work too.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Mood.entries.forEach { m ->
                    Column {
                        Text("${m.emoji} ${m.label}", style = MaterialTheme.typography.labelLarge, color = moodColors.getValue(m))
                        Text("Built in: " + m.words.joinToString(", "), style = MaterialTheme.typography.bodySmall)
                        androidx.compose.material3.OutlinedTextField(
                            value = edits[m].orEmpty(), onValueChange = { edits[m] = it },
                            placeholder = { Text("your words") }, modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(Mood.entries.associateWith { edits[it].orEmpty() }) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** One persona's Pokédex entry. [dex] is only given for the active persona (stats are about whoever is live). */
@Composable
private fun DexCard(ch: CharacterEntity, index: Int, dex: Dex?) {
    val current = dex?.let { MoodAnalyzer.dominant(it.moods) }
    val dominant = current ?: dex?.let { MoodAnalyzer.dominant(it.moodTotals) }
    val types = dex?.moodTotals.orEmpty().filterValues { it > 0.05 }.entries.sortedByDescending { it.value }.take(2).map { it.key }
    val avatar = avatarOf(ch, index)
    // Light frame colours get dark text.
    val ink = if (avatar.frame.luminance() > 0.55f) Color(0xFF1A1A1A) else Color.White
    Card(colors = CardDefaults.cardColors(containerColor = avatar.frame), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("No. %03d".format(index + 1), color = ink.copy(alpha = 0.8f), fontFamily = FontFamily.Monospace)
                Text(
                    "  ${ch.longName}", color = ink, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f), maxLines = 1,
                )
                Text("[${ch.shortName}]", color = ink, fontFamily = FontFamily.Monospace)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!ch.active) TypeBadge("💤 BENCHED", Color.Gray)
                else (types.ifEmpty { listOf(null) }).forEach { m ->
                    TypeBadge(m?.let { "${it.emoji} ${it.label.uppercase()}" } ?: "??? NORMAL", m?.let { moodColors[it] } ?: Color.Gray)
                }
            }
            Box(
                Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(14.dp))
                    .border(4.dp, Color(0xFF263238), RoundedCornerShape(14.dp)),
            ) {
                BackdropCanvas(avatar.backdrop, Modifier.fillMaxSize())
                dex?.let { MoodCloud(it.moodTotals, Modifier.fillMaxSize()) }
                PokeableChibi(dominant, avatar, Modifier.fillMaxSize(0.62f).align(Alignment.Center))
            }
            dex?.let { OrganicMeter(it, ink) }
            Text(
                when {
                    !ch.active -> "Swipe to browse. Make active to put ${ch.pronouns.obj} on the radio."
                    current != null -> "Feeling ${current.label.lowercase()} ${current.emoji} right now."
                    dominant != null -> "Mostly ${dominant.label.lowercase()} ${dominant.emoji}. Quiet lately."
                    else -> "Hasn't felt much yet. Say hi!"
                },
                color = ink, style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                ch.persona.take(220) + if (ch.persona.length > 220) "…" else "",
                color = ink.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** Share of the persona's messages it wrote on its own (vs. ones you sent as it). Higher is better. */
@Composable
private fun OrganicMeter(dex: Dex, ink: Color) {
    val total = dex.sentAuto + dex.sentManual
    val pct = if (total == 0) null else (dex.sentAuto * 100 + total / 2) / total
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Organic", color = ink, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            pct?.let { "  $it% ${organicEmoji(it)}" } ?: "  —",
            color = ink, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f),
        )
        Text("${dex.sentAuto} own · ${dex.sentManual} by you", color = ink.copy(alpha = 0.8f), style = MaterialTheme.typography.labelSmall)
    }
    LinearProgressIndicator(
        progress = { (pct ?: 0) / 100f }, color = Color(0xFFFFD54F), trackColor = ink.copy(alpha = 0.25f),
        modifier = Modifier.fillMaxWidth().height(6.dp),
    )
}

/** A reaction for every 15%: furious at 0, gold medal at 100. */
internal fun organicEmoji(pct: Int): String = when {
    pct <= 0 -> "😡"
    pct < 15 -> "😠"
    pct < 30 -> "😒"
    pct < 45 -> "😐"
    pct < 60 -> "🙂"
    pct < 75 -> "😊"
    pct < 90 -> "😎"
    pct < 100 -> "🤩"
    else -> "🤩🥇"
}

private data class Poke(val emoji: String, val line: String, val mood: Mood, val move: Int)

private val pokes = listOf(
    Poke("✨", "hehe!", Mood.HYPE, 0), Poke("💫", "wheee", Mood.CHAOS, 1), Poke("💖", "aww", Mood.LOVE, 2),
    Poke("💢", "hey!!", Mood.SALTY, 3), Poke("😳", "o-oh", Mood.FLIRTY, 2), Poke("🎶", "boop", Mood.PARTY, 0),
    Poke("❓", "huh?", Mood.CONFUSED, 3), Poke("💤", "five more min", Mood.SAD, 1),
)

/** The chibi reacts to taps: jumps, spins, pulses or shakes, makes a face and says something. */
@Composable
private fun PokeableChibi(mood: Mood?, avatar: Avatar, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    val jump = remember { Animatable(0f) }
    val spin = remember { Animatable(0f) }
    val pulse = remember { Animatable(1f) }
    val shake = remember { Animatable(0f) }
    val float = remember { Animatable(0f) }
    var poke by remember { mutableStateOf<Poke?>(null) }
    var job by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    Box(
        modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
            val p = pokes.random()
            poke = p
            job?.cancel()
            job = scope.launch {
                launch { float.snapTo(0f); float.animateTo(1f, tween(1400)) }
                when (p.move) {
                    0 -> { jump.animateTo(1f, tween(160)); jump.animateTo(0f, spring(dampingRatio = 0.35f, stiffness = 300f)) }
                    1 -> { spin.snapTo(0f); spin.animateTo(360f, tween(600)); spin.snapTo(0f) }
                    2 -> { pulse.animateTo(1.18f, tween(140)); pulse.animateTo(1f, spring(dampingRatio = 0.3f, stiffness = 400f)) }
                    else -> repeat(3) { shake.animateTo(6f, tween(50)); shake.animateTo(-6f, tween(80)); shake.animateTo(0f, tween(50)) }
                }
                kotlinx.coroutines.delay(900)
                poke = null
            }
        },
    ) {
        ChibiChan(
            poke?.mood ?: mood,
            Modifier.fillMaxSize().graphicsLayer {
                translationY = -jump.value * size.height * 0.18f
                rotationZ = spin.value
                scaleX = pulse.value
                scaleY = pulse.value
                translationX = shake.value * density
            },
            avatar,
        )
        poke?.let { p ->
            Column(
                Modifier.align(Alignment.TopEnd).graphicsLayer {
                    translationY = -float.value * 40f * density
                    alpha = 1f - float.value * 0.8f
                },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(p.emoji, fontSize = 28.sp)
                Text(
                    p.line, color = Color(0xFF263238), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
                    modifier = Modifier.background(Color.White.copy(alpha = 0.9f), RoundedCornerShape(10.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
    }
}

/** The persona's saved look; personas without one keep the classic look in their pager colour. */
private fun avatarOf(ch: CharacterEntity, index: Int) = Avatar.parse(ch.avatar, personaHair[index.coerceAtLeast(0) % personaHair.size])

@Composable
private fun TypeBadge(label: String, color: Color) {
    Text(
        label, color = Color.White, style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.background(color, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
private fun NewPersonaCard(onCreate: () -> Unit, onImport: () -> Unit) {
    Card(
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth().aspectRatio(0.8f),
    ) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("No. ???", fontFamily = FontFamily.Monospace)
            Text("A new persona", style = MaterialTheme.typography.titleLarge)
            Text("Only one is on the radio at a time; memories are shared.", style = MaterialTheme.typography.bodySmall)
            Button(onClick = onCreate, modifier = Modifier.padding(top = 12.dp)) { Text("Create persona") }
            OutlinedButton(onClick = onImport) { Text("📥 Import from file") }
        }
    }
}

/** Starred messages; long-press any message in the Feed or a DM to add one. */
@Composable
private fun FavoritesCard(vm: StatsViewModel) {
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    var showAll by rememberSaveable { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("⭐ Favorites (${favorites.size})", style = MaterialTheme.typography.titleMedium)
            if (favorites.isEmpty()) Text("Long-press any message in the Feed or a DM to star it.", style = MaterialTheme.typography.bodySmall)
            (if (showAll) favorites else favorites.take(5)).forEach { f ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(f.text, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 2)
                    TextButton(onClick = { vm.unfavorite(f) }) { Text("Unstar") }
                }
            }
            if (favorites.size > 5) TextButton(onClick = { showAll = !showAll }) { Text(if (showAll) "Show fewer" else "Show all") }
        }
    }
}

@Composable
private fun StatBar(label: String, value: Int, max: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(150.dp), style = MaterialTheme.typography.bodySmall)
        Text("$value", Modifier.width(44.dp), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        LinearProgressIndicator(progress = { value.toFloat() / max }, modifier = Modifier.weight(1f).height(8.dp))
    }
}

/** Free spots around the chibi, best first: (x, y as fractions of the frame, font size sp, rotation). */
private val cloudSlots = listOf(
    Slot(0.50f, 0.08f, 34f, 0f), Slot(0.50f, 0.93f, 28f, 0f),
    Slot(0.09f, 0.50f, 22f, -90f), Slot(0.91f, 0.50f, 22f, 90f),
    Slot(0.16f, 0.17f, 15f, -12f), Slot(0.84f, 0.17f, 15f, 12f),
    Slot(0.16f, 0.83f, 15f, 12f), Slot(0.84f, 0.83f, 15f, -12f),
)

private data class Slot(val x: Float, val y: Float, val sp: Float, val rot: Float)

/** Mood words around her: the biggest totals get the biggest open spots. */
@Composable
private fun MoodCloud(moods: Map<Mood, Double>, modifier: Modifier) {
    val ranked = moods.filterValues { it > 0.01 }.entries.sortedByDescending { it.value }.take(cloudSlots.size)
    val max = ranked.firstOrNull()?.value ?: return
    Box(modifier) {
        ranked.forEachIndexed { i, (m, v) ->
            val k = (v / max).toFloat()
            val slot = cloudSlots[i]
            Text(
                m.label.uppercase(),
                color = moodColors.getValue(m).copy(alpha = 0.45f + 0.5f * k),
                fontSize = (slot.sp * (0.75f + 0.25f * k)).sp,
                fontWeight = if (i < 2) FontWeight.Black else FontWeight.Bold,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier
                    .layout { measurable, constraints ->
                        val p = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0, maxWidth = Constraints.Infinity))
                        layout(constraints.maxWidth, constraints.maxHeight) {
                            val cx = (constraints.maxWidth * slot.x - p.width / 2f).toInt()
                            val cy = (constraints.maxHeight * slot.y - p.height / 2f).toInt()
                            // Rotated side words may extend past their box; only clamp unrotated ones.
                            val x = if (slot.rot in -45f..45f) cx.coerceIn(0, (constraints.maxWidth - p.width).coerceAtLeast(0)) else cx
                            p.place(x, cy)
                        }
                    }
                    .rotate(slot.rot),
            )
        }
    }
}

@Composable
private fun BrainstormButton(name: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text("💡 Brainstorm with $name") }
}

@Composable
private fun IdleAndThoughtCard(vm: StatsViewModel) {
    val nextIdle by vm.nextIdleAt.collectAsStateWithLifecycle()
    val sleepUntil by vm.sleepUntil.collectAsStateWithLifecycle()
    val idleMinutes by vm.idleMinutes.collectAsStateWithLifecycle()
    val status by vm.brainStatus.collectAsStateWithLifecycle()
    val thought by vm.thought.collectAsStateWithLifecycle()
    val activeName by vm.activeName.collectAsStateWithLifecycle()
    val pronouns by vm.activePronouns.collectAsStateWithLifecycle()
    val idleSource by vm.idleSource.collectAsStateWithLifecycle()
    val callout by vm.callout.collectAsStateWithLifecycle()
    val favoriteCount = vm.favorites.collectAsStateWithLifecycle().value.size
    var editMinutes by remember { mutableStateOf(false) }
    var sleepDialog by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val who = activeName.ifBlank { "Your persona" }
            val p = pronouns
            val off = sleepUntil == com.meshie4LoRa.data.SLEEP_FOREVER
            val asleep = sleepUntil > now
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Next thought · $who", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { editMinutes = true }) { Text(if (idleMinutes == 0) "⏱ off" else "⏱ $idleMinutes min") }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val line = when {
                    off -> "Off"
                    asleep -> "Asleep until ${java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(sleepUntil))}"
                    idleMinutes == 0 -> "On · speaks only when spoken to"
                    status != null -> "🤖 $status"
                    nextIdle == null -> "On · waiting for someone else to talk"
                    else -> {
                        val left = ((nextIdle!! - now) / 1000).coerceAtLeast(0)
                        if (left == 0L) "Any second now…" else "Speaks in %d:%02d if it stays quiet".format(left / 60, left % 60)
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text("🔌 Bot on", style = MaterialTheme.typography.labelLarge)
                    Text(line, style = MaterialTheme.typography.bodyMedium, fontFamily = if (nextIdle != null && !asleep) FontFamily.Monospace else FontFamily.Default)
                }
                if (!asleep) TextButton(onClick = { sleepDialog = true }) { Text("Sleep for…") }
                androidx.compose.material3.Switch(checked = !asleep, onCheckedChange = vm::setBotOn)
            }
            Text("Speaks up with:", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                com.meshie4LoRa.data.IdleSource.entries.forEach { s ->
                    FilterChip(selected = idleSource == s, onClick = { vm.setIdleSource(s) }, label = { Text(s.label) })
                }
            }
            if (idleSource != com.meshie4LoRa.data.IdleSource.THOUGHT && favoriteCount == 0) {
                Text("No favorites yet, so it's a plain thought until you star some.", style = MaterialTheme.typography.bodySmall)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("📣 Callout someone", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                androidx.compose.material3.Switch(checked = callout, onCheckedChange = vm::setCallout)
            }
            Text(
                "Aims each thought at someone heard in the last hour, preferring recent chatters and people $who remembers.",
                style = MaterialTheme.typography.bodySmall,
            )

            HorizontalDivider()
            when (val t = thought) {
                ThoughtState.None -> Column {
                    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class) androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.Center,
                    ) {
                        Button(onClick = vm::proposeThought) { Text("💭 Write one now") }
                        OutlinedButton(onClick = vm::proposeFromFavorites) { Text("⭐ From favorites") }
                        TextButton(onClick = vm::freshStart) { Text("🧹 Fresh start") }
                    }
                    Text("Fresh start hides the chat so far from the model; memories and the feed stay.", style = MaterialTheme.typography.bodySmall)
                }
                ThoughtState.Thinking -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.width(20.dp).height(20.dp), strokeWidth = 2.dp)
                    Text("Thinking…")
                    TextButton(onClick = vm::dismissThought) { Text("Cancel") }
                }
                is ThoughtState.Failed -> Column {
                    Text("Couldn't come up with one: ${t.why}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = vm::proposeAgain) { Text("Try again") }
                        TextButton(onClick = vm::dismissThought) { Text("Close") }
                    }
                }
                is ThoughtState.Ready -> {
                    var text by remember(t.text) { mutableStateOf(t.text) }
                    val bytes = text.toByteArray().size
                    t.target?.let { Text("📣 Aimed at $it", style = MaterialTheme.typography.labelMedium) }
                    OutlinedTextField(
                        text, { text = it }, Modifier.fillMaxWidth(),
                        supportingText = { Text("$bytes / $MAX_TEXT_BYTES bytes") }, isError = bytes > MAX_TEXT_BYTES,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.sendThought(text) }, enabled = text.isNotBlank() && bytes <= MAX_TEXT_BYTES) { Text("Send") }
                        OutlinedButton(onClick = vm::proposeAgain) { Text("Another") }
                        TextButton(onClick = vm::dismissThought) { Text("Discard") }
                    }
                }
            }
        }
    }
    if (sleepDialog) com.meshie4LoRa.ui.SleepDialog(onDismiss = { sleepDialog = false }, onSleep = { vm.sleepFor(it); sleepDialog = false })
    if (editMinutes) IdleMinutesDialog(idleMinutes, who = activeName.ifBlank { "your persona" }, onDismiss = { editMinutes = false }) {
        vm.setIdleMinutes(it)
        editMinutes = false
    }
}

@Composable
private fun IdleMinutesDialog(current: Int, who: String, onDismiss: () -> Unit, onSave: (Int) -> Unit) {
    var text by remember { mutableStateOf(current.toString()) }
    val minutes = text.trim().toIntOrNull()?.takeIf { it in 0..MAX_IDLE_MINUTES }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Speak up after…") },
        text = {
            OutlinedTextField(
                text, { text = it.filter(Char::isDigit).take(3) }, singleLine = true, suffix = { Text("min") },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                isError = minutes == null,
                supportingText = { Text("How long the chat must be quiet before $who says something. 0 turns it off.") },
            )
        },
        confirmButton = { Button(onClick = { minutes?.let(onSave) }, enabled = minutes != null) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Private chat with the persona to work out a post together (or just ask it things). Nothing here goes to the mesh. */
@Composable
private fun BrainstormScreen(vm: StatsViewModel, who: String, onBack: () -> Unit, modifier: Modifier) {
    val turns by vm.brainstorm.collectAsStateWithLifecycle()
    val busy by vm.brainstormBusy.collectAsStateWithLifecycle()
    val error by vm.brainstormError.collectAsStateWithLifecycle()
    var input by rememberSaveable { mutableStateOf("") }
    Column(modifier.fillMaxSize().imePadding()) {
        Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
            Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                Column(Modifier.weight(1f)) {
                    Text("Brainstorm with $who", style = MaterialTheme.typography.titleMedium)
                    Text("Private, nothing here is sent", style = MaterialTheme.typography.bodySmall)
                }
                if (turns.isNotEmpty()) TextButton(onClick = vm::clearBrainstorm) { Text("Clear") }
            }
        }
        val listState = rememberLazyListState()
        LaunchedEffect(turns.size, busy) { listState.scrollToItem(0) }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(), state = listState, reverseLayout = true,
            contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            error?.let { item { Text("Couldn't answer: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) } }
            if (busy) item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("$who is typing…", style = MaterialTheme.typography.bodySmall)
                }
            }
            items(turns.asReversed()) { (mine, text) ->
                Column(
                    Modifier.fillMaxWidth().padding(start = if (mine) 40.dp else 0.dp, end = if (mine) 0.dp else 40.dp)
                        .background(
                            if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(12.dp),
                        ).padding(10.dp),
                ) {
                    Text(if (mine) "You" else who, style = MaterialTheme.typography.labelSmall)
                    Text(text, style = MaterialTheme.typography.bodyLarge)
                    if (!mine) TextButton(onClick = { vm.useAsThought(text); onBack() }) { Text("Use as the post") }
                }
            }
            if (turns.isEmpty() && !busy) item {
                Text(
                    "Ask $who anything, or work out a post together: \"help me roast whoever's on the rail\", " +
                        "\"what do you think of Steve?\". When you like where it's going, tap Write the post.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(input, { input = it }, Modifier.weight(1f), label = { Text("Message $who") }, maxLines = 4)
                Button(onClick = { vm.ask(input); input = "" }, enabled = input.isNotBlank() && !busy, modifier = Modifier.padding(start = 8.dp)) { Text("Send") }
            }
            OutlinedButton(
                onClick = { vm.writePostFromBrainstorm(); onBack() }, enabled = turns.isNotEmpty() && !busy, modifier = Modifier.fillMaxWidth(),
            ) { Text("✍ Write the post from this chat") }
        }
    }
}
