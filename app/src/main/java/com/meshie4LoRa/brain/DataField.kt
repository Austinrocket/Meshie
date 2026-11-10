package com.meshie4LoRa.brain

/** Every piece of mesh data the model may see; toggled individually in Settings (D11). */
enum class DataField(val group: String, val label: String, val defaultOn: Boolean, val note: String = "") {
    LONG_NAME("Identity", "Long name", true),
    SHORT_NAME("Identity", "Short name", true),
    NODE_ID("Identity", "Node ID", false),
    HW_MODEL("Identity", "Hardware model", true),
    ROLE("Identity", "Device role", false),

    PROXIMITY("Location", "Proximity (next to me / nearby / far…)", true),
    DISTANCE("Location", "Distance", true),
    DIRECTION("Location", "Compass direction", true),
    MOVEMENT("Location", "Approaching / leaving (getting closer, moving away)", false),
    ALTITUDE("Location", "Altitude", false),
    SPEED("Location", "Speed & heading", false),
    COORDINATES("Location", "Raw coordinates", false, "Never put in replies, but the model could still leak them."),

    HOPS("Signal", "Hops away", true),
    SNR("Signal", "SNR", false),
    RSSI("Signal", "RSSI", false),
    VIA_MQTT("Signal", "Via internet (MQTT)", true),

    BATTERY("Device", "Battery level", true),
    VOLTAGE("Device", "Voltage", false),
    UPTIME("Device", "Uptime", false),

    TEMPERATURE("Environment", "Temperature", true),
    HUMIDITY("Environment", "Humidity", false),
    PRESSURE("Environment", "Pressure", false),

    FIRST_SEEN("Activity", "First seen", true),
    LAST_HEARD("Activity", "Last heard", true),
    MESSAGES_TODAY("Activity", "Messages today", true),

    OWN_LOCATION(
        "Self", "Use my radio's GPS", false,
        "Off: nothing derived from where you are (distance, direction, nearby, approaching) reaches the model.",
    ),
    OWN_BATTERY("Self", "My battery", true),
    LOCAL_TIME("Self", "Local time", true),

    NODES_HEARD("Mesh", "How many people are around", true),
    NEAREST_PEOPLE("Mesh", "Who is nearest", true);

    companion object {
        val defaults: Set<DataField> = entries.filter { it.defaultOn }.toSet()

        /** Only usable when [OWN_LOCATION] is on; they reveal where we are relative to others. */
        val needsOwnLocation = setOf(PROXIMITY, DISTANCE, DIRECTION, MOVEMENT, NEAREST_PEOPLE)
    }
}
