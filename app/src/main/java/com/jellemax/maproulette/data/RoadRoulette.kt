package com.jellemax.maproulette.data

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.zip.GZIPInputStream
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

data class LatLon(val lat: Double, val lon: Double)

/**
 * Picks a random point on a road within a radius, using the Overpass API
 * (OpenStreetMap data).
 *
 * For large radii it does NOT download every road in the circle (which can be
 * tens of MB over a city). Instead it samples a random sub-area (uniform by
 * area) and queries only a small circle around it, widening the search when
 * the sampled spot has no roads. Within the fetched roads the point is chosen
 * uniformly by road length.
 */
object RoadRoulette {

    private val ENDPOINTS = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
    )

    fun randomRoadPoint(center: LatLon, radiusMeters: Double, highwayRegex: String): LatLon {
        // Small circles are cheap to fetch whole.
        if (radiusMeters <= 1500) {
            return pickPoint(fetchRoads(center, radiusMeters, highwayRegex), center, radiusMeters)
                ?: throw IOException("No roads found within radius")
        }

        var lastError: IOException? = null
        for (attempt in 0 until 4) {
            val sample = randomPointInCircle(center, radiusMeters)
            // 600 m, 2.4 km, 5.4 km, 9.6 km — widen only if the spot was empty.
            val searchRadius = min(600.0 * (attempt + 1) * (attempt + 1), radiusMeters)
            try {
                val ways = fetchRoads(sample, searchRadius, highwayRegex)
                pickPoint(ways, center, radiusMeters)?.let { return it }
            } catch (e: IOException) {
                lastError = e
            }
        }
        throw lastError ?: IOException("No roads found within radius")
    }

    /** Uniform-by-area random point inside the circle. */
    private fun randomPointInCircle(center: LatLon, radiusMeters: Double): LatLon {
        val r = radiusMeters * sqrt(Random.nextDouble())
        val theta = Random.nextDouble(2 * PI)
        val dLat = (r * cos(theta)) / 111_320.0
        val dLon = (r * sin(theta)) / (111_320.0 * cos(Math.toRadians(center.lat)))
        return LatLon(center.lat + dLat, center.lon + dLon)
    }

    /** Length-weighted random point on the given ways, restricted to the main circle. */
    private fun pickPoint(ways: List<List<LatLon>>, center: LatLon, radiusMeters: Double): LatLon? {
        data class Segment(val a: LatLon, val b: LatLon, val length: Double)

        val segments = ArrayList<Segment>()
        for (way in ways) {
            for (i in 0 until way.size - 1) {
                val a = way[i]
                val b = way[i + 1]
                val mid = LatLon((a.lat + b.lat) / 2, (a.lon + b.lon) / 2)
                if (distanceMeters(center, mid) <= radiusMeters) {
                    segments.add(Segment(a, b, distanceMeters(a, b)))
                }
            }
        }
        if (segments.isEmpty()) return null

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

    private fun fetchRoads(
        center: LatLon,
        radiusMeters: Double,
        highwayRegex: String,
    ): List<List<LatLon>> {
        val query = """
            [out:json][timeout:10];
            way(around:${radiusMeters.toInt()},${center.lat},${center.lon})["highway"~"$highwayRegex"];
            out geom;
        """.trimIndent()

        var lastError: IOException? = null
        for (endpoint in ENDPOINTS) {
            try {
                return parseWays(post(endpoint, query))
            } catch (e: IOException) {
                lastError = e
            }
        }
        throw lastError ?: IOException("All Overpass endpoints failed")
    }

    private fun post(endpoint: String, query: String): String {
        val conn = URL(endpoint).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 8_000
            conn.readTimeout = 15_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.setRequestProperty("Accept-Encoding", "gzip")
            conn.outputStream.use {
                it.write("data=${URLEncoder.encode(query, "UTF-8")}".toByteArray())
            }
            if (conn.responseCode != 200) {
                throw IOException("Overpass API error: HTTP ${conn.responseCode}")
            }
            val stream = if (conn.contentEncoding == "gzip") {
                GZIPInputStream(conn.inputStream)
            } else {
                conn.inputStream
            }
            return stream.bufferedReader().readText()
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
