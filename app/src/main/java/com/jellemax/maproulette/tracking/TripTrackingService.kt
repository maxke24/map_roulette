package com.jellemax.maproulette.tracking

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.jellemax.maproulette.MainActivity
import com.jellemax.maproulette.data.LatLon
import com.jellemax.maproulette.data.RoadRoulette
import com.jellemax.maproulette.data.TraceStore
import com.jellemax.maproulette.data.Trip
import com.jellemax.maproulette.data.TripStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class TripStats(
    val startTimeMs: Long,
    val durationMs: Long = 0,
    val distanceMeters: Double = 0.0,
    val currentSpeedMps: Double = 0.0,
    val topSpeedMps: Double = 0.0,
)

/**
 * Foreground service that records the active trip: duration, distance,
 * current and top speed. Live stats are exposed through [stats]; when the
 * service stops the trip is written to [TripStore].
 */
class TripTrackingService : Service() {

    companion object {
        const val EXTRA_DEST_LAT = "dest_lat"
        const val EXTRA_DEST_LON = "dest_lon"
        private const val CHANNEL_ID = "trip_tracking"
        private const val NOTIFICATION_ID = 1

        private val _stats = MutableStateFlow<TripStats?>(null)
        val stats: StateFlow<TripStats?> = _stats

        fun start(context: Context, destLat: Double?, destLon: Double?) {
            val intent = Intent(context, TripTrackingService::class.java).apply {
                destLat?.let { putExtra(EXTRA_DEST_LAT, it) }
                destLon?.let { putExtra(EXTRA_DEST_LON, it) }
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TripTrackingService::class.java))
        }
    }

    private lateinit var fusedClient: FusedLocationProviderClient
    private var lastLocation: Location? = null
    private var destLat: Double? = null
    private var destLon: Double? = null
    private val tracePoints = ArrayList<LatLon>()
    private var origin: LatLon? = null
    private var awayFromOrigin = false

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            for (location in result.locations) onLocation(location)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (_stats.value != null) return START_STICKY // already tracking

        destLat = intent?.takeIf { it.hasExtra(EXTRA_DEST_LAT) }?.getDoubleExtra(EXTRA_DEST_LAT, 0.0)
        destLon = intent?.takeIf { it.hasExtra(EXTRA_DEST_LON) }?.getDoubleExtra(EXTRA_DEST_LON, 0.0)

        createChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0,
        )

        _stats.value = TripStats(startTimeMs = System.currentTimeMillis())

        fusedClient = LocationServices.getFusedLocationProviderClient(this)
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(500L)
            .build()
        try {
            fusedClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
        } catch (e: SecurityException) {
            stopSelf()
        }
        return START_STICKY
    }

    private fun onLocation(location: Location) {
        val stats = _stats.value ?: return
        val now = System.currentTimeMillis()

        var distance = stats.distanceMeters
        val last = lastLocation
        // Only accumulate distance for accurate, recent fixes to avoid GPS jumps.
        if (last != null && location.accuracy <= 50f &&
            location.time - last.time in 1..15_000
        ) {
            distance += last.distanceTo(location).toDouble()
        }
        val speed = if (location.hasSpeed()) location.speed.toDouble() else 0.0
        lastLocation = location

        // Trace for the fog-of-war map, decimated to ~25 m spacing.
        if (location.accuracy <= 50f) {
            val p = LatLon(location.latitude, location.longitude)
            val lastTrace = tracePoints.lastOrNull()
            if (lastTrace == null ||
                RoadRoulette.distanceMeters(lastTrace, p) >= 25.0
            ) {
                tracePoints.add(p)
            }

            // Auto-stop when back at the starting point after a real trip.
            if (origin == null) origin = p
            origin?.let { start ->
                val fromStart = RoadRoulette.distanceMeters(p, start)
                if (fromStart > 400) awayFromOrigin = true
                if (awayFromOrigin && fromStart < 120 &&
                    now - stats.startTimeMs > 5 * 60_000
                ) {
                    notifyTripEnded()
                    stopSelf()
                    return
                }
            }
        }

        _stats.value = stats.copy(
            durationMs = now - stats.startTimeMs,
            distanceMeters = distance,
            currentSpeedMps = speed,
            topSpeedMps = maxOf(stats.topSpeedMps, speed),
        )
    }

    override fun onDestroy() {
        if (::fusedClient.isInitialized) {
            fusedClient.removeLocationUpdates(locationCallback)
        }
        val stats = _stats.value
        if (stats != null && stats.durationMs > 0) {
            TraceStore.append(this, tracePoints)
            TripStore.save(
                this,
                Trip(
                    startTimeMs = stats.startTimeMs,
                    endTimeMs = System.currentTimeMillis(),
                    distanceMeters = stats.distanceMeters,
                    topSpeedMps = stats.topSpeedMps,
                    destinationLat = destLat,
                    destinationLon = destLon,
                ),
            )
        }
        _stats.value = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Trip tracking", NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun notifyTripEnded() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Map Roulette")
            .setContentText("Back at your starting point — trip saved.")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(2, notification)
    }

    private fun buildNotification(): android.app.Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Map Roulette")
            .setContentText("Tracking your drive…")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .build()
    }
}
