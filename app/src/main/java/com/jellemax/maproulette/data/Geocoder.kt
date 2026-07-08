package com.jellemax.maproulette.data

import org.json.JSONArray
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.zip.GZIPInputStream

data class GeocodeResult(val name: String, val location: LatLon)

/** Address/place search via OSM Nominatim, biased toward the user's area. */
object Geocoder {

    fun search(query: String, near: LatLon?, limit: Int = 6): List<GeocodeResult> {
        val viewbox = near?.let {
            // Preference box (~75 km) around the user; not a hard bound.
            "&viewbox=${it.lon - 0.7},${it.lat + 0.7},${it.lon + 0.7},${it.lat - 0.7}"
        } ?: ""
        val url = "https://nominatim.openstreetmap.org/search?format=jsonv2" +
            "&q=" + URLEncoder.encode(query, "UTF-8") +
            "&limit=$limit&dedupe=1" + viewbox

        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 5_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("Accept-Encoding", "gzip")
            // Nominatim usage policy requires an identifying user agent.
            conn.setRequestProperty("User-Agent", "MapRoulette/1.11 (personal Android app)")
            if (conn.responseCode != 200) {
                throw IOException("Search failed: HTTP ${conn.responseCode}")
            }
            val stream = if (conn.contentEncoding == "gzip") {
                GZIPInputStream(conn.inputStream)
            } else {
                conn.inputStream
            }
            return parse(stream.bufferedReader().readText())
        } finally {
            conn.disconnect()
        }
    }

    private fun parse(json: String): List<GeocodeResult> {
        val arr = JSONArray(json)
        val results = ArrayList<GeocodeResult>(arr.length())
        for (i in 0 until arr.length()) {
            val el = arr.getJSONObject(i)
            val display = el.optString("display_name")
            if (display.isBlank()) continue
            results.add(GeocodeResult(
                // Full display names run long; the first parts identify the place.
                name = display.split(",").take(3).joinToString(",").trim(),
                location = LatLon(el.getDouble("lat"), el.getDouble("lon")),
            ))
        }
        return results
    }
}
