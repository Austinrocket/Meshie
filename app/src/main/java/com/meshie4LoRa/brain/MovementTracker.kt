package com.meshie4LoRa.brain

import com.meshie4LoRa.data.NodeEntity
import com.meshie4LoRa.data.PositionEntity
import com.meshie4LoRa.mesh.Geo
import kotlin.math.abs

enum class Trend { APPROACHING, LEAVING }

data class Movement(val trend: Trend, val wasMeters: Double, val nowMeters: Double)

/** Approaching / leaving from radio positions only: compares distance now with distance ~10 minutes ago. */
object MovementTracker {
    const val TARGET_AGO_MS = 10 * 60_000L
    const val MIN_AGO_MS = 4 * 60_000L
    const val MAX_AGO_MS = 30 * 60_000L
    /** Positions older than this are too stale to say someone is moving right now. */
    const val FRESH_MS = 20 * 60_000L
    const val MIN_CHANGE_M = 75.0
    const val MIN_CHANGE_RATIO = 0.3

    fun compute(myNum: Long?, nodes: Map<Long, NodeEntity>, samples: List<PositionEntity>, now: Long): Map<Long, Movement> {
        val me = myNum?.let { nodes[it] }?.takeIf { it.hasPosition } ?: return emptyMap()
        val byNode = samples.groupBy { it.nodeNum }
        val myPast = pastSample(byNode[me.nodeNum], now)
        val out = mutableMapOf<Long, Movement>()
        for (n in nodes.values) {
            if (n.nodeNum == me.nodeNum || !n.hasPosition) continue
            if (now - (n.positionTime ?: 0) > FRESH_MS) continue
            val theirPast = pastSample(byNode[n.nodeNum], now) ?: continue
            val was = Geo.distanceMeters(
                myPast?.latitude ?: me.latitude!!, myPast?.longitude ?: me.longitude!!, theirPast.latitude, theirPast.longitude,
            )
            val nowD = Geo.distanceMeters(me.latitude!!, me.longitude!!, n.latitude!!, n.longitude!!)
            val delta = nowD - was
            val noise = Geo.precisionMeters(me.positionPrecisionBits) + Geo.precisionMeters(n.positionPrecisionBits)
            val threshold = maxOf(MIN_CHANGE_M, MIN_CHANGE_RATIO * was, noise)
            if (abs(delta) < threshold) continue
            out[n.nodeNum] = Movement(if (delta < 0) Trend.APPROACHING else Trend.LEAVING, was, nowD)
        }
        return out
    }

    /** The sample closest to [TARGET_AGO_MS] ago, within the allowed window. */
    private fun pastSample(list: List<PositionEntity>?, now: Long): PositionEntity? =
        list?.filter { now - it.at in MIN_AGO_MS..MAX_AGO_MS }?.minByOrNull { abs(now - it.at - TARGET_AGO_MS) }
}
