package com.meshie4LoRa.brain

import com.meshie4LoRa.data.CharacterEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BotBrainTest {
    private val c = CharacterEntity(longName = "Meshimi-chan", shortName = "uwu", nicknames = "chan, mesh-chan", persona = "x")

    @Test
    fun mentionsMatchNamesAndNicknamesAsWholeWords() {
        assertTrue(BotBrain.mentions(c, "hey chan what's up"))
        assertTrue(BotBrain.mentions(c, "MESHIMI-CHAN!!"))
        assertTrue(BotBrain.mentions(c, "uwu"))
        assertTrue(BotBrain.mentions(c, "@mesh-chan you there?"))
        assertFalse(BotBrain.mentions(c, "the channel is quiet"))
        assertFalse(BotBrain.mentions(c, "chandelier"))
    }
}
