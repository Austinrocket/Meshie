package com.meshie4LoRa.brain

import org.junit.Assert.assertTrue
import org.junit.Test

class RefusalsTest {
    @Test
    fun minorRefusalPointsAtWording() {
        val why = Refusals.explain("I cannot create content that depicts a sexual relationship between an adult and a minor.")
        assertTrue(why.contains("adult"))
    }

    @Test
    fun unknownRefusalStillExplains() {
        assertTrue(Refusals.explain("I'd rather not.").isNotBlank())
    }
}
