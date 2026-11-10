package com.meshie4LoRa.brain

/** Fixed limits, grammars and starter-character defaults. All prompt wording lives in [PromptTemplate]. */
object Prompts {
    const val MAX_REPLY_BYTES = 200

    /** GBNF forcing {"reason": "...", "respond": true|false}. Reason first so the model thinks before choosing. */
    const val JUDGE_GRAMMAR = """root ::= "{\"reason\": \"" reason "\", \"respond\": " bool "}"
reason ::= [^"\\\n]{1,120}
bool ::= "true" | "false"
"""

    /** GBNF forcing {"reason": "...", "send": true|false} for the second-pass review. */
    const val REVIEW_GRAMMAR = """root ::= "{\"reason\": \"" reason "\", \"send\": " bool "}"
reason ::= [^"\\\n]{1,100}
bool ::= "true" | "false"
"""

    const val DEFAULT_PERSONA =
        "You are a cheerful, curious anime-style girl. You love meeting new people, you're playful and a little dramatic, but kind."

    const val DEFAULT_RULES = "Use at most one emoji."

    const val DEFAULT_EXAMPLES = "omg hiii who else is here rn\nok that's actually iconic"
}
