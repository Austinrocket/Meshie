package com.meshie4LoRa.brain

import android.util.Log
import com.meshie4LoRa.data.AppDatabase
import com.meshie4LoRa.data.CharacterEntity
import com.meshie4LoRa.data.MemoryEntity
import com.meshie4LoRa.data.MessageEntity
import com.meshie4LoRa.data.SettingsStore
import com.meshie4LoRa.llm.ChatMessage
import com.meshie4LoRa.mesh.MeshRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

private const val TAG = "MeshChan.Chars"

/** Characters (one active, D4) and assembling live context for the model. */
class CharacterRepository(
    private val db: AppDatabase,
    private val mesh: MeshRepository,
    private val settings: SettingsStore,
    scope: CoroutineScope,
) {
    val characters: Flow<List<CharacterEntity>> = db.characters().observeAll()
    val active: Flow<CharacterEntity?> = db.characters().observeActive()

    init {
        // Keep the radio's mesh names in sync with the active character whenever we (re)connect.
        scope.launch {
            combine(active, mesh.configComplete, mesh.myNodeNum) { c, ready, me -> Triple(c, ready, me) }.collect { (c, ready, me) ->
                if (!ready || me == null) return@collect
                val node = db.nodes().get(me)
                if (c == null) {
                    // First run: adopt the radio's current names so the operator's choice isn't overwritten.
                    if (db.characters().count() == 0) {
                        db.characters().upsert(
                            CharacterEntity(
                                longName = node?.longName ?: "Meshie",
                                shortName = node?.shortName ?: "chan",
                                nicknames = "chan, mesh-chan",
                                persona = Prompts.DEFAULT_PERSONA,
                                rules = Prompts.DEFAULT_RULES,
                                examples = Prompts.DEFAULT_EXAMPLES,
                                active = true,
                            )
                        )
                    }
                    return@collect
                }
                if (node?.longName != c.longName || node.shortName != c.shortName) {
                    try {
                        mesh.setOwner(c.longName, c.shortName)
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not push character names to radio", e)
                    }
                }
            }
        }
    }

    suspend fun save(c: CharacterEntity): Long = db.characters().upsert(c)

    suspend fun activate(id: Long) = db.characters().setActive(id)

    suspend fun delete(id: Long) = db.characters().delete(id)

    suspend fun snapshot(): MeshSnapshot {
        val nodes = db.nodes().observeAll().first().associateBy { it.nodeNum }
        val midnight = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val today = db.messages().countsSince(midnight).associate { it.fromNode to it.count }
        val relationships = db.relationships().all().associateBy { it.nodeNum }
        val memories = db.memories().all()
        val facts = memories.filter { it.nodeNum != null }
            .sortedWith(compareByDescending<MemoryEntity> { it.pinned }.thenByDescending { it.updatedAt })
            .groupBy({ it.nodeNum!! }, { it.text })
        val self = memories.filter { it.nodeNum == null }
            .sortedWith(compareByDescending<MemoryEntity> { it.pinned }.thenBy { it.createdAt })
            .map { it.text }
        val now = System.currentTimeMillis()
        val movement = if (DataField.OWN_LOCATION in settings.dataFields.first()) {
            MovementTracker.compute(mesh.myNodeNum.value, nodes, db.positions().since(now - MovementTracker.MAX_AGO_MS), now)
        } else emptyMap()
        return MeshSnapshot(mesh.myNodeNum.value, nodes, now, today, relationships, facts, self, movement)
    }

    suspend fun builder(): ContextBuilder = ContextBuilder(
        settings.dataFields.first(), templates = settings.templates.first(), phrases = settings.phrases.first(),
        logLimit = settings.behavior.first().historySize,
    )

    /** A random 1..[max] favorites, each with the message it answered, formatted as "Name: text" lines. */
    suspend fun favoriteMoments(character: CharacterEntity, builder: ContextBuilder, snapshot: MeshSnapshot, max: Int): List<String> {
        val messages = db.messages().favorites()
        val decisions = db.decisions().favorites()
        val total = messages.size + decisions.size
        if (total == 0) return emptyList()
        val names = builder.Names(snapshot)
        fun line(m: com.meshie4LoRa.data.MessageEntity): String {
            val who = if (m.origin != com.meshie4LoRa.data.MessageOrigin.MESH) character.longName else names.of(m.fromNode)
            return "$who: ${ContextBuilder.sanitize(m.text)}"
        }
        val picks: List<suspend () -> String> = messages.map { m ->
            suspend {
                val me = snapshot.myNodeNum ?: -1L
                val peer = if (m.isDirect) m.dmPartner(me) else null
                listOfNotNull(db.messages().previousIn(m.channel, peer, me, m.rxTime), m).joinToString("\n") { line(it) }
            }
        } + decisions.map { d ->
            suspend {
                listOfNotNull(
                    d.triggerMessageId?.let { db.messages().byId(it) }?.let { line(it) },
                    "${character.longName}: ${ContextBuilder.sanitize(d.reply.orEmpty())}",
                ).joinToString("\n")
            }
        }
        return picks.shuffled().take(kotlin.random.Random.nextInt(1, minOf(total, max) + 1)).map { it() }
    }

    /** Texts of every favorite (starred messages and starred unsent replies). */
    suspend fun favoriteTexts(): List<String> =
        db.messages().favorites().map { it.text } + db.decisions().favorites().mapNotNull { it.reply }

    /**
     * Someone to call out: only people heard over the radio in the last hour (so they'll likely see it), picked at
     * random but weighted toward whoever chatted recently and people the persona has memories about.
     */
    suspend fun calloutTarget(snapshot: MeshSnapshot): Long? {
        val fields = settings.dataFields.first()
        if (DataField.LONG_NAME !in fields && DataField.SHORT_NAME !in fields) return null
        val now = System.currentTimeMillis()
        val lastSaid = db.messages().activeSince(now - CALLOUT_CHAT_WINDOW_MS).associate { it.fromNode to it.lastAt }
        val weighted = snapshot.nodes.values.filter {
            it.nodeNum != snapshot.myNodeNum && !it.ignored && !it.viaMqtt && it.lastHeard >= now - CALLOUT_WINDOW_MS &&
                (it.longName ?: it.shortName) != null
        }.map { n ->
            var w = 1.0
            lastSaid[n.nodeNum]?.let { at -> w += if (now - at < CALLOUT_WINDOW_MS) 6.0 else 3.0 }
            w += minOf(snapshot.facts[n.nodeNum]?.size ?: 0, 4) * 1.0
            snapshot.relationships[n.nodeNum]?.let { r -> if (r.feeling.isNotBlank() || r.summary.isNotBlank()) w += 2.0 }
            n.nodeNum to w
        }
        if (weighted.isEmpty()) return null
        var roll = kotlin.random.Random.nextDouble() * weighted.sumOf { it.second }
        for ((num, w) in weighted) {
            roll -= w
            if (roll <= 0) return num
        }
        return weighted.last().first
    }

    /** Exactly what each kind of call would send right now (memory pass is built by MemoryManager). */
    suspend fun previewMessages(kind: PromptKind, character: CharacterEntity, dmPeer: Long?): List<ChatMessage> {
        val b = builder()
        val notes = settings.eventNotes.first()
        val examples = manualExamples()
        val history = history(dmPeer)
        val snapshot = snapshot()
        val trigger = ContextBuilder.lastFromOthers(history, snapshot)
        return when (kind) {
            PromptKind.REPLY -> b.replyMessages(character, notes, history, snapshot, dmPeer, trigger, examples)
            PromptKind.JUDGE -> b.judgeMessages(character, notes, history, snapshot, trigger, examples)
            PromptKind.IDLE -> b.idleMessages(character, notes, history, snapshot, settings.behavior.first().idleMinutes.toLong(), examples)
            PromptKind.FAVORITES -> b.favoritesMessages(
                character, notes, history, snapshot, favoriteMoments(character, b, snapshot, BotBrain.MAX_FAVORITES_IN_PROMPT), examples,
            )
            PromptKind.ARRIVAL -> {
                val someone = dmPeer ?: snapshot.nodes.values.filter { it.nodeNum != snapshot.myNodeNum }.maxByOrNull { it.lastHeard }?.nodeNum ?: 0L
                b.arrivalMessages(character, notes, history, snapshot, someone, examples)
            }
            PromptKind.REVIEW -> b.reviewMessages(character, snapshot, trigger, "(the drafted reply goes here)")
            PromptKind.MEMORY -> emptyList()
        }
    }

    /**
     * Rolling window of chat from the last `historyMinutes`, never older than the operator's "fresh start".
     * Fetches more than `historySize` (the trigger and loop-guard need it); the builder trims what the model sees.
     */
    suspend fun history(dmPeer: Long?): List<MessageEntity> {
        val b = settings.behavior.first()
        val since = maxOf(settings.contextResetAt.first(), System.currentTimeMillis() - b.historyMinutes * 60_000L)
        val limit = maxOf(b.historySize + 1, b.loopGuard * 2)
        val recent = if (dmPeer != null) db.messages().recentDm(dmPeer, mesh.myNodeNum.value ?: -1L, limit)
        else db.messages().recentChannel(settings.operatingChannel.first(), limit)
        return recent.filter { it.rxTime >= since }.reversed()
    }

    /** The operator's own recent manual messages, used as style examples when enabled. */
    suspend fun manualExamples(): List<String> {
        if (!settings.behavior.first().learnFromManual) return emptyList()
        val since = settings.memoryResetAt.first()
        return db.messages().recentManual(MANUAL_EXAMPLES).filter { it.rxTime >= since }
            .map { it.text.trim() }.filter { it.length in 3..200 }.distinct()
    }

    /** The exact messages the model would get to reply in the channel ([dmPeer] = null) or a DM. */
    suspend fun replyContext(character: CharacterEntity, dmPeer: Long?): List<ChatMessage> {
        val history = history(dmPeer)
        val snapshot = snapshot()
        return builder().replyMessages(
            character, settings.eventNotes.first(), history, snapshot, dmPeer,
            trigger = ContextBuilder.lastFromOthers(history, snapshot), extraExamples = manualExamples(),
        )
    }

    suspend fun systemPrompt(character: CharacterEntity): String =
        builder().systemPrompt(character, settings.eventNotes.first(), snapshot().selfMemories, manualExamples())

    companion object {
        const val MANUAL_EXAMPLES = 3
        const val CALLOUT_WINDOW_MS = 60 * 60 * 1000L
        const val CALLOUT_CHAT_WINDOW_MS = 6 * 60 * 60 * 1000L
    }
}
