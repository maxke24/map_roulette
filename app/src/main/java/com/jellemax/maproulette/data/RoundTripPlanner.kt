package com.jellemax.maproulette.data

import java.io.IOException
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout

/**
 * Junction-aware curviness scoring.
 *
 * A road's score is the fraction of its length spent in sweeping bends
 * (curve radius 25–300 m). The turn radius is estimated per vertex from the
 * circumcircle of it and its two neighbours. Vertices at or next to a
 * junction node (a node shared with another way, or a way endpoint) are
 * skipped entirely, so a 90° turn at an intersection contributes nothing —
 * only curvature *within* the road counts.
 */
object Curviness {

    fun junctionNodes(ways: List<OverpassWay>): Set<Long> {
        val seen = HashSet<Long>()
        val junctions = HashSet<Long>()
        for (way in ways) {
            for (node in way.nodes) {
                if (node == 0L) continue
                if (!seen.add(node)) junctions.add(node)
            }
            // Way endpoints are OSM split points: treat as junctions to be safe.
            way.nodes.firstOrNull()?.let { junctions.add(it) }
            way.nodes.lastOrNull()?.let { junctions.add(it) }
        }
        junctions.remove(0L)
        return junctions
    }

    fun score(way: OverpassWay, junctions: Set<Long>): Double {
        val pts = way.points
        if (pts.size < 3) return 0.0
        var total = 0.0
        for (i in 0 until pts.size - 1) total += RoadRoulette.distanceMeters(pts[i], pts[i + 1])
        if (total < 500) return 0.0 // stubs can't be judged

        var curvy = 0.0
        for (i in 1 until pts.size - 1) {
            if (way.nodes[i - 1] in junctions ||
                way.nodes[i] in junctions ||
                way.nodes[i + 1] in junctions
            ) continue
            val r = circumradiusMeters(pts[i - 1], pts[i], pts[i + 1])
            if (r in 25.0..300.0) {
                curvy += (RoadRoulette.distanceMeters(pts[i - 1], pts[i]) +
                    RoadRoulette.distanceMeters(pts[i], pts[i + 1])) / 2
            }
        }
        return curvy / total
    }

    /** Circumcircle radius of three points, in meters (planar approximation). */
    private fun circumradiusMeters(a: LatLon, b: LatLon, c: LatLon): Double {
        val cosLat = cos(Math.toRadians(b.lat))
        val ax = Math.toRadians(a.lon - b.lon) * 6_371_000.0 * cosLat
        val ay = Math.toRadians(a.lat - b.lat) * 6_371_000.0
        val cx = Math.toRadians(c.lon - b.lon) * 6_371_000.0 * cosLat
        val cy = Math.toRadians(c.lat - b.lat) * 6_371_000.0
        // b is the origin; cross of (b-a) and (c-a)
        val cross = abs((-ax) * (cy - ay) - (-ay) * (cx - ax))
        if (cross < 1e-6) return Double.MAX_VALUE // collinear = straight
        val ab = hypot(ax, ay)
        val bc = hypot(cx, cy)
        val ca = hypot(cx - ax, cy - ay)
        return ab * bc * ca / (2 * cross)
    }
}

/**
 * Plans a round trip through curvy roads: divides the circle into angular
 * sectors, finds the curviest road in each, and returns their midpoints as an
 * ordered loop of waypoints. Navigation between waypoints is left to the
 * user's maps app.
 */
object RoundTripPlanner {

    private const val SECTORS = 6
    private const val MIN_WAYPOINTS = 3
    private const val MIN_CURVY_SCORE = 0.12

    suspend fun plan(
        center: LatLon,
        radiusMeters: Double,
        highwayRegex: String,
        bearingDeg: Double? = null,
    ): List<LatLon> =
        withTimeout(45_000) {
            coroutineScope {
                // A loop covers all directions; a bearing just anchors where it starts.
                val startAngle = bearingDeg?.let { Math.toRadians(it) }
                    ?: Random.nextDouble(2 * PI)
                val overpassLimit = Semaphore(2) // be polite to the public API
                val waypoints = (0 until SECTORS).map { s ->
                    async(Dispatchers.IO) {
                        overpassLimit.withPermit {
                            // Any single-sector failure just skips that sector.
                            try {
                                sectorWaypoint(center, radiusMeters,
                                    startAngle + s * 2 * PI / SECTORS, highwayRegex, s)
                            } catch (e: Exception) {
                                if (e is CancellationException) throw e
                                null
                            }
                        }
                    }
                }.awaitAll().filterNotNull()
                if (waypoints.size < MIN_WAYPOINTS) {
                    throw IOException("Not enough roads for a round trip — try a larger radius")
                }
                waypoints
            }
        }

    private fun sectorWaypoint(
        center: LatLon,
        radiusMeters: Double,
        sectorAngle: Double,
        highwayRegex: String,
        sectorIndex: Int,
    ): LatLon? {
        // Jitter within the sector; aim for a ring at 50–85% of the radius so
        // the loop stays inside the circle.
        val bearing = sectorAngle + Random.nextDouble(-0.35, 0.35)
        val distance = radiusMeters * Random.nextDouble(0.5, 0.85)
        val sample = RoadRoulette.offset(center, distance, bearing)

        for (searchRadius in listOf(2_000.0, 5_000.0)) {
            val ways = try {
                RoadRoulette.fetchRoads(sample, searchRadius, highwayRegex, sectorIndex)
            } catch (e: IOException) {
                continue
            }
            if (ways.isEmpty()) continue

            val junctions = Curviness.junctionNodes(ways)
            val scored = ways.map { it to Curviness.score(it, junctions) }

            val curvy = scored.filter { it.second >= MIN_CURVY_SCORE }
            val chosen = if (curvy.isNotEmpty()) {
                weightedRandom(curvy)
            } else {
                // No curvy road here; keep the loop shape with the longest road.
                scored.maxByOrNull { wayLength(it.first) }?.first ?: continue
            }
            return chosen.points[chosen.points.size / 2]
        }
        return null
    }

    private fun weightedRandom(candidates: List<Pair<OverpassWay, Double>>): OverpassWay {
        val weights = candidates.map { (way, score) -> score * wayLength(way) }
        var pick = Random.nextDouble(weights.sum())
        for (i in candidates.indices) {
            if (pick <= weights[i]) return candidates[i].first
            pick -= weights[i]
        }
        return candidates.last().first
    }

    private fun wayLength(way: OverpassWay): Double {
        var total = 0.0
        for (i in 0 until way.points.size - 1) {
            total += RoadRoulette.distanceMeters(way.points[i], way.points[i + 1])
        }
        return total
    }
}
