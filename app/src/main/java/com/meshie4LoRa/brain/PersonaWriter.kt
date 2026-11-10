package com.meshie4LoRa.brain

import com.meshie4LoRa.data.Pronouns
import com.meshie4LoRa.mesh.MAX_LONG_NAME_BYTES
import com.meshie4LoRa.mesh.MAX_SHORT_NAME_BYTES
import org.json.JSONObject

/** A persona the model dreamed up from a one-line idea; [look] is one of the preset look names it was offered. */
data class GeneratedPersona(
    val name: String,
    val shortName: String,
    val pronouns: Pronouns,
    val persona: String,
    val examples: List<String>,
    val look: String?,
)

object PersonaWriter {
    const val SURPRISE_IDEA = "anything you like, surprise me"

    /** GBNF forcing the JSON shape of [PromptTemplate.PERSONA_WRITER]; [looks] become the allowed look values. */
    fun grammar(looks: List<String>): String {
        val lookRule = looks.filter { it.isNotBlank() && '"' !in it }.joinToString(" | ") { "\"\\\"$it\\\"\"" }.ifEmpty { "str" }
        return """root ::= "{\"name\": " name ", \"short_name\": " short ", \"pronouns\": \"" pron "\", \"persona\": " persona ", \"things_they_would_type_in_chat\": [" line ", " line ", " line "], \"look\": " look "}"
name ::= "\"" [^"\\\n]{2,30} "\""
short ::= "\"" [A-Za-z0-9]{2,4} "\""
pron ::= "she" | "he" | "they" | "it"
persona ::= "\"" [^"\\\n]{30,450} "\""
line ::= "\"" [^"\\\n]{3,90} "\""
str ::= "\"" [^"\\\n]{1,30} "\""
look ::= $lookRule
"""
    }

    /** Null if the text isn't usable JSON; names are clipped to the radio's byte limits. */
    fun parse(json: String, looks: List<String>): GeneratedPersona? {
        val o = runCatching { JSONObject(json.trim()) }.getOrNull() ?: return null
        val name = cutBytes(o.optString("name").trim(), MAX_LONG_NAME_BYTES)
        val persona = o.optString("persona").trim()
        if (name.isEmpty() || persona.isEmpty()) return null
        val short = cutBytes(o.optString("short_name").trim().filter { it.isLetterOrDigit() }, MAX_SHORT_NAME_BYTES)
            .ifEmpty { cutBytes(name.filter { it.isLetterOrDigit() }, MAX_SHORT_NAME_BYTES) }
        val examples = (o.optJSONArray("things_they_would_type_in_chat") ?: o.optJSONArray("examples"))?.let { a -> (0 until a.length()).map { a.optString(it).trim() } }.orEmpty()
            .filter { it.isNotEmpty() }
        return GeneratedPersona(
            name = name,
            shortName = short,
            pronouns = when (o.optString("pronouns").trim().lowercase()) {
                "she" -> Pronouns.SHE
                "he" -> Pronouns.HE
                "it" -> Pronouns.IT
                else -> Pronouns.THEY
            },
            persona = persona,
            examples = examples,
            look = o.optString("look").trim().let { l -> looks.firstOrNull { it.equals(l, ignoreCase = true) } },
        )
    }

    private fun cutBytes(s: String, max: Int) = PersonaFile.cutBytes(s, max)
}
