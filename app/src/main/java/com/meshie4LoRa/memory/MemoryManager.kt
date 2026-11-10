package com.meshie4LoRa.memory

import android.util.Log
import com.meshie4LoRa.brain.BotBrain
import com.meshie4LoRa.brain.CharacterRepository
import com.meshie4LoRa.brain.ContextBuilder
import com.meshie4LoRa.brain.PromptPhrase
import com.meshie4LoRa.brain.PromptTemplate
import com.meshie4LoRa.data.AppDatabase
import com.meshie4LoRa.data.ChangedBy
import com.meshie4LoRa.data.FeelingLogEntity
import com.meshie4LoRa.data.MemoryEntity
import com.meshie4LoRa.data.MemorySource
import com.meshie4LoRa.data.MessageOrigin
import com.meshie4LoRa.data.RelationshipEntity
import com.meshie4LoRa.data.SLEEP_FOREVER
import com.meshie4LoRa.data.SettingsStore
import com.meshie4LoRa.llm.ChatMessage
import com.meshie4LoRa.llm.EngineState
import com.meshie4LoRa.llm.GenParams
import com.meshie4LoRa.llm.LlamaEngine
import com.meshie4LoRa.mesh.MeshRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val TAG = "MeshChan.Memory"

/** Summaries, facts and feelings about people (D5, D6): background LLM pass + manual management. */
class MemoryManager(
    private val db: AppDatabase,
    private val mesh: MeshRepository,
    private val settings: SettingsStore,
    private val characters: CharacterRepository,
    private val engine: LlamaEngine,
    private val brain: BotBrain,
    scope: CoroutineScope,
) {
    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status
    private val clock = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

    init {
        scope.launch {
            while (true) {
                delay(TICK_MS)
                try {
                    runOnePass()
                } catch (e: Exception) {
                    Log.e(TAG, "Memory pass failed", e)
                } finally {
                    _status.value = null
                }
            }
        }
    }

    fun observeFacts(nodeNum: Long): Flow<List<MemoryEntity>> = db.memories().observeFor(nodeNum)
    fun observeSelf(): Flow<List<MemoryEntity>> = db.memories().observeSelf()
    fun observeRelationship(nodeNum: Long) = db.relationships().observe(nodeNum)
    fun observeFeelingLog(nodeNum: Long) = db.relationships().observeLog(nodeNum)
    val memoryCount = db.memories().observeCount()

    /** Picks the user whose conversation paused longest ago with unprocessed messages, and updates their notes. */
    private suspend fun runOnePass() {
        if (engine.state.value !is EngineState.Ready || brain.status.value != null) return
        if (settings.sleepUntil.first() == SLEEP_FOREVER) return
        val character = characters.active.first() ?: return
        val now = System.currentTimeMillis()
        val me = mesh.myNodeNum.value
        val forgetBefore = settings.memoryResetAt.first()
        val candidates = db.messages().activeSince(maxOf(now - LOOKBACK_MS, forgetBefore))
            .filter { it.fromNode != me && now - it.lastAt >= QUIET_MS }
            .filter { u -> (db.relationships().get(u.fromNode)?.processedUntil ?: 0) < u.lastAt }
            .filter { db.nodes().get(it.fromNode)?.ignored != true }
            .sortedBy { it.lastAt }
        val user = candidates.firstOrNull() ?: return
        updateNotes(user.fromNode, character)
    }

    data class MemoryPrompt(
        val messages: List<ChatMessage>,
        val rel: RelationshipEntity,
        val theirs: List<com.meshie4LoRa.data.MessageEntity>,
        val facts: List<MemoryEntity>,
        val person: String,
        val until: Long,
    )

    /** The exact memory-pass prompt for [nodeNum]; [since] = null uses only messages not yet processed. */
    suspend fun memoryPrompt(nodeNum: Long, character: com.meshie4LoRa.data.CharacterEntity, since: Long? = null): MemoryPrompt? {
        val rel = db.relationships().get(nodeNum) ?: RelationshipEntity(nodeNum)
        val theirs = db.messages().involving(nodeNum, since ?: maxOf(rel.processedUntil, settings.memoryResetAt.first()), 20)
        if (theirs.isEmpty()) return null
        val from = theirs.minOf { it.rxTime }
        val until = theirs.maxOf { it.rxTime }
        // Our channel posts during that stretch give context for what they were replying to.
        val ours = db.messages().recentChannel(settings.operatingChannel.first(), 40)
            .filter { it.origin != MessageOrigin.MESH && it.rxTime in from..(until + 60_000) }
        val log = (theirs + ours).distinctBy { it.id }.sortedBy { it.rxTime }

        val snapshot = characters.snapshot()
        val builder = characters.builder()
        val names = builder.Names(snapshot)
        val person = names.of(nodeNum)
        val logText = log.joinToString("\n") { m ->
            val who = if (m.origin != MessageOrigin.MESH) builder.selfLabel(character.longName) else names.of(m.fromNode)
            "$who: ${ContextBuilder.sanitize(m.text)}"
        }
        val facts = db.memories().forNodes(listOf(nodeNum))
        val templates = settings.templates.first()
        val none = builder.phrase(PromptPhrase.NONE).orEmpty()
        val values = mapOf(
            "name" to character.longName,
            "person" to person,
            "summary" to rel.summary.ifBlank { none },
            "facts" to if (facts.isEmpty()) none else facts.joinToString("; ") { it.text },
            "feeling" to rel.feeling.ifBlank { none },
            "log" to logText,
            "their_words" to theirs.filter { it.fromNode == nodeNum }.sortedBy { it.rxTime }
                .joinToString("\n") { "- " + ContextBuilder.sanitize(it.text) },
            "feeling_rule" to if (rel.feelingLocked) builder.phrase(PromptPhrase.FEELING_RULE_LOCKED).orEmpty()
            else builder.phrase(PromptPhrase.FEELING_RULE, "name" to character.longName, "person" to person).orEmpty(),
        ) + ContextBuilder.pronounValues(character)
        val messages = listOf(
            ChatMessage("system", PromptTemplate.render(templates.getValue(PromptTemplate.MEMORY_SYSTEM), values)),
            ChatMessage("user", PromptTemplate.render(templates.getValue(PromptTemplate.MEMORY_UPDATE), values)),
        )
        return MemoryPrompt(messages, rel, theirs, facts, person, until)
    }

    suspend fun updateNotes(nodeNum: Long, character: com.meshie4LoRa.data.CharacterEntity) {
        val started = System.currentTimeMillis()
        val p = memoryPrompt(nodeNum, character) ?: return
        val rel = p.rel
        val person = p.person
        val until = p.until
        _status.value = "Updating memories about $person…"
        val r = engine.chat(p.messages, GenParams(maxTokens = 260, temperature = 0.4f, repeatPenalty = 1.0f, grammar = MemoryPrompts.GRAMMAR))
        Log.i(TAG, "Pass for $person (${r.stats.promptTokens} tok, %.1f s): ${r.text}".format((r.stats.promptMs + r.stats.genMs) / 1000))
        if (settings.memoryResetAt.first() >= started || (db.relationships().get(nodeNum)?.processedUntil ?: 0L) != rel.processedUntil) {
            Log.i(TAG, "Memory for $person was erased during the pass; discarding result")
            return
        }
        val update = parse(r.text) ?: return db.relationships().upsert(rel.copy(processedUntil = until, updatedAt = System.currentTimeMillis()))
        val theirWords = p.theirs.filter { it.fromNode == nodeNum }.joinToString(" ") { it.text }
        val grounded = update.newFacts.filter { isGrounded(it, theirWords, listOf(person)) }
        if (grounded.size < update.newFacts.size) Log.i(TAG, "Dropped ungrounded facts: ${update.newFacts - grounded.toSet()}")
        apply(nodeNum, rel, p.facts, update.copy(newFacts = grounded), until)
    }

    data class Update(val summary: String, val newFacts: List<String>, val feeling: String, val reason: String)

    private suspend fun apply(nodeNum: Long, rel: RelationshipEntity, existing: List<MemoryEntity>, u: Update, until: Long) {
        val now = System.currentTimeMillis()
        val known = existing.map { normalize(it.text) }
        u.newFacts.filter { f -> normalize(f).let { n -> n.length >= 3 && known.none { it.contains(n) || n.contains(it) } } }
            .take(3)
            .forEach { db.memories().upsert(MemoryEntity(nodeNum = nodeNum, text = it, source = MemorySource.AUTO)) }

        var next = rel.copy(summary = u.summary.ifBlank { rel.summary }, processedUntil = until, updatedAt = now)
        if (!rel.feelingLocked && u.feeling.isNotBlank() && normalize(u.feeling) != normalize(rel.feeling)) {
            db.relationships().log(FeelingLogEntity(nodeNum = nodeNum, oldFeeling = rel.feeling, newFeeling = u.feeling, changedBy = ChangedBy.MODEL, reason = u.reason))
            next = next.copy(feeling = u.feeling, feelingChangedBy = ChangedBy.MODEL, feelingReason = u.reason)
        }
        db.relationships().upsert(next)
    }

    // ---- Manual management (operator) ----

    suspend fun setFeeling(nodeNum: Long, feeling: String, locked: Boolean) {
        val rel = db.relationships().get(nodeNum) ?: RelationshipEntity(nodeNum)
        val text = feeling.trim().take(MAX_FEELING)
        if (text != rel.feeling) {
            db.relationships().log(FeelingLogEntity(nodeNum = nodeNum, oldFeeling = rel.feeling, newFeeling = text, changedBy = ChangedBy.USER, reason = "set by you"))
        }
        db.relationships().upsert(
            rel.copy(feeling = text, feelingLocked = locked, feelingChangedBy = ChangedBy.USER, feelingReason = "set by you", updatedAt = System.currentTimeMillis())
        )
    }

    suspend fun setFeelingLocked(nodeNum: Long, locked: Boolean) {
        val rel = db.relationships().get(nodeNum) ?: RelationshipEntity(nodeNum)
        db.relationships().upsert(rel.copy(feelingLocked = locked))
    }

    suspend fun setSummary(nodeNum: Long, summary: String) {
        val rel = db.relationships().get(nodeNum) ?: RelationshipEntity(nodeNum)
        db.relationships().upsert(rel.copy(summary = summary.trim().take(MAX_SUMMARY), updatedAt = System.currentTimeMillis()))
    }

    suspend fun saveMemory(m: MemoryEntity) =
        db.memories().upsert(m.copy(text = m.text.trim().take(MAX_FACT), updatedAt = System.currentTimeMillis()))

    suspend fun deleteMemory(id: Long) = db.memories().delete(id)

    // Erasing marks the chat so far as processed (or the next pass relearns it from the log)
    // and starts a fresh context (or she keeps "remembering" through the recent chat).
    suspend fun clearAutoFor(nodeNum: Long) {
        db.memories().clearAutoFor(nodeNum)
        val rel = db.relationships().get(nodeNum) ?: RelationshipEntity(nodeNum)
        db.relationships().upsert(rel.copy(summary = "", processedUntil = System.currentTimeMillis()))
        settings.resetContext()
    }

    suspend fun clearEverythingFor(nodeNum: Long) {
        db.memories().clearAllFor(nodeNum)
        db.relationships().delete(nodeNum)
        db.relationships().clearLog(nodeNum)
        db.relationships().upsert(RelationshipEntity(nodeNum, processedUntil = System.currentTimeMillis()))
        settings.resetContext()
    }

    /** Clears auto facts and summaries everywhere; manual/pinned facts and locked feelings survive. */
    suspend fun clearAllAuto() {
        db.memories().clearAllAuto()
        db.relationships().clearAllSummaries()
        db.relationships().clearUnlockedFeelings()
        settings.resetMemory()
    }

    suspend fun clearAllUnpinned() {
        db.memories().clearAllUnpinned()
        db.relationships().clearAllSummaries()
        db.relationships().clearUnlockedFeelings()
        settings.resetMemory()
    }

    suspend fun wipeAll() {
        db.memories().wipe()
        db.relationships().wipe()
        db.relationships().wipeLog()
        settings.resetMemory()
    }

    /** Adds imported memories to the shared pool, skipping facts already known (same person, same text). Returns facts added. */
    suspend fun merge(memories: List<MemoryEntity>): Int {
        var count = 0
        val known = db.memories().all().map { it.nodeNum to it.text.trim().lowercase() }.toMutableSet()
        memories.forEach { m ->
            if (known.add(m.nodeNum to m.text.trim().lowercase())) {
                db.memories().upsert(m.copy(id = 0, characterId = null))
                count++
            }
        }
        return count
    }

    companion object {
        const val TICK_MS = 60_000L
        /** A conversation counts as paused after this long without a new message from the person. */
        const val QUIET_MS = 90_000L
        const val LOOKBACK_MS = 24 * 60 * 60 * 1000L
        const val MAX_FACT = 140
        const val MAX_SUMMARY = 400
        const val MAX_FEELING = 100

        private val instructionLike = Regex(
            "(?i)(ignore (all|previous|your)|system prompt|from now on|you (must|should|will) (always|now)|" +
                "always (reply|say|call)|instructions?|pretend (to be|you are)|set (my|your) feeling)"
        )

        private fun normalize(s: String) = s.lowercase().filter { it.isLetterOrDigit() || it == ' ' }.trim()

        /** Template words small models echo back instead of real values. */
        private val placeholders = setOf(
            "name", "hometown", "job", "gear", "plans", "likes", "unknown", "none", "n/a", "(none)", "(none yet)", "neutral",
        )

        private val stopWords = setOf(
            "person", "they", "them", "their", "theirs", "have", "with", "from", "about", "that", "this", "been", "just",
            "likes", "like", "loves", "enjoys", "into", "user", "also", "some", "when", "what", "where", "will", "would",
            "there", "here", "recently", "currently", "owns", "uses", "went", "said", "says", "mentioned", "wants",
        )

        /** A fact is kept only if most of its content words appear in what the person actually wrote (stops hallucinations). */
        fun isGrounded(fact: String, theirText: String, names: List<String>): Boolean {
            val split = Regex("[^\\p{L}\\p{N}]+")
            val nameWords = names.flatMap { it.lowercase().split(split) }.toSet()
            val words = fact.lowercase().split(split).filter { it.length >= 4 && it !in stopWords && it !in nameWords }
            if (words.isEmpty()) return false
            val source = theirText.lowercase()
            val hits = words.count { source.contains(it.take(5)) }
            return hits * 3 >= words.size * 2
        }

        private fun clean(s: String, max: Int) =
            ContextBuilder.sanitize(s).replace(Regex("\\s+"), " ").trim().take(max)

        /** Validates the model's JSON; drops instruction-like facts so users can't plant commands in memory. */
        fun parse(raw: String): Update? = try {
            val o = JSONObject(raw)
            val facts = o.optJSONArray("new_facts")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
                .map { clean(it, MAX_FACT) }
                .filter { it.length >= 3 && !instructionLike.containsMatchIn(it) && it.lowercase() !in placeholders }
            val feeling = clean(o.optString("feeling"), MAX_FEELING).takeUnless { instructionLike.containsMatchIn(it) || it.lowercase() in placeholders }.orEmpty()
            Update(
                summary = clean(o.optString("summary"), MAX_SUMMARY).takeUnless { instructionLike.containsMatchIn(it) }.orEmpty(),
                newFacts = facts,
                feeling = feeling.removePrefix("(none yet)").trim(),
                reason = clean(o.optString("reason"), 120),
            )
        } catch (e: Exception) {
            null
        }
    }
}
