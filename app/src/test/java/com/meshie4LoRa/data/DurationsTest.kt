package com.meshie4LoRa.data

import org.junit.Assert.assertEquals
import org.junit.Test

class DurationsTest {
    @Test
    fun bestUnitKeepsTheTypedNumberExact() {
        assertEquals(DurationUnit.MINUTES, DurationUnit.bestFor(120))
        assertEquals(DurationUnit.SECONDS, DurationUnit.bestFor(90))
        assertEquals(DurationUnit.HOURS, DurationUnit.bestFor(7200))
        assertEquals(DurationUnit.SECONDS, DurationUnit.bestFor(0))
    }

    @Test
    fun humanDurations() {
        assertEquals("45 s", humanDuration(45))
        assertEquals("2 min 30 s", humanDuration(150))
        assertEquals("10 min", humanDuration(615))
        assertEquals("2 h 5 min", humanDuration(7500))
    }
}
