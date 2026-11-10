package com.meshie4LoRa.brain

import android.util.Log
import com.meshie4LoRa.data.AppDatabase
import com.meshie4LoRa.data.BROADCAST_NODE
import com.meshie4LoRa.data.CharacterEntity
import com.meshie4LoRa.data.DecisionEntity
import com.meshie4LoRa.data.DecisionOutcome
import com.meshie4LoRa.data.IdleSource
import com.meshie4LoRa.data.MessageEntity
import com.meshie4LoRa.data.MessageOrigin
import com.meshie4LoRa.data.SettingsStore
import com.meshie4LoRa.data.humanDuration
import com.meshie4LoRa.llm.ChatMessage
import com.meshie4LoRa.llm.EngineState
import com.meshie4LoRa.llm.GenParams
import com.meshie4LoRa.llm.LlamaEngine
import com.meshie4LoRa.mesh.MeshRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import kotlin.random.Random

private const val TAG = "MeshChan.Brain"

/** Second-pass verdict: the model names a problem; only "none" sends. Avoids negated yes/no questions small models flip. */
private const val REVIEW_GRAMMAR = """root ::= "{\"problem\": \"" ("none" | "refusal" | "off_topic" | "confused") "\"}"
"""

/** Receives drafts in Approve mode (implemented by the notification layer). */
fun interface DraftSink {
    fun onDraft(decisionId: Long, text: String, targetName: String)
}

/**
 * Pipeline (DESIGN section 7): gate → debounce → self/loop checks → judge → generate → post-process → review → dispatch.
 * Model output only ever becomes mesh text or a fixed JSON decision (D16).
 */
class BotBrain(
    private val db: AppDatabase,
    private val mesh: MeshRepository,
    private val settings: SettingsStore,
    private val characters: CharacterRepository,
    private val engine: LlamaEngine,
    private val scope: CoroutineScope,
) {
    var draftSink: DraftSink? = null

    private val _status = MutableStateFlow<String?>(null)
    /** Human-readable "what the brain is doing", null when idle. */
    val status: StateFlow<String?> = _status

    private val _nextIdleAt = MutableStateFlow<Long?>(null)
    /** When idle chatter will next fire (null = off, asleep, or waiting for a human to speak first). */
    val nextIdleAt: StateFlow<Long?> = _nextIdleAt

    private val work = Channel<Long>(Channel.UNLIMITED)
    private val debounceJobs = mutableMapOf<Long, Job>()
    private val latestTrigger = mutableMapOf<Long, MessageEntity>()
    private val pendingMention = mutableSetOf<Long>()
    private val lock = Mutex()
    private val startedAt = System.currentTimeMillis()
    private val lastGreeted = mutableMapOf<Long, Long>()

    init {
        scope.launch { mesh.incoming.collect { onIncoming(it) } }
        scope.launch {
            for (key in work) {
                try {
                    process(key)
                } catch (e: Exception) {
                    Log.e(TAG, "Decision failed", e)
                    record(key, null, respond = false, reason = "error: ${e.message}", reply = null, outcome = DecisionOutcome.ERROR, t0 = System.currentTimeMillis())
                } finally {
                    _status.value = null
                }
            }
        }
        scope.launch { idleLoop() }
        scope.launch { arrivalLoop() }
    }

    private suspend fun onIncoming(m: MessageEntity) {
        val me = mesh.myNodeNum.value
        if (m.fromNode == me) return
        if (db.nodes().get(m.fromNode)?.ignored == true) return
        val isDm = m.toNode != BROADCAST_NODE
        if (isDm && m.toNode != me) return
        if (!isDm && m.channel != settings.operatingChannel.first()) return
        val key = if (isDm) m.fromNode else BROADCAST_NODE
        val debounceMs = settings.behavior.first().debounceSec * 1000L
        lock.withLock {
            latestTrigger[key] = m
            characters.active.first()?.let { if (mentions(it, m.text)) pendingMention += key }
            debounceJobs[key]?.cancel()
            debounceJobs[key] = scope.launch {
                delay(debounceMs)
                work.send(key)
            }
        }
    }

    private suspend fun process(key: Long) {
        val (queued, mentioned) = lock.withLock { latestTrigger.remove(key) to pendingMention.remove(key) }
        if (queued == null || settings.botMode.first() == BotMode.MANUAL) return
        val t0 = System.currentTimeMillis()
        val isDm = key != BROADCAST_NODE
        suspend fun skip(reason: String, c: CharacterEntity? = null, respond: Boolean = false) {
            record(key, queued, respond, reason, null, DecisionOutcome.SKIPPED, t0, c)
        }
        val character = characters.active.first() ?: return skip("no active character")
        if (settings.sleepUntil.first() > t0) return skip("asleep")
        if (engine.state.value !is EngineState.Ready) return skip("no model loaded")

        val behavior = settings.behavior.first()
        val history = characters.history(key.takeIf { isDm })
        val snapshot = characters.snapshot()
        // She already spoke after the newest human message (e.g. a queued duplicate): don't answer again.
        val trigger = ContextBuilder.lastFromOthers(history, snapshot) ?: return skip("nothing new since my last message", character)
        if (history.lastOrNull()?.let { it.origin != MessageOrigin.MESH } == true) return skip("I spoke last; waiting for someone else", character)
        if (loopGuardTripped(history, trigger.fromNode, behavior.loopGuard)) {
            return skip("loop guard: already replied ${behavior.loopGuard}+ times in a row to the same node", character)
        }

        val builder = characters.builder()
        val notes = settings.eventNotes.first()
        val examples = characters.manualExamples()
        val targetName = builder.Names(snapshot).of(trigger.fromNode)
        cooldownLeftMs(trigger.fromNode)?.let { left ->
            return skip("cooldown: answering $targetName again in ${humanDuration(left / 1000 + 1)}", character)
        }
        if (settings.botMode.first() == BotMode.AUTO) rateLimited()?.let { return skip(it, character) }

        val (respond, reason) = when {
            isDm -> true to "direct message"
            mentioned -> true to "mentioned by name"
            else -> {
                _status.value = "Deciding whether to reply to $targetName…"
                judge(builder.judgeMessages(character, notes, history, snapshot, trigger, examples))
            }
        }
        if (!respond) return skip(reason, character)

        _status.value = "Writing a reply to $targetName…"
        val messages = builder.replyMessages(character, notes, history, snapshot, key.takeIf { isDm }, trigger, examples)
        val result = generate(messages, character, snapshot, key, history, notes, examples, trigger)
        val reply = result.text ?: run {
            record(key, queued, true, "$reason, but ${result.rejected}", result.refusal, DecisionOutcome.SKIPPED, t0, character)
            return
        }
        dispatch(key, trigger, reason, reply, t0, character, targetName)
    }

    private suspend fun idleLoop() {
        while (true) {
            try {
                val due = computeNextIdle()
                _nextIdleAt.value = due
                if (due != null && System.currentTimeMillis() >= due) {
                    val t0 = System.currentTimeMillis()
                    val (r, source) = proposeIdle()
                    val character = characters.active.first()
                    val what = source + (r.target?.let { " → callout $it" } ?: "")
                    if (r.text == null) {
                        record(BROADCAST_NODE, null, false, "idle chatter ($what): ${r.rejected}", r.refusal, DecisionOutcome.SKIPPED, t0, character, idle = true)
                    } else if (character != null) {
                        dispatch(BROADCAST_NODE, null, "chat was quiet ($what)", r.text, t0, character, "the group", idle = true)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Idle chatter failed", e)
            } finally {
                _status.value = null
            }
            delay(15_000)
        }
    }

    private suspend fun arrivalLoop() {
        while (true) {
            delay(60_000)
            try {
                checkArrivals()
            } catch (e: Exception) {
                Log.e(TAG, "Arrival check failed", e)
            } finally {
                _status.value = null
            }
        }
    }

    /** Posts once when someone moves from farther than [ARRIVAL_RADIUS_M] to within it (radio GPS only, opt-in). */
    private suspend fun checkArrivals() {
        if (!settings.behavior.first().greetArrivals || settings.botMode.first() == BotMode.MANUAL) return
        if (DataField.OWN_LOCATION !in settings.dataFields.first()) return
        val now = System.currentTimeMillis()
        if (settings.sleepUntil.first() > now || engine.state.value !is EngineState.Ready) return
        val character = characters.active.first() ?: return
        val snapshot = characters.snapshot()
        val arrived = snapshot.movement.entries.firstOrNull { (num, m) ->
            m.trend == Trend.APPROACHING && m.nowMeters < ARRIVAL_RADIUS_M && m.wasMeters >= ARRIVAL_RADIUS_M &&
                now - (lastGreeted[num] ?: 0L) > GREET_COOLDOWN_MS && snapshot.nodes[num]?.ignored != true
        }?.key ?: return
        lastGreeted[arrived] = now
        val builder = characters.builder()
        val name = builder.Names(snapshot).of(arrived)
        _status.value = "Noticing $name came nearby…"
        val history = characters.history(null)
        val notes = settings.eventNotes.first()
        val examples = characters.manualExamples()
        val msgs = builder.arrivalMessages(character, notes, history, snapshot, arrived, examples)
        val r = generate(msgs, character, snapshot, BROADCAST_NODE, history, notes, examples, null)
        if (r.text == null) {
            record(BROADCAST_NODE, null, false, "$name came nearby, but ${r.rejected}", r.refusal, DecisionOutcome.SKIPPED, now, character, idle = true)
        } else {
            dispatch(BROADCAST_NODE, null, "$name came nearby", r.text, now, character, "the group", idle = true)
        }
    }

    /**
     * Idle fires [BehaviorSettings.idleMinutes] after the last human message, and only once until a human speaks again,
     * whatever the outcome (in Observe mode nothing is sent, so "the bot spoke last" alone never stops it).
     */
    private suspend fun computeNextIdle(): Long? {
        val behavior = settings.behavior.first()
        if (behavior.idleMinutes <= 0 || settings.botMode.first() == BotMode.MANUAL) return null
        if (settings.sleepUntil.first() > System.currentTimeMillis() || engine.state.value !is EngineState.Ready) return null
        if (characters.active.first() == null) return null
        val last = db.messages().recentChannel(settings.operatingChannel.first(), 1).firstOrNull()
        if (last != null && last.origin != MessageOrigin.MESH) return null
        val base = maxOf(last?.rxTime ?: startedAt, settings.contextResetAt.first(), startedAt)
        if ((db.decisions().lastIdleAt() ?: 0L) >= base) return null
        return base + behavior.idleMinutes * 60_000L
    }

    /** [target] is the person a callout was aimed at, if any. */
    data class Thought(val text: String?, val rejected: String = "", val refusal: String? = null, val target: String? = null)

    /** Idle chatter from the configured source; favorites fall back to a plain thought when none are starred. Also the Feed's Manual "Thought" button. */
    suspend fun proposeIdle(): Pair<Thought, String> {
        val hasFavorites = characters.favoriteTexts().isNotEmpty()
        val useFavorites = hasFavorites && when (settings.behavior.first().idleSource) {
            IdleSource.THOUGHT -> false
            IdleSource.FAVORITES -> true
            IdleSource.MIX -> Random.nextBoolean()
        }
        return if (useFavorites) proposeFromFavorites() to "from favorites" else proposeThought() to "thought"
    }

    /** A new group message inspired by a random handful of starred messages, with extra randomness. [target] forces a callout. */
    suspend fun proposeFromFavorites(target: Long? = null): Thought {
        val character = characters.active.first() ?: return Thought(null, "no active character")
        if (engine.state.value !is EngineState.Ready) return Thought(null, "no model loaded")
        val all = characters.favoriteTexts()
        if (all.isEmpty()) return Thought(null, "no favorites yet: long-press a message in the Feed to star it")
        _status.value = "Riffing on favorites…"
        try {
            val history = characters.history(null)
            val snapshot = characters.snapshot()
            val builder = characters.builder()
            val moments = characters.favoriteMoments(character, builder, snapshot, MAX_FAVORITES_IN_PROMPT)
            Log.i(TAG, "From favorites: ${moments.size} of ${all.size} picked")
            val notes = settings.eventNotes.first()
            val examples = characters.manualExamples()
            val aim = target ?: calloutTarget(snapshot)
            val msgs = builder.favoritesMessages(character, notes, history, snapshot, moments, examples, aim)
            val r = generate(msgs, character, snapshot, BROADCAST_NODE, history, notes, examples, null, all, FAVORITES_TEMP_BOOST)
            return aimed(Thought(r.text, r.rejected, r.refusal), aim, snapshot, builder)
        } finally {
            _status.value = null
        }
    }

    /** A spontaneous message for the group, run through every check. Used by idle chatter and the "propose a thought" button. [target] forces a callout. */
    suspend fun proposeThought(target: Long? = null): Thought {
        val character = characters.active.first() ?: return Thought(null, "no active character")
        if (engine.state.value !is EngineState.Ready) return Thought(null, "no model loaded")
        _status.value = "Thinking of something to say…"
        try {
            val history = characters.history(null)
            val snapshot = characters.snapshot()
            val notes = settings.eventNotes.first()
            val examples = characters.manualExamples()
            val quiet = (System.currentTimeMillis() - (history.lastOrNull()?.rxTime ?: startedAt)) / 60_000
            val builder = characters.builder()
            val aim = target ?: calloutTarget(snapshot)
            val msgs = builder.idleMessages(character, notes, history, snapshot, quiet, examples, aim)
            val r = generate(msgs, character, snapshot, BROADCAST_NODE, history, notes, examples, null)
            return aimed(Thought(r.text, r.rejected, r.refusal), aim, snapshot, builder)
        } finally {
            _status.value = null
        }
    }

    /**
     * A channel reply to messages the operator picked in the Feed. One message: answered with the chat before it as context.
     * Several: they are the whole context and the newest is the one answered.
     */
    suspend fun proposeReply(picked: List<MessageEntity>): Thought {
        val character = characters.active.first() ?: return Thought(null, "no active character")
        if (engine.state.value !is EngineState.Ready) return Thought(null, "no model loaded")
        val trigger = picked.maxByOrNull { it.rxTime } ?: return Thought(null, "no message picked")
        _status.value = "Writing a reply…"
        try {
            val snapshot = characters.snapshot()
            val history = if (picked.size > 1) picked.sortedBy { it.rxTime }
            else (characters.history(null).filter { it.rxTime <= trigger.rxTime && it.id != trigger.id } + trigger)
            val notes = settings.eventNotes.first()
            val examples = characters.manualExamples()
            val builder = characters.builder()
            val msgs = builder.replyMessages(character, notes, history, snapshot, null, trigger, examples)
            val r = generate(msgs, character, snapshot, BROADCAST_NODE, history, notes, examples, trigger)
            return Thought(r.text, r.rejected, r.refusal, builder.Names(snapshot).of(trigger.fromNode))
        } finally {
            _status.value = null
        }
    }

    /** A brand-new persona written by the model from a one-line [idea] (blank = surprise me). [looks] are the preset look names. */
    suspend fun writePersona(idea: String, looks: List<String>): GeneratedPersona? {
        if (engine.state.value !is EngineState.Ready) return null
        _status.value = "Dreaming up a character…"
        try {
            val prompt = PromptTemplate.render(
                settings.templates.first().getValue(PromptTemplate.PERSONA_WRITER),
                mapOf("idea" to idea.trim().ifEmpty { PersonaWriter.SURPRISE_IDEA }.take(300), "looks" to looks.joinToString(", ")),
            )
            val r = engine.chat(
                listOf(ChatMessage("user", prompt)),
                GenParams(maxTokens = 420, temperature = 0.95f, repeatPenalty = 1.05f, grammar = PersonaWriter.grammar(looks)),
            )
            Log.i(TAG, "Persona writer: ${r.text}")
            return PersonaWriter.parse(r.text, looks)
        } finally {
            _status.value = null
        }
    }

    /** The character's answer in the private brainstorm chat. Never sent anywhere, so only lightly cleaned. */
    suspend fun brainstormReply(turns: List<Pair<Boolean, String>>): Thought {
        val character = characters.active.first() ?: return Thought(null, "no active character")
        if (engine.state.value !is EngineState.Ready) return Thought(null, "no model loaded")
        _status.value = "Brainstorming\u2026"
        try {
            val builder = characters.builder()
            val msgs = builder.brainstormMessages(
                character, settings.eventNotes.first(), characters.history(null), characters.snapshot(), turns, characters.manualExamples(),
            )
            val s = settings.llmSettings.first()
            val strength = settings.behavior.first().personaStrength
            val r = engine.chat(
                msgs,
                GenParams(
                    s.maxTokens * 2, s.temperature, s.topP, s.topK, s.minP, s.repeatPenalty,
                    negative = if (strength > 1.01f) builder.withoutPersona(msgs, character) else null, guidance = strength,
                ),
            )
            val ownNames = listOf(character.longName, character.shortName) + character.nicknameList
            val text = PostProcessor.clean(r.text, ownNames, maxBytes = BRAINSTORM_MAX_BYTES) ?: r.text.trim()
            return if (text.isBlank()) Thought(null, "empty reply") else Thought(text)
        } finally {
            _status.value = null
        }
    }

    /** The group message that comes out of a brainstorm, with every normal check. */
    suspend fun brainstormPost(turns: List<Pair<Boolean, String>>): Thought {
        val character = characters.active.first() ?: return Thought(null, "no active character")
        if (engine.state.value !is EngineState.Ready) return Thought(null, "no model loaded")
        _status.value = "Writing the post\u2026"
        try {
            val history = characters.history(null)
            val snapshot = characters.snapshot()
            val notes = settings.eventNotes.first()
            val examples = characters.manualExamples()
            val msgs = characters.builder().brainstormMessages(character, notes, history, snapshot, turns, examples, askForPost = true)
            val r = generate(msgs, character, snapshot, BROADCAST_NODE, history, notes, examples, null)
            return Thought(r.text, r.rejected, r.refusal)
        } finally {
            _status.value = null
        }
    }

    private suspend fun calloutTarget(snapshot: MeshSnapshot): Long? =
        if (settings.behavior.first().callout) characters.calloutTarget(snapshot) else null

    /** Small models sometimes forget the name; a callout must say who it's for. */
    private fun aimed(t: Thought, target: Long?, snapshot: MeshSnapshot, builder: ContextBuilder): Thought {
        target ?: return t
        val name = builder.Names(snapshot).of(target)
        val node = snapshot.nodes[target]
        val text = t.text?.let { s ->
            val named = listOfNotNull(node?.longName, node?.shortName, name).any { it.isNotBlank() && s.contains(it, ignoreCase = true) }
            if (named) s else PostProcessor.truncateUtf8("$name, $s", Prompts.MAX_REPLY_BYTES)
        }
        return t.copy(text = text, target = name)
    }

    /** Sends a proposed message the operator approved (recorded as a bot message). */
    suspend fun sendThought(text: String, reason: String = "proposed thought, approved by you", idle: Boolean = true) {
        val body = PostProcessor.truncateUtf8(text.trim(), Prompts.MAX_REPLY_BYTES)
        if (body.isEmpty()) return
        val character = characters.active.first()
        send(BROADCAST_NODE, body, null)
        record(BROADCAST_NODE, null, true, reason, body, DecisionOutcome.SENT, System.currentTimeMillis(), character, idle = idle)
    }

    private suspend fun dispatch(
        key: Long, trigger: MessageEntity?, reason: String, reply: String, t0: Long,
        character: CharacterEntity, targetName: String, idle: Boolean = false,
    ) {
        when (settings.botMode.first()) {
            BotMode.MANUAL, BotMode.OBSERVE -> record(key, trigger, true, reason, reply, DecisionOutcome.OBSERVED, t0, character, idle)
            BotMode.APPROVE -> {
                val id = record(key, trigger, true, reason, reply, DecisionOutcome.DRAFT, t0, character, idle)
                draftSink?.onDraft(id, reply, targetName)
            }
            BotMode.AUTO -> {
                val limited = rateLimited()
                if (limited != null) {
                    record(key, trigger, true, "$reason, but $limited", reply, DecisionOutcome.OBSERVED, t0, character, idle)
                } else {
                    send(key, reply, trigger?.channel)
                    record(key, trigger, true, reason, reply, DecisionOutcome.SENT, t0, character, idle)
                }
            }
        }
    }

    /** Time left on the operator's per-person reply cooldown for [node], or null if it may be answered now. */
    private suspend fun cooldownLeftMs(node: Long): Long? {
        val seconds = db.cooldowns().get(node)?.takeIf { it > 0 } ?: return null
        val last = db.decisions().lastAnsweredFrom(node) ?: return null
        return (last + seconds * 1000 - System.currentTimeMillis()).takeIf { it > 0 }
    }

    /** Why an automatic send has to wait under the hard rate limit, or null if it may go. */
    private suspend fun rateLimited(): String? {
        val b = settings.behavior.first()
        if (b.rateLimitCount <= 0) return null
        val sent = db.messages().countSentSince(System.currentTimeMillis() - b.rateLimitWindowSec * 1000L)
        return if (sent >= b.rateLimitCount) "rate limit: already sent $sent in the last ${humanDuration(b.rateLimitWindowSec.toLong())}" else null
    }

    /** Sends an approved draft (optionally edited by the operator). */
    suspend fun sendDraft(decisionId: Long, text: String? = null) {
        val d = db.decisions().get(decisionId) ?: return
        if (d.outcome != DecisionOutcome.DRAFT && d.outcome != DecisionOutcome.OBSERVED) return
        val body = PostProcessor.truncateUtf8((text ?: d.reply ?: return).trim(), Prompts.MAX_REPLY_BYTES)
        if (body.isEmpty()) return
        send(d.peer, body, d.channel)
        db.decisions().update(decisionId, DecisionOutcome.SENT, body)
    }

    suspend fun discardDraft(decisionId: Long) {
        val d = db.decisions().get(decisionId) ?: return
        if (d.outcome == DecisionOutcome.DRAFT) db.decisions().update(decisionId, DecisionOutcome.DISCARDED, d.reply)
    }

    private suspend fun send(key: Long, text: String, channel: Int?) {
        val ch = channel ?: settings.operatingChannel.first()
        mesh.sendText(text, ch, key, MessageOrigin.BOT_AUTO)
    }

    private suspend fun judge(messages: List<ChatMessage>): Pair<Boolean, String> {
        val r = engine.chat(messages, GenParams(maxTokens = 60, temperature = 0.3f, repeatPenalty = 1.0f, grammar = Prompts.JUDGE_GRAMMAR))
        Log.i(TAG, "Judge (${r.stats.promptTokens} new / ${r.stats.reusedTokens} cached tok, ${r.stats.promptMs.toInt()} ms): ${r.text}")
        return try {
            val json = JSONObject(r.text)
            json.getBoolean("respond") to json.optString("reason").ifBlank { "no reason" }
        } catch (e: Exception) {
            false to "judge gave invalid JSON"
        }
    }

    private suspend fun review(character: CharacterEntity, snapshot: MeshSnapshot, trigger: MessageEntity?, reply: String): Pair<Boolean, String> {
        val msgs = characters.builder().reviewMessages(character, snapshot, trigger, reply)
        val r = engine.chat(msgs, GenParams(maxTokens = 16, temperature = 0.2f, repeatPenalty = 1.0f, grammar = REVIEW_GRAMMAR))
        Log.i(TAG, "Review: ${r.text}")
        return try {
            val problem = JSONObject(r.text).getString("problem")
            (problem == "none") to problem.replace('_', ' ')
        } catch (e: Exception) {
            true to "review unreadable"
        }
    }

    data class Generated(val text: String?, val rejected: String = "", val refusal: String? = null)

    /** Generates, cleans and checks a reply; tries up to three seeds before giving up. */
    private suspend fun generate(
        messages: List<ChatMessage>,
        character: CharacterEntity,
        snapshot: MeshSnapshot,
        key: Long,
        history: List<MessageEntity>,
        notes: String,
        examples: List<String>,
        trigger: MessageEntity?,
        extraSources: List<String> = emptyList(),
        tempBoost: Float = 0f,
    ): Generated {
        val s = settings.llmSettings.first()
        val behavior = settings.behavior.first()
        val ownNames = listOf(character.longName, character.shortName) + character.nicknameList
        val otherNames = snapshot.nodes.values.filter { it.nodeNum != snapshot.myNodeNum }
            .flatMap { listOfNotNull(it.longName, it.shortName) }
        val recentBot = db.decisions().recentSent(key, 10).mapNotNull { it.reply }
        val strength = behavior.personaStrength
        val negative = if (strength > 1.01f) characters.builder().withoutPersona(messages, character) else null
        // Anything the model was shown that it shouldn't parrot back.
        val copySources = history.map { it.text } + character.examples.lines() + examples + character.persona + character.rules + notes + recentBot + extraSources
        var lastReason = "no reply"
        var refusal: String? = null
        repeat(3) { attempt ->
            val r = engine.chat(
                messages,
                GenParams(
                    s.maxTokens, (s.temperature + tempBoost).coerceAtMost(1.5f), s.topP, s.topK, s.minP, s.repeatPenalty,
                    seed = Random.nextInt(), negative = negative, guidance = strength,
                ),
            )
            Log.i(TAG, "Reply attempt ${attempt + 1} (${r.stats.promptTokens} new / ${r.stats.reusedTokens} cached tok, " +
                "%.1f s): ${r.text.trim()}".format((r.stats.promptMs + r.stats.genMs) / 1000))
            val cleaned = PostProcessor.clean(r.text, ownNames, otherNames = otherNames)
            lastReason = when {
                cleaned == null -> "empty or leaked prompt"
                PostProcessor.looksLikeRefusal(cleaned) -> "refusal"
                PostProcessor.bannedHit(cleaned, character.bannedList) != null -> "said \"${PostProcessor.bannedHit(cleaned, character.bannedList)}\""
                PostProcessor.isRepeat(cleaned, recentBot) -> "repeat of an earlier message"
                PostProcessor.copiesContext(cleaned, copySources) -> "copied text from the chat or prompt"
                behavior.reviewPass -> review(character, snapshot, trigger, cleaned).let { (ok, why) -> if (ok) "" else "review: $why" }
                else -> ""
            }
            if (lastReason.isEmpty()) return Generated(cleaned)
            if (lastReason == "refusal") refusal = cleaned
            Log.i(TAG, "Rejected ($lastReason): $cleaned")
        }
        if (refusal != null) return Generated(null, "the model refused. ${Refusals.explain(refusal!!)}", refusal)
        return Generated(null, "no usable reply ($lastReason)")
    }

    private fun loopGuardTripped(history: List<MessageEntity>, from: Long, limit: Int): Boolean {
        if (limit <= 0) return false
        val tail = history.takeLast(limit * 2)
        val humans = tail.filter { it.origin == MessageOrigin.MESH }.map { it.fromNode }.toSet()
        val botCount = tail.count { it.origin == MessageOrigin.BOT_AUTO }
        val recentEnough = tail.isNotEmpty() && tail.last().rxTime - tail.first().rxTime < 10 * 60_000
        return humans == setOf(from) && botCount >= limit && recentEnough
    }

    private suspend fun record(
        key: Long, trigger: MessageEntity?, respond: Boolean, reason: String, reply: String?,
        outcome: DecisionOutcome, t0: Long, character: CharacterEntity? = null, idle: Boolean = false,
    ): Long {
        Log.i(TAG, "Decision ${outcome.name}: $reason${reply?.let { " -> $it" } ?: ""}")
        return db.decisions().insert(
            DecisionEntity(
                peer = key,
                channel = trigger?.channel ?: settings.operatingChannel.first(),
                triggerMessageId = trigger?.id,
                idle = idle,
                respond = respond,
                reason = reason.take(600),
                reply = reply,
                outcome = outcome,
                latencyMs = System.currentTimeMillis() - t0,
                characterId = character?.id,
            )
        )
    }

    companion object {
        const val ARRIVAL_RADIUS_M = 300.0
        const val GREET_COOLDOWN_MS = 2 * 60 * 60 * 1000L
        const val MAX_FAVORITES_IN_PROMPT = 5
        const val FAVORITES_TEMP_BOOST = 0.25f
        const val BRAINSTORM_MAX_BYTES = 1000

        fun mentions(c: CharacterEntity, text: String): Boolean {
            val names = (listOf(c.longName, c.shortName) + c.nicknameList).filter { it.length >= 2 }
            return names.any { n ->
                Regex("(?i)(^|[^\\p{L}\\p{N}])" + Regex.escape(n) + "($|[^\\p{L}\\p{N}])").containsMatchIn(text)
            }
        }
    }
}
