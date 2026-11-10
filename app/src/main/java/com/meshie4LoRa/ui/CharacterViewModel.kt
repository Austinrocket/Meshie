package com.meshie4LoRa.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.meshie4LoRa.MeshChanApp
import com.meshie4LoRa.brain.DataField
import com.meshie4LoRa.brain.PersonaFile
import com.meshie4LoRa.brain.Prompts
import com.meshie4LoRa.data.BROADCAST_NODE
import com.meshie4LoRa.data.BehaviorSettings
import com.meshie4LoRa.data.CharacterEntity
import com.meshie4LoRa.llm.EngineState
import com.meshie4LoRa.llm.GenParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PromptPreview(
    val kind: com.meshie4LoRa.brain.PromptKind,
    val messages: List<com.meshie4LoRa.llm.ChatMessage>,
    /** Exactly what the model reads (chat template applied); null when no model is loaded. */
    val raw: String?,
) {
    val approxTokens: Int get() = ((raw?.length ?: messages.sumOf { it.content.length }) / 4)
}

class CharacterViewModel(app: Application) : AndroidViewModel(app) {
    private val c = (app as MeshChanApp).container

    val characters = c.characters.characters.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val active = c.characters.active.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val eventNotes = c.settings.eventNotes.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val behavior = c.settings.behavior.stateIn(viewModelScope, SharingStarted.Eagerly, BehaviorSettings())
    val templates = c.settings.templates.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())
    val phrases = c.settings.phrases.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    fun setPhrase(p: com.meshie4LoRa.brain.PromptPhrase, text: String?) = viewModelScope.launch {
        c.settings.setPhrase(p, text)
        _toast.value = if (text == null) "Phrase reset" else "Phrase saved"
    }

    fun setTemplate(t: com.meshie4LoRa.brain.PromptTemplate, text: String?) = viewModelScope.launch {
        c.settings.setTemplate(t, text)
        _toast.value = if (text == null) "${t.title} reset" else "${t.title} saved"
    }

    fun setBehavior(b: BehaviorSettings) = viewModelScope.launch {
        c.settings.setBehavior(b)
        _toast.value = "Behavior saved"
    }
    val dataFields = c.settings.dataFields.stateIn(viewModelScope, SharingStarted.Eagerly, DataField.defaults)
    val nodes = c.mesh.nodes.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val myNodeNum = c.mesh.myNodeNum
    val engineState = c.engine.state

    private val _preview = MutableStateFlow<PromptPreview?>(null)
    val preview: StateFlow<PromptPreview?> = _preview
    private val _sample = MutableStateFlow<String?>(null)
    val sample: StateFlow<String?> = _sample
    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast
    private var sampleJob: Job? = null

    fun newCharacter() = CharacterEntity(
        longName = "",
        shortName = "",
        persona = Prompts.DEFAULT_PERSONA,
        rules = Prompts.DEFAULT_RULES,
        examples = Prompts.DEFAULT_EXAMPLES,
        avatar = com.meshie4LoRa.ui.stats.Avatar.random().toJson(),
    )

    /** Writes [ch], its look and its memories about itself (nothing about mesh users) to [uri]. */
    fun exportPersona(ch: CharacterEntity, uri: android.net.Uri) = viewModelScope.launch {
        try {
            val json = PersonaFile.encode(ch, c.db.memories().self())
            withContext(Dispatchers.IO) {
                getApplication<Application>().contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
                    ?: error("couldn't open the file")
            }
            _toast.value = "Exported ${ch.longName}"
        } catch (e: Exception) {
            Log.w("MeshChan.UI", "Persona export failed", e)
            _toast.value = "Export failed: ${e.message}"
        }
    }

    /** Adds the persona in [uri] as a new, inactive persona and merges its memories into the shared pool. */
    fun importPersona(uri: android.net.Uri) = viewModelScope.launch {
        try {
            val json = withContext(Dispatchers.IO) {
                getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
                    val out = java.io.ByteArrayOutputStream()
                    val buf = ByteArray(8192)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        require(out.size() <= PersonaFile.MAX_FILE_BYTES) { "file is too big" }
                    }
                    out.toByteArray().decodeToString()
                } ?: error("couldn't open the file")
            }
            val p = withContext(Dispatchers.Default) { PersonaFile.decode(json) }
            c.characters.save(p.character)
            val added = c.memory.merge(p.memories)
            _toast.value = "Imported ${p.character.longName}" + if (added > 0) " and $added memories" else ""
        } catch (e: Exception) {
            Log.w("MeshChan.UI", "Persona import failed", e)
            _toast.value = "Import failed: ${e.message}"
        }
    }

    private val _writing = MutableStateFlow(false)
    val writing: StateFlow<Boolean> = _writing

    /** Has the model invent a persona from [idea]; [onDone] gets null if it couldn't. */
    fun writePersona(idea: String, onDone: (com.meshie4LoRa.brain.GeneratedPersona?) -> Unit) {
        if (_writing.value) return
        _writing.value = true
        viewModelScope.launch {
            // Shuffled: small models tend to pick whatever is listed first.
            val looks = com.meshie4LoRa.ui.stats.Avatar.PRESETS.map { it.first }.filter { it != "Classic" }.shuffled()
            val result = try {
                c.brain.writePersona(idea, looks)
            } catch (e: Exception) {
                Log.w("MeshChan.UI", "Persona writer failed", e)
                null
            } finally {
                _writing.value = false
            }
            onDone(result)
        }
    }

    /** Saves a brand-new persona and puts it on the radio. */
    fun createAndActivate(ch: CharacterEntity) = viewModelScope.launch {
        val id = c.characters.save(ch.copy(longName = ch.longName.trim(), shortName = ch.shortName.trim()))
        c.characters.activate(id)
    }

    fun save(ch: CharacterEntity) = viewModelScope.launch {
        c.characters.save(ch.copy(longName = ch.longName.trim(), shortName = ch.shortName.trim()))
        _toast.value = "Saved ${ch.longName}"
    }

    fun activate(id: Long) = viewModelScope.launch {
        c.characters.activate(id)
        _toast.value = "Activated; the radio will be renamed"
    }

    fun delete(id: Long) = viewModelScope.launch { c.characters.delete(id) }

    fun setEventNotes(notes: String) = viewModelScope.launch {
        c.settings.setEventNotes(notes)
        _toast.value = "Event notes saved"
    }

    fun setField(field: DataField, on: Boolean) = viewModelScope.launch { c.settings.setDataField(field, on) }

    /** [peer] = null previews the channel; otherwise a DM with (or memory pass about) that node. */
    fun buildPreview(peer: Long?, kind: com.meshie4LoRa.brain.PromptKind = com.meshie4LoRa.brain.PromptKind.REPLY) = viewModelScope.launch {
        val ch = active.value ?: c.characters.active.first() ?: return@launch
        val dm = peer?.takeIf { it != BROADCAST_NODE }
        val msgs = if (kind == com.meshie4LoRa.brain.PromptKind.MEMORY) {
            val node = dm ?: nodes.value.filter { it.nodeNum != myNodeNum.value }.maxByOrNull { it.lastHeard }?.nodeNum
            node?.let { c.memory.memoryPrompt(it, ch, since = 0)?.messages }.orEmpty()
        } else c.characters.previewMessages(kind, ch, dm)
        _preview.value = PromptPreview(kind, msgs, c.engine.renderPrompt(msgs))
        _sample.value = null
    }

    /** Generates (but never sends) a reply from the previewed context. */
    fun generateSample(peer: Long?) {
        if (sampleJob?.isActive == true) return
        if (c.engine.state.value !is EngineState.Ready) {
            _toast.value = "Load a model first (Settings → Models)"
            return
        }
        sampleJob = viewModelScope.launch {
            try {
                val ch = c.characters.active.first() ?: return@launch
                val msgs = c.characters.replyContext(ch, peer?.takeIf { it != BROADCAST_NODE })
                val s = c.settings.llmSettings.first()
                _sample.value = "Thinking…"
                val r = c.engine.chat(msgs, GenParams(s.maxTokens, s.temperature, s.topP, s.topK, s.minP, s.repeatPenalty)) { piece ->
                    _sample.value = (_sample.value?.takeUnless { it == "Thinking…" } ?: "") + piece
                }
                _sample.value = r.text.trim() + "\n\n(%d bytes · %d new prompt tokens, %d cached · %.1f s)".format(
                    r.text.trim().toByteArray().size, r.stats.promptTokens, r.stats.reusedTokens, (r.stats.promptMs + r.stats.genMs) / 1000,
                )
            } catch (e: Exception) {
                Log.w("MeshChan.UI", "Sample failed", e)
                _sample.value = "Failed: ${e.message}"
            }
        }
    }

    fun clearToast() {
        _toast.value = null
    }
}
