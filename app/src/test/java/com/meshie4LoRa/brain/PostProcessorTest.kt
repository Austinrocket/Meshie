package com.meshie4LoRa.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PostProcessorTest {
    @Test
    fun stripsEchoedPrefixesQuotesAndThinking() {
        assertEquals("hi there", PostProcessor.clean("<think>hmm</think>\"[12:01] Meshimi-chan: hi there\"", listOf("Meshimi-chan")))
        assertEquals("yo", PostProcessor.clean("you: yo\n\nsecond paragraph", emptyList()))
        assertEquals("bold move", PostProcessor.clean("**bold** move", emptyList()))
        assertEquals("Hey! Found a good spot", PostProcessor.clean("[RCKT]: Hey! Found a good spot", emptyList()))
        assertNull(PostProcessor.clean("[16:17] RCKT: hey chan, what's up?", listOf("uwu"), otherNames = listOf("Rocket", "RCKT")))
        assertNull(PostProcessor.clean("Person 7e57: okkay w8 ur turn", listOf("uwu")))
        assertEquals("Dude, what the...?!", PostProcessor.clean("(You) Dude, what the...?!", listOf("uwu")))
        assertEquals("hiii", PostProcessor.clean("sakura (you): hiii", listOf("sakura")))
        assertEquals("i'm single now", PostProcessor.clean("(sakura) : i'm single now\nDave: omg", listOf("sakura")))
        assertEquals("ur hot AF", PostProcessor.clean("(sakura's reply) ur hot AF", listOf("sakura")))
        assertEquals("nope", PostProcessor.clean("(sakura) (you): nope", listOf("sakura")))
        assertEquals("(just kidding) ok", PostProcessor.clean("(just kidding) ok", listOf("sakura")))
        assertNull(PostProcessor.clean(":2072><+8-%25AA1F\$>).%6E\$75F&<#=(F>7(&4<;2B", listOf("sakura")))
        assertEquals("lol pizza gang!!! 🔥🔥", PostProcessor.clean("lol pizza gang!!! 🔥🔥", listOf("sakura")))
        assertEquals("I'm so confused", PostProcessor.clean("(sakura) (you)\nI'm so confused", listOf("sakura")))
        assertEquals("lol no", PostProcessor.clean("You: \"lol no\"", listOf("sakura")))
        assertNull(PostProcessor.clean("(sakura) (You) <PRESIDIO_ANONYMIZED_RESPONSE>", listOf("sakura")))
        assertNull(PostProcessor.clean("(You) (you are sakura)\nDave: what's your favorite color", listOf("sakura"), otherNames = listOf("Dave")))
        assertEquals("test what tryin to do lmao", PostProcessor.clean("test what (you) tryin to do lmao", listOf("sakura")))
    }

    @Test
    fun removesCoordinatePairs() {
        val out = PostProcessor.clean("I'm at 12.3456, -65.4321 come say hi", emptyList())!!
        assertFalse(out, out.contains("12.3456"))
        assertTrue(out, out.contains("somewhere nearby"))
    }

    @Test
    fun rejectsLeakedPromptAndEmpty() {
        assertNull(PostProcessor.clean("   ", emptyList()))
        assertNull(PostProcessor.clean("<mesh channel=\"LongFast\">", emptyList()))
        assertNull(PostProcessor.clean("Sure! How you write: - Messages travel over slow radio", emptyList()))
        assertEquals("rate it 2/10, that name is a cry for help",
            PostProcessor.clean("<mesh channel=\"LongFast\">  rate it 2/10, that name is a cry for help", emptyList()))
    }

    @Test
    fun truncatesToByteLimitWithoutSplittingEmoji() {
        val long = "Wow 🎉 ".repeat(60)
        val out = PostProcessor.truncateUtf8(long, 200)
        assertTrue(out.toByteArray().size <= 200)
        assertFalse(out.contains('\uFFFD'))
        assertFalse(Character.isHighSurrogate(out.last()))
    }

    @Test
    fun prefersSentenceBoundary() {
        val text = "This is a pretty long first sentence that keeps going for a while! And then a second one that overflows the limit"
        assertEquals("This is a pretty long first sentence that keeps going for a while!", PostProcessor.truncateUtf8(text, 90))
    }

    @Test
    fun detectsRepeats() {
        assertTrue(PostProcessor.isRepeat("Hi hi!", listOf("hi hi")))
        assertFalse(PostProcessor.isRepeat("Hi there", listOf("hi hi")))
    }

    @Test
    fun detectsCopiedContext() {
        val log = listOf("who wants to grab tacos by the north gate?? I'm starving rn", "that sunset insane")
        assertTrue(PostProcessor.copiesContext("whoa who wants to grab tacos by the north gate?? I'm starving rn", log))
        assertTrue(PostProcessor.copiesContext("that sunset insane!!", log))
        assertFalse(PostProcessor.copiesContext("that sunset just melted my whole face off", log))
        assertFalse(PostProcessor.copiesContext("lol", log))
    }

    @Test
    fun bannedPhrasesAreWholeWord() {
        val banned = listOf("signal", "fuzzy")
        assertEquals("signal", PostProcessor.bannedHit("the Signal is fuzzy", banned))
        assertNull(PostProcessor.bannedHit("signaling my love for bass", listOf("signal")))
        assertNull(PostProcessor.bannedHit("this song slaps", banned))
    }

    @Test
    fun detectsRefusalsButNotShitposts() {
        assertTrue(PostProcessor.looksLikeRefusal("I cannot write a message that contains explicit content. Is there something else I can help you with?"))
        assertTrue(PostProcessor.looksLikeRefusal("I'm sorry, but I can't help with that."))
        assertTrue(PostProcessor.looksLikeRefusal("As an AI, I don't have feelings"))
        assertFalse(PostProcessor.looksLikeRefusal("I can't believe you drive with a 12 foot antenna lmao"))
        assertFalse(PostProcessor.looksLikeRefusal("Meme my ass BDA, you're not even a real signal"))
        assertFalse(PostProcessor.looksLikeRefusal("I won't stop until you reply, coward"))
    }
}
