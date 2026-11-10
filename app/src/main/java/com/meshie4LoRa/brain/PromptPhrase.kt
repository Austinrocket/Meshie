package com.meshie4LoRa.brain

/**
 * Short fixed phrases the context builder puts around data (labels, time, battery, aliases).
 * Operator-editable like [PromptTemplate]; `{key}` placeholders are filled in, an empty phrase drops the line.
 */
enum class PromptPhrase(val title: String, val placeholders: List<String>, val default: String) {
    YOU_MARKER("Marks the character's own lines in chat logs", emptyList(), "(you)"),
    ALIAS("Name for people whose names are hidden", listOf("id"), "Person {id}"),
    FEELING("Feeling about a person", listOf("feeling"), "How you feel about them: {feeling}"),
    SUMMARY("Memory summary of a person", listOf("summary"), "What you remember: {summary}"),
    FACTS("Facts about a person", listOf("facts"), "Facts about them: {facts}"),
    TIME("Current time", listOf("time"), "It is now {time}."),
    MY_BATTERY("Own battery", listOf("percent"), "Your battery is at {percent}%."),
    PLUGGED_IN("Own device plugged in", emptyList(), "You are plugged in."),
    ACTIVE_PEOPLE("How many people are around", listOf("count", "people"), "{count} other {people} active in the last hour."),
    NEAREST("Who is nearest", listOf("list"), "Nearest people: {list}."),
    NONE("Shown when there's no summary/facts/feeling yet (memory pass)", emptyList(), "(none)"),
    FEELING_RULE(
        "Memory pass: how to update the feeling", listOf("name", "person"),
        "a short phrase for how {name} feels about {person} now. Keep the current feeling if nothing changed it.",
    ),
    FEELING_RULE_LOCKED("Memory pass: feeling is locked", emptyList(), "repeat the current feeling exactly."),
    CALLOUT(
        "Callout: aim the message at one person", listOf("person", "about"),
        "Aim this one at {person}: use their name and roast them, hype them up or ask them something.{#about} About them: {about}{/about}",
    ),
    PERSONA_REMINDER(
        "Persona reminder at the end of every request (the model weighs the end most)", listOf("name", "persona"),
        "Stay in character as {name}: {persona}",
    ),
    BRAINSTORM_POST(
        "Brainstorm: asks for the group message at the end", emptyList(),
        "Now write the one message you'll post to the group, based on what we just talked about. Only the message text.",
    );

    companion object {
        fun all(overrides: Map<PromptPhrase, String> = emptyMap()) = entries.associateWith { overrides[it] ?: it.default }
    }
}
