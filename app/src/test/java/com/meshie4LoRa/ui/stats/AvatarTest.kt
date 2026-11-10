package com.meshie4LoRa.ui.stats

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

class AvatarTest {
    @Test
    fun jsonRoundTripsEveryPresetAndRandomLook() {
        val looks = Avatar.PRESETS.map { it.second } + (1..20).map { Avatar.random(Random(it)) }
        looks.forEach { assertEquals(it, Avatar.parse(it.toJson())) }
    }

    @Test
    fun blankOrBrokenJsonFallsBackToClassicWithGivenHair() {
        val pink = Color(0xFFFF8FC8)
        assertEquals(pink, Avatar.parse("", pink).hairColor)
        assertEquals(HairStyle.TWIN_TAILS, Avatar.parse("{not json", pink).hair)
    }

    @Test
    fun organicEmojiChangesEvery15Percent() {
        assertEquals("😡", organicEmoji(0))
        assertEquals("😠", organicEmoji(14))
        assertEquals("😒", organicEmoji(15))
        assertEquals("😎", organicEmoji(89))
        assertEquals("🤩", organicEmoji(99))
        assertEquals("🤩🥇", organicEmoji(100))
    }

    @Test
    fun unknownValuesAreIgnored() {
        val a = Avatar.parse("""{"hair":"DREADLOCKS","eyes":"CAT","extras":["WINGS","POMPOMS"]}""")
        assertEquals(HairStyle.TWIN_TAILS, a.hair)
        assertEquals(EyeStyle.CAT, a.eyes)
        assertEquals(setOf(Extra.POMPOMS), a.extras)
    }
}
