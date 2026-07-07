package com.jellemax.maproulette.data

import android.content.Context
import com.jellemax.maproulette.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * Bidirectional sync with the owner's sync server (see
 * server/SYNC_SETUP_GUIDE.md). One POST uploads local trips + fog-of-war
 * traces; the server merges them with its copy and returns the union, which
 * replaces the local stores. Deleting and reinstalling the app therefore
 * restores everything on the first sync.
 *
 * The server lives behind the same Cloudflare Access as the routing server,
 * so the routing config's service-token headers are reused.
 */
object SyncClient {

    data class SyncResult(val trips: Int, val traces: Int)

    /** Effective sync URL: user setting first, baked default second. */
    fun url(): String? =
        Settings.syncUrl.value.ifBlank { BuildConfig.SYNC_URL }.ifBlank { null }

    val configured: Boolean get() = url() != null

    /** Fire-and-forget sync for the tracking service; never throws. */
    fun syncQuietly(context: Context) {
        if (!configured) return
        Thread {
            try {
                sync(context)
            } catch (e: Exception) {
                // Offline or server down; next sync catches up.
            }
        }.start()
    }

    /** Blocking sync; call off the main thread. */
    fun sync(context: Context): SyncResult {
        val url = url() ?: throw IOException("No sync server configured")
        Settings.init(context)
        val cf = RoutingServer.load(context)

        val payload = JSONObject()
            .put("trips", JSONArray(TripStore.rawJson(context)))
            .put("traces", JSONArray(TraceStore.rawLines(context)))
            .toString()

        val conn = URL(url.trimEnd('/') + "/sync").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 5_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept-Encoding", "gzip")
            conn.setRequestProperty("User-Agent", "MapRoulette/${BuildConfig.VERSION_NAME}")
            if (cf.clientId.isNotBlank()) {
                conn.setRequestProperty("CF-Access-Client-Id", cf.clientId)
                conn.setRequestProperty("CF-Access-Client-Secret", cf.clientSecret)
            }
            conn.outputStream.use { it.write(payload.toByteArray()) }
            if (conn.responseCode != 200) {
                throw IOException("Sync server error: HTTP ${conn.responseCode}")
            }
            val stream = if (conn.contentEncoding == "gzip") {
                GZIPInputStream(conn.inputStream)
            } else {
                conn.inputStream
            }
            val merged = JSONObject(stream.bufferedReader().readText())
            val trips = merged.getJSONArray("trips")
            val traces = merged.getJSONArray("traces")
            TripStore.replaceRaw(context, trips.toString())
            TraceStore.replaceLines(
                context, (0 until traces.length()).map { traces.getString(it) })
            return SyncResult(trips = trips.length(), traces = traces.length())
        } finally {
            conn.disconnect()
        }
    }
}
