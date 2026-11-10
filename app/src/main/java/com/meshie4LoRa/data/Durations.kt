package com.meshie4LoRa.data

/** Short human form of a duration, e.g. "45 s", "12 min", "2 h 5 min". */
fun humanDuration(seconds: Long): String = when {
    seconds < 60 -> "$seconds s"
    seconds < 3600 -> "${seconds / 60} min" + (seconds % 60).let { if (it > 0 && seconds < 600) " $it s" else "" }
    else -> "${seconds / 3600} h" + ((seconds % 3600) / 60).let { if (it > 0) " $it min" else "" }
}

/** Units the operator can type a duration in. */
enum class DurationUnit(val label: String, val seconds: Long) {
    SECONDS("sec", 1), MINUTES("min", 60), HOURS("hr", 3600);

    companion object {
        /** The largest unit that represents [seconds] exactly, so 120 shows as "2 min" and 90 as "90 sec". */
        fun bestFor(seconds: Long): DurationUnit = entries.reversed().firstOrNull { seconds > 0 && seconds % it.seconds == 0L } ?: SECONDS
    }
}
