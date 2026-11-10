package com.meshie4LoRa.mesh

import org.junit.Assert.assertEquals
import org.junit.Test

class GeoTest {
    @Test
    fun distanceBetweenKnownPoints() {
        // Paris -> London is ~343.5 km
        val d = Geo.distanceMeters(48.8566, 2.3522, 51.5074, -0.1278)
        assertEquals(343_500.0, d, 1_500.0)
    }

    @Test
    fun bearingAndCompass() {
        assertEquals("N", Geo.compass(Geo.bearingDegrees(0.0, 0.0, 1.0, 0.0)))
        assertEquals("E", Geo.compass(Geo.bearingDegrees(0.0, 0.0, 0.0, 1.0)))
        assertEquals("SW", Geo.compass(Geo.bearingDegrees(0.0, 0.0, -1.0, -1.0)))
        assertEquals("N", Geo.compass(359.0))
    }

    @Test
    fun precision() {
        assertEquals(0.0, Geo.precisionMeters(null), 0.0)
        assertEquals(0.0, Geo.precisionMeters(32), 0.0)
        assertEquals(23_300.0, Geo.precisionMeters(10), 1.0)
        assertEquals(2_912.5, Geo.precisionMeters(13), 1.0)
    }

    @Test
    fun formatting() {
        assertEquals("420 m", Geo.formatDistance(420.0))
        assertEquals("4.2 km", Geo.formatDistance(4_200.0))
        assertEquals("42 km", Geo.formatDistance(42_000.0))
    }
}
