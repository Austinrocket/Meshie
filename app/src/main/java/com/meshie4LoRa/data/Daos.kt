package com.meshie4LoRa.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface NodeDao {
    @Query("SELECT * FROM nodes WHERE nodeNum = :nodeNum")
    suspend fun get(nodeNum: Long): NodeEntity?

    @Upsert
    suspend fun upsert(node: NodeEntity)

    @Query("SELECT * FROM nodes ORDER BY lastHeard DESC")
    fun observeAll(): Flow<List<NodeEntity>>

    @Query("UPDATE nodes SET ignored = :ignored WHERE nodeNum = :nodeNum")
    suspend fun setIgnored(nodeNum: Long, ignored: Boolean)
}

@Dao
interface MessageDao {
    @Insert
    suspend fun insert(message: MessageEntity): Long

    @Query("SELECT * FROM messages ORDER BY rxTime DESC, id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<MessageEntity>>

    /** One DM conversation, newest first, however old (the Feed's recent window would drop older DMs). */
    @Query("SELECT * FROM messages WHERE toNode != $BROADCAST_NODE AND $DM_PARTNER_SQL = :peer ORDER BY rxTime DESC, id DESC")
    fun observeDm(peer: Long, me: Long): Flow<List<MessageEntity>>

    @Query("SELECT COUNT(*) FROM messages WHERE packetId = :packetId AND fromNode = :fromNode")
    suspend fun countByPacket(packetId: Long, fromNode: Long): Int

    @Query("SELECT fromNode, COUNT(*) AS count FROM messages WHERE read = 0 GROUP BY fromNode")
    fun observeUnreadByNode(): Flow<List<UnreadCount>>

    @Query("UPDATE messages SET read = 1 WHERE fromNode = :nodeNum AND read = 0")
    suspend fun markRead(nodeNum: Long)

    @Query("SELECT DISTINCT $DM_PARTNER_SQL FROM messages WHERE toNode != $BROADCAST_NODE")
    fun observeDmPeers(me: Long): Flow<List<Long>>

    /** Newest-first; callers reverse for chronological prompts. */
    @Query("SELECT * FROM messages WHERE toNode = $BROADCAST_NODE AND channel = :channel ORDER BY rxTime DESC, id DESC LIMIT :limit")
    suspend fun recentChannel(channel: Int, limit: Int): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE toNode != $BROADCAST_NODE AND $DM_PARTNER_SQL = :peer ORDER BY rxTime DESC, id DESC LIMIT :limit")
    suspend fun recentDm(peer: Long, me: Long, limit: Int): List<MessageEntity>

    /** Everything a user said (channel or DM) plus our DM replies to them, newer than [since]. */
    @Query(
        "SELECT * FROM messages WHERE rxTime > :since AND (fromNode = :peer OR (toNode = :peer AND origin != 'MESH')) " +
            "ORDER BY rxTime DESC LIMIT :limit"
    )
    suspend fun involving(peer: Long, since: Long, limit: Int): List<MessageEntity>

    /** Users (not us) who sent a text after [since], with their newest message time. */
    @Query("SELECT fromNode, MAX(rxTime) AS lastAt FROM messages WHERE origin = 'MESH' AND rxTime > :since GROUP BY fromNode")
    suspend fun activeSince(since: Long): List<ActiveUser>

    @Query("SELECT * FROM messages WHERE origin = 'BOT_MANUAL' ORDER BY rxTime DESC LIMIT :limit")
    suspend fun recentManual(limit: Int): List<MessageEntity>

    @Query("UPDATE messages SET favorite = :favorite WHERE id = :id")
    suspend fun setFavorite(id: Long, favorite: Boolean)

    @Query("UPDATE messages SET favorite = 0 WHERE favorite = 1")
    suspend fun clearFavorites()

    @Query("SELECT * FROM messages WHERE favorite = 1 ORDER BY rxTime DESC")
    fun observeFavorites(): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE favorite = 1")
    suspend fun favorites(): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun byId(id: Long): MessageEntity?

    /** The message just before [m] in the same conversation (what it was answering), within 10 minutes. */
    @Query(
        "SELECT * FROM messages WHERE channel = :channel AND rxTime < :at AND rxTime >= :at - 600000 AND " +
            "((:peer IS NULL AND toNode = 4294967295) OR (:peer IS NOT NULL AND toNode != 4294967295 AND $DM_PARTNER_SQL = :peer)) " +
            "ORDER BY rxTime DESC LIMIT 1"
    )
    suspend fun previousIn(channel: Int, peer: Long?, me: Long, at: Long): MessageEntity?

    @Query("SELECT origin, COUNT(*) AS count FROM messages WHERE rxTime >= :since GROUP BY origin")
    fun observeOriginCounts(since: Long): Flow<List<OriginCount>>

    @Query("SELECT * FROM messages WHERE origin != 'MESH' AND rxTime >= :since ORDER BY rxTime DESC")
    fun observeAllBot(since: Long): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE origin = :origin AND rxTime >= :since ORDER BY rxTime DESC")
    fun observeByOrigin(origin: MessageOrigin, since: Long): Flow<List<MessageEntity>>

    @Query(
        "SELECT COUNT(DISTINCT CASE WHEN origin = 'MESH' THEN fromNode WHEN toNode != 4294967295 THEN toNode END) " +
            "FROM messages WHERE rxTime >= :since"
    )
    fun observeTalkedTo(since: Long): Flow<Int>

    /** Everyone who talked (or was DMed) since [since], with how many messages each. */
    @Query(
        "SELECT CASE WHEN origin = 'MESH' THEN fromNode ELSE toNode END AS nodeNum, COUNT(*) AS count FROM messages " +
            "WHERE rxTime >= :since AND (origin = 'MESH' OR toNode != 4294967295) GROUP BY nodeNum ORDER BY count DESC"
    )
    fun observeTalkedToList(since: Long): Flow<List<NodeCount>>

    @Query("SELECT * FROM messages WHERE origin != 'MESH' AND rxTime >= :since ORDER BY LENGTH(text) DESC LIMIT 1")
    fun observeLongestBot(since: Long): Flow<MessageEntity?>

    @Query("SELECT fromNode, COUNT(*) AS count FROM messages WHERE rxTime >= :since GROUP BY fromNode")
    suspend fun countsSince(since: Long): List<UnreadCount>

    /** Messages we sent (by the bot or by hand) since [since]; feeds the hard rate limit. */
    @Query("SELECT COUNT(*) FROM messages WHERE origin != 'MESH' AND rxTime >= :since")
    suspend fun countSentSince(since: Long): Int
}

@Dao
interface CharacterDao {
    @Query("SELECT * FROM characters ORDER BY active DESC, longName")
    fun observeAll(): Flow<List<CharacterEntity>>

    @Query("SELECT * FROM characters WHERE active = 1 LIMIT 1")
    fun observeActive(): Flow<CharacterEntity?>

    @Query("SELECT * FROM characters WHERE id = :id")
    suspend fun get(id: Long): CharacterEntity?

    @Query("SELECT COUNT(*) FROM characters")
    suspend fun count(): Int

    @Upsert
    suspend fun upsert(character: CharacterEntity): Long

    @Query("DELETE FROM characters WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE characters SET active = (id = :id)")
    suspend fun setActive(id: Long)
}

data class UnreadCount(val fromNode: Long, val count: Int)

data class OriginCount(val origin: MessageOrigin, val count: Int)

data class NodeCount(val nodeNum: Long, val count: Int)

@Dao
interface MemoryDao {
    @Query("SELECT * FROM memories WHERE nodeNum = :nodeNum ORDER BY pinned DESC, updatedAt DESC")
    fun observeFor(nodeNum: Long): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memories WHERE nodeNum IS NULL ORDER BY pinned DESC, updatedAt DESC")
    fun observeSelf(): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memories WHERE nodeNum IS NULL ORDER BY pinned DESC, updatedAt DESC")
    suspend fun self(): List<MemoryEntity>

    @Query("SELECT * FROM memories WHERE nodeNum IN (:nodes) ORDER BY pinned DESC, updatedAt DESC")
    suspend fun forNodes(nodes: List<Long>): List<MemoryEntity>

    @Query("SELECT * FROM memories")
    suspend fun all(): List<MemoryEntity>

    @Query("SELECT COUNT(*) FROM memories")
    fun observeCount(): Flow<Int>

    @Query("SELECT * FROM memories")
    fun observeAll(): Flow<List<MemoryEntity>>

    @Upsert
    suspend fun upsert(m: MemoryEntity): Long

    @Query("DELETE FROM memories WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM memories WHERE nodeNum = :nodeNum AND source = 'AUTO' AND pinned = 0")
    suspend fun clearAutoFor(nodeNum: Long)

    @Query("DELETE FROM memories WHERE nodeNum = :nodeNum")
    suspend fun clearAllFor(nodeNum: Long)

    @Query("DELETE FROM memories WHERE source = 'AUTO' AND pinned = 0")
    suspend fun clearAllAuto()

    @Query("DELETE FROM memories WHERE pinned = 0")
    suspend fun clearAllUnpinned()

    @Query("DELETE FROM memories")
    suspend fun wipe()
}

@Dao
interface PositionDao {
    @Insert
    suspend fun insert(p: PositionEntity)

    @Query("SELECT * FROM positions WHERE at >= :since ORDER BY at")
    suspend fun since(since: Long): List<PositionEntity>

    @Query("DELETE FROM positions WHERE at < :before")
    suspend fun prune(before: Long)
}

@Dao
interface RelationshipDao {
    @Query("SELECT * FROM relationships WHERE nodeNum = :nodeNum")
    suspend fun get(nodeNum: Long): RelationshipEntity?

    @Query("SELECT * FROM relationships WHERE nodeNum = :nodeNum")
    fun observe(nodeNum: Long): Flow<RelationshipEntity?>

    @Query("SELECT * FROM relationships")
    fun observeAll(): Flow<List<RelationshipEntity>>

    @Query("SELECT * FROM relationships")
    suspend fun all(): List<RelationshipEntity>

    @Upsert
    suspend fun upsert(r: RelationshipEntity)

    @Query("DELETE FROM relationships WHERE nodeNum = :nodeNum")
    suspend fun delete(nodeNum: Long)

    @Query("UPDATE relationships SET summary = '', processedUntil = 0")
    suspend fun clearAllSummaries()

    @Query("UPDATE relationships SET feeling = '', feelingReason = '', feelingChangedBy = NULL WHERE feelingLocked = 0")
    suspend fun clearUnlockedFeelings()

    @Query("DELETE FROM relationships")
    suspend fun wipe()

    @Insert
    suspend fun log(entry: FeelingLogEntity)

    @Query("SELECT * FROM feeling_log WHERE nodeNum = :nodeNum ORDER BY at DESC LIMIT 20")
    fun observeLog(nodeNum: Long): Flow<List<FeelingLogEntity>>

    @Query("DELETE FROM feeling_log WHERE nodeNum = :nodeNum")
    suspend fun clearLog(nodeNum: Long)

    @Query("DELETE FROM feeling_log")
    suspend fun wipeLog()
}

data class ActiveUser(val fromNode: Long, val lastAt: Long)

@Dao
interface CooldownDao {
    @Query("SELECT seconds FROM cooldowns WHERE nodeNum = :nodeNum")
    suspend fun get(nodeNum: Long): Long?

    @Query("SELECT seconds FROM cooldowns WHERE nodeNum = :nodeNum")
    fun observe(nodeNum: Long): Flow<Long?>

    @Upsert
    suspend fun upsert(c: CooldownEntity)

    @Query("DELETE FROM cooldowns WHERE nodeNum = :nodeNum")
    suspend fun delete(nodeNum: Long)
}

@Dao
interface DecisionDao {
    @Query("SELECT COUNT(*) FROM decisions WHERE outcome = :outcome AND at >= :since")
    fun observeCount(outcome: DecisionOutcome, since: Long): Flow<Int>

    @Query("SELECT MAX(at) FROM decisions WHERE idle = 1")
    suspend fun lastIdleAt(): Long?

    /** When the bot last answered a message from [nodeNum] (sent, drafted or, in Observe mode, would have). */
    @Query(
        "SELECT MAX(d.at) FROM decisions d JOIN messages m ON m.id = d.triggerMessageId " +
            "WHERE m.fromNode = :nodeNum AND d.outcome IN ('SENT', 'DRAFT', 'OBSERVED')"
    )
    suspend fun lastAnsweredFrom(nodeNum: Long): Long?

    @Insert
    suspend fun insert(d: DecisionEntity): Long

    @Query("SELECT * FROM decisions WHERE id = :id")
    suspend fun get(id: Long): DecisionEntity?

    @Query("UPDATE decisions SET favorite = :favorite WHERE id = :id")
    suspend fun setFavorite(id: Long, favorite: Boolean)

    @Query("UPDATE decisions SET favorite = 0 WHERE favorite = 1")
    suspend fun clearFavorites()

    @Query("SELECT * FROM decisions WHERE favorite = 1 AND reply IS NOT NULL ORDER BY at DESC")
    fun observeFavorites(): Flow<List<DecisionEntity>>

    @Query("SELECT * FROM decisions WHERE favorite = 1 AND reply IS NOT NULL")
    suspend fun favorites(): List<DecisionEntity>

    @Query("UPDATE decisions SET outcome = :outcome, reply = :reply WHERE id = :id")
    suspend fun update(id: Long, outcome: DecisionOutcome, reply: String?)

    @Query("SELECT * FROM decisions ORDER BY at DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<DecisionEntity>>

    @Query("SELECT * FROM decisions WHERE peer = :peer AND outcome = 'SENT' ORDER BY at DESC LIMIT :limit")
    suspend fun recentSent(peer: Long, limit: Int): List<DecisionEntity>
}

@Dao
interface ModelDao {
    @Query("SELECT * FROM models ORDER BY source, sizeBytes")
    fun observeAll(): Flow<List<ModelEntity>>

    @Query("SELECT * FROM models WHERE id = :id")
    suspend fun get(id: String): ModelEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfMissing(models: List<ModelEntity>)

    @Upsert
    suspend fun upsert(model: ModelEntity)

    @Query("DELETE FROM models WHERE id = :id")
    suspend fun delete(id: String)
}
