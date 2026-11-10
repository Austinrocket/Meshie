package com.meshie4LoRa.data

import org.junit.Assert.assertEquals
import org.junit.Test

class DmPartnerTest {
    private val sakura = 0x11111111L
    private val bob = 0x22222222L
    private val carol = 0x33333333L

    private fun dm(from: Long, to: Long, origin: MessageOrigin) =
        MessageEntity(packetId = 1, channel = 0, fromNode = from, toNode = to, text = "x", rxTime = 0, origin = origin)

    @Test
    fun partnerIsTheOtherEndEvenForMessagesLoggedOnAnotherRadio() {
        // Logged while connected to Bob's radio: our manual DM to Carol must not land in Bob's chat.
        assertEquals(carol, dm(bob, carol, MessageOrigin.BOT_MANUAL).dmPartner(sakura))
        assertEquals(carol, dm(carol, bob, MessageOrigin.MESH).dmPartner(sakura))
        // Bob <-> sakura (our current radio) belongs to Bob's chat, whichever direction.
        assertEquals(bob, dm(bob, sakura, MessageOrigin.MESH).dmPartner(sakura))
        assertEquals(bob, dm(sakura, bob, MessageOrigin.MESH).dmPartner(sakura))
        assertEquals(bob, dm(sakura, bob, MessageOrigin.BOT_AUTO).dmPartner(sakura))
    }
}
