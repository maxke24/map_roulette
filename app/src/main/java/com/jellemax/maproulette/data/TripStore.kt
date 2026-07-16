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
    /** Which vehicle this was. Trips saved before modes existed read as CAR. */
    val mode: TravelMode = TravelMode.CAR,
) {
    val durationMs: Long get() = endTimeMs - startTimeMs
    val avgSpeedMps: Double
        get() = if (durationMs > 0) distanceMeters / (durationMs / 1000.0) else 0.0
}

/** Persists finished trips as a JSON array in app-private storage. */
object TripStore {

    private const val FILE_NAME = "trips.json"
    private const val DELETED_FILE_NAME = "deleted_trips.json"

    fun save(context: Context, trip: Trip) {
        writeAll(context, listOf(trip) + load(context))
    }

    /** Correct a misclassified trip's vehicle. Trips are keyed by their start
     *  time, which is unique per recording. No-op if no such trip exists. */
    fun updateMode(context: Context, startTimeMs: Long, mode: TravelMode) {
        val trips = load(context)
        if (trips.none { it.startTimeMs == startTimeMs }) return
        writeAll(context, trips.map {
            if (it.startTimeMs == startTimeMs) it.copy(mode = mode) else it
        })
    }

    /**
     * Delete a trip (e.g. a false-positive auto-detection). The start time is
     * also tombstoned, so the server's copy — the /sync merge returns the union
     * and replaces the local store — can't quietly bring it back on the next
     * sync. Tombstones are honoured in [replaceRaw].
     */
    fun delete(context: Context, startTimeMs: Long) {
        val tombstones = tombstones(context)
        tombstones.add(startTimeMs)
        writeTombstones(context, tombstones)
        writeAll(context, load(context).filterNot { it.startTimeMs == startTimeMs })
    }

    private fun writeAll(context: Context, trips: List<Trip>) {
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
                    .put("mode", t.mode.name)
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
                    mode = TravelMode.of(o.optString("mode")),
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

    /** Overwrite the store with a merged JSON array from the sync server, minus
     *  any trips this device has deleted — otherwise the server's copy would
     *  reappear on every sync. */
    fun replaceRaw(context: Context, json: String) {
        val incoming = JSONArray(json) // validate before overwriting
        val tombstones = tombstones(context)
        if (tombstones.isEmpty()) {
            file(context).writeText(json)
            return
        }
        val kept = JSONArray()
        for (i in 0 until incoming.length()) {
            val o = incoming.getJSONObject(i)
            if (o.optLong("startTimeMs") !in tombstones) kept.put(o)
        }
        file(context).writeText(kept.toString())
    }

    private fun tombstones(context: Context): MutableSet<Long> {
        val f = File(context.filesDir, DELETED_FILE_NAME)
        if (!f.exists()) return mutableSetOf()
        return try {
            val array = JSONArray(f.readText())
            (0 until array.length()).map { array.getLong(it) }.toMutableSet()
        } catch (e: Exception) {
            mutableSetOf()
        }
    }

    private fun writeTombstones(context: Context, ids: Set<Long>) {
        val array = JSONArray()
        ids.forEach { array.put(it) }
        File(context.filesDir, DELETED_FILE_NAME).writeText(array.toString())
    }

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)
}
