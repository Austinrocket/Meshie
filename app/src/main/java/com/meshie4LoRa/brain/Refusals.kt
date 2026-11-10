package com.meshie4LoRa.brain

/** Plain-language guesses at why a model refused, so the operator knows what in the persona/chat to change. */
object Refusals {
    private val causes = listOf(
        Regex("(?i)\\b(minor|minors|child|children|kid|underage|teen)") to
            "It read something as involving a minor. Make sure the persona clearly describes an adult.",
        Regex("(?i)\\b(explicit|sexual|sexually|nsfw|pornograph)") to
            "It thought the reply would be sexually explicit. Try toning down sexual words in the persona or picking an abliterated (uncensored) model.",
        Regex("(?i)\\b(drug|drugs|illegal|substance|alcohol)") to
            "It flagged drugs, alcohol or something illegal. An abliterated (uncensored) model refuses this much less.",
        Regex("(?i)\\b(hate|harass|bully|offensive|derogatory|discriminat)") to
            "It thought the reply would be hateful or harassing. Rude-but-playful personas work better on abliterated models.",
        Regex("(?i)\\b(harm|violence|violent|dangerous|weapon)") to
            "It thought the reply could encourage harm.",
    )

    fun explain(refusal: String): String =
        causes.firstOrNull { it.first.containsMatchIn(refusal) }?.second
            ?: "Its words are shown below; they usually hint at which part of the persona or chat set it off."
}
