package com.meshie4LoRa.mesh

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

object Geo {
    private const val EARTH_RADIUS_M = 6_371_000.0
    private val COMPASS = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")

    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return EARTH_RADIUS_M * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    /** Initial bearing from point 1 to point 2, 0..360 degrees. */
    fun bearingDegrees(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dLon = Math.toRadians(lon2 - lon1)
        val y = sin(dLon) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    fun compass(bearing: Double): String = COMPASS[((bearing / 45.0).roundToInt()) % 8]

    /** Approximate grid size of a precision-reduced Meshtastic position; 0 = exact/unknown. */
    fun precisionMeters(bits: Int?): Double =
        if (bits == null || bits >= 32) 0.0 else 23_300.0 * Math.pow(2.0, (10 - bits).toDouble())

    fun formatDistance(meters: Double): String = when {
        meters < 1000 -> "${meters.roundToInt()} m"
        meters < 10_000 -> "%.1f km".format(meters / 1000)
        else -> "${(meters / 1000).roundToInt()} km"
    }
}
