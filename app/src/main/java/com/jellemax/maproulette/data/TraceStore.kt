package com.jellemax.maproulette.data

import android.content.Context
import org.json.JSONArray
import java.io.File

/**
 * Persists driven GPS traces (decimated polylines), one JSON array per line.
 * Powers the fog-of-war overlay: every trace is explored territory.
 */
object TraceStore {

    private const val FILE_NAME = "traces.jsonl"

    fun append(context: Context, trace: List<LatLon>) {
        if (trace.size < 2) return
        val line = JSONArray().apply {
            for (p in trace) put(JSONArray().put(p.lat).put(p.lon))
        }
        file(context).appendText(line.toString() + "\n")
    }

    fun loadAll(context: Context): List<List<LatLon>> {
        val f = file(context)
        if (!f.exists()) return emptyList()
        return f.readLines().mapNotNull { line ->
            try {
                val arr = JSONArray(line)
                (0 until arr.length()).map { i ->
                    val p = arr.getJSONArray(i)
                    LatLon(p.getDouble(0), p.getDouble(1))
                }.takeIf { it.size >= 2 }
            } catch (e: Exception) {
                null
            }
        }
    }

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)
}
