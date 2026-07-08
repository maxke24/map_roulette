package com.jellemax.maproulette.data

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Route-following math for in-app navigation. Pure functions, no state. */
object NavEngine {

    data class Progress(
        /** Distance from the current position to the nearest point on the route. */
        val offRouteMeters: Double,
        /** The upcoming maneuver (arrival instruction near the end). */
        val nextInstruction: NavInstruction?,
        val distanceToTurnMeters: Double,
        val remainingMeters: Double,
        val remainingTimeMs: Long?,
    )

    /** Where [pos] is along [route]: snap to the nearest segment, then derive
     *  the upcoming instruction and remaining distance/time. */
    fun progress(route: RouteResult, pos: LatLon): Progress? {
        val line = route.polyline
        if (line.size < 2) return null

        // Local equirectangular projection around pos; fine at route scale.
        val mPerLat = 111_320.0
        val mPerLon = 111_320.0 * cos(Math.toRadians(pos.lat))
        fun x(p: LatLon) = (p.lon - pos.lon) * mPerLon
        fun y(p: LatLon) = (p.lat - pos.lat) * mPerLat

        // One pass: nearest segment plus cumulative distance to each vertex.
        val cumAt = DoubleArray(line.size)
        var bestDist = Double.MAX_VALUE
        var bestIndex = 0
        var bestAlong = 0.0
        for (i in 0 until line.size - 1) {
            val ax = x(line[i]); val ay = y(line[i])
            val bx = x(line[i + 1]); val by = y(line[i + 1])
            val dx = bx - ax; val dy = by - ay
            val segLen2 = dx * dx + dy * dy
            val segLen = sqrt(segLen2)
            // Project pos (the local origin) onto segment A→B, clamped.
            val t = if (segLen2 == 0.0) 0.0
                else max(0.0, min(1.0, -(ax * dx + ay * dy) / segLen2))
            val d = hypot(ax + t * dx, ay + t * dy)
            if (d < bestDist) {
                bestDist = d
                bestIndex = i
                bestAlong = cumAt[i] + t * segLen
            }
            cumAt[i + 1] = cumAt[i] + segLen
        }
        val total = cumAt.last()
        val remaining = max(0.0, total - bestAlong)

        val next = route.instructions.firstOrNull { it.startIndex > bestIndex }
            ?: route.instructions.lastOrNull()
        val distToTurn = next
            ?.let { max(0.0, cumAt[it.startIndex.coerceIn(0, line.size - 1)] - bestAlong) }
            ?: remaining

        return Progress(
            offRouteMeters = bestDist,
            nextInstruction = next,
            distanceToTurnMeters = distToTurn,
            remainingMeters = remaining,
            remainingTimeMs = route.timeMs?.let {
                if (total > 0) (it * remaining / total).toLong() else null
            },
        )
    }
}
