package com.meshie4LoRa.brain

import com.meshie4LoRa.data.CharacterEntity
import com.meshie4LoRa.data.MemoryEntity
import com.meshie4LoRa.data.MemorySource
import com.meshie4LoRa.data.Pronouns
import com.meshie4LoRa.mesh.MAX_LONG_NAME_BYTES
import com.meshie4LoRa.mesh.MAX_SHORT_NAME_BYTES
import org.json.JSONArray
import org.json.JSONObject

/** A shareable persona: the character, its chibi look, and its memories about itself. Nothing about mesh users, so it travels. */
object PersonaFile {
    const val FORMAT = "meshchan-persona"
    const val VERSION = 1
    /** Larger files are refused before parsing. */
    const val MAX_FILE_BYTES = 2_000_000
    private const val MAX_TEXT = 8_000
    private const val MAX_FACT = 140

    data class Contents(val character: CharacterEntity, val memories: List<MemoryEntity>)

    /** Only memories about the character itself (nodeNum null) are written. */
    fun encode(ch: CharacterEntity, memories: List<MemoryEntity>, now: Long = System.currentTimeMillis()): String {
        val persona = JSONObject()
            .put("longName", ch.longName).put("shortName", ch.shortName).put("nicknames", ch.nicknames)
            .put("pronouns", ch.pronouns.name).put("persona", ch.persona).put("rules", ch.rules).put("examples", ch.examples)
            .put("eagerness", ch.eagerness).put("idlePrompt", ch.idlePrompt).put("bannedPhrases", ch.bannedPhrases)
        runCatching { JSONObject(ch.avatar) }.getOrNull()?.let { persona.put("avatar", it) }
        return JSONObject()
            .put("format", FORMAT).put("version", VERSION).put("exportedAt", now)
            .put("persona", persona)
            .put("memories", JSONArray().apply {
                memories.filter { it.nodeNum == null }.forEach { m ->
                    put(JSONObject().put("text", m.text).put("source", m.source.name).put("pinned", m.pinned).put("createdAt", m.createdAt))
                }
            })
            .toString(2)
    }

    /** Throws [IllegalArgumentException] for anything that isn't a persona file. The character comes back new and inactive. */
    fun decode(json: String, now: Long = System.currentTimeMillis()): Contents {
        val root = runCatching { JSONObject(json) }.getOrElse { throw IllegalArgumentException("not a persona file") }
        require(root.optString("format") == FORMAT) { "not a persona file" }
        require(root.optInt("version") in 1..VERSION) { "made by a newer version of the app" }
        val p = root.optJSONObject("persona") ?: throw IllegalArgumentException("no persona in the file")
        fun text(key: String) = p.optString(key).take(MAX_TEXT)
        val character = CharacterEntity(
            longName = cutBytes(p.optString("longName").trim(), MAX_LONG_NAME_BYTES),
            shortName = cutBytes(p.optString("shortName").trim(), MAX_SHORT_NAME_BYTES),
            nicknames = text("nicknames"),
            persona = text("persona"),
            examples = text("examples"),
            rules = text("rules"),
            eagerness = p.optInt("eagerness", 50).coerceIn(0, 100),
            idlePrompt = text("idlePrompt"),
            bannedPhrases = text("bannedPhrases"),
            pronouns = Pronouns.entries.firstOrNull { it.name == p.optString("pronouns") } ?: Pronouns.SHE,
            avatar = p.optJSONObject("avatar")?.toString().orEmpty(),
            active = false,
            createdAt = now,
        )
        require(character.longName.isNotEmpty() && character.persona.isNotBlank()) { "the persona has no name or description" }
        // Facts about specific users (from older or hand-made files) are dropped.
        val memories = root.optJSONArray("memories").objects().mapNotNull { o ->
            val t = o.optString("text").trim().take(MAX_FACT)
            if (t.isEmpty() || (o.has("nodeNum") && !o.isNull("nodeNum"))) return@mapNotNull null
            MemoryEntity(
                nodeNum = null,
                text = t,
                source = MemorySource.entries.firstOrNull { it.name == o.optString("source") } ?: MemorySource.MANUAL,
                pinned = o.optBoolean("pinned"),
                createdAt = o.optLong("createdAt", now),
                updatedAt = now,
            )
        }
        return Contents(character, memories)
    }

    /** [s] clipped to at most [max] UTF-8 bytes without splitting a character. */
    internal fun cutBytes(s: String, max: Int): String {
        var end = 0
        var bytes = 0
        while (end < s.length) {
            val next = s.offsetByCodePoints(end, 1)
            bytes += s.substring(end, next).toByteArray().size
            if (bytes > max) break
            end = next
        }
        return s.substring(0, end)
    }

    private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
}
