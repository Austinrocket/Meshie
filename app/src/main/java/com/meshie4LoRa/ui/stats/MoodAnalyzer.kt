package com.meshie4LoRa.ui.stats

import kotlin.math.exp

/** Running "what is the persona feeling" score: keyword hits in its recent messages and feelings, decayed by age. */
object MoodAnalyzer {
    enum class Mood(val label: String, val emoji: String, val words: List<String>) {
        HYPE("Hype", "🔥", listOf("hype", "hyped", "lit", "fire", "insane", "excited", "slay", "slayyy", "banger", "slaps", "iconic", "let's go", "lets go", "omg", "🔥", "!!!")),
        LOVE("Love", "💖", listOf("love", "loving", "crush", "adore", "cute", "bestie", "heart", "marry", "sweet", "❤", "💖", "😍", "🥰")),
        FLIRTY("Flirty", "😏", listOf("flirt", "flirty", "hot", "kiss", "babe", "baby", "wink", "😉", "😏", "😘")),
        CHAOS("Chaos", "💀", listOf("unhinged", "chaos", "chaotic", "menace", "crazy", "wild", "gremlin", "feral", "danger", "💀")),
        SALTY("Salty", "😤", listOf("annoyed", "annoying", "angry", "hate", "mad", "rude", "irritated", "frustrated", "shut up", "gaslight", "gaslighting", "smh", "skill issue", "😤")),
        SAD("Sad", "😢", listOf("sad", "cry", "crying", "lonely", "miss", "tired", "depressed", "melting", "ceased to exist", "dead", "died", "killed", "😭", "😢")),
        CURIOUS("Curious", "🤔", listOf("curious", "wonder", "interesting", "who", "why", "how", "what's", "wya", "🤔")),
        CONFUSED("Confused", "😵", listOf("confused", "huh", "wtf", "idk", "what even", "???", "lost", "😵")),
        CHILL("Chill", "🌸", listOf("chill", "vibe", "vibes", "relaxed", "peace", "calm", "cozy", "🌸", "✨")),
        PARTY("Party", "🍻", listOf("party", "beer", "beers", "dance", "dancing", "music", "dj", "🍻", "🍺")),
    }

    data class Signal(val text: String, val at: Long, val weight: Double = 1.0)

    // Whole-word matchers for words, plain substring checks for emoji/punctuation.
    private fun matcher(w: String): (String) -> Boolean =
        if (w.any { it.isLetter() }) {
            val re = Regex("(?<![\\p{L}])" + Regex.escape(w) + "(?![\\p{L}])")
            ({ t: String -> re.containsMatchIn(t) })
        } else ({ t: String -> t.contains(w) })

    private val matchers: Map<Mood, List<(String) -> Boolean>> = Mood.entries.associateWith { m -> m.words.map(::matcher) }

    /** Splits the operator's word bank text (commas or new lines) into lowercase keywords, skipping built-ins. */
    fun parseWords(mood: Mood, text: String): List<String> =
        text.split(',', '\n').map { it.trim().lowercase() }.filter { it.isNotEmpty() && it !in mood.words }.distinct()

    /**
     * Scores per mood. Half-life ~1 hour reflects "right now"; null half-life gives the all-time running sum.
     * [extra] adds the operator's own keywords to the built-in ones.
     */
    fun score(signals: List<Signal>, now: Long, halfLifeMs: Long? = 60 * 60_000L, extra: Map<Mood, List<String>> = emptyMap()): Map<Mood, Double> {
        val all = if (extra.isEmpty()) matchers else matchers.mapValues { (m, base) -> base + extra[m].orEmpty().map(::matcher) }
        val scores = Mood.entries.associateWith { 0.0 }.toMutableMap()
        for (s in signals) {
            val decay = if (halfLifeMs == null) 1.0 else exp(-(now - s.at).coerceAtLeast(0) * Math.log(2.0) / halfLifeMs)
            val text = " " + s.text.lowercase() + " "
            for (m in Mood.entries) {
                val hits = all.getValue(m).count { it(text) }
                if (hits > 0) scores[m] = scores.getValue(m) + hits * s.weight * decay
            }
        }
        return scores
    }

    fun dominant(scores: Map<Mood, Double>): Mood? = scores.filterValues { it > 0.05 }.maxByOrNull { it.value }?.key
}
