package com.meshie4LoRa.ui

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.meshie4LoRa.MeshChanApp
import com.meshie4LoRa.data.LlmSettings
import com.meshie4LoRa.llm.ChatMessage
import com.meshie4LoRa.llm.GenParams
import com.meshie4LoRa.llm.GenStats
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ChatLine(val role: String, val text: String, val stats: GenStats? = null)

const val DEFAULT_TEST_SYSTEM_PROMPT =
    "You are a friendly chatbot. " +
        "Reply in one or two short sentences (under 200 characters). No emoji spam."

class ModelsViewModel(app: Application) : AndroidViewModel(app) {
    private val c = (app as MeshChanApp).container

    val models = c.models.models.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val activeModelId = c.settings.activeModelId.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val engineState = c.engine.state
    val busy = c.engine.busy
    val llmSettings = c.settings.llmSettings.stateIn(viewModelScope, SharingStarted.Eagerly, LlmSettings())

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast

    private val _chat = MutableStateFlow<List<ChatLine>>(emptyList())
    val chat: StateFlow<List<ChatLine>> = _chat
    val systemPrompt = MutableStateFlow(DEFAULT_TEST_SYSTEM_PROMPT)
    private val _bench = MutableStateFlow<String?>(null)
    val bench: StateFlow<String?> = _bench
    private var genJob: Job? = null

    init {
        // Test chat talks to the active character by default.
        viewModelScope.launch {
            combine(c.characters.active, c.settings.eventNotes) { ch, _ -> ch }.collect { ch ->
                if (ch != null) systemPrompt.value = c.characters.systemPrompt(ch)
            }
        }
    }

    private fun launchSafely(what: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: Exception) {
                Log.w("MeshChan.UI", "$what failed", e)
                _toast.value = "$what failed: ${e.message}"
            }
        }
    }

    fun download(id: String) = launchSafely("Download") { c.models.download(id) }
    fun cancelDownload(id: String) = launchSafely("Cancel") { c.models.cancelDownload(id) }
    fun delete(id: String) = launchSafely("Delete") { c.models.delete(id) }
    fun activate(id: String) = launchSafely("Load") { c.models.activate(id) }
    fun unload() = launchSafely("Unload") { c.engine.unload() }
    fun addFromUrl(url: String) = launchSafely("Add model") { c.models.addFromUrl(url) }
    fun importFile(uri: Uri) = launchSafely("Import") {
        _toast.value = "Importing…"
        c.models.importFile(uri)
        _toast.value = "Imported"
    }

    /** Saves settings; reloads the model if context size or threads changed. */
    fun saveSettings(s: LlmSettings) = launchSafely("Save settings") {
        val old = c.settings.llmSettings.first()
        c.settings.setLlmSettings(s)
        if (old.nCtx != s.nCtx || old.nThreads != s.nThreads || old.repackWeights != s.repackWeights) c.models.loadActive()
    }

    private suspend fun params(): GenParams {
        val s = c.settings.llmSettings.first()
        return GenParams(s.maxTokens, s.temperature, s.topP, s.topK, s.minP, s.repeatPenalty)
    }

    fun sendChat(text: String) {
        if (text.isBlank() || genJob?.isActive == true) return
        _chat.update { it + ChatLine("user", text) + ChatLine("assistant", "") }
        genJob = viewModelScope.launch {
            try {
                val history = buildList {
                    add(ChatMessage("system", systemPrompt.value))
                    _chat.value.dropLast(1).forEach { add(ChatMessage(it.role, it.text)) }
                }
                val result = c.engine.chat(history, params()) { piece ->
                    _chat.update { lines -> lines.dropLast(1) + lines.last().copy(text = lines.last().text + piece) }
                }
                _chat.update { lines -> lines.dropLast(1) + lines.last().copy(stats = result.stats) }
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    _chat.update { lines -> lines.dropLast(1) + ChatLine("error", e.message ?: "Generation failed") }
                }
            }
        }
    }

    fun stop() {
        c.engine.cancel()
        genJob?.cancel()
    }

    fun clearChat() {
        stop()
        _chat.value = emptyList()
    }

    /** Posts a test-chat reply to the operating channel as the persona. */
    fun sendToChannel(text: String) = viewModelScope.launch {
        try {
            c.brain.sendThought(text, "from the test chat, sent by you", idle = false)
            _toast.value = "Sent to the channel"
        } catch (e: Exception) {
            _toast.value = "Send failed: ${e.message}"
        }
    }

    fun benchmark() {
        if (genJob?.isActive == true) return
        _bench.value = "Running…"
        genJob = viewModelScope.launch {
            try {
                val prompt = listOf(
                    ChatMessage("system", DEFAULT_TEST_SYSTEM_PROMPT),
                    ChatMessage("user", "Tell me a short story about a radio that learned to talk. ".repeat(8)),
                )
                val r = c.engine.chat(prompt, params().copy(maxTokens = 64, temperature = 0.7f))
                _bench.value = "Prompt: %d tok @ %.1f tok/s · Generate: %d tok @ %.1f tok/s".format(
                    r.stats.promptTokens, r.stats.promptTps, r.stats.genTokens, r.stats.genTps,
                )
            } catch (e: Exception) {
                _bench.value = "Benchmark failed: ${e.message}"
            }
        }
    }

    fun clearToast() {
        _toast.value = null
    }
}
