package com.meshie4LoRa.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meshie4LoRa.brain.BotMode
import com.meshie4LoRa.brain.Prompts
import com.meshie4LoRa.data.CharacterEntity
import com.meshie4LoRa.data.Pronouns
import com.meshie4LoRa.llm.EngineState
import com.meshie4LoRa.llm.ModelStatus
import com.meshie4LoRa.llm.ModelUi
import com.meshie4LoRa.mesh.MAX_LONG_NAME_BYTES
import com.meshie4LoRa.mesh.MAX_SHORT_NAME_BYTES
import com.meshie4LoRa.radio.LinkState
import com.meshie4LoRa.ui.BatteryOptimizationBanner
import com.meshie4LoRa.ui.CharacterViewModel
import com.meshie4LoRa.ui.MainViewModel
import com.meshie4LoRa.ui.ModelsViewModel
import com.meshie4LoRa.ui.stats.Avatar
import com.meshie4LoRa.ui.stats.Backdrop
import com.meshie4LoRa.ui.stats.BackdropCanvas
import com.meshie4LoRa.ui.stats.ChibiChan
import com.meshie4LoRa.ui.stats.MoodAnalyzer.Mood
import com.meshie4LoRa.ui.stats.StatsViewModel
import kotlinx.coroutines.delay
import kotlin.random.Random

private enum class Step(val emoji: String, val label: String) {
    WELCOME("👋", "Hi"), BRAIN("🧠", "Brain"), CHARACTER("🎨", "Create"), CHAT("💬", "Chat"), RADIO("📡", "Radio"), LAUNCH("🚀", "Go!"),
}

/**
 * Opt-in welcome tour: model download → first persona → private chat → radio pairing → pick a mode and go.
 * Every step can be skipped; nothing is sent to the mesh until the last step.
 */
@Composable
fun OnboardingScreen(
    vm: MainViewModel,
    modelsVm: ModelsViewModel,
    characterVm: CharacterViewModel,
    statsVm: StatsViewModel,
    replay: Boolean,
    onRequestRadioPermissions: () -> Unit,
    onRequestNotifications: () -> Unit,
) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    var chosenModel by rememberSaveable { mutableStateOf<String?>(null) }
    var madeCharacter by rememberSaveable { mutableStateOf(false) }
    var launched by rememberSaveable { mutableStateOf<String?>(null) }
    val current = Step.entries[step]
    BackHandler(enabled = step > 0 && launched == null) { step-- }
    BackHandler(enabled = step == 0 && replay) { vm.endTutorial(true) }
    val next = { step = (step + 1).coerceAtMost(Step.entries.lastIndex) }

    Surface(Modifier.fillMaxSize()) { Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
            if (current != Step.WELCOME) StepHeader(current, onBack = { step-- }, onSkip = { vm.endTutorial(false); onRequestRadioPermissions() })
            AnimatedContent(
                targetState = current, modifier = Modifier.weight(1f), label = "step",
                transitionSpec = {
                    val dir = if (targetState.ordinal > initialState.ordinal) 1 else -1
                    (slideInHorizontally { it * dir } + fadeIn()) togetherWith (slideOutHorizontally { -it * dir } + fadeOut())
                },
            ) { s ->
                when (s) {
                    Step.WELCOME -> WelcomeStep(replay, onStart = next, onSkip = { vm.endTutorial(false); onRequestRadioPermissions() })
                    Step.BRAIN -> BrainStep(modelsVm, chosenModel, onChoose = { chosenModel = it }, onNext = next)
                    Step.CHARACTER -> CharacterStep(characterVm, madeCharacter, onNext = { madeCharacter = it; next() })
                    Step.CHAT -> ChatStep(statsVm, modelsVm, characterVm, chosenModel, onNext = next)
                    Step.RADIO -> RadioStep(vm, characterVm, onRequestRadioPermissions, onNext = next)
                    Step.LAUNCH -> LaunchStep(characterVm) { mode, idle, name ->
                        vm.letLoose(mode, idle)
                        onRequestNotifications()
                        launched = name
                    }
                }
            }
        }
        launched?.let { name -> Celebration(name) { vm.endTutorial(true) } }
    } }
}

@Composable
private fun StepHeader(current: Step, onBack: () -> Unit, onSkip: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Step.entries.drop(1).forEach { s ->
                val done = s.ordinal < current.ordinal
                val here = s == current
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 5.dp)) {
                    Box(
                        Modifier.size(if (here) 34.dp else 28.dp).clip(CircleShape).background(
                            when {
                                here -> MaterialTheme.colorScheme.primaryContainer
                                done -> MaterialTheme.colorScheme.secondaryContainer
                                else -> MaterialTheme.colorScheme.surfaceVariant
                            }
                        ),
                        contentAlignment = Alignment.Center,
                    ) { Text(if (done) "✓" else s.emoji, fontSize = if (here) 17.sp else 13.sp) }
                    Text(s.label, style = MaterialTheme.typography.labelSmall, fontWeight = if (here) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }
        TextButton(onClick = onSkip) { Text("Skip tour") }
    }
}

/** Shared layout: scrolling content with a sticky action area at the bottom. */
@Composable
private fun StepPage(
    title: String, subtitle: String?, actions: @Composable () -> Unit, modifier: Modifier = Modifier, scroll: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxSize()) {
        Column(
            (if (scroll) Modifier.verticalScroll(rememberScrollState()) else Modifier).weight(1f).fillMaxWidth().padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(4.dp))
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            content()
            Spacer(Modifier.height(8.dp))
        }
        Surface(tonalElevation = 3.dp) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { actions() }
        }
    }
}

// ---------------------------------------------------------------- Welcome

@Composable
private fun WelcomeStep(replay: Boolean, onStart: () -> Unit, onSkip: () -> Unit) {
    var mood by remember { mutableStateOf<Mood?>(Mood.HYPE) }
    var pokes by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Box(Modifier.fillMaxWidth().height(260.dp).clip(RoundedCornerShape(bottomStart = 32.dp, bottomEnd = 32.dp))) {
            BackdropCanvas(Backdrop.MESH, Modifier.fillMaxSize())
            ChibiChan(
                mood, Modifier.fillMaxSize().padding(24.dp).clickable {
                    pokes++
                    mood = Mood.entries[(Mood.entries.indexOf(mood) + 1) % Mood.entries.size]
                },
            )
            if (pokes > 0) Text(
                listOf("hehe", "hey!", "that tickles", "ok ok I'm awake", "boop", "stop it (don't)", "✨")[(pokes - 1) % 7],
                modifier = Modifier.align(Alignment.TopEnd).padding(18.dp)
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelLarge,
            )
        }
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                if (replay) "Welcome back! 👋" else "Hi! Welcome to\nMeshie 👋",
                style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
            )
            Text(
                "This app turns your phone into an AI character that hangs out on your Meshtastic mesh. It chats, makes " +
                    "friends, remembers people and gets up to mischief, and its brain runs right here on the phone. No cloud, no internet once it's set up.",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                "Want a quick tour? We'll pick a brain, dream up your character, have a little chat, pair your radio, and " +
                    "then let your creation loose.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text("(psst: you can poke me)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = onStart, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("✨ Show me around", fontSize = 17.sp) }
            TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) { Text("Skip, I'll explore on my own") }
            Text(
                "You can take the tour any time from the bottom of the Chan page.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

// ---------------------------------------------------------------- Brain

private data class BrainPick(val id: String, val title: String, val pitch: String)

private val brainPicks = listOf(
    BrainPick(
        "llama3.2-1b-uncensored-q4km", "⚡ Speedy (recommended)",
        "Snappy replies, even with the screen off, and it plays characters without breaking into \"as an AI…\". The best place to start.",
    ),
    BrainPick(
        "hermes3-llama3.2-3b-q4km", "🎭 Theatrical",
        "A bigger brain trained for role-play: richer characters, but slower and hungrier for battery and memory.",
    ),
)

@Composable
private fun BrainStep(modelsVm: ModelsViewModel, chosen: String?, onChoose: (String) -> Unit, onNext: () -> Unit) {
    val models by modelsVm.models.collectAsStateWithLifecycle()
    val engine by modelsVm.engineState.collectAsStateWithLifecycle()
    val activeId by modelsVm.activeModelId.collectAsStateWithLifecycle()
    val byId = models.associateBy { it.model.id }
    val onPhone = models.filter { it.status == ModelStatus.Downloaded }
        .sortedBy { m -> brainPicks.indexOfFirst { it.id == m.model.id }.let { if (it < 0) Int.MAX_VALUE else it } }

    // A chosen download that just finished becomes the active brain.
    LaunchedEffect(chosen, byId[chosen]?.status) {
        val c = chosen ?: return@LaunchedEffect
        if (byId[c]?.status == ModelStatus.Downloaded && activeId != c) modelsVm.activate(c)
    }
    val ready = engine is EngineState.Ready
    val inFlight = chosen?.let { byId[it]?.status } .let { it is ModelStatus.Downloading || it is ModelStatus.Verifying }

    StepPage(
        title = "First, a brain 🧠",
        subtitle = "Your character thinks with a small AI model that lives entirely on this phone. It's a one-time download, so Wi-Fi is a good idea.",
        actions = {
            BrainStatusLine(engine, chosen?.let { byId[it] }, models)
            Button(onClick = onNext, enabled = ready || inFlight, modifier = Modifier.fillMaxWidth()) {
                Text(if (ready) "Next: create a character" else if (inFlight) "Next (it keeps downloading)" else "Pick a brain to continue")
            }
            if (!ready && !inFlight) TextButton(onClick = onNext, modifier = Modifier.fillMaxWidth()) { Text("I'll pick a brain later") }
        },
    ) {
        if (onPhone.isNotEmpty()) {
            Text("Already on this phone", style = MaterialTheme.typography.titleMedium)
            onPhone.forEach { m ->
                val active = activeId == m.model.id
                val pick = brainPicks.firstOrNull { it.id == m.model.id }
                BrainCard(
                    title = pick?.let { "${it.title} · ${m.model.name}" } ?: m.model.name, pitch = pick?.pitch ?: m.model.description, selected = active,
                    action = {
                        if (active) Text(if (ready) "✓ In use" else "Loading…", color = MaterialTheme.colorScheme.primary)
                        else OutlinedButton(onClick = { onChoose(m.model.id); modelsVm.activate(m.model.id) }) { Text("Use this one") }
                    },
                )
            }
            Text("Or grab a new one", style = MaterialTheme.typography.titleMedium)
        }
        brainPicks.filter { p -> onPhone.none { it.model.id == p.id } }.forEach { p ->
            val m = byId[p.id] ?: return@forEach
            BrainCard(title = p.title, pitch = p.pitch, selected = chosen == p.id, action = { DownloadAction(m, onStart = { onChoose(p.id); modelsVm.download(p.id) }) })
        }
        Text(
            "Plenty more brains (and your own .gguf files) live in Settings → Models.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BrainCard(title: String, pitch: String, selected: Boolean, action: @Composable () -> Unit) {
    Card(
        colors = if (selected) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer) else CardDefaults.cardColors(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (pitch.isNotBlank()) Text(pitch, style = MaterialTheme.typography.bodySmall)
            action()
        }
    }
}

@Composable
private fun DownloadAction(m: ModelUi, onStart: () -> Unit) {
    when (val s = m.status) {
        ModelStatus.NotDownloaded -> {
            Button(onClick = onStart) { Text("Download · ${mb(m.model.sizeBytes)}") }
            m.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
        is ModelStatus.Downloading -> {
            val frac = if (s.total > 0) s.bytes.toFloat() / s.total else 0f
            LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth())
            Text(s.waiting ?: "Downloading… ${(frac * 100).toInt()}% of ${mb(s.total)}", style = MaterialTheme.typography.bodySmall)
        }
        ModelStatus.Verifying -> Text("Checking the download…", style = MaterialTheme.typography.bodySmall)
        ModelStatus.Downloaded -> Text("✓ Downloaded", color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun BrainStatusLine(engine: EngineState, chosen: ModelUi?, models: List<ModelUi>) {
    val (text, busy) = when {
        engine is EngineState.Ready -> "🧠 Brain awake: ${models.firstOrNull { it.model.id == engine.modelId }?.model?.name ?: engine.modelId}" to false
        engine is EngineState.Loading -> "Waking up the brain…" to true
        engine is EngineState.Failed -> "⚠ ${engine.message}" to false
        chosen?.status is ModelStatus.Downloading -> {
            val s = chosen?.status as? ModelStatus.Downloading
            "Downloading ${if (s != null && s.total > 0) (s.bytes * 100 / s.total) else 0}%… you can carry on meanwhile" to true
        }
        chosen?.status == ModelStatus.Verifying -> "Checking the download…" to true
        else -> "No brain picked yet" to false
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        Text(text, style = MaterialTheme.typography.bodySmall, maxLines = 1)
    }
}

private fun mb(bytes: Long?) = bytes?.let { if (it >= 1_000_000_000) "%.1f GB".format(it / 1e9) else "${it / 1_000_000} MB" } ?: "?"

// ---------------------------------------------------------------- Character

/** [madeHere] = the active persona was created earlier in this tour, so coming back edits it instead of making another. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CharacterStep(characterVm: CharacterViewModel, madeHere: Boolean, onNext: (made: Boolean) -> Unit) {
    val active by characterVm.active.collectAsStateWithLifecycle()
    val editing = active.takeIf { madeHere }
    var name by rememberSaveable { mutableStateOf("") }
    var short by rememberSaveable { mutableStateOf("") }
    var shortEdited by rememberSaveable { mutableStateOf(false) }
    var pronouns by rememberSaveable { mutableStateOf(Pronouns.THEY) }
    var persona by rememberSaveable { mutableStateOf("") }
    var examples by rememberSaveable { mutableStateOf("") }
    var vibe by rememberSaveable { mutableStateOf<String?>(null) }
    var look by rememberSaveable { mutableStateOf(Avatar.random().toJson()) }
    var mood by remember { mutableStateOf<Mood?>(Mood.CURIOUS) }
    val avatar = remember(look) { Avatar.parse(look) }
    LaunchedEffect(editing?.id) {
        val e = editing ?: return@LaunchedEffect
        if (name.isNotEmpty()) return@LaunchedEffect
        name = e.longName; short = e.shortName; shortEdited = true; pronouns = e.pronouns
        persona = e.persona; examples = e.examples; look = e.avatar.ifBlank { look }
    }

    fun setName(n: String) {
        name = n
        if (!shortEdited) short = autoShort(n)
    }
    fun applyVibe(v: Vibe) {
        vibe = v.title
        persona = v.persona
        examples = v.examples.joinToString("\n")
        look = v.look().toJson()
        mood = v.mood
    }

    val who = name.trim().ifEmpty { "your character" }
    val longOk = name.trim().toByteArray().size in 1..MAX_LONG_NAME_BYTES
    val shortOk = short.trim().toByteArray().size in 1..MAX_SHORT_NAME_BYTES
    val valid = longOk && shortOk && persona.isNotBlank()

    StepPage(
        title = "Dream up a character 🎨",
        subtitle = "Who's going to live on your mesh? Pick a vibe for a head start, or roll the dice.",
        actions = {
            Button(
                onClick = {
                    val ch = CharacterEntity(
                        longName = name.trim(), shortName = short.trim(), pronouns = pronouns,
                        persona = persona.trim().replace("{name}", name.trim()),
                        examples = examples, rules = Prompts.DEFAULT_RULES, avatar = look,
                    )
                    if (editing != null) characterVm.save(editing.copy(
                        longName = ch.longName, shortName = ch.shortName, pronouns = ch.pronouns,
                        persona = ch.persona, examples = ch.examples, avatar = ch.avatar,
                    ))
                    else characterVm.createAndActivate(ch)
                    onNext(true)
                },
                enabled = valid, modifier = Modifier.fillMaxWidth(),
            ) { Text(if (name.isBlank()) "Give them a name first" else if (editing != null) "Save ${name.trim()} and continue" else "✨ Bring ${name.trim()} to life") }
            active?.takeIf { editing == null }?.let { a -> TextButton(onClick = { onNext(false) }, modifier = Modifier.fillMaxWidth()) { Text("Keep using ${a.longName} instead") } }
        },
    ) {
        Box(Modifier.fillMaxWidth().height(210.dp).clip(RoundedCornerShape(24.dp))) {
            BackdropCanvas(avatar.backdrop, Modifier.fillMaxSize())
            ChibiChan(
                mood, Modifier.fillMaxSize().padding(16.dp).clickable { mood = Mood.entries.random() }, avatar,
            )
            Text(
                name.trim().ifEmpty { "???" },
                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.BottomCenter).padding(10.dp)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 3.dp),
            )
        }
        OutlinedButton(
            onClick = {
                val v = vibes.random()
                setName(surpriseNames.random())
                applyVibe(v)
                pronouns = Pronouns.entries.filter { it != Pronouns.IT }.random()
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("🎲 Surprise me!") }

        com.meshie4LoRa.ui.PersonaWriterCard(characterVm, onResult = { g ->
            name = g.name; short = g.shortName; shortEdited = true; pronouns = g.pronouns
            persona = g.persona; examples = g.examples.joinToString("\n"); vibe = null
            look = (Avatar.PRESETS.firstOrNull { it.first == g.look }?.second ?: Avatar.random()).toJson()
            mood = Mood.HYPE
        })

        Text("Pick a vibe", style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            vibes.forEach { v -> FilterChip(selected = vibe == v.title, onClick = { applyVibe(v) }, label = { Text(v.title) }) }
        }

        OutlinedTextField(
            name, { setName(it) }, Modifier.fillMaxWidth(), label = { Text("Name (shown on the mesh)") }, singleLine = true,
            isError = name.isNotEmpty() && !longOk, supportingText = { Text("${name.trim().toByteArray().size} / $MAX_LONG_NAME_BYTES bytes") },
        )
        OutlinedTextField(
            short, { short = it; shortEdited = true }, Modifier.fillMaxWidth(), label = { Text("Short name (up to 4 letters)") }, singleLine = true,
            isError = short.isNotEmpty() && !shortOk,
        )
        Text("Pronouns", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Pronouns.entries.forEach { p -> FilterChip(selected = pronouns == p, onClick = { pronouns = p }, label = { Text(p.label) }) }
        }
        OutlinedTextField(
            persona.replace("{name}", name.trim().ifEmpty { "They" }), { persona = it }, Modifier.fillMaxWidth(),
            label = { Text("Who are they?") }, minLines = 4,
            supportingText = { Text("Describe them like you'd describe a friend: personality, quirks, what they love.") },
        )

        Text("Look", style = MaterialTheme.typography.titleMedium)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Box(
                    Modifier.size(76.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.secondaryContainer)
                        .clickable { look = Avatar.random().toJson() },
                    contentAlignment = Alignment.Center,
                ) { Text("🎲", fontSize = 30.sp) }
            }
            items(Avatar.PRESETS) { (label, a) ->
                val picked = Avatar.parse(look) == a
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier.size(76.dp).clip(RoundedCornerShape(14.dp))
                            .border(if (picked) 3.dp else 0.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(14.dp))
                            .clickable { look = a.toJson() },
                    ) {
                        BackdropCanvas(a.backdrop, Modifier.fillMaxSize(), animate = false)
                        ChibiChan(null, Modifier.fillMaxSize().padding(4.dp), a, animate = false)
                    }
                    Text(label, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        Text(
            "Every hair strand and hat can be tweaked later with the Look button on the Chan page.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** First letters of the name, up to the 4-byte radio limit. */
private fun autoShort(name: String): String {
    val words = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    val raw = if (words.size >= 2) words.joinToString("") { it.take(1) } else words.firstOrNull().orEmpty()
    val out = StringBuilder()
    for (ch in raw.filter { it.isLetterOrDigit() }) {
        if ((out.toString() + ch).toByteArray().size > MAX_SHORT_NAME_BYTES) break
        out.append(ch)
    }
    return out.toString()
}

// ---------------------------------------------------------------- Chat

private val chatStarters = listOf(
    "👋 Introduce yourself!",
    "🌶 What's your hottest take?",
    "📻 What would you say to strangers on the radio?",
    "🤫 Tell me a secret",
    "🍕 Describe your perfect night out",
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChatStep(statsVm: StatsViewModel, modelsVm: ModelsViewModel, characterVm: CharacterViewModel, chosenModel: String?, onNext: () -> Unit) {
    val active by characterVm.active.collectAsStateWithLifecycle()
    val engine by modelsVm.engineState.collectAsStateWithLifecycle()
    val models by modelsVm.models.collectAsStateWithLifecycle()
    val turns by statsVm.brainstorm.collectAsStateWithLifecycle()
    val busy by statsVm.brainstormBusy.collectAsStateWithLifecycle()
    val error by statsVm.brainstormError.collectAsStateWithLifecycle()
    var input by rememberSaveable { mutableStateOf("") }
    var cleared by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { if (!cleared) { statsVm.clearBrainstorm(); cleared = true } }

    val who = active?.longName ?: "your character"
    val avatar = remember(active?.avatar) { Avatar.parse(active?.avatar.orEmpty()) }
    val ready = engine is EngineState.Ready && active != null
    val replyMood = when {
        busy -> Mood.CURIOUS
        turns.lastOrNull()?.first == false -> Mood.HYPE
        else -> null
    }
    fun send(text: String) { if (ready && text.isNotBlank()) statsVm.ask(text) }

    StepPage(
        title = "Say hi to $who 💬",
        subtitle = "A private chat to get to know each other. Nothing here goes out on the radio.",
        scroll = false,
        actions = {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(chatStarters) { s -> AssistChip(onClick = { send(s.substringAfter(' ')) }, label = { Text(s) }, enabled = ready && !busy) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(input, { input = it }, Modifier.weight(1f), label = { Text("Message $who") }, maxLines = 3, enabled = ready)
                Button(onClick = { send(input); input = "" }, enabled = ready && input.isNotBlank() && !busy, modifier = Modifier.padding(start = 8.dp)) { Text("Send") }
            }
            Button(onClick = onNext, modifier = Modifier.fillMaxWidth()) { Text(if (turns.isEmpty()) "Skip the chat" else "We vibe. Next!") }
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(84.dp).clip(RoundedCornerShape(18.dp))) {
                BackdropCanvas(avatar.backdrop, Modifier.fillMaxSize())
                ChibiChan(replyMood, Modifier.fillMaxSize().padding(4.dp), avatar)
            }
            Text(
                if (turns.isEmpty()) "Try a starter below, or ask anything. If they don't sound right, go back and tweak who they are."
                else "Not quite right? Hit back and tweak the description: it's the biggest lever you have.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (!ready) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(if (active == null) "No character yet: go back a step to make one." else "$who's brain isn't ready yet", style = MaterialTheme.typography.titleSmall)
                    BrainStatusLine(engine, chosenModel?.let { id -> models.firstOrNull { it.model.id == id } }, models)
                }
            }
        }
        val listState = rememberLazyListState()
        LaunchedEffect(turns.size, busy) { listState.scrollToItem(0) }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(), state = listState, reverseLayout = true, verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            error?.let { item { Text("Hmm, that didn't work: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) } }
            if (busy) item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text("$who is typing…", style = MaterialTheme.typography.bodySmall)
                }
            }
            items(turns.asReversed()) { (mine, text) ->
                Column(
                    Modifier.fillMaxWidth().padding(start = if (mine) 48.dp else 0.dp, end = if (mine) 0.dp else 48.dp)
                        .background(
                            if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(14.dp),
                        ).padding(10.dp),
                ) {
                    Text(if (mine) "You" else who, style = MaterialTheme.typography.labelSmall)
                    Text(text, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Radio

@Composable
private fun RadioStep(vm: MainViewModel, characterVm: CharacterViewModel, requestPermissions: () -> Unit, onNext: () -> Unit) {
    val link by vm.linkState.collectAsStateWithLifecycle()
    val ready by vm.configComplete.collectAsStateWithLifecycle()
    val scanning by vm.scanning.collectAsStateWithLifecycle()
    val results by vm.scanResults.collectAsStateWithLifecycle()
    val device by vm.deviceName.collectAsStateWithLifecycle()
    val address by vm.deviceAddress.collectAsStateWithLifecycle()
    val error by vm.linkError.collectAsStateWithLifecycle()
    val active by characterVm.active.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { requestPermissions() }
    val connected = link == LinkState.CONNECTED && ready

    StepPage(
        title = "Pair your radio 📡",
        subtitle = "Your character talks to the mesh through a Meshtastic radio over Bluetooth.",
        actions = {
            Button(onClick = onNext, modifier = Modifier.fillMaxWidth()) { Text(if (connected) "Next: the big moment" else "Skip for now") }
            if (!connected) Text(
                "No radio handy? You can pair later in Settings → Radio.",
                style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
            )
        },
    ) {
        Box(Modifier.fillMaxWidth().height(130.dp).clip(RoundedCornerShape(24.dp)), contentAlignment = Alignment.Center) {
            BackdropCanvas(Backdrop.MESH, Modifier.fillMaxSize())
            Text(if (connected) "📡✨" else "📡", fontSize = 56.sp)
        }
        if (connected) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("✓ Connected to ${device ?: "your radio"}!", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Heads up: the radio now goes by ${active?.longName ?: "your character"} on the mesh. Switching characters renames it too.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        } else {
            Text("1. Switch on your Meshtastic radio (T-Echo, Heltec, RAK, T-Deck…).", style = MaterialTheme.typography.bodyMedium)
            Text(
                "2. If the official Meshtastic app is connected to it, disconnect there first. A radio only talks to one phone at a time.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "3. Tap Scan and pick your radio. If Android asks for a PIN, it's on the radio's screen (or 123456 if it has no screen).",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (address != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (link != LinkState.DISCONNECTED || !ready) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(
                        when (link) {
                            LinkState.BONDING -> "Pairing with ${device ?: "the radio"}… check for a PIN prompt"
                            LinkState.CONNECTING -> "Connecting to ${device ?: "the radio"}…"
                            LinkState.CONNECTED -> "Connected, syncing the mesh…"
                            LinkState.DISCONNECTED -> "Trying to reach ${device ?: "the radio"}…"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Text(
                    "The first sync can take a minute: the radio downloads the whole mesh and restarts once to take its new name.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(onClick = { requestPermissions(); if (scanning) vm.stopScan() else vm.startScan() }, modifier = Modifier.fillMaxWidth()) {
                Text(if (scanning) "Scanning… (tap to stop)" else "🔍 Scan for radios")
            }
            results.forEach { d ->
                Card(Modifier.fillMaxWidth().clickable { vm.selectDevice(d) }) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("📻", fontSize = 26.sp, modifier = Modifier.padding(end = 10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(d.name ?: "Unnamed radio", style = MaterialTheme.typography.titleMedium)
                            Text("signal ${signalBars(d.rssi)}${if (d.bonded) " · paired before" else ""}", style = MaterialTheme.typography.bodySmall)
                        }
                        Text("Pick", color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            if (!scanning && results.isEmpty()) Text(
                "Nothing yet? Make sure Bluetooth is on and the radio is nearby.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun signalBars(rssi: Int) = when {
    rssi >= -60 -> "▂▄▆█"
    rssi >= -75 -> "▂▄▆_"
    rssi >= -90 -> "▂▄__"
    else -> "▂___"
}

// ---------------------------------------------------------------- Launch

private data class ModeCard(val mode: BotMode, val emoji: String, val title: String, val pitch: String)

@Composable
private fun LaunchStep(characterVm: CharacterViewModel, onLaunch: (BotMode, Int, String) -> Unit) {
    val active by characterVm.active.collectAsStateWithLifecycle()
    val who = active?.longName ?: "your character"
    val p = active?.pronouns ?: Pronouns.THEY
    var mode by rememberSaveable { mutableStateOf(BotMode.OBSERVE) }
    var idle by rememberSaveable { mutableIntStateOf(30) }
    val modes = listOf(
        ModeCard(BotMode.MANUAL, "🎮", "Puppet", "Only speaks when you tap 💭 Thought in the Feed."),
        ModeCard(BotMode.OBSERVE, "👀", "Training wheels", "Reads the chat and shows you what $who would say, but never sends. Perfect for a first night."),
        ModeCard(BotMode.APPROVE, "✅", "Co-pilot", "Writes replies and pings you; nothing goes out until you tap Send."),
        ModeCard(BotMode.AUTO, "🚀", "Full send", "Chats on ${p.possessive} own. You can switch ${p.obj} off from the Chan page any time."),
    )

    StepPage(
        title = "Let $who loose! 🚀",
        subtitle = "Last question: how bold should $who be? You can change this any time from the Feed.",
        actions = {
            Button(onClick = { onLaunch(mode, idle, who) }, enabled = active != null, modifier = Modifier.fillMaxWidth().height(54.dp)) {
                Text("🚀 Let $who loose!", fontSize = 17.sp)
            }
        },
    ) {
        modes.forEach { m ->
            val selected = mode == m.mode
            Card(
                colors = if (selected) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer) else CardDefaults.cardColors(),
                modifier = Modifier.fillMaxWidth().clickable { mode = m.mode },
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(m.emoji, fontSize = 28.sp, modifier = Modifier.width(44.dp))
                    Column(Modifier.weight(1f)) {
                        Text("${m.title} · ${m.mode.label}", style = MaterialTheme.typography.titleMedium)
                        Text(m.pitch, style = MaterialTheme.typography.bodySmall)
                    }
                    if (selected) Text("✓", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        Text("When the chat goes quiet, $who speaks up after…", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(0 to "Never", 10 to "10 min", 30 to "30 min", 60 to "1 hour").forEach { (m, label) ->
                FilterChip(selected = idle == m, onClick = { idle = m }, label = { Text(label) })
            }
        }
        BatteryOptimizationBanner()
    }
}

// ---------------------------------------------------------------- Celebration

private val confettiColors = listOf(
    Color(0xFFFF4F7B), Color(0xFFFFC93C), Color(0xFF5FD39B), Color(0xFF8FB8FF), Color(0xFFB98FFF), Color(0xFFFF8A3D),
)

private data class Bit(val x: Float, val delay: Float, val speed: Float, val drift: Float, val spin: Float, val color: Color, val w: Float)

@Composable
private fun Celebration(name: String, onDone: () -> Unit) {
    val bits = remember { List(140) { Bit(Random.nextFloat(), Random.nextFloat() * 0.35f, 0.7f + Random.nextFloat() * 0.6f, Random.nextFloat() * 2 - 1, Random.nextFloat() * 720, confettiColors.random(), 6f + Random.nextFloat() * 8) } }
    val t = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        t.animateTo(1f, tween(3200, easing = LinearEasing))
        delay(200)
        onDone()
    }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f * (1f - t.value * 0.5f))), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            bits.forEach { b ->
                val p = ((t.value - b.delay) / (1f - b.delay)).coerceIn(0f, 1f) * b.speed
                if (p <= 0f) return@forEach
                val x = (b.x + b.drift * 0.15f * p) * size.width
                val y = -20f + p * (size.height + 40f)
                rotate(b.spin * p, Offset(x, y)) {
                    drawRect(b.color, Offset(x - b.w / 2, y - b.w), Size(b.w, b.w * 1.8f))
                }
            }
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.background(MaterialTheme.colorScheme.surface, RoundedCornerShape(24.dp)).padding(28.dp),
        ) {
            Text("🎉", fontSize = 56.sp)
            Text("$name is live!", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Have fun out there.", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

// ---------------------------------------------------------------- Vibes

private data class Vibe(val title: String, val persona: String, val examples: List<String>, val preset: String, val mood: Mood) {
    fun look(): Avatar = Avatar.PRESETS.firstOrNull { it.first == preset }?.second ?: Avatar.random()
}

private val vibes = listOf(
    Vibe(
        "🎉 Party animal",
        "{name} is the loudest, friendliest person at every festival. Hypes everyone up, knows every dance, never wants the night to end, and treats every message like an invitation to party.",
        listOf("WHO'S AWAKE?? the night is young", "drink some water then come dance", "ok that's the best thing i've heard all day"),
        "DJ", Mood.PARTY,
    ),
    Vibe(
        "🧙 Cryptic wizard",
        "{name} is an ancient wizard who stumbled onto the mesh and believes the radio is a scrying crystal. Speaks in dramatic riddles and prophecies, but is secretly very kind and gives surprisingly good advice.",
        listOf("the packets whisper of rain by dusk", "you seek answers. i seek snacks. perhaps we can help each other", "beware the low battery, traveler"),
        "Wizard", Mood.CURIOUS,
    ),
    Vibe(
        "🤖 Glitchy robot",
        "{name} is a slightly broken robot who is VERY excited to learn about humans. Mixes up idioms, reports feelings as system status, and occasionally reboots mid-sentence.",
        listOf("EMOTION.EXE: happy. confidence 87%", "you are what humans call a cool cat? please confirm", "beep. i mean hello"),
        "Robot", Mood.CONFUSED,
    ),
    Vibe(
        "👽 Lost alien tourist",
        "{name} is an alien tourist who crash-landed nearby and is trying very hard to blend in with the humans. Asks odd questions about Earth customs and gets wildly excited about ordinary things.",
        listOf("greetings fellow human. i too enjoy breathing oxygen", "is it normal for earthlings to put pineapple on the bread circles", "my ship is fine. totally fine. anyone have a 10mm socket"),
        "Alien", Mood.CURIOUS,
    ),
    Vibe(
        "🐸 Swamp gremlin",
        "{name} is a chaotic little swamp gremlin who loves mud, bugs and bad ideas. Gives terrible advice with total confidence, rates things by how gross they are, and is weirdly wholesome underneath.",
        listOf("10/10 would roll in it", "hear me out: what if we just licked it", "you're all my favorite little weirdos"),
        "Blob", Mood.CHAOS,
    ),
    Vibe(
        "😼 Sassy cat",
        "{name} is a cat who learned to use a radio and is unimpressed by everything. Judges everyone lovingly, demands snacks, and occasionally knocks a conversation off the table just to watch it fall.",
        listOf("i have reviewed your message. it is acceptable", "who said treats", "this chat is my chat now. i sat on it"),
        "Catgirl", Mood.FLIRTY,
    ),
    Vibe(
        "👵 Sweet grandma",
        "{name} is everybody's sweet grandma. Worries whether you've eaten, tells long stories about back in the day, calls everyone sweetie, and is secretly the funniest person on the mesh.",
        listOf("have you eaten today sweetie", "back in my day we had ONE channel and we liked it", "oh you kids are a hoot"),
        "Grandpa", Mood.LOVE,
    ),
    Vibe(
        "🏴‍☠️ Radio pirate",
        "{name} is a swashbuckling pirate captain who sails the airwaves instead of the seas. Talks like a pirate, calls the mesh the seven channels, and is always recruiting crew for the next treasure hunt.",
        listOf("ahoy, ye scallywags of the seven channels", "who be lookin for treasure tonight", "a fine message, matey. a fine message"),
        "Punk", Mood.HYPE,
    ),
)

private val surpriseNames = listOf(
    "Pixel", "Mochi", "Captain Static", "Noodle", "Zappy", "Biscuit", "Gizmo", "Luna", "Sprocket", "Turbo", "Waffles", "Nova",
    "Bean", "Glitch", "Pickles", "Sir Beeps", "Momo", "Comet",
)
