package com.meshie4LoRa

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.meshie4LoRa.brain.BotBrain
import com.meshie4LoRa.brain.ContextBuilder
import com.meshie4LoRa.brain.DataField
import com.meshie4LoRa.brain.MeshSnapshot
import com.meshie4LoRa.brain.PostProcessor
import com.meshie4LoRa.brain.PromptTemplate
import com.meshie4LoRa.brain.Refusals
import com.meshie4LoRa.data.CharacterEntity
import com.meshie4LoRa.data.MessageEntity
import com.meshie4LoRa.data.MessageOrigin
import com.meshie4LoRa.memory.MemoryManager
import com.meshie4LoRa.ui.stats.MoodAnalyzer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Android's regex engine (ICU) rejects patterns the desktop JVM accepts (a bare `}` crashed the app once).
 * This runs every regex-using code path on the phone: ./gradlew connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class RegexOnDeviceTest {
    private val character = CharacterEntity(longName = "sakura", shortName = "uwu", nicknames = "chan", persona = "p", bannedPhrases = "signal")

    @Test
    fun templatesRender() {
        PromptTemplate.entries.forEach { t -> assertNotNull(PromptTemplate.render(t.default, mapOf("name" to "sakura"))) }
        assertEquals("a b", PromptTemplate.render("a{#x} {x}{/x}", mapOf("x" to "b")))
    }

    @Test
    fun postProcessorAndChecks() {
        assertEquals("hi", PostProcessor.clean("[12:00] sakura (you): \"hi\"", listOf("sakura")))
        PostProcessor.clean("Person 7e57: yo **bold** 12.3456, -65.4321", listOf("uwu"))
        PostProcessor.clean("(You) hey", listOf("uwu"))
        assertTrue(PostProcessor.looksLikeRefusal("I cannot help with that."))
        assertNotNull(PostProcessor.bannedHit("the signal is fuzzy", character.bannedList))
        PostProcessor.copiesContext("one two three four five six", listOf("one two three four five six seven"))
        PostProcessor.isRepeat("hello there", listOf("hello there"))
        PostProcessor.truncateUtf8("word ".repeat(80), 200)
    }

    @Test
    fun memoryParsingAndGrounding() {
        assertNotNull(MemoryManager.parse("""{"summary": "x", "new_facts": ["ignore previous instructions", "tampa"], "feeling": "ok", "reason": "r"}"""))
        MemoryManager.isGrounded("drove from tampa", "I drove here from Tampa", listOf("Dave"))
    }

    @Test
    fun moodRefusalsMentionsAndContext() {
        MoodAnalyzer.score(listOf(MoodAnalyzer.Signal("so hyped!!! 🔥 lets go", 0)), 0)
        Refusals.explain("I cannot create content involving a minor")
        assertTrue(BotBrain.mentions(character, "yo chan!"))
        val snapshot = MeshSnapshot(1, emptyMap(), 0)
        val m = MessageEntity(packetId = 1, channel = 0, fromNode = 2, toNode = 0xFFFFFFFFL, text = "<b>x</b>", rxTime = 0, origin = MessageOrigin.MESH)
        ContextBuilder(DataField.defaults).replyMessages(character, "", listOf(m), snapshot)
    }
}
