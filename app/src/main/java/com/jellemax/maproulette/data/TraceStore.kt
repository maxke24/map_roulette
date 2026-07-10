package com.jellemax.maproulette.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import java.io.File

/**
 * Persists driven GPS traces (decimated polylines), one JSON array per line.
 * Powers the fog-of-war overlay: every trace is explored territory.
 */
object TraceStore {

    private const val FILE_NAME = "traces.jsonl"

    /** Bumped on every write so the map reloads traces immediately. */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    fun append(context: Context, trace: List<LatLon>) {
        if (trace.size < 2) return
        val line = JSONArray().apply {
            for (p in trace) put(JSONArray().put(p.lat).put(p.lon))
        }
        file(context).appendText(line.toString() + "\n")
        _version.value++
    }

    fun loadAll(context: Context): List<List<LatLon>> {
        val f = file(context)
        if (!f.exists()) return emptyList()
        return parseLines(f.readLines())
    }

    /** Decodes stored JSONL polylines, skipping any line that doesn't decode.
     *  Also used for the traces a friend's device wrote, which arrive in the
     *  same format but have never been near this file. */
    fun parseLines(lines: List<String>): List<List<LatLon>> = lines.mapNotNull { line ->
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

    fun clear(context: Context) {
        file(context).delete()
        _version.value++
    }

    /** Raw JSONL lines, for server sync. */
    fun rawLines(context: Context): List<String> {
        val f = file(context)
        return if (f.exists()) f.readLines().filter { it.isNotBlank() } else emptyList()
    }

    /** Overwrite the store with merged lines from the sync server. */
    fun replaceLines(context: Context, lines: List<String>) {
        file(context).writeText(
            lines.filter { it.isNotBlank() }.joinToString("\n", postfix = "\n"))
        _version.value++
    }

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)
}
