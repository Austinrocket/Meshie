package com.meshie4LoRa.brain

import com.meshie4LoRa.data.CharacterEntity
import com.meshie4LoRa.data.MemoryEntity
import com.meshie4LoRa.data.MemorySource
import com.meshie4LoRa.data.Pronouns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonaFileTest {
    private val ch = CharacterEntity(
        id = 7, longName = "sakura", shortName = "uwu", nicknames = "saku", persona = "Pink-haired party girl.",
        rules = "lowercase", examples = "hiii", eagerness = 70, idlePrompt = "festivals", bannedPhrases = "as an AI",
        pronouns = Pronouns.SHE, avatar = """{"hair":"BOB","frame":"FF1E88E5"}""", active = true,
    )

    @Test
    fun roundTripKeepsPersonaLookAndOwnMemoriesOnly() {
        val json = PersonaFile.encode(
            ch,
            listOf(MemoryEntity(nodeNum = null, text = "I won karaoke", source = MemorySource.MANUAL, pinned = true),
                MemoryEntity(nodeNum = 42, text = "likes tacos", source = MemorySource.AUTO)),
        )
        assertFalse(json, json.contains("tacos"))
        assertFalse(json, json.contains("nodeNum"))
        val back = PersonaFile.decode(json)
        val c = back.character
        assertEquals(0L, c.id)
        assertFalse(c.active)
        assertEquals(listOf("sakura", "uwu", "saku", "Pink-haired party girl.", "lowercase", "hiii", "festivals", "as an AI"),
            listOf(c.longName, c.shortName, c.nicknames, c.persona, c.rules, c.examples, c.idlePrompt, c.bannedPhrases))
        assertEquals(70, c.eagerness)
        assertEquals(Pronouns.SHE, c.pronouns)
        assertTrue(c.avatar, c.avatar.contains("BOB") && c.avatar.contains("FF1E88E5"))
        assertEquals(listOf<Long?>(null), back.memories.map { it.nodeNum })
        assertEquals("I won karaoke", back.memories[0].text)
        assertTrue(back.memories[0].pinned)
    }

    @Test
    fun importDropsFactsAboutUsers() {
        val json = """{"format":"meshchan-persona","version":1,"persona":{"longName":"x","shortName":"x","persona":"p"},
            "memories":[{"nodeNum":42,"text":"likes tacos"},{"nodeNum":null,"text":"I sing"},{"text":"I dance"}],
            "relationships":[{"nodeNum":42,"feeling":"fond"}]}"""
        assertEquals(listOf("I sing", "I dance"), PersonaFile.decode(json).memories.map { it.text })
    }

    @Test
    fun rejectsOtherFilesAndClampsNames() {
        assertThrows(IllegalArgumentException::class.java) { PersonaFile.decode("""{"memories":[]}""") }
        assertThrows(IllegalArgumentException::class.java) { PersonaFile.decode("not json") }
        val long = PersonaFile.encode(ch.copy(shortName = "toolong"), emptyList())
        assertEquals("tool", PersonaFile.decode(long).character.shortName)
    }
}
