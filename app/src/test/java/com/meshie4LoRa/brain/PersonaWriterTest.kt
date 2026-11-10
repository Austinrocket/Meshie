package com.meshie4LoRa.brain

import com.meshie4LoRa.data.Pronouns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonaWriterTest {
    private val looks = listOf("Classic", "Alien", "Big Homie")

    @Test
    fun parsesGrammarShapedOutput() {
        val json = """{"name": "Crabby Pete", "short_name": "CRAB", "pronouns": "he", "persona": "Crabby Pete keeps a lighthouse and a bucket of crabs.", "things_they_would_type_in_chat": ["get off my rocks", "the crabs say hi", "fog's rolling in"], "look": "big homie"}"""
        val p = PersonaWriter.parse(json, looks)!!
        assertEquals("Crabby Pete", p.name)
        assertEquals("CRAB", p.shortName)
        assertEquals(Pronouns.HE, p.pronouns)
        assertEquals(3, p.examples.size)
        assertEquals("Big Homie", p.look)
    }

    @Test
    fun clipsNamesAndFallsBackSensibly() {
        val json = """{"name": "Zed", "short_name": "!!", "pronouns": "xe", "persona": "Zed hums.", "examples": [], "look": "Wizard"}"""
        val p = PersonaWriter.parse(json, looks)!!
        assertEquals("Zed", p.shortName)
        assertEquals(Pronouns.THEY, p.pronouns)
        assertNull(p.look)
        assertNull(PersonaWriter.parse("not json", looks))
        assertNull(PersonaWriter.parse("""{"name": "", "persona": "x"}""", looks))
    }

    @Test
    fun grammarListsEveryLookAsQuotedLiteral() {
        val g = PersonaWriter.grammar(looks)
        assertTrue(g, g.contains("look ::= \"\\\"Classic\\\"\" | \"\\\"Alien\\\"\" | \"\\\"Big Homie\\\"\""))
    }
}
