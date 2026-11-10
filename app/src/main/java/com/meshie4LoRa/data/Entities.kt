package com.meshie4LoRa.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

const val BROADCAST_NODE = 0xFFFFFFFFL

@Entity(tableName = "nodes")
data class NodeEntity(
    @PrimaryKey val nodeNum: Long,
    val userId: String? = null,
    val longName: String? = null,
    val shortName: String? = null,
    val hwModel: String? = null,
    val role: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val altitude: Int? = null,
    val positionTime: Long? = null,
    val positionPrecisionBits: Int? = null,
    val groundSpeed: Int? = null,
    val groundTrack: Int? = null,
    val batteryLevel: Int? = null,
    val voltage: Float? = null,
    val channelUtil: Float? = null,
    val airUtilTx: Float? = null,
    val uptimeSeconds: Long? = null,
    val temperature: Float? = null,
    val humidity: Float? = null,
    val pressure: Float? = null,
    val snr: Float? = null,
    val rssi: Int? = null,
    val hopsAway: Int? = null,
    val viaMqtt: Boolean = false,
    val firstSeen: Long,
    val lastHeard: Long,
    val ignored: Boolean = false,
) {
    val displayName: String get() = longName ?: shortName ?: nodeIdString(nodeNum)
    val hasPosition: Boolean get() = latitude != null && longitude != null
}

enum class MessageOrigin { MESH, BOT_AUTO, BOT_MANUAL }

/** Recent radio-reported positions (ours and others'), kept a few hours for approaching/leaving. Never the phone's GPS. */
@Entity(tableName = "positions", indices = [Index("nodeNum", "at")])
data class PositionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val nodeNum: Long,
    val latitude: Double,
    val longitude: Double,
    val at: Long,
)

@Entity(tableName = "messages", indices = [Index("rxTime"), Index("fromNode")])
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packetId: Long,
    val channel: Int,
    val fromNode: Long,
    val toNode: Long,
    val text: String,
    val rxTime: Long,
    val rxSnr: Float? = null,
    val rxRssi: Int? = null,
    val hopsAway: Int? = null,
    val viaMqtt: Boolean = false,
    val origin: MessageOrigin,
    @ColumnInfo(defaultValue = "1") val read: Boolean = true,
    /** Starred by the operator; used as inspiration for "from favorites" messages. */
    @ColumnInfo(defaultValue = "0") val favorite: Boolean = false,
) {
    val isDirect: Boolean get() = toNode != BROADCAST_NODE

    /** The other person in a DM. Older rows may be from a different radio of ours, so fall back on direction. Keep in sync with [DM_PARTNER_SQL]. */
    fun dmPartner(me: Long?): Long = when {
        fromNode == me -> toNode
        toNode == me -> fromNode
        origin == MessageOrigin.MESH -> fromNode
        else -> toNode
    }
}

const val DM_PARTNER_SQL = "(CASE WHEN fromNode = :me THEN toNode WHEN toNode = :me THEN fromNode WHEN origin = 'MESH' THEN fromNode ELSE toNode END)"

fun nodeIdString(nodeNum: Long): String = "!%08x".format(nodeNum)

enum class ModelSource { CATALOG, CUSTOM_URL, IMPORTED }

enum class DecisionOutcome { SKIPPED, OBSERVED, DRAFT, SENT, DISCARDED, ERROR }

enum class MemorySource { AUTO, MANUAL }

enum class ChangedBy { MODEL, USER }

/** A remembered fact. nodeNum null = about the character/world; characterId null = shared by all characters (D5). */
@Entity(tableName = "memories", indices = [Index("nodeNum")])
data class MemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val nodeNum: Long?,
    val characterId: Long? = null,
    val text: String,
    val source: MemorySource,
    val pinned: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

/** Per-user rolling summary and free-text feeling (D6). characterId kept for future per-character memory. */
@Entity(tableName = "relationships")
data class RelationshipEntity(
    @PrimaryKey val nodeNum: Long,
    val characterId: Long? = null,
    val summary: String = "",
    val feeling: String = "",
    val feelingLocked: Boolean = false,
    val feelingChangedBy: ChangedBy? = null,
    val feelingReason: String = "",
    /** Newest message time already folded into summary/facts by the memory pass. */
    val processedUntil: Long = 0,
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "feeling_log", indices = [Index("nodeNum")])
data class FeelingLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val nodeNum: Long,
    val oldFeeling: String,
    val newFeeling: String,
    val changedBy: ChangedBy,
    val reason: String,
    val at: Long = System.currentTimeMillis(),
)

/** Per-person reply cooldown, kept apart from memory so clearing memories never drops a bot-loop defence. */
@Entity(tableName = "cooldowns")
data class CooldownEntity(
    @PrimaryKey val nodeNum: Long,
    val seconds: Long,
)

/** One pass of the brain (reply decision or idle chatter); shown in the Feed and kept for tuning. */
@Entity(tableName = "decisions", indices = [Index("at")])
data class DecisionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val at: Long = System.currentTimeMillis(),
    /** 0xFFFFFFFF for the channel, otherwise the DM peer. */
    val peer: Long,
    val channel: Int,
    val triggerMessageId: Long? = null,
    val idle: Boolean = false,
    val respond: Boolean,
    val reason: String,
    val reply: String? = null,
    val outcome: DecisionOutcome,
    val latencyMs: Long = 0,
    val characterId: Long? = null,
    /** Starred observed/unsent reply; counts as a favorite like a starred message. */
    @ColumnInfo(defaultValue = "0") val favorite: Boolean = false,
)

/** Persona pronouns, used everywhere the UI or a prompt refers to the character. */
enum class Pronouns(val label: String, val subject: String, val obj: String, val possessive: String, val reflexive: String, val plural: Boolean) {
    SHE("she/her", "she", "her", "her", "herself", false),
    HE("he/him", "he", "him", "his", "himself", false),
    THEY("they/them", "they", "them", "their", "themself", true),
    IT("it/its", "it", "it", "its", "itself", false);

    val capitalSubject: String get() = subject.replaceFirstChar(Char::uppercase)

    /** Picks the verb form: verb("knows", "know") -> "knows" for she/he/it, "know" for they. */
    fun verb(singular: String, plural: String) = if (this.plural) plural else singular
}

/** A persona wrapper. Exactly one is active (D4); its names are pushed to the radio. */
@Entity(tableName = "characters")
data class CharacterEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val longName: String,
    val shortName: String,
    /** Comma-separated extra names that count as a mention, e.g. "chan, mesh-chan". */
    val nicknames: String = "",
    val persona: String,
    /** Example lines in the character's voice, one per line. */
    val examples: String = "",
    /** Style rules, one per line. */
    val rules: String = "",
    /** 0..100, bias for joining conversations. */
    val eagerness: Int = 50,
    /** Optional flavor for idle conversation starters. */
    val idlePrompt: String = "",
    /** Words/phrases (one per line) that block a reply. Never shown to the model, so they can't prime it. */
    @ColumnInfo(defaultValue = "") val bannedPhrases: String = "",
    @ColumnInfo(defaultValue = "SHE") val pronouns: Pronouns = Pronouns.SHE,
    /** Chibi avatar layers as JSON (see ui.stats.Avatar); empty = default look. */
    @ColumnInfo(defaultValue = "") val avatar: String = "",
    val active: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
) {
    val nicknameList: List<String> get() = nicknames.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    val bannedList: List<String> get() = bannedPhrases.split('\n', ',').map { it.trim() }.filter { it.isNotEmpty() }
}

@Entity(tableName = "models")
data class ModelEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String = "",
    val url: String? = null,
    val fileName: String,
    val sizeBytes: Long? = null,
    val sha256: String? = null,
    val source: ModelSource,
    val downloadId: Long? = null,
    val verified: Boolean = false,
    val addedAt: Long = System.currentTimeMillis(),
)
