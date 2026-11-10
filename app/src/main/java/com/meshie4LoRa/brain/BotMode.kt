package com.meshie4LoRa.brain

/** D8: how the brain's output is used. Sleep is separate (a time until which nothing is sent). */
enum class BotMode(val label: String, val description: String) {
    MANUAL("Manual", "Only speaks when you tap Thought"),
    OBSERVE("Observe", "Decides and drafts, never sends"),
    APPROVE("Approve", "Drafts need your tap to send"),
    AUTO("Auto", "Sends on its own"),
}
