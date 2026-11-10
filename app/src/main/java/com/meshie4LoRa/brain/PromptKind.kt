package com.meshie4LoRa.brain

/** Every kind of call the app makes to the model, for the prompt preview. */
enum class PromptKind(val label: String) {
    REPLY("Reply"),
    JUDGE("Should I reply?"),
    IDLE("Idle chatter"),
    FAVORITES("From favorites"),
    ARRIVAL("Someone came nearby"),
    REVIEW("Second pass"),
    MEMORY("Memory notes"),
}
