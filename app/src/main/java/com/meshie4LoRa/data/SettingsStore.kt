package com.meshie4LoRa.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.meshie4LoRa.brain.BotMode
import com.meshie4LoRa.brain.DataField
import com.meshie4LoRa.brain.PromptTemplate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

/** sleepUntil value meaning "off until the operator turns the bot back on". */
const val SLEEP_FOREVER = Long.MAX_VALUE

/** Where automatic idle messages come from. */
enum class IdleSource(val label: String) { THOUGHT("💭 Thought"), FAVORITES("⭐ Favorites"), MIX("🎲 Mix") }

data class LlmSettings(
    val nCtx: Int = 4096,
    val nThreads: Int = 4,
    val maxTokens: Int = 160,
    val temperature: Float = 0.8f,
    val topP: Float = 0.95f,
    val topK: Int = 40,
    val minP: Float = 0.05f,
    val repeatPenalty: Float = 1.1f,
    val repackWeights: Boolean = true,
)

data class BehaviorSettings(
    val debounceSec: Int = 4,
    /** 0 = idle chatter off. */
    val idleMinutes: Int = 20,
    /** Max messages of chat history the model sees. */
    val historySize: Int = 6,
    /** Only messages from the last N minutes are shown to the model. */
    val historyMinutes: Int = 20,
    /** Max consecutive bot replies to one node with no other human speaking. */
    val loopGuard: Int = 4,
    /** Second model pass that approves each reply before it goes out (unreliable on 1B models, so off by default). */
    val reviewPass: Boolean = false,
    /** Use the operator's own manual messages as style examples. */
    val learnFromManual: Boolean = true,
    /** Post once when someone comes nearby (needs "Use my radio's GPS"). */
    val greetArrivals: Boolean = false,
    val idleSource: IdleSource = IdleSource.THOUGHT,
    /** Aim idle chatter and proposed thoughts at a random person who's on the air right now. */
    val callout: Boolean = false,
    /** 1 = off. Higher pushes replies harder toward the persona (classifier-free guidance; roughly doubles generation time). */
    val personaStrength: Float = 1.5f,
    /** Hard rate limit: at most this many sent messages per [rateLimitWindowSec]; 0 = off. Only blocks automatic sends. */
    val rateLimitCount: Int = 0,
    val rateLimitWindowSec: Int = 600,
)

class SettingsStore(private val context: Context) {
    private object Keys {
        val deviceAddress = stringPreferencesKey("device_address")
        val deviceName = stringPreferencesKey("device_name")
        val operatingChannel = intPreferencesKey("operating_channel")
        val activeModelId = stringPreferencesKey("active_model_id")
        val nCtx = intPreferencesKey("llm_n_ctx")
        val nThreads = intPreferencesKey("llm_n_threads")
        val maxTokens = intPreferencesKey("llm_max_tokens")
        val temperature = floatPreferencesKey("llm_temperature")
        val topP = floatPreferencesKey("llm_top_p")
        val topK = intPreferencesKey("llm_top_k")
        val minP = floatPreferencesKey("llm_min_p")
        val repeatPenalty = floatPreferencesKey("llm_repeat_penalty")
        val repackWeights = booleanPreferencesKey("llm_repack_weights")
        val eventNotes = stringPreferencesKey("event_notes")
        val dataFields = stringSetPreferencesKey("data_fields")
        val botMode = stringPreferencesKey("bot_mode")
        val sleepUntil = longPreferencesKey("sleep_until")
        val debounceSec = intPreferencesKey("debounce_sec")
        val idleMinutes = intPreferencesKey("idle_minutes")
        val historySize = intPreferencesKey("history_size")
        val loopGuard = intPreferencesKey("loop_guard")
        val historyMinutes = intPreferencesKey("history_minutes")
        val reviewPass = booleanPreferencesKey("review_pass")
        val learnFromManual = booleanPreferencesKey("learn_from_manual")
        val greetArrivals = booleanPreferencesKey("greet_arrivals")
        val idleSource = stringPreferencesKey("idle_source")
        val callout = booleanPreferencesKey("callout")
        val personaStrength = floatPreferencesKey("persona_strength")
        val rateLimitCount = intPreferencesKey("rate_limit_count")
        val rateLimitWindowSec = intPreferencesKey("rate_limit_window_sec")
        val contextResetAt = longPreferencesKey("context_reset_at")
        val myNodeNum = longPreferencesKey("my_node_num")
        val memoryResetAt = longPreferencesKey("memory_reset_at")
        val tutorial = stringPreferencesKey("tutorial")
        fun template(t: PromptTemplate) = stringPreferencesKey("tpl_${t.name}")
        fun phrase(p: com.meshie4LoRa.brain.PromptPhrase) = stringPreferencesKey("phrase_${p.name}")
        fun moodWords(mood: String) = stringPreferencesKey("mood_words_$mood")
    }

    /** Operator's extra mood keywords, raw text keyed by mood name. */
    fun moodWords(moods: List<String>): Flow<Map<String, String>> = context.dataStore.data.map { p ->
        moods.associateWith { p[Keys.moodWords(it)].orEmpty() }
    }

    suspend fun setMoodWords(words: Map<String, String>) {
        context.dataStore.edit { p ->
            words.forEach { (mood, text) -> if (text.isBlank()) p.remove(Keys.moodWords(mood)) else p[Keys.moodWords(mood)] = text.trim() }
        }
    }

    /** Effective template text (operator override or built-in default). */
    val templates: Flow<Map<PromptTemplate, String>> = context.dataStore.data.map { p ->
        PromptTemplate.entries.associateWith { p[Keys.template(it)] ?: it.default }
    }

    /** Effective phrase text (operator override, possibly empty, or the default). */
    val phrases: Flow<Map<com.meshie4LoRa.brain.PromptPhrase, String>> = context.dataStore.data.map { p ->
        com.meshie4LoRa.brain.PromptPhrase.entries.associateWith { p[Keys.phrase(it)] ?: it.default }
    }

    suspend fun setPhrase(ph: com.meshie4LoRa.brain.PromptPhrase, text: String?) {
        context.dataStore.edit { if (text == null || text == ph.default) it.remove(Keys.phrase(ph)) else it[Keys.phrase(ph)] = text }
    }

    suspend fun setTemplate(t: PromptTemplate, text: String?) {
        context.dataStore.edit { if (text == null || text == t.default) it.remove(Keys.template(t)) else it[Keys.template(t)] = text }
    }

    /** Messages before this time are never shown to the model ("fresh start"). */
    val contextResetAt: Flow<Long> = context.dataStore.data.map { it[Keys.contextResetAt] ?: 0L }

    suspend fun resetContext() {
        context.dataStore.edit { it[Keys.contextResetAt] = System.currentTimeMillis() }
    }

    /** Messages before this time are never used to learn memories or as style examples (set by global memory wipes). */
    val memoryResetAt: Flow<Long> = context.dataStore.data.map { it[Keys.memoryResetAt] ?: 0L }

    suspend fun resetMemory() {
        val now = System.currentTimeMillis()
        context.dataStore.edit {
            it[Keys.memoryResetAt] = now
            it[Keys.contextResetAt] = now
        }
    }

    val myNodeNum: Flow<Long?> = context.dataStore.data.map { it[Keys.myNodeNum] }

    /** How the welcome tour ended: null = never offered, otherwise "done" or "skipped". */
    val tutorial: Flow<String?> = context.dataStore.data.map { it[Keys.tutorial] }

    suspend fun setTutorial(outcome: String) {
        context.dataStore.edit { it[Keys.tutorial] = outcome }
    }

    suspend fun setMyNodeNum(num: Long) {
        context.dataStore.edit { it[Keys.myNodeNum] = num }
    }

    val botMode: Flow<BotMode> = context.dataStore.data.map { p ->
        BotMode.entries.firstOrNull { it.name == p[Keys.botMode] } ?: BotMode.OBSERVE
    }
    val sleepUntil: Flow<Long> = context.dataStore.data.map { it[Keys.sleepUntil] ?: 0L }
    val behavior: Flow<BehaviorSettings> = context.dataStore.data.map { p ->
        val d = BehaviorSettings()
        BehaviorSettings(
            debounceSec = p[Keys.debounceSec] ?: d.debounceSec,
            idleMinutes = p[Keys.idleMinutes] ?: d.idleMinutes,
            historySize = p[Keys.historySize] ?: d.historySize,
            historyMinutes = p[Keys.historyMinutes] ?: d.historyMinutes,
            loopGuard = p[Keys.loopGuard] ?: d.loopGuard,
            reviewPass = p[Keys.reviewPass] ?: d.reviewPass,
            learnFromManual = p[Keys.learnFromManual] ?: d.learnFromManual,
            greetArrivals = p[Keys.greetArrivals] ?: d.greetArrivals,
            idleSource = IdleSource.entries.firstOrNull { it.name == p[Keys.idleSource] } ?: d.idleSource,
            callout = p[Keys.callout] ?: d.callout,
            personaStrength = p[Keys.personaStrength] ?: d.personaStrength,
            rateLimitCount = p[Keys.rateLimitCount] ?: d.rateLimitCount,
            rateLimitWindowSec = p[Keys.rateLimitWindowSec] ?: d.rateLimitWindowSec,
        )
    }

    suspend fun setBotMode(mode: BotMode) {
        context.dataStore.edit { it[Keys.botMode] = mode.name }
    }

    suspend fun setSleepUntil(until: Long) {
        context.dataStore.edit { it[Keys.sleepUntil] = until }
    }

    suspend fun setBehavior(b: BehaviorSettings) {
        context.dataStore.edit {
            it[Keys.debounceSec] = b.debounceSec
            it[Keys.idleMinutes] = b.idleMinutes
            it[Keys.historySize] = b.historySize
            it[Keys.historyMinutes] = b.historyMinutes
            it[Keys.loopGuard] = b.loopGuard
            it[Keys.reviewPass] = b.reviewPass
            it[Keys.learnFromManual] = b.learnFromManual
            it[Keys.greetArrivals] = b.greetArrivals
            it[Keys.idleSource] = b.idleSource.name
            it[Keys.callout] = b.callout
            it[Keys.personaStrength] = b.personaStrength
            it[Keys.rateLimitCount] = b.rateLimitCount
            it[Keys.rateLimitWindowSec] = b.rateLimitWindowSec
        }
    }

    val eventNotes: Flow<String> = context.dataStore.data.map { it[Keys.eventNotes].orEmpty() }
    val dataFields: Flow<Set<DataField>> = context.dataStore.data.map { p ->
        p[Keys.dataFields]?.mapNotNull { name -> DataField.entries.firstOrNull { it.name == name } }?.toSet() ?: DataField.defaults
    }

    suspend fun setEventNotes(notes: String) {
        context.dataStore.edit { it[Keys.eventNotes] = notes }
    }

    suspend fun setDataField(field: DataField, enabled: Boolean) {
        context.dataStore.edit { p ->
            val current = p[Keys.dataFields] ?: DataField.defaults.map { it.name }.toSet()
            p[Keys.dataFields] = if (enabled) current + field.name else current - field.name
        }
    }

    val activeModelId: Flow<String?> = context.dataStore.data.map { it[Keys.activeModelId] }
    val llmSettings: Flow<LlmSettings> = context.dataStore.data.map { p ->
        val d = LlmSettings()
        LlmSettings(
            nCtx = p[Keys.nCtx] ?: d.nCtx,
            nThreads = p[Keys.nThreads] ?: d.nThreads,
            maxTokens = p[Keys.maxTokens] ?: d.maxTokens,
            temperature = p[Keys.temperature] ?: d.temperature,
            topP = p[Keys.topP] ?: d.topP,
            topK = p[Keys.topK] ?: d.topK,
            minP = p[Keys.minP] ?: d.minP,
            repeatPenalty = p[Keys.repeatPenalty] ?: d.repeatPenalty,
            repackWeights = p[Keys.repackWeights] ?: d.repackWeights,
        )
    }

    suspend fun setActiveModel(id: String?) {
        context.dataStore.edit { if (id == null) it.remove(Keys.activeModelId) else it[Keys.activeModelId] = id }
    }

    suspend fun setLlmSettings(s: LlmSettings) {
        context.dataStore.edit {
            it[Keys.nCtx] = s.nCtx
            it[Keys.nThreads] = s.nThreads
            it[Keys.maxTokens] = s.maxTokens
            it[Keys.temperature] = s.temperature
            it[Keys.topP] = s.topP
            it[Keys.topK] = s.topK
            it[Keys.minP] = s.minP
            it[Keys.repeatPenalty] = s.repeatPenalty
            it[Keys.repackWeights] = s.repackWeights
        }
    }

    val deviceAddress: Flow<String?> = context.dataStore.data.map { it[Keys.deviceAddress] }
    val deviceName: Flow<String?> = context.dataStore.data.map { it[Keys.deviceName] }
    val operatingChannel: Flow<Int> = context.dataStore.data.map { it[Keys.operatingChannel] ?: 0 }

    suspend fun setDevice(address: String?, name: String?) {
        context.dataStore.edit {
            if (address == null) it.remove(Keys.deviceAddress) else it[Keys.deviceAddress] = address
            if (name == null) it.remove(Keys.deviceName) else it[Keys.deviceName] = name
        }
    }

    suspend fun setOperatingChannel(index: Int) {
        context.dataStore.edit { it[Keys.operatingChannel] = index }
    }
}
