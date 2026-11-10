package com.meshie4LoRa.brain

/** Turns raw model output into a single mesh-ready message, or null if nothing usable remains. */
object PostProcessor {
    private val thinkBlock = Regex("(?s)<think>.*?(</think>|$)")
    private val timestampPrefix = Regex("^(\\[[^\\]]{1,24}]\\s*:?\\s*)+")
    // Two decimal numbers that look like a lat/lon pair, e.g. "12.3456, -65.4321".
    private val coordinatePair = Regex("-?\\d{1,3}\\.\\d{3,}\\s*[,/ ]\\s*-?\\d{1,3}\\.\\d{3,}")
    private val markdown = Regex("(\\*\\*|__|`|^#+\\s+)", RegexOption.MULTILINE)
    private val sentenceEnd = Regex("[.!?~…](\\s|$)")

    private val meshTag = Regex("</?mesh[^>]*>")
    // The log's alias for unnamed nodes (see ContextBuilder.Names).
    private val personAlias = Regex("^Person [0-9a-fA-F]{4}\\s*:")
    // The log marks her own lines "(you)"; models sometimes start a reply with it.
    private val youLabel = Regex("^\\(you\\)\\s*:?\\s*", RegexOption.IGNORE_CASE)
    private val youAnywhere = Regex("\\s*\\(you\\)", RegexOption.IGNORE_CASE)
    // e.g. "(sakura) :", "(sakura's reply)", "(sakura) (you):" from fine-tunes that script both sides.
    private val labelPrefix = Regex("^\\(([^)]{1,40})\\)\\s*(\\(you\\))?\\s*:?\\s*", RegexOption.IGNORE_CASE)
    // Training-data artifacts like <PRESIDIO_ANONYMIZED_RESPONSE>.
    private val placeholderToken = Regex("<[A-Z][A-Z_]{3,}>")
    private val promptLeak = Regex("(?i)(How you write:|Staying yourself:|Examples of your voice|People in this conversation:)")

    /**
     * [ownNames]: our character's names (a leading "Name:" is stripped).
     * [otherNames]: other speakers; a reply starting with "Other:" means the model is writing someone else's line, so it's rejected.
     */
    fun clean(
        raw: String,
        ownNames: Collection<String>,
        maxBytes: Int = Prompts.MAX_REPLY_BYTES,
        otherNames: Collection<String> = emptyList(),
    ): String? {
        var t = raw.replace(thinkBlock, "").replace(meshTag, " ").trim()
        if (promptLeak.containsMatchIn(t) || placeholderToken.containsMatchIn(t)) return null
        // Models sometimes echo the log format or their own name: "[12:01] you: hi" / "Meshimi-chan: hi" / "(sakura) (you)" alone on a line.
        for (line in t.lines().filter { it.isNotBlank() }) {
            val bare = unquote(line).replace(timestampPrefix, "")
            if (otherNames.any { it.isNotBlank() && bare.startsWith("$it:", ignoreCase = true) } || personAlias.containsMatchIn(bare)) return null
            val stripped = stripLabels(line, ownNames)
            if (stripped.isNotEmpty()) {
                t = stripped
                break
            }
            t = ""
        }
        if (t.isEmpty()) return null
        // Broken models emit symbol soup; real messages are mostly letters and spaces.
        if (t.length >= 12 && t.count { it.isLetter() || it.isWhitespace() } < t.length / 2) return null
        t = t.replace(markdown, "").replace(coordinatePair, "somewhere nearby").replace(youAnywhere, " ")
        t = unquote(t)
        t = t.replace(Regex("\\s+"), " ")
        if (t.isEmpty()) return null
        return truncateUtf8(t, maxBytes)
    }

    private fun unquote(s: String) = s.trim().removeSurrounding("\"").removeSurrounding("“", "”").trim()

    /** Removes our own speaker labels from the start of a line; repeats for stacked labels like "(sakura) (you):". */
    private fun stripLabels(line: String, ownNames: Collection<String>): String {
        var t = unquote(line).replace(timestampPrefix, "")
        repeat(3) {
            for (name in ownNames + "you") {
                if (name.isNotBlank() && t.startsWith("$name (you):", ignoreCase = true)) t = t.substring(name.length + 7).trim()
                if (name.isNotBlank() && t.startsWith("$name:", ignoreCase = true)) t = t.substring(name.length + 1).trim()
            }
            t = t.replace(youLabel, "")
            labelPrefix.find(t)?.let { m ->
                val label = m.groupValues[1].lowercase()
                if (label.contains("you") || label.contains("reply") || ownNames.any { it.isNotBlank() && label.contains(it.lowercase()) }) {
                    t = t.substring(m.range.last + 1).trim()
                }
            }
            t = unquote(t)
        }
        return t
    }

    /** Cuts to [maxBytes] UTF-8, preferring a sentence end, then a word boundary, never splitting a character. */
    fun truncateUtf8(text: String, maxBytes: Int): String {
        if (text.toByteArray().size <= maxBytes) return text
        val sb = StringBuilder()
        var bytes = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val s = String(Character.toChars(cp))
            val b = s.toByteArray().size
            if (bytes + b > maxBytes) break
            sb.append(s)
            bytes += b
            i += Character.charCount(cp)
        }
        val cut = sb.toString()
        val lastSentence = sentenceEnd.findAll(cut).lastOrNull()?.range?.first
        if (lastSentence != null && lastSentence >= cut.length / 2) return cut.substring(0, lastSentence + 1).trim()
        val lastSpace = cut.lastIndexOf(' ')
        val wordCut = if (lastSpace >= cut.length / 2) cut.substring(0, lastSpace) else cut
        // Leave room for the ellipsis (3 bytes).
        return truncateBytesPlain(wordCut.trimEnd(), maxBytes - 3) + "…"
    }

    private fun truncateBytesPlain(text: String, maxBytes: Int): String {
        var out = text
        while (out.toByteArray().size > maxBytes) out = out.dropLast(1)
        if (out.isNotEmpty() && Character.isHighSurrogate(out.last())) out = out.dropLast(1)
        return out
    }

    /** True if [reply] is basically something we already said recently. */
    fun isRepeat(reply: String, recent: Collection<String>): Boolean {
        val norm = { s: String -> s.lowercase().filter { it.isLetterOrDigit() } }
        val r = norm(reply)
        return r.isNotEmpty() && recent.any { norm(it) == r }
    }

    // Assistant-style refusals/disclaimers; even abliterated models occasionally emit these.
    private val refusal = Regex(
        "(?i)(\\bI (can'?t|cannot|won'?t|am not able to|'m not able to|'m unable to) (help|assist|write|create|provide|do that|fulfill|comply|engage)|" +
            "\\bas an ai\\b|\\bI'?m (just )?an ai\\b|language model|is there (something|anything) else I can help|" +
            "I'?m sorry,? but I|I (don'?t|do not) feel comfortable|against my (guidelines|programming)|" +
            "\\b(not|isn'?t) appropriate\\b|content polic)"
    )

    fun looksLikeRefusal(reply: String): Boolean = refusal.containsMatchIn(reply)

    private fun words(s: String) = s.lowercase().split(Regex("[^\\p{L}\\p{N}']+")).filter { it.isNotEmpty() }

    /**
     * True if [reply] lifts text from [sources] (chat log, persona, examples, notes): any run of [run] words in a row,
     * or the whole reply when it's shorter than that.
     */
    fun copiesContext(reply: String, sources: Collection<String>, run: Int = 5): Boolean {
        val r = words(reply)
        if (r.isEmpty()) return false
        val sourceWords = sources.map { words(it) }
        if (r.size < run) {
            val joined = r.joinToString(" ")
            return r.size >= 3 && sourceWords.any { it.joinToString(" ").contains(joined) }
        }
        val shingles = HashSet<String>()
        sourceWords.forEach { w -> for (i in 0..w.size - run) shingles += w.subList(i, i + run).joinToString(" ") }
        return (0..r.size - run).any { i -> r.subList(i, i + run).joinToString(" ") in shingles }
    }

    /** The first banned word/phrase found in [reply] (whole-word, case-insensitive), or null. */
    fun bannedHit(reply: String, banned: Collection<String>): String? = banned.firstOrNull { b ->
        b.isNotBlank() && Regex("(?i)(^|[^\\p{L}\\p{N}])" + Regex.escape(b.trim()) + "($|[^\\p{L}\\p{N}])").containsMatchIn(reply)
    }
}
