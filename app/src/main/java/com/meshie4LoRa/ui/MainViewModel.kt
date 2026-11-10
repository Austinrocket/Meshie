package com.meshie4LoRa.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.meshie4LoRa.MeshChanApp
import com.meshie4LoRa.brain.BotMode
import com.meshie4LoRa.llm.EngineState
import com.meshie4LoRa.data.BROADCAST_NODE
import com.meshie4LoRa.data.MessageEntity
import com.meshie4LoRa.data.SLEEP_FOREVER
import com.meshie4LoRa.radio.FoundDevice
import com.meshie4LoRa.radio.RadioService
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val c = (app as MeshChanApp).container

    val linkState = c.transport.state
    val linkError = c.transport.lastError
    val configComplete = c.mesh.configComplete
    val myNodeNum = c.mesh.myNodeNum
    val channels = c.mesh.channels
    val deviceName = c.mesh.radioLabel.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val deviceAddress = c.settings.deviceAddress.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val operatingChannel = c.settings.operatingChannel.stateIn(viewModelScope, SharingStarted.Eagerly, 0)
    val nodes = c.mesh.nodes.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val messages = c.mesh.recentMessages().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val myNode = combine(c.mesh.nodes, c.mesh.myNodeNum) { nodes, me -> nodes.firstOrNull { it.nodeNum == me } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val unreadByNode = c.mesh.unreadByNode.map { list -> list.associate { it.fromNode to it.count } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())
    val activeCharacterName = c.characters.active.map { it?.longName ?: "your persona" }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "your persona")
    val botMode = c.settings.botMode.stateIn(viewModelScope, SharingStarted.Eagerly, BotMode.OBSERVE)
    val sleepUntil = c.settings.sleepUntil.stateIn(viewModelScope, SharingStarted.Eagerly, 0L)
    val decisions = c.db.decisions().observeRecent(100).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val brainStatus = combine(c.brain.status, c.memory.status) { b, m -> b ?: m }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val feelings = c.db.relationships().observeAll()
        .map { list -> list.filter { it.feeling.isNotBlank() }.associate { it.nodeNum to (it.feeling + if (it.feelingLocked) " 🔒" else "") } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())
    /** How many facts the bot remembers about each person. */
    val factCounts = c.db.memories().observeAll()
        .map { list -> list.mapNotNull { it.nodeNum }.groupingBy { it }.eachCount() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())
    /** Everyone the bot has stored any thought about (facts, a feeling or a summary). */
    val knownPeople = combine(factCounts, c.db.relationships().observeAll()) { facts, rels ->
        facts.keys + rels.filter { it.feeling.isNotBlank() || it.summary.isNotBlank() }.map { it.nodeNum }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
    val modelReady = c.engine.state.map { it is EngineState.Ready }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setBotMode(mode: BotMode) = viewModelScope.launch { c.settings.setBotMode(mode) }

    /** Whether the welcome tour is showing; null until settings have loaded. Offered once on a fresh install. */
    val touring = MutableStateFlow<Boolean?>(null)

    init {
        viewModelScope.launch {
            touring.value = c.settings.tutorial.first() == null && c.characters.characters.first().isEmpty()
        }
    }

    /** True when the tour was started again from the Chan page rather than offered on first launch. */
    var replaying = false
        private set

    fun startTutorial() {
        replaying = true
        touring.value = true
    }

    fun endTutorial(done: Boolean) = viewModelScope.launch {
        c.settings.setTutorial(if (done) "done" else "skipped")
        touring.value = false
    }

    /** The tour's last step: pick how the persona behaves, switch it on, and let it go. */
    fun letLoose(mode: BotMode, idleMinutes: Int) = viewModelScope.launch {
        c.settings.setBotMode(mode)
        c.settings.setBehavior(c.settings.behavior.first().copy(idleMinutes = idleMinutes))
        c.settings.setSleepUntil(0)
    }

    fun sleepFor(minutes: Int) = viewModelScope.launch { c.settings.setSleepUntil(System.currentTimeMillis() + minutes * 60_000L) }

    fun turnOff() = viewModelScope.launch { c.settings.setSleepUntil(SLEEP_FOREVER) }

    fun wake() = viewModelScope.launch { c.settings.setSleepUntil(0) }

    /** A request from another screen to show a person in the People tab; [memory] opens their Memory tab. */
    data class OpenPerson(val nodeNum: Long, val memory: Boolean)

    private val _openPerson = MutableStateFlow<OpenPerson?>(null)
    val openPerson: StateFlow<OpenPerson?> = _openPerson

    fun openPerson(nodeNum: Long, memory: Boolean) {
        _openPerson.value = OpenPerson(nodeNum, memory)
    }

    fun consumeOpenPerson() {
        _openPerson.value = null
    }

    private val _openFeed = MutableStateFlow(false)
    val openFeed: StateFlow<Boolean> = _openFeed
    fun openFeed() { _openFeed.value = true }
    fun consumeOpenFeed() { _openFeed.value = false }

    /** Which DM chat is open on the People tab, so its notifications are skipped. */
    val viewingPerson = MutableStateFlow<Long?>(null)

    fun dmWith(peer: Long, me: Long?) = c.db.messages().observeDm(peer, me ?: -1L)

    /** Tells the notifier what's on screen (null = app in the background). */
    fun setVisibleScreen(screen: String?) { c.notifier.visible.value = screen }

    fun toggleFavorite(m: MessageEntity) = viewModelScope.launch {
        c.db.messages().setFavorite(m.id, !m.favorite)
        _toast.value = if (m.favorite) "Removed from favorites" else "⭐ Added to favorites"
    }

    /** A message the operator asked for from the Feed menu (reply or callout), shown for editing before it's sent. */
    sealed interface Proposal {
        val title: String
        data class Thinking(override val title: String) : Proposal
        data class Ready(override val title: String, val text: String) : Proposal
        data class Failed(override val title: String, val why: String) : Proposal
    }

    private val _proposal = MutableStateFlow<Proposal?>(null)
    val proposal: StateFlow<Proposal?> = _proposal
    private var proposalJob: Job? = null
    private var lastAsk: (() -> Unit)? = null

    private fun propose(title: String, reason: String, ask: suspend () -> com.meshie4LoRa.brain.BotBrain.Thought) {
        proposalJob?.cancel()
        lastAsk = { propose(title, reason, ask) }
        proposalReason = reason
        _proposal.value = Proposal.Thinking(title)
        proposalJob = viewModelScope.launch {
            _proposal.value = try {
                val t = ask()
                t.text?.let { Proposal.Ready(title, it) } ?: Proposal.Failed(title, t.rejected + (t.refusal?.let { "\nModel's words: \"$it\"" } ?: ""))
            } catch (e: Exception) {
                Log.w("MeshChan.UI", "Proposal failed", e)
                Proposal.Failed(title, e.message ?: "error")
            }
        }
    }

    private var proposalReason = ""

    fun respondTo(picked: List<MessageEntity>, title: String) =
        propose(title, if (picked.size > 1) "reply to ${picked.size} picked messages, approved by you" else "reply you asked for, approved by you") {
            c.brain.proposeReply(picked)
        }

    /** Uses the idle source setting (thought / favorites / mix) for what to say. */
    fun callOut(nodeNum: Long, name: String) = propose("Call out $name", "callout of $name, approved by you") {
        val favorites = when (c.settings.behavior.first().idleSource) {
            com.meshie4LoRa.data.IdleSource.THOUGHT -> false
            com.meshie4LoRa.data.IdleSource.FAVORITES -> true
            com.meshie4LoRa.data.IdleSource.MIX -> kotlin.random.Random.nextBoolean()
        }
        val fromFavorites = if (favorites) c.brain.proposeFromFavorites(nodeNum) else null
        fromFavorites?.takeIf { it.text != null } ?: c.brain.proposeThought(nodeNum)
    }

    /** Manual mode: a thought using the Next thought settings on the Chan page (source and callout). */
    fun proposeNextThought() = propose("Next thought", "thought you asked for, approved by you") { c.brain.proposeIdle().first }

    fun proposeAgain() { lastAsk?.invoke() }

    fun sendProposal(text: String) = viewModelScope.launch {
        c.brain.sendThought(text, proposalReason, idle = false)
        _proposal.value = null
    }

    fun dismissProposal() {
        proposalJob?.cancel()
        _proposal.value = null
    }

    fun toggleFavorite(d: com.meshie4LoRa.data.DecisionEntity) = viewModelScope.launch {
        c.db.decisions().setFavorite(d.id, !d.favorite)
        _toast.value = if (d.favorite) "Removed from favorites" else "⭐ Added to favorites"
    }

    fun sendDraft(decisionId: Long, text: String) = viewModelScope.launch {
        try {
            c.brain.sendDraft(decisionId, text)
            c.drafts.cancel(decisionId)
        } catch (e: Exception) {
            _toast.value = "Send failed: ${e.message}"
        }
    }

    fun discardDraft(decisionId: Long) = viewModelScope.launch {
        c.brain.discardDraft(decisionId)
        c.drafts.cancel(decisionId)
    }
    val dmPeers = c.mesh.dmPeers.map { it.toSet() }.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    private val _scanResults = MutableStateFlow<List<FoundDevice>>(emptyList())
    val scanResults: StateFlow<List<FoundDevice>> = _scanResults
    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning
    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast
    private var scanJob: Job? = null

    fun startScan() {
        scanJob?.cancel()
        _scanResults.value = emptyList()
        _scanning.value = true
        scanJob = viewModelScope.launch {
            try {
                withTimeout(15_000) {
                    c.transport.scan().collect { d ->
                        _scanResults.update { list -> (list.filter { it.address != d.address } + d).sortedByDescending { it.rssi } }
                    }
                }
            } catch (_: TimeoutCancellationException) {
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) _toast.value = e.message
            } finally {
                _scanning.value = false
            }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
    }

    fun selectDevice(device: FoundDevice) {
        stopScan()
        viewModelScope.launch {
            c.settings.setDevice(device.address, device.name)
            RadioService.start(getApplication())
        }
    }

    fun forgetDevice() {
        viewModelScope.launch {
            c.settings.setDevice(null, null)
            RadioService.stop(getApplication())
        }
    }

    fun ensureServiceRunning() {
        if (deviceAddress.value != null) RadioService.start(getApplication())
    }

    fun setOperatingChannel(index: Int) {
        viewModelScope.launch { c.settings.setOperatingChannel(index) }
    }

    fun sendText(text: String, to: Long = BROADCAST_NODE) {
        viewModelScope.launch {
            try {
                c.mesh.sendText(text, operatingChannel.value, to)
            } catch (e: Exception) {
                Log.w("MeshChan.UI", "Send failed", e)
                _toast.value = "Send failed: ${e.message}"
            }
        }
    }

    fun renameRadio(longName: String, shortName: String) {
        viewModelScope.launch {
            try {
                c.mesh.setOwner(longName.trim(), shortName.trim())
                // The active character owns the radio's names, so keep it in sync.
                c.characters.active.first()?.let { c.characters.save(it.copy(longName = longName.trim(), shortName = shortName.trim())) }
                _toast.value = "Radio renamed"
            } catch (e: Exception) {
                Log.w("MeshChan.UI", "Rename failed", e)
                _toast.value = "Rename failed: ${e.message}"
            }
        }
    }

    fun announceName() {
        viewModelScope.launch {
            try {
                c.mesh.announceNodeInfo()
                _toast.value = "Name announced to the mesh"
            } catch (e: Exception) {
                _toast.value = "Announce failed: ${e.message}"
            }
        }
    }

    fun markRead(nodeNum: Long) {
        viewModelScope.launch { c.mesh.markRead(nodeNum) }
    }

    fun setIgnored(nodeNum: Long, ignored: Boolean) {
        viewModelScope.launch { c.db.nodes().setIgnored(nodeNum, ignored) }
    }

    fun clearToast() {
        _toast.value = null
    }
}
