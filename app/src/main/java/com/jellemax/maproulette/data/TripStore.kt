package com.jellemax.maproulette.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Trip(
    val startTimeMs: Long,
    val endTimeMs: Long,
    val distanceMeters: Double,
    val topSpeedMps: Double,
    val maxLeanAngleDeg: Double = 0.0,
    val maxGForce: Double = 0.0,
    val destinationLat: Double?,
    val destinationLon: Double?,
) {
    val durationMs: Long get() = endTimeMs - startTimeMs
    val avgSpeedMps: Double
        get() = if (durationMs > 0) distanceMeters / (durationMs / 1000.0) else 0.0
}

/** Persists finished trips as a JSON array in app-private storage. */
object TripStore {

    private const val FILE_NAME = "trips.json"

    fun save(context: Context, trip: Trip) {
        val trips = load(context).toMutableList()
        trips.add(0, trip)
        val array = JSONArray()
        for (t in trips) {
            array.put(
                JSONObject()
                    .put("startTimeMs", t.startTimeMs)
                    .put("endTimeMs", t.endTimeMs)
                    .put("distanceMeters", t.distanceMeters)
                    .put("topSpeedMps", t.topSpeedMps)
                    .put("maxLeanAngleDeg", t.maxLeanAngleDeg)
                    .put("maxGForce", t.maxGForce)
                    .put("destinationLat", t.destinationLat ?: JSONObject.NULL)
                    .put("destinationLon", t.destinationLon ?: JSONObject.NULL)
            )
        }
        file(context).writeText(array.toString())
    }

    fun load(context: Context): List<Trip> {
        val f = file(context)
        if (!f.exists()) return emptyList()
        return try {
            val array = JSONArray(f.readText())
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                Trip(
                    startTimeMs = o.getLong("startTimeMs"),
                    endTimeMs = o.getLong("endTimeMs"),
                    distanceMeters = o.getDouble("distanceMeters"),
                    topSpeedMps = o.getDouble("topSpeedMps"),
                    maxLeanAngleDeg = o.optDouble("maxLeanAngleDeg", 0.0),
                    maxGForce = o.optDouble("maxGForce", 0.0),
                    destinationLat = if (o.isNull("destinationLat")) null else o.getDouble("destinationLat"),
                    destinationLon = if (o.isNull("destinationLon")) null else o.getDouble("destinationLon"),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Raw stored JSON array, for server sync. */
    fun rawJson(context: Context): String {
        val f = file(context)
        return if (f.exists()) f.readText() else "[]"
    }

    /** Overwrite the store with a merged JSON array from the sync server. */
    fun replaceRaw(context: Context, json: String) {
        JSONArray(json) // validate before overwriting
        file(context).writeText(json)
    }

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)
}
