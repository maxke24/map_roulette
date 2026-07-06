package com.jellemax.maproulette.data

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

data class LatLon(val lat: Double, val lon: Double)

/**
 * Picks a random point on a public road within a radius, using the Overpass API
 * (OpenStreetMap data). The point is chosen uniformly by road length, so dense
 * side-street grids and long country roads are weighted fairly.
 */
object RoadRoulette {

    private const val OVERPASS_URL = "https://overpass-api.de/api/interpreter"

    // Drivable road classes; excludes footpaths, cycleways, service alleys, tracks.
    private const val HIGHWAY_FILTER =
        "^(motorway|trunk|primary|secondary|tertiary|unclassified|residential|" +
            "motorway_link|trunk_link|primary_link|secondary_link|tertiary_link)$"

    fun randomRoadPoint(center: LatLon, radiusMeters: Double): LatLon {
        val ways = fetchRoads(center, radiusMeters)
        if (ways.isEmpty()) throw IOException("No roads found within radius")

        data class Segment(val a: LatLon, val b: LatLon, val length: Double)

        val segments = ArrayList<Segment>()
        for (way in ways) {
            for (i in 0 until way.size - 1) {
                val a = way[i]
                val b = way[i + 1]
                val mid = LatLon((a.lat + b.lat) / 2, (a.lon + b.lon) / 2)
                // "around" matches ways that pass through the radius; clip segments
                // whose midpoint falls outside so the pin always lands inside.
                if (distanceMeters(center, mid) <= radiusMeters) {
                    segments.add(Segment(a, b, distanceMeters(a, b)))
                }
            }
        }
        if (segments.isEmpty()) throw IOException("No road segments within radius")

        val total = segments.sumOf { it.length }
        var pick = Random.nextDouble(total)
        for (seg in segments) {
            if (pick <= seg.length) {
                val t = if (seg.length == 0.0) 0.0 else pick / seg.length
                return LatLon(
                    seg.a.lat + (seg.b.lat - seg.a.lat) * t,
                    seg.a.lon + (seg.b.lon - seg.a.lon) * t,
                )
            }
            pick -= seg.length
        }
        return segments.last().b
    }

    private fun fetchRoads(center: LatLon, radiusMeters: Double): List<List<LatLon>> {
        val query = """
            [out:json][timeout:30];
            way(around:${radiusMeters.toInt()},${center.lat},${center.lon})["highway"~"$HIGHWAY_FILTER"];
            out geom;
        """.trimIndent()

        val conn = URL(OVERPASS_URL).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 40_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.outputStream.use {
                it.write("data=${URLEncoder.encode(query, "UTF-8")}".toByteArray())
            }
            if (conn.responseCode != 200) {
                throw IOException("Overpass API error: HTTP ${conn.responseCode}")
            }
            val body = conn.inputStream.bufferedReader().readText()
            return parseWays(body)
        } finally {
            conn.disconnect()
        }
    }

    private fun parseWays(json: String): List<List<LatLon>> {
        val elements = JSONObject(json).getJSONArray("elements")
        val ways = ArrayList<List<LatLon>>(elements.length())
        for (i in 0 until elements.length()) {
            val el = elements.getJSONObject(i)
            val geometry = el.optJSONArray("geometry") ?: continue
            val points = ArrayList<LatLon>(geometry.length())
            for (j in 0 until geometry.length()) {
                val p = geometry.getJSONObject(j)
                points.add(LatLon(p.getDouble("lat"), p.getDouble("lon")))
            }
            if (points.size >= 2) ways.add(points)
        }
        return ways
    }

    fun distanceMeters(a: LatLon, b: LatLon): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) *
            sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * atan2(sqrt(h), sqrt(1 - h))
    }
}
