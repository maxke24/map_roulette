package com.jellemax.maproulette.data

import org.json.JSONObject
import java.io.IOException

/**
 * Speed cameras and average-speed sections near you, from OpenStreetMap via
 * Overpass — the only source that is an actual queryable API.
 *
 * In OSM a fixed camera is a node tagged `highway=speed_camera`. A Belgian
 * trajectcontrole (average-speed section) is a `type=enforcement,
 * enforcement=average_speed` relation whose start/end *device* members are
 * themselves such nodes; the relation carries the posted `maxspeed`. We fetch
 * the individual camera nodes (for the map markers and the over-speed chime)
 * and the enforcement relations (whose device coordinates let us tell when you
 * enter and leave a section, so the average can be timed) in one request.
 *
 * Same prefetch shape as [RoadRoulette.speedLimitWays]: fetched once for a wide
 * area, refreshed only as you near the edge of what you already have, so there
 * is no network round-trip per fix. [near] returns null on any network error —
 * cameras are an overlay, never something the drive depends on, and a null lets
 * the caller keep the markers it already has instead of flickering them off.
 */
object SpeedCameras {

    /** One camera to draw on the map. */
    data class Camera(val at: LatLon)

    /** An average-speed section: its device (start/end) points and the posted
     *  limit the average is judged against, when the relation tags one. */
    data class Section(val devices: List<LatLon>, val maxspeedKmh: Double?)

    data class Result(val cameras: List<Camera>, val sections: List<Section>)

    /** Radius fetched around you. Wide enough that one fetch covers a few
     *  minutes of driving before the edge-of-area refetch kicks in. */
    const val PREFETCH_RADIUS_M = 4000.0

    /** Null on network error; an empty [Result] means the area really has none. */
    fun near(
        center: LatLon,
        radiusMeters: Double = PREFETCH_RADIUS_M,
    ): Result? {
        val r = radiusMeters.toInt()
        val query = "[out:json][timeout:20];(" +
            "node(around:$r,${center.lat},${center.lon})[\"highway\"=\"speed_camera\"];" +
            "relation(around:$r,${center.lat},${center.lon})[\"enforcement\"=\"average_speed\"];" +
            ");out geom;"
        val json = try {
            RoadRoulette.rawQuery(query)
        } catch (e: IOException) {
            return null
        }
        val elements = JSONObject(json).getJSONArray("elements")
        val cameras = ArrayList<Camera>()
        val sections = ArrayList<Section>()
        for (i in 0 until elements.length()) {
            val el = elements.getJSONObject(i)
            when (el.optString("type")) {
                "node" -> {
                    val lat = el.optDouble("lat", Double.NaN)
                    val lon = el.optDouble("lon", Double.NaN)
                    if (!lat.isNaN() && !lon.isNaN()) cameras.add(Camera(LatLon(lat, lon)))
                }
                "relation" -> parseSection(el)?.let { sections.add(it) }
            }
        }
        return Result(cameras, sections)
    }

    /** Device points come from the relation's node members, which `out geom`
     *  prints inline with their coordinates. A section with none is unusable. */
    private fun parseSection(relation: JSONObject): Section? {
        val members = relation.optJSONArray("members") ?: return null
        val devices = ArrayList<LatLon>()
        for (i in 0 until members.length()) {
            val m = members.getJSONObject(i)
            if (m.optString("type") != "node") continue
            val lat = m.optDouble("lat", Double.NaN)
            val lon = m.optDouble("lon", Double.NaN)
            if (!lat.isNaN() && !lon.isNaN()) devices.add(LatLon(lat, lon))
        }
        if (devices.size < 2) return null
        val maxspeed = relation.optJSONObject("tags")?.optString("maxspeed")
            ?.takeIf { it.isNotBlank() }?.let { RoadRoulette.parseMaxSpeed(it) }
        return Section(devices, maxspeed)
    }
}
