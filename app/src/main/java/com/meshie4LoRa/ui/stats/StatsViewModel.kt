package com.meshie4LoRa.ui.stats

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.meshie4LoRa.MeshChanApp
import com.meshie4LoRa.data.DecisionOutcome
import com.meshie4LoRa.data.MessageOrigin
import com.meshie4LoRa.data.NodeEntity
import com.meshie4LoRa.data.RelationshipEntity
import com.meshie4LoRa.ui.stats.MoodAnalyzer.Mood
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class Bestie(val node: NodeEntity?, val nodeNum: Long, val facts: Int, val feeling: String)

data class Dex(
    val name: String = "",
    val shortName: String = "",
    val persona: String = "",
    val pronouns: com.meshie4LoRa.data.Pronouns = com.meshie4LoRa.data.Pronouns.THEY,
    val sentAuto: Int = 0,
    val sentManual: Int = 0,
    val heard: Int = 0,
    val talkedTo: Int = 0,
    val skipped: Int = 0,
    val drafts: Int = 0,
    val peopleKnown: Int = 0,
    val facts: Int = 0,
    val feelings: Int = 0,
    val besties: List<Bestie> = emptyList(),
    /** Decayed (~1 h half-life): what she feels right now. */
    val moods: Map<Mood, Double> = emptyMap(),
    /** All-time running sum of mood points. */
    val moodTotals: Map<Mood, Double> = emptyMap(),
    val longest: String? = null,
)

const val MAX_IDLE_MINUTES = 720

sealed interface ThoughtState {
    data object None : ThoughtState
    data object Thinking : ThoughtState
    data class Ready(val text: String, val target: String? = null) : ThoughtState
    data class Failed(val why: String) : ThoughtState
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class StatsViewModel(app: Application) : AndroidViewModel(app) {
    private val c = (app as MeshChanApp).container
    /** Stats count from the last full reset, so a reset zeroes them without deleting the chat log. */
    private val since = c.settings.memoryResetAt

    private val counts = since.flatMapLatest { t ->
        combine(
            c.db.messages().observeOriginCounts(t),
            c.db.messages().observeTalkedTo(t),
            c.db.decisions().observeCount(DecisionOutcome.SKIPPED, t),
            c.db.messages().observeLongestBot(t),
        ) { origins, talked, skipped, longest ->
            val by = origins.associate { it.origin to it.count }
            Dex(
                sentAuto = by[MessageOrigin.BOT_AUTO] ?: 0,
                sentManual = by[MessageOrigin.BOT_MANUAL] ?: 0,
                heard = by[MessageOrigin.MESH] ?: 0,
                talkedTo = talked,
                skipped = skipped,
                longest = longest?.text,
            )
        }
    }

    private val memory = combine(
        c.db.memories().observeAll(),
        c.db.relationships().observeAll(),
        c.mesh.nodes,
        c.mesh.myNodeNum,
    ) { allMemories, allRels, nodes, me ->
        val memories = allMemories.filter { it.nodeNum != me }
        val rels = allRels.filter { it.nodeNum != me }
        val byNode = nodes.associateBy { it.nodeNum }
        val factCounts = memories.filter { it.nodeNum != null }.groupingBy { it.nodeNum!! }.eachCount()
        val known = (factCounts.keys + rels.filter { it.feeling.isNotBlank() || it.summary.isNotBlank() }.map { it.nodeNum }).toSet()
        val relBy = rels.associateBy { it.nodeNum }
        val besties = known.map { n -> Bestie(byNode[n], n, factCounts[n] ?: 0, relBy[n]?.feeling.orEmpty()) }
            .sortedWith(compareByDescending<Bestie> { it.facts }.thenByDescending { it.feeling.isNotBlank() })
            .take(5)
        Triple(known.size to memories.count { it.nodeNum != null }, rels.count { it.feeling.isNotBlank() }, besties to rels)
    }

    /** The operator's word bank text per mood, as typed. */
    val moodWords: StateFlow<Map<Mood, String>> = c.settings.moodWords(Mood.entries.map { it.name })
        .map { raw -> Mood.entries.associateWith { raw[it.name].orEmpty() } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    fun setMoodWords(words: Map<Mood, String>) = viewModelScope.launch {
        c.settings.setMoodWords(words.mapKeys { it.key.name })
    }

    private val mood = since.flatMapLatest { t ->
        combine(c.db.messages().observeAllBot(t), c.db.relationships().observeAll(), moodWords) { mine, rels, words -> Triple(mine, rels, words) }
    }
        .map { (mine, rels, words) ->
            val now = System.currentTimeMillis()
            val extra = words.mapValues { (m, text) -> MoodAnalyzer.parseWords(m, text) }.filterValues { it.isNotEmpty() }
            val signals = mine.map { MoodAnalyzer.Signal(it.text, it.rxTime) } +
                rels.filter { it.feeling.isNotBlank() }.map { r: RelationshipEntity -> MoodAnalyzer.Signal(r.feeling, r.updatedAt, weight = 2.0) }
            MoodAnalyzer.score(signals, now, extra = extra) to MoodAnalyzer.score(signals, now, halfLifeMs = null, extra = extra)
        }.flowOn(kotlinx.coroutines.Dispatchers.Default)

    val dex: StateFlow<Dex> = combine(counts, memory, mood, c.characters.active) { base, mem, moods, ch ->
        base.copy(
            name = ch?.longName.orEmpty(),
            shortName = ch?.shortName.orEmpty(),
            persona = ch?.persona.orEmpty(),
            pronouns = ch?.pronouns ?: com.meshie4LoRa.data.Pronouns.THEY,
            peopleKnown = mem.first.first,
            facts = mem.first.second,
            feelings = mem.second,
            besties = mem.third.first,
            moods = moods.first,
            moodTotals = moods.second,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, Dex())

    val nextIdleAt = c.brain.nextIdleAt
    val brainStatus = c.brain.status
    val sleepUntil = c.settings.sleepUntil.stateIn(viewModelScope, SharingStarted.Eagerly, 0L)
    val idleMinutes = c.settings.behavior.map { it.idleMinutes }.stateIn(viewModelScope, SharingStarted.Eagerly, 20)

    fun setIdleMinutes(minutes: Int) = viewModelScope.launch {
        c.settings.setBehavior(c.settings.behavior.first().copy(idleMinutes = minutes.coerceIn(0, MAX_IDLE_MINUTES)))
    }

    /** Same switch as the Feed tab: off stops replies and idle chatter until switched back on. */
    fun setBotOn(on: Boolean) = viewModelScope.launch {
        c.settings.setSleepUntil(if (on) 0L else com.meshie4LoRa.data.SLEEP_FOREVER)
    }

    fun sleepFor(minutes: Int) = viewModelScope.launch { c.settings.setSleepUntil(System.currentTimeMillis() + minutes * 60_000L) }
    val idleSource = c.settings.behavior.map { it.idleSource }.stateIn(viewModelScope, SharingStarted.Eagerly, com.meshie4LoRa.data.IdleSource.THOUGHT)
    val activeName = c.characters.active.map { it?.longName.orEmpty() }.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val activePronouns = c.characters.active.map { it?.pronouns ?: com.meshie4LoRa.data.Pronouns.THEY }
        .stateIn(viewModelScope, SharingStarted.Eagerly, com.meshie4LoRa.data.Pronouns.THEY)

    fun setIdleSource(s: com.meshie4LoRa.data.IdleSource) = viewModelScope.launch {
        c.settings.setBehavior(c.settings.behavior.first().copy(idleSource = s))
    }

    val callout = c.settings.behavior.map { it.callout }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setCallout(on: Boolean) = viewModelScope.launch {
        c.settings.setBehavior(c.settings.behavior.first().copy(callout = on))
    }

    // Detail pages (counted since the last full reset, like the stats).
    val nodesByNum = c.mesh.nodes.map { list -> list.associateBy { it.nodeNum } }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())
    val myNodeNum = c.mesh.myNodeNum
    val talkedList = since.flatMapLatest { c.db.messages().observeTalkedToList(it) }
    val memoriesList = c.db.memories().observeAll()
    val relationshipsList = c.db.relationships().observeAll()

    fun messagesBy(origin: MessageOrigin) = since.flatMapLatest { c.db.messages().observeByOrigin(origin, it) }

    fun toggleFavorite(m: com.meshie4LoRa.data.MessageEntity) = viewModelScope.launch { c.db.messages().setFavorite(m.id, !m.favorite) }

    private val _thought = MutableStateFlow<ThoughtState>(ThoughtState.None)
    val thought: StateFlow<ThoughtState> = _thought
    private var thoughtJob: Job? = null

    /** A starred message or a starred unsent reply (exactly one id is set). */
    data class Favorite(val text: String, val at: Long, val messageId: Long? = null, val decisionId: Long? = null)

    val favorites = kotlinx.coroutines.flow.combine(c.db.messages().observeFavorites(), c.db.decisions().observeFavorites()) { m, d ->
        (m.map { Favorite(it.text, it.rxTime, messageId = it.id) } + d.map { Favorite("(unsent) " + it.reply.orEmpty(), it.at, decisionId = it.id) })
            .sortedByDescending { it.at }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun unfavorite(f: Favorite) = viewModelScope.launch {
        f.messageId?.let { c.db.messages().setFavorite(it, false) }
        f.decisionId?.let { c.db.decisions().setFavorite(it, false) }
    }

    fun proposeFromFavorites() = propose { c.brain.proposeFromFavorites() }

    /** "Another" repeats whichever kind was asked for last. */
    fun proposeThought() = propose { c.brain.proposeThought() }

    fun proposeAgain() { lastAsk?.let { propose(it) } }

    private var lastAsk: (suspend () -> com.meshie4LoRa.brain.BotBrain.Thought)? = null

    private fun propose(ask: suspend () -> com.meshie4LoRa.brain.BotBrain.Thought) {
        if (thoughtJob?.isActive == true) return
        lastAsk = ask
        _thought.value = ThoughtState.Thinking
        thoughtJob = viewModelScope.launch {
            _thought.value = try {
                val t = ask()
                t.text?.let { ThoughtState.Ready(it, t.target) } ?: ThoughtState.Failed(t.rejected + (t.refusal?.let { "\nModel's words: \"$it\"" } ?: ""))
            } catch (e: Exception) {
                Log.w("MeshChan.UI", "Thought failed", e)
                ThoughtState.Failed(e.message ?: "error")
            }
        }
    }

    // Private brainstorm chat with the persona: (fromOperator, text) turns. Never sent to the mesh.
    private val _brainstorm = MutableStateFlow<List<Pair<Boolean, String>>>(emptyList())
    val brainstorm: StateFlow<List<Pair<Boolean, String>>> = _brainstorm
    private val _brainstormBusy = MutableStateFlow(false)
    val brainstormBusy: StateFlow<Boolean> = _brainstormBusy
    private val _brainstormError = MutableStateFlow<String?>(null)
    val brainstormError: StateFlow<String?> = _brainstormError

    fun ask(text: String) {
        if (text.isBlank() || _brainstormBusy.value) return
        _brainstorm.value = _brainstorm.value + (true to text.trim())
        _brainstormError.value = null
        _brainstormBusy.value = true
        viewModelScope.launch {
            try {
                val t = c.brain.brainstormReply(_brainstorm.value)
                t.text?.let { _brainstorm.value = _brainstorm.value + (false to it) } ?: run { _brainstormError.value = t.rejected }
            } catch (e: Exception) {
                Log.w("MeshChan.UI", "Brainstorm failed", e)
                _brainstormError.value = e.message ?: "error"
            } finally {
                _brainstormBusy.value = false
            }
        }
    }

    /** Puts one of the character's brainstorm replies into the editable thought, ready to send. */
    fun useAsThought(text: String) {
        thoughtJob?.cancel()
        _thought.value = ThoughtState.Ready(text)
    }

    fun writePostFromBrainstorm() {
        val turns = _brainstorm.value
        if (turns.isNotEmpty()) propose { c.brain.brainstormPost(turns) }
    }

    fun clearBrainstorm() {
        _brainstorm.value = emptyList()
        _brainstormError.value = null
    }

    fun sendThought(text: String) = viewModelScope.launch {
        c.brain.sendThought(text)
        _thought.value = ThoughtState.None
    }

    fun dismissThought() {
        thoughtJob?.cancel()
        _thought.value = ThoughtState.None
    }

    fun freshStart() = viewModelScope.launch { c.settings.resetContext() }
}
