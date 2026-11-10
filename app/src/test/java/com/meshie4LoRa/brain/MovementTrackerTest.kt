package com.meshie4LoRa.brain

import com.meshie4LoRa.data.NodeEntity
import com.meshie4LoRa.data.PositionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MovementTrackerTest {
    private val now = 1_800_000_000_000L
    private val me = NodeEntity(nodeNum = 1, latitude = 0.5, longitude = 0.4, positionTime = now, firstSeen = 0, lastHeard = now)
    // ~1.1 km north of me 10 minutes ago, now ~220 m north.
    private val farPast = PositionEntity(nodeNum = 2, latitude = 0.51, longitude = 0.4, at = now - 10 * 60_000L)
    private val them = NodeEntity(nodeNum = 2, latitude = 0.502, longitude = 0.4, positionTime = now - 60_000L, firstSeen = 0, lastHeard = now)

    private fun compute(nodes: List<NodeEntity>, samples: List<PositionEntity>) =
        MovementTracker.compute(1, nodes.associateBy { it.nodeNum }, samples, now)

    @Test
    fun detectsApproaching() {
        val m = compute(listOf(me, them), listOf(farPast)).getValue(2)
        assertEquals(Trend.APPROACHING, m.trend)
        assertTrue(m.wasMeters > 1_000 && m.nowMeters < 300)
    }

    @Test
    fun detectsLeaving() {
        val closePast = farPast.copy(latitude = 0.5005)
        val away = them.copy(latitude = 0.51)
        assertEquals(Trend.LEAVING, compute(listOf(me, away), listOf(closePast)).getValue(2).trend)
    }

    @Test
    fun ignoresJitterStaleAndMissingData() {
        val jitter = farPast.copy(latitude = 0.5021)
        assertTrue(compute(listOf(me, them), listOf(jitter)).isEmpty())
        val stale = them.copy(positionTime = now - 2 * 60 * 60_000L)
        assertTrue(compute(listOf(me, stale), listOf(farPast)).isEmpty())
        assertTrue(compute(listOf(me, them), emptyList()).isEmpty())
        assertTrue(compute(listOf(me.copy(latitude = null, longitude = null), them), listOf(farPast)).isEmpty())
    }

    @Test
    fun accountsForMyOwnMovement() {
        // They stood still; I walked toward them.
        val still = them.copy(latitude = 0.51)
        val myPast = PositionEntity(nodeNum = 1, latitude = 0.49, longitude = 0.4, at = now - 10 * 60_000L)
        val samples = listOf(farPast.copy(latitude = 0.51), myPast)
        assertEquals(Trend.APPROACHING, compute(listOf(me.copy(latitude = 0.508), still), samples).getValue(2).trend)
    }
}
