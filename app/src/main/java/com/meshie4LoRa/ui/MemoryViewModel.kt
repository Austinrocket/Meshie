package com.meshie4LoRa.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.meshie4LoRa.MeshChanApp
import com.meshie4LoRa.data.MemoryEntity
import com.meshie4LoRa.data.MemorySource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MemoryViewModel(app: Application) : AndroidViewModel(app) {
    private val c = (app as MeshChanApp).container
    private val m = c.memory

    val selfMemories = m.observeSelf().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val memoryCount = m.memoryCount.stateIn(viewModelScope, SharingStarted.Eagerly, 0)
    val status = m.status

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast

    fun facts(nodeNum: Long) = m.observeFacts(nodeNum)
    fun relationship(nodeNum: Long) = m.observeRelationship(nodeNum)
    fun cooldown(nodeNum: Long) = c.db.cooldowns().observe(nodeNum)

    fun setCooldown(nodeNum: Long, seconds: Long) = go(if (seconds > 0) "Cooldown saved" else "Cooldown off") {
        if (seconds > 0) c.db.cooldowns().upsert(com.meshie4LoRa.data.CooldownEntity(nodeNum, seconds))
        else c.db.cooldowns().delete(nodeNum)
    }
    fun feelingLog(nodeNum: Long) = m.observeFeelingLog(nodeNum)

    private fun go(msg: String? = null, block: suspend () -> Unit) = viewModelScope.launch {
        try {
            block()
            msg?.let { _toast.value = it }
        } catch (e: Exception) {
            _toast.value = "Failed: ${e.message}"
        }
    }

    fun setFeeling(nodeNum: Long, feeling: String, locked: Boolean) = go("Feeling saved") { m.setFeeling(nodeNum, feeling, locked) }
    fun setLocked(nodeNum: Long, locked: Boolean) = go { m.setFeelingLocked(nodeNum, locked) }
    fun setSummary(nodeNum: Long, summary: String) = go("Summary saved") { m.setSummary(nodeNum, summary) }
    fun addFact(nodeNum: Long?, text: String) = go {
        if (text.isNotBlank()) m.saveMemory(MemoryEntity(nodeNum = nodeNum, text = text, source = MemorySource.MANUAL))
    }
    fun togglePin(mem: MemoryEntity) = go { m.saveMemory(mem.copy(pinned = !mem.pinned)) }
    fun delete(id: Long) = go { m.deleteMemory(id) }
    fun clearAutoFor(nodeNum: Long) = go("Cleared auto memories") { m.clearAutoFor(nodeNum) }
    fun forget(nodeNum: Long) = go("Forgot everything about them") { m.clearEverythingFor(nodeNum) }
    fun clearAllAuto() = go("Cleared all auto memories") { m.clearAllAuto() }
    fun clearAllUnpinned() = go("Cleared everything not pinned") { m.clearAllUnpinned() }
    fun wipeAll() = go("All memories wiped") { m.wipeAll() }

    /** Everything the model could carry over: memories, chat context, style examples, favorites, positions, prompt cache. */
    fun fullReset() = go(null) {
        m.wipeAll()
        c.db.messages().clearFavorites()
        c.db.decisions().clearFavorites()
        c.db.positions().prune(Long.MAX_VALUE)
        c.models.loadActive()
        val ch = c.characters.active.first()
        _toast.value = "Full reset done: ${ch?.longName ?: "the persona"} starts from ${ch?.pronouns?.possessive ?: "its"} persona only"
    }
    fun updateNow(nodeNum: Long) = go("Memory updated") {
        c.characters.active.first()?.let { m.updateNotes(nodeNum, it) }
    }

    fun clearToast() {
        _toast.value = null
    }
}
