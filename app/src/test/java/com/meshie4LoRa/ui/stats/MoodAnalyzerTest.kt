package com.meshie4LoRa.ui.stats

import com.meshie4LoRa.ui.stats.MoodAnalyzer.Mood
import com.meshie4LoRa.ui.stats.MoodAnalyzer.Signal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MoodAnalyzerTest {
    private val now = 10_000_000_000L

    @Test
    fun keywordsScoreTheirMood() {
        val s = MoodAnalyzer.score(listOf(Signal("that was insane, total chaos 🔥", now)), now)
        assertTrue(s.getValue(Mood.HYPE) > 0)
        assertTrue(s.getValue(Mood.CHAOS) > 0)
        assertEquals(0.0, s.getValue(Mood.SAD), 0.0)
    }

    @Test
    fun wholeWordsOnly() {
        val s = MoodAnalyzer.score(listOf(Signal("the hotel is lovely", now)), now)
        assertEquals(0.0, s.getValue(Mood.FLIRTY), 0.0)
    }

    @Test
    fun wordBankAddsKeywords() {
        val words = MoodAnalyzer.parseWords(Mood.PARTY, " Bass Drop, party,\n🪩 ,, ")
        assertEquals(listOf("bass drop", "🪩"), words)
        val signal = listOf(Signal("wait for the bass drop 🪩", now))
        assertEquals(0.0, MoodAnalyzer.score(signal, now).getValue(Mood.PARTY), 0.0)
        assertTrue(MoodAnalyzer.score(signal, now, extra = mapOf(Mood.PARTY to words)).getValue(Mood.PARTY) >= 2.0 * 0.99)
    }

    @Test
    fun olderSignalsFade() {
        val fresh = MoodAnalyzer.score(listOf(Signal("I love you", now)), now).getValue(Mood.LOVE)
        val old = MoodAnalyzer.score(listOf(Signal("I love you", now - 60 * 60_000L)), now).getValue(Mood.LOVE)
        assertEquals(fresh / 2, old, 1e-6)
        val total = MoodAnalyzer.score(listOf(Signal("I love you", now - 100 * 60 * 60_000L), Signal("love", now)), now, halfLifeMs = null)
        assertEquals(2.0, total.getValue(Mood.LOVE), 1e-9)
        assertEquals(Mood.LOVE, MoodAnalyzer.dominant(mapOf(Mood.LOVE to 2.0, Mood.SAD to 1.0)))
        assertNull(MoodAnalyzer.dominant(emptyMap()))
    }
}
