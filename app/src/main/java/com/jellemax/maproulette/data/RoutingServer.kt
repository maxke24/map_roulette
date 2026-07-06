package com.jellemax.maproulette.data

import android.content.Context
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

data class RouteResult(
    /** Full route geometry (road-following when from GraphHopper). */
    val polyline: List<LatLon>,
    /** Sampled via points for the Google Maps handoff (max 9 supported). */
    val waypoints: List<LatLon>,
    /** Total loop length, if the router reported it. */
    val distanceMeters: Double?,
)

data class ServerConfig(
    val url: String = "",
    val clientId: String = "",
    val clientSecret: String = "",
    val enabled: Boolean = false,
) {
    val usable: Boolean get() = enabled && url.isNotBlank()
}

/**
 * Client for a self-hosted GraphHopper instance (see server/CLAUDE_SETUP_GUIDE.md),
 * optionally behind Cloudflare Access. Configured by the user in the app; the
 * URL and token live only in app-private preferences, never in the repo/APK.
 */
object RoutingServer {

    private const val PREFS = "routing_server"

    fun load(context: Context): ServerConfig {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return ServerConfig(
            url = p.getString("url", "") ?: "",
            clientId = p.getString("clientId", "") ?: "",
            clientSecret = p.getString("clientSecret", "") ?: "",
            enabled = p.getBoolean("enabled", false),
        )
    }

    fun save(context: Context, config: ServerConfig) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("url", config.url.trim())
            .putString("clientId", config.clientId.trim())
            .putString("clientSecret", config.clientSecret.trim())
            .putBoolean("enabled", config.enabled)
            .apply()
    }

    fun roundTrip(
        config: ServerConfig,
        start: LatLon,
        distanceMeters: Double,
        seed: Long,
    ): RouteResult {
        val url = config.url.trimEnd('/') +
            "/route?profile=moto" +
            "&point=${start.lat},${start.lon}" +
            "&algorithm=round_trip" +
            "&round_trip.distance=${distanceMeters.toInt()}" +
            "&round_trip.seed=$seed" +
            "&points_encoded=false"

        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 5_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("Accept-Encoding", "gzip")
            if (config.clientId.isNotBlank()) {
                conn.setRequestProperty("CF-Access-Client-Id", config.clientId)
                conn.setRequestProperty("CF-Access-Client-Secret", config.clientSecret)
            }
            if (conn.responseCode != 200) {
                throw IOException("Routing server error: HTTP ${conn.responseCode}")
            }
            val stream = if (conn.contentEncoding == "gzip") {
                GZIPInputStream(conn.inputStream)
            } else {
                conn.inputStream
            }
            return parseRoute(stream.bufferedReader().readText())
        } finally {
            conn.disconnect()
        }
    }

    private fun parseRoute(json: String): RouteResult {
        val path = JSONObject(json).getJSONArray("paths").getJSONObject(0)
        val coords = path.getJSONObject("points").getJSONArray("coordinates")
        val polyline = ArrayList<LatLon>(coords.length())
        for (i in 0 until coords.length()) {
            val c = coords.getJSONArray(i) // GeoJSON order: [lon, lat]
            polyline.add(LatLon(c.getDouble(1), c.getDouble(0)))
        }
        if (polyline.size < 2) throw IOException("Routing server returned an empty route")
        return RouteResult(
            polyline = polyline,
            waypoints = sampleInterior(polyline, 8),
            distanceMeters = path.optDouble("distance").takeIf { !it.isNaN() },
        )
    }

    /** [count] evenly spaced interior points, excluding start and end. */
    private fun sampleInterior(line: List<LatLon>, count: Int): List<LatLon> {
        val n = line.size
        if (n <= 2) return emptyList()
        return (1..count)
            .map { i -> line[(i * (n - 1)) / (count + 1)] }
            .distinct()
    }
}
