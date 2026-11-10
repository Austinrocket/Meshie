package com.meshie4LoRa.brain

import com.meshie4LoRa.data.BROADCAST_NODE
import com.meshie4LoRa.data.CharacterEntity
import com.meshie4LoRa.data.MessageEntity
import com.meshie4LoRa.data.MessageOrigin
import com.meshie4LoRa.data.NodeEntity
import com.meshie4LoRa.data.RelationshipEntity
import com.meshie4LoRa.data.nodeIdString
import com.meshie4LoRa.llm.ChatMessage
import com.meshie4LoRa.mesh.Geo
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/** What the mesh looks like right now; built by the caller so this class stays pure and testable. */
data class MeshSnapshot(
    val myNodeNum: Long?,
    val nodes: Map<Long, NodeEntity>,
    val now: Long,
    val messagesToday: Map<Long, Int> = emptyMap(),
    val relationships: Map<Long, RelationshipEntity> = emptyMap(),
    /** Remembered facts per user, best first (pinned, then newest). */
    val facts: Map<Long, List<String>> = emptyMap(),
    /** Memories about the character itself / the world. */
    val selfMemories: List<String> = emptyList(),
    /** Who is getting closer / moving away, from radio positions (see [MovementTracker]). */
    val movement: Map<Long, Movement> = emptyMap(),
)

/**
 * Turns character + mesh state into chat messages for the model. All wording comes from [templates] (operator-editable),
 * and only enabled [DataField]s are exposed. Static system prompt first so it stays in the KV cache (D15).
 */
class ContextBuilder(
    private val fields: Set<DataField>,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val templates: Map<PromptTemplate, String> = PromptTemplate.entries.associateWith { it.default },
    private val phrases: Map<PromptPhrase, String> = PromptPhrase.all(),
    /** How many chat lines the model sees (the trigger is shown separately and not counted). */
    private val logLimit: Int = Int.MAX_VALUE,
) {

    private val dayClock = DateTimeFormatter.ofPattern("EEEE HH:mm").withZone(zone)

    private fun tpl(t: PromptTemplate) = templates[t] ?: t.default

    /** A filled-in phrase, or null when the operator emptied it (the line is then left out). */
    fun phrase(p: PromptPhrase, vararg values: Pair<String, String>): String? =
        PromptTemplate.render(phrases[p] ?: p.default, values.toMap()).takeIf { it.isNotBlank() }

    /** How the character's own lines are labelled in logs, e.g. "sakura (you)". */
    fun selfLabel(name: String): String = listOfNotNull(name, phrase(PromptPhrase.YOU_MARKER)).joinToString(" ")

    fun systemPrompt(
        character: CharacterEntity,
        eventNotes: String,
        selfMemories: List<String> = emptyList(),
        extraExamples: List<String> = emptyList(),
    ): String {
        fun bullets(lines: List<String>) = lines.map { it.trim().removePrefix("- ") }.filter { it.isNotEmpty() }.joinToString("\n") { "- $it" }
        return PromptTemplate.render(
            tpl(PromptTemplate.SYSTEM),
            mapOf(
                "name" to character.longName,
                "short_name" to character.shortName,
                "nicknames" to character.nicknameList.joinToString(", "),
                "persona" to character.persona.trim(),
                "rules" to bullets(character.rules.lines()),
                "examples" to bullets(character.examples.lines() + extraExamples),
                "event_notes" to eventNotes.trim(),
                "memories" to bullets(selfMemories.take(MAX_SELF_MEMORIES)),
            ) + pronounValues(character),
        )
    }

    /** Messages for the character's reply to [trigger] in a channel ([dmPeer] = null) or a DM. */
    fun replyMessages(
        character: CharacterEntity,
        eventNotes: String,
        history: List<MessageEntity>,
        snapshot: MeshSnapshot,
        dmPeer: Long? = null,
        trigger: MessageEntity? = lastFromOthers(history, snapshot),
        extraExamples: List<String> = emptyList(),
    ): List<ChatMessage> = build(PromptTemplate.REPLY, character, eventNotes, history, snapshot, dmPeer, trigger, extraExamples) { names ->
        mapOf("target" to (dmPeer?.let { "in a private message to ${names.of(it)}" } ?: "to the group"))
    }

    /** Same leading text as [replyMessages] (shared KV cache), but asks for a JSON respond/skip decision. */
    fun judgeMessages(
        character: CharacterEntity,
        eventNotes: String,
        history: List<MessageEntity>,
        snapshot: MeshSnapshot,
        trigger: MessageEntity? = lastFromOthers(history, snapshot),
        extraExamples: List<String> = emptyList(),
    ): List<ChatMessage> = build(PromptTemplate.JUDGE, character, eventNotes, history, snapshot, null, trigger, extraExamples) { _ ->
        mapOf("eagerness" to character.eagerness.toString())
    }

    fun idleMessages(
        character: CharacterEntity,
        eventNotes: String,
        history: List<MessageEntity>,
        snapshot: MeshSnapshot,
        quietMinutes: Long,
        extraExamples: List<String> = emptyList(),
        callout: Long? = null,
    ): List<ChatMessage> = build(PromptTemplate.IDLE, character, eventNotes, history, snapshot, null, null, extraExamples) { names ->
        mapOf("quiet_minutes" to quietMinutes.toString(), "ideas" to character.idlePrompt.trim(), "callout" to calloutLine(callout, snapshot, names))
    }

    /** [person] just came nearby; the description only includes location details the operator allowed. */
    fun arrivalMessages(
        character: CharacterEntity,
        eventNotes: String,
        history: List<MessageEntity>,
        snapshot: MeshSnapshot,
        person: Long,
        extraExamples: List<String> = emptyList(),
    ): List<ChatMessage> = build(PromptTemplate.ARRIVAL, character, eventNotes, history, snapshot, null, null, extraExamples) { names ->
        val about = snapshot.nodes[person]?.let { describePerson(it, snapshot, names).substringAfter(": ", "") }.orEmpty()
        mapOf("person" to names.of(person), "about" to about)
    }

    /** A new group message inspired by [favorites] (already formatted moments, picked at random by the caller). */
    fun favoritesMessages(
        character: CharacterEntity,
        eventNotes: String,
        history: List<MessageEntity>,
        snapshot: MeshSnapshot,
        favorites: List<String>,
        extraExamples: List<String> = emptyList(),
        callout: Long? = null,
    ): List<ChatMessage> = build(PromptTemplate.FAVORITES, character, eventNotes, history, snapshot, null, null, extraExamples) { names ->
        mapOf("favorites" to favorites.joinToString("\n---\n"), "callout" to calloutLine(callout, snapshot, names))
    }

    private fun calloutLine(person: Long?, snapshot: MeshSnapshot, names: Names): String {
        person ?: return ""
        val about = snapshot.nodes[person]?.let { describePerson(it, snapshot, names).substringAfter(": ", "") }.orEmpty()
        return phrase(PromptPhrase.CALLOUT, "person" to names.of(person), "about" to about).orEmpty()
    }

    /** Second pass: does [reply] make sense as an answer to [trigger]? */
    fun reviewMessages(character: CharacterEntity, snapshot: MeshSnapshot, trigger: MessageEntity?, reply: String): List<ChatMessage> {
        val names = Names(snapshot)
        val body = PromptTemplate.render(
            tpl(PromptTemplate.REVIEW),
            mapOf(
                "name" to character.longName,
                "sender" to (trigger?.let { names.of(it.fromNode) } ?: "Nobody"),
                "message" to (trigger?.let { sanitize(it.text) } ?: "(the chat was quiet)"),
                "reply" to sanitize(reply),
            ) + pronounValues(character),
        )
        return listOf(ChatMessage("user", body))
    }

    private fun build(
        template: PromptTemplate,
        character: CharacterEntity,
        eventNotes: String,
        history: List<MessageEntity>,
        snapshot: MeshSnapshot,
        dmPeer: Long?,
        trigger: MessageEntity?,
        extraExamples: List<String>,
        extra: (Names) -> Map<String, String>,
    ): List<ChatMessage> {
        val names = Names(snapshot)
        // Only the person being answered gets details; everyone else in the log is just a name.
        val people = listOfNotNull(trigger?.fromNode ?: dmPeer)
            .filter { it != snapshot.myNodeNum && it != 0L }
            .mapNotNull { num -> snapshot.nodes[num]?.let { describePerson(it, snapshot, names) } }
            .filter { it.contains(':') || it.contains('\n') }
        val values = mapOf(
            "name" to character.longName,
            "log" to conversationLog(history.filter { it.id != trigger?.id }.takeLast(logLimit), snapshot, character, names),
            "people" to people.joinToString("\n") { "- $it" },
            "surroundings" to surroundings(snapshot, names).orEmpty(),
            "sender" to (trigger?.let { names.of(it.fromNode) } ?: ""),
            "message" to (trigger?.let { sanitize(it.text) } ?: ""),
            "reminder" to personaReminder(character),
        ) + pronounValues(character) + extra(names)
        val body = PromptTemplate.render(tpl(template), values)
            .let { b -> values["callout"]?.takeIf { it.isNotBlank() && "{callout}" !in tpl(template) }?.let { "$b\n$it" } ?: b }
        return listOf(
            ChatMessage("system", systemPrompt(character, eventNotes, snapshot.selfMemories, extraExamples)),
            ChatMessage("user", body),
        )
    }

    /** The persona again at the very end, where small models pay the most attention; long personas are cut to the first sentences. */
    fun personaReminder(character: CharacterEntity): String {
        val p = character.persona.trim().replace(Regex("\\s+"), " ")
        if (p.isEmpty()) return ""
        val brief = if (p.length <= REMINDER_CHARS) p
        else p.take(REMINDER_CHARS).let { cut -> cut.substring(0, (cut.lastIndexOfAny(charArrayOf('.', '!', '?')) + 1).takeIf { it > 40 } ?: cut.length) }
        return phrase(PromptPhrase.PERSONA_REMINDER, "name" to character.longName, "persona" to brief).orEmpty()
    }

    /**
     * Private operator <-> character chat. [turns] alternate (fromOperator, text); [askForPost] appends the request
     * for the actual group message so the reply can go through the normal post checks.
     */
    fun brainstormMessages(
        character: CharacterEntity,
        eventNotes: String,
        history: List<MessageEntity>,
        snapshot: MeshSnapshot,
        turns: List<Pair<Boolean, String>>,
        extraExamples: List<String> = emptyList(),
        askForPost: Boolean = false,
    ): List<ChatMessage> {
        val names = Names(snapshot)
        val side = PromptTemplate.render(
            tpl(PromptTemplate.BRAINSTORM),
            mapOf(
                "name" to character.longName,
                "log" to conversationLog(history.takeLast(logLimit), snapshot, character, names),
                "reminder" to personaReminder(character),
            ) + pronounValues(character),
        )
        val system = systemPrompt(character, eventNotes, snapshot.selfMemories, extraExamples) + "\n\n" + side
        val chat = turns.map { (operator, text) -> ChatMessage(if (operator) "user" else "assistant", text) }
        val ask = phrase(PromptPhrase.BRAINSTORM_POST)?.takeIf { askForPost }?.let { listOf(ChatMessage("user", it)) }.orEmpty()
        return listOf(ChatMessage("system", system)) + chat + ask
    }

    /** [messages] with the persona and its reminder removed: the baseline that persona strength pushes away from. */
    fun withoutPersona(messages: List<ChatMessage>, character: CharacterEntity): List<ChatMessage> {
        val persona = character.persona.trim()
        val reminder = personaReminder(character)
        return messages.map { m ->
            var c = m.content
            if (persona.isNotEmpty()) c = c.replace(persona, "")
            if (reminder.isNotEmpty()) c = c.replace(reminder, "")
            m.copy(content = c)
        }
    }

    /**
     * One line per message, oldest first. The character's own lines are marked "(you)" so it knows what it said.
     * Only the newest [FULL_LINES] lines are kept whole; older ones are trimmed so they weigh less.
     */
    fun conversationLog(
        history: List<MessageEntity>,
        snapshot: MeshSnapshot,
        character: CharacterEntity? = null,
        names: Names = Names(snapshot),
    ): String {
        val sorted = history.sortedWith(compareBy({ it.rxTime }, { it.id }))
        return sorted.mapIndexed { i, m ->
            val mine = m.origin != MessageOrigin.MESH || m.fromNode == snapshot.myNodeNum
            val who = if (mine) selfLabel(character?.longName ?: "you") else names.of(m.fromNode)
            val text = sanitize(m.text).let { t ->
                if (i >= sorted.size - FULL_LINES || t.length <= OLD_LINE_CHARS) t else t.take(OLD_LINE_CHARS).trimEnd() + "…"
            }
            "$who: $text"
        }.joinToString("\n")
    }

    fun describePerson(n: NodeEntity, snapshot: MeshSnapshot, names: Names = Names(snapshot)): String {
        val parts = mutableListOf<String>()
        relativeLocation(n, snapshot)?.let { parts += it }
        if (DataField.ALTITUDE in fields) n.altitude?.let { parts += "altitude ${it} m" }
        // Firmware reports ground_speed in km/h and ground_track in degrees * 1e5.
        if (DataField.SPEED in fields) n.groundSpeed?.takeIf { it > 0 }?.let { s ->
            parts += "moving ~$s km/h" + (n.groundTrack?.let { " heading ${compassWord(Geo.compass(it / 1e5))}" } ?: "")
        }
        if (DataField.COORDINATES in fields && n.hasPosition) parts += "coordinates %.4f, %.4f (never share these)".format(n.latitude, n.longitude)
        if (DataField.HOPS in fields) n.hopsAway?.let { parts += if (it == 0) "close enough to reach you directly" else "$it relay${if (it == 1) "" else "s"} away" }
        if (DataField.SNR in fields) n.snr?.let { parts += "SNR %.1f dB".format(it) }
        if (DataField.RSSI in fields) n.rssi?.let { parts += "RSSI $it dBm" }
        if (DataField.VIA_MQTT in fields && n.viaMqtt) parts += "chatting from far away over the internet"
        if (DataField.BATTERY in fields) n.batteryLevel?.let { parts += if (it > 100) "plugged in" else "battery $it%" }
        if (DataField.VOLTAGE in fields) n.voltage?.let { parts += "%.2f V".format(it) }
        if (DataField.UPTIME in fields) n.uptimeSeconds?.let { parts += "device on for ${duration(it * 1000)}" }
        if (DataField.TEMPERATURE in fields) n.temperature?.let { parts += "%.0f°C where they are".format(it) }
        if (DataField.HUMIDITY in fields) n.humidity?.let { parts += "humidity %.0f%%".format(it) }
        if (DataField.PRESSURE in fields) n.pressure?.let { parts += "pressure %.0f hPa".format(it) }
        if (DataField.HW_MODEL in fields) n.hwModel?.takeIf { it != "UNSET" }?.let { parts += "uses a ${prettyEnum(it)}" }
        if (DataField.ROLE in fields) n.role?.let { parts += "device role ${prettyEnum(it)}" }
        if (DataField.FIRST_SEEN in fields) parts += "first seen ${ago(snapshot.now - n.firstSeen)}"
        if (DataField.LAST_HEARD in fields && n.lastHeard > 0) parts += "last heard ${ago(snapshot.now - n.lastHeard)}"
        if (DataField.MESSAGES_TODAY in fields) snapshot.messagesToday[n.nodeNum]?.let { parts += "$it message${if (it == 1) "" else "s"} today" }
        val head = names.full(n.nodeNum) + if (parts.isEmpty()) "" else ": " + parts.joinToString("; ")
        return head + memoryLines(n.nodeNum, snapshot)
    }

    private fun memoryLines(num: Long, snapshot: MeshSnapshot): String = buildString {
        val r = snapshot.relationships[num]
        r?.feeling?.takeIf { it.isNotBlank() }?.let { f -> phrase(PromptPhrase.FEELING, "feeling" to f)?.let { append("\n  ").append(it) } }
        r?.summary?.takeIf { it.isNotBlank() }?.let { s -> phrase(PromptPhrase.SUMMARY, "summary" to s)?.let { append("\n  ").append(it) } }
        snapshot.facts[num]?.take(MAX_FACTS_IN_PROMPT)?.takeIf { it.isNotEmpty() }?.let {
            phrase(PromptPhrase.FACTS, "facts" to it.joinToString("; "))?.let { line -> append("\n  ").append(line) }
        }
    }

    private fun relativeLocation(n: NodeEntity, snapshot: MeshSnapshot): String? {
        val wantsLocation = DataField.PROXIMITY in fields || DataField.DISTANCE in fields || DataField.DIRECTION in fields
        if (!wantsLocation) return null
        val me = snapshot.myNodeNum?.let { snapshot.nodes[it] }
        if (DataField.OWN_LOCATION !in fields || me == null || !me.hasPosition) return null
        if (!n.hasPosition) return "location unknown"
        val d = Geo.distanceMeters(me.latitude!!, me.longitude!!, n.latitude!!, n.longitude!!)
        val uncertainty = Geo.precisionMeters(me.positionPrecisionBits) + Geo.precisionMeters(n.positionPrecisionBits)
        val dir = Geo.compass(Geo.bearingDegrees(me.latitude, me.longitude, n.latitude, n.longitude))
        if (uncertainty > d) {
            return if (DataField.DISTANCE in fields) "somewhere within ~${Geo.formatDistance(uncertainty)} of you (their location is approximate)"
            else "location is approximate"
        }
        val pieces = mutableListOf<String>()
        if (DataField.DISTANCE in fields) {
            pieces += (if (uncertainty > 0) "about " else "") + Geo.formatDistance(d)
        }
        if (DataField.DIRECTION in fields) pieces += "${compassWord(dir)} of you"
        val tier = if (DataField.PROXIMITY in fields) " (${proximity(d)})" else ""
        val age = n.positionTime?.let { snapshot.now - it } ?: 0
        val stale = if (age > STALE_POSITION_MS) ", but that location is from ${ago(age)} so they may have moved" else ""
        return pieces.joinToString(" ") + tier + stale + movementText(n.nodeNum, snapshot)
    }

    private fun movementText(num: Long, snapshot: MeshSnapshot): String {
        if (DataField.MOVEMENT !in fields) return ""
        val m = snapshot.movement[num] ?: return ""
        val was = if (DataField.DISTANCE in fields) " (was ${Geo.formatDistance(m.wasMeters)} ~10 min ago)" else ""
        return when (m.trend) {
            Trend.APPROACHING -> "; getting closer$was"
            Trend.LEAVING -> "; moving away$was"
        }
    }

    /** Volatile facts at the end of the prompt (time, my battery, who's around). */
    fun surroundings(snapshot: MeshSnapshot, names: Names = Names(snapshot)): String? {
        val lines = mutableListOf<String>()
        if (DataField.LOCAL_TIME in fields) phrase(PromptPhrase.TIME, "time" to dayClock.format(Instant.ofEpochMilli(snapshot.now)))?.let { lines += it }
        val me = snapshot.myNodeNum?.let { snapshot.nodes[it] }
        if (DataField.OWN_BATTERY in fields) me?.batteryLevel?.let { b ->
            (if (b > 100) phrase(PromptPhrase.PLUGGED_IN) else phrase(PromptPhrase.MY_BATTERY, "percent" to b.toString()))?.let { lines += it }
        }
        val others = snapshot.nodes.values.filter { it.nodeNum != snapshot.myNodeNum && !it.ignored }
        if (DataField.NODES_HEARD in fields) {
            val recent = others.count { snapshot.now - it.lastHeard < 60 * 60 * 1000 }
            phrase(
                PromptPhrase.ACTIVE_PEOPLE,
                "count" to recent.toString(), "people" to if (recent == 1) "person has been" else "people have been",
            )?.let { lines += it }
        }
        if (DataField.NEAREST_PEOPLE in fields && DataField.OWN_LOCATION in fields && me != null && me.hasPosition) {
            val nearest = others.filter { it.hasPosition && snapshot.now - it.lastHeard < 6 * 60 * 60 * 1000 }
                .map { it to Geo.distanceMeters(me.latitude!!, me.longitude!!, it.latitude!!, it.longitude!!) }
                .sortedBy { it.second }
                .take(3)
            if (nearest.isNotEmpty()) {
                val list = nearest.joinToString(", ") { (n, d) ->
                    names.of(n.nodeNum) + if (DataField.DISTANCE in fields) " (${Geo.formatDistance(d)})" else ""
                }
                phrase(PromptPhrase.NEAREST, "list" to list)?.let { lines += it }
            }
        }
        return lines.takeIf { it.isNotEmpty() }?.joinToString(" ")
    }

    /** Display names honoring the identity toggles; falls back to a stable per-node alias like "Person 4f11". */
    inner class Names(private val snapshot: MeshSnapshot) {
        fun of(num: Long): String {
            if (num == BROADCAST_NODE) return "everyone"
            val n = snapshot.nodes[num]
            if (DataField.LONG_NAME in fields) n?.longName?.let { return it }
            if (DataField.SHORT_NAME in fields) n?.shortName?.let { return it }
            if (DataField.NODE_ID in fields) return nodeIdString(num)
            return phrase(PromptPhrase.ALIAS, "id" to "%04x".format(num and 0xFFFF)) ?: "%04x".format(num and 0xFFFF)
        }

        fun full(num: Long): String {
            val base = of(num)
            val n = snapshot.nodes[num]
            val extras = buildList {
                if (DataField.SHORT_NAME in fields && DataField.LONG_NAME in fields) n?.shortName?.let { add(it) }
                if (DataField.NODE_ID in fields && base != nodeIdString(num)) add(nodeIdString(num))
            }
            return if (extras.isEmpty()) base else "$base (${extras.joinToString(", ")})"
        }
    }

    companion object {
        const val STALE_POSITION_MS = 60 * 60 * 1000L
        const val MAX_FACTS_IN_PROMPT = 3
        const val MAX_SELF_MEMORIES = 5
        const val FULL_LINES = 2
        const val OLD_LINE_CHARS = 80
        const val REMINDER_CHARS = 240

        /** {they} {them} {their} {themself} for the character, usable in every template. */
        fun pronounValues(c: CharacterEntity) = mapOf(
            "they" to c.pronouns.subject, "them" to c.pronouns.obj, "their" to c.pronouns.possessive, "themself" to c.pronouns.reflexive,
        )

        /** The newest message in [history] that someone else wrote (what a reply should answer). */
        fun lastFromOthers(history: List<MessageEntity>, snapshot: MeshSnapshot): MessageEntity? =
            history.filter { it.origin == MessageOrigin.MESH && it.fromNode != snapshot.myNodeNum }
                .maxWithOrNull(compareBy({ it.rxTime }, { it.id }))

        fun sanitize(text: String): String =
            text.replace('<', '‹').replace('>', '›').replace('\n', ' ').trim()

        fun proximity(meters: Double): String = when {
            meters < 50 -> "right next to you"
            meters < 300 -> "nearby"
            meters < 1_500 -> "around the area"
            meters < 15_000 -> "across town"
            else -> "far away"
        }

        private fun compassWord(c: String) = when (c) {
            "N" -> "north"; "NE" -> "northeast"; "E" -> "east"; "SE" -> "southeast"
            "S" -> "south"; "SW" -> "southwest"; "W" -> "west"; else -> "northwest"
        }

        private fun prettyEnum(s: String) = s.lowercase().split('_').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
            .replace("T Echo", "T-Echo")

        fun ago(ms: Long): String {
            val m = (ms / 60_000.0).roundToInt()
            return when {
                m < 1 -> "just now"
                m < 60 -> "$m min ago"
                m < 48 * 60 -> "${(m / 60.0).roundToInt()} h ago"
                else -> "${m / (24 * 60)} days ago"
            }
        }

        private fun duration(ms: Long): String = ago(ms).removeSuffix(" ago").replace("just now", "under a minute")
    }
}
