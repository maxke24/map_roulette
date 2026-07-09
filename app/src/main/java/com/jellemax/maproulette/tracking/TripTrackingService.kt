package com.jellemax.maproulette.tracking

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.jellemax.maproulette.MainActivity
import com.jellemax.maproulette.data.LatLon
import com.jellemax.maproulette.data.RoadRoulette
import com.jellemax.maproulette.data.Settings
import com.jellemax.maproulette.data.SyncClient
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
    val currentLeanAngleDeg: Double = 0.0,
    val maxLeanAngleDeg: Double = 0.0,
    val currentGForce: Double = 0.0,
    val maxGForce: Double = 0.0,
)

/** Latest GPS fix, published live for the map (fog, navigation). */
data class Fix(
    val lat: Double,
    val lon: Double,
    val speedMps: Double,
    val bearingDeg: Float?,
    val accuracyMeters: Float,
    val timeMs: Long,
)

/**
 * Always-on foreground service with two modes:
 *
 *  - Idle: low-power location updates that extend the fog-of-war trace and
 *    watch for the start of a drive (activity recognition + speed).
 *  - Trip: high-accuracy updates recording duration, distance and speed.
 *    Live stats are exposed through [stats]; when the trip ends it is
 *    written to [TripStore].
 *
 * Trips start automatically when activity recognition reports IN_VEHICLE or
 * when speed stays above ~25 km/h (catches motorcycles, which activity
 * recognition often misclassifies). Auto-started trips end after leaving the
 * vehicle or being stationary; manually started trips keep the existing
 * End-button / back-at-origin behavior.
 */
class TripTrackingService : Service() {

    companion object {
        const val EXTRA_DEST_LAT = "dest_lat"
        const val EXTRA_DEST_LON = "dest_lon"
        private const val ACTION_START_TRIP = "com.jellemax.maproulette.START_TRIP"
        private const val ACTION_END_TRIP = "com.jellemax.maproulette.END_TRIP"
        private const val ACTION_TRANSITION = "com.jellemax.maproulette.ACTIVITY_TRANSITION"
        private const val CHANNEL_ID = "trip_tracking"
        private const val NOTIFICATION_ID = 1

        // Auto start/stop tuning.
        private const val FAST_SPEED_MPS = 7.0          // ~25 km/h
        private const val FAST_FIXES_TO_START = 2
        private const val EXIT_GRACE_MS = 2 * 60_000L   // after IN_VEHICLE exit
        private const val STATIONARY_END_MS = 10 * 60_000L
        private const val MIN_AUTO_TRIP_METERS = 500.0

        private val _stats = MutableStateFlow<TripStats?>(null)
        val stats: StateFlow<TripStats?> = _stats

        private val _lastFix = MutableStateFlow<Fix?>(null)
        val lastFix: StateFlow<Fix?> = _lastFix

        /** Trace points not yet flushed to [TraceStore]; live fog-of-war. */
        private val _liveTrace = MutableStateFlow<List<LatLon>>(emptyList())
        val liveTrace: StateFlow<List<LatLon>> = _liveTrace

        /** Start (or keep) the always-on tracker in idle mode. */
        fun startMonitoring(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, TripTrackingService::class.java))
        }

        /** Manually start a trip (Go/Track button). */
        fun start(context: Context, destLat: Double?, destLon: Double?) {
            val intent = Intent(context, TripTrackingService::class.java).apply {
                action = ACTION_START_TRIP
                destLat?.let { putExtra(EXTRA_DEST_LAT, it) }
                destLon?.let { putExtra(EXTRA_DEST_LON, it) }
            }
            ContextCompat.startForegroundService(context, intent)
        }

        /** End the current trip; the service stays alive in idle mode. */
        fun stop(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, TripTrackingService::class.java).setAction(ACTION_END_TRIP),
            )
        }
    }

    private lateinit var fusedClient: FusedLocationProviderClient
    private lateinit var sensorManager: SensorManager
    private var lastLocation: Location? = null
    private var destLat: Double? = null
    private var destLon: Double? = null
    private val tracePoints = ArrayList<LatLon>()
    private var origin: LatLon? = null
    private var awayFromOrigin = false

    private var autoStarted = false
    private var consecutiveFastFixes = 0
    private var pendingStopAtMs: Long? = null
    private var lastMovingMs = 0L
    private var transitionsRegistered = false
    /** Which mode the active location request was made for; null = none yet. */
    private var requestedTripMode: Boolean? = null

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            for (location in result.locations) onLocation(location)
        }
    }

    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)

    /**
     * Lean angle (roll, from the rotation-vector sensor) and g-force
     * (accelerometer magnitude) only make sense while a trip is running, so
     * these sensors are only registered between [beginTrip] and [endTrip].
     * Lean angle assumes the phone is mounted upright facing forward, e.g. a
     * handlebar mount — a phone in a pocket will read garbage.
     */
    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val stats = _stats.value ?: return
            when (event.sensor.type) {
                Sensor.TYPE_ROTATION_VECTOR -> {
                    SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                    SensorManager.getOrientation(rotationMatrix, orientationAngles)
                    val leanDeg = Math.toDegrees(orientationAngles[2].toDouble())
                    _stats.value = stats.copy(
                        currentLeanAngleDeg = leanDeg,
                        maxLeanAngleDeg = maxOf(stats.maxLeanAngleDeg, kotlin.math.abs(leanDeg)),
                    )
                }
                Sensor.TYPE_ACCELEROMETER -> {
                    val (x, y, z) = event.values
                    val gForce = kotlin.math.sqrt((x * x + y * y + z * z).toDouble()) /
                        SensorManager.GRAVITY_EARTH
                    _stats.value = stats.copy(
                        currentGForce = gForce,
                        maxGForce = maxOf(stats.maxGForce, gForce),
                    )
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private fun startMotionSensors() {
        val rotation = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        rotation?.let { sensorManager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_GAME) }
        accel?.let { sensorManager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    private fun stopMotionSensors() {
        sensorManager.unregisterListener(sensorListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Settings.init(this)
        createChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0,
        )
        if (!::fusedClient.isInitialized) {
            fusedClient = LocationServices.getFusedLocationProviderClient(this)
        }
        if (!::sensorManager.isInitialized) {
            sensorManager = getSystemService(SensorManager::class.java)
        }

        when (intent?.action) {
            ACTION_START_TRIP -> {
                if (_stats.value == null) {
                    destLat = intent.takeIf { it.hasExtra(EXTRA_DEST_LAT) }
                        ?.getDoubleExtra(EXTRA_DEST_LAT, 0.0)
                    destLon = intent.takeIf { it.hasExtra(EXTRA_DEST_LON) }
                        ?.getDoubleExtra(EXTRA_DEST_LON, 0.0)
                    beginTrip(auto = false)
                }
            }
            ACTION_END_TRIP -> endTrip()
            ACTION_TRANSITION -> handleTransition(intent)
        }

        ensureLocationUpdates()
        registerActivityTransitions()
        return START_STICKY
    }

    private fun beginTrip(auto: Boolean) {
        autoStarted = auto
        origin = null
        awayFromOrigin = false
        consecutiveFastFixes = 0
        pendingStopAtMs = null
        lastMovingMs = System.currentTimeMillis()
        _stats.value = TripStats(startTimeMs = System.currentTimeMillis())
        ensureLocationUpdates()
        startMotionSensors()
        updateNotification()
    }

    private fun endTrip() {
        val stats = _stats.value ?: return
        stopMotionSensors()
        flushTrace()
        val worthSaving =
            if (autoStarted) stats.distanceMeters >= MIN_AUTO_TRIP_METERS
            else stats.durationMs > 0
        if (worthSaving) {
            TripStore.save(
                this,
                Trip(
                    startTimeMs = stats.startTimeMs,
                    endTimeMs = System.currentTimeMillis(),
                    distanceMeters = stats.distanceMeters,
                    topSpeedMps = stats.topSpeedMps,
                    maxLeanAngleDeg = stats.maxLeanAngleDeg,
                    maxGForce = stats.maxGForce,
                    destinationLat = destLat,
                    destinationLon = destLon,
                ),
            )
            SyncClient.syncQuietly(this)
        }
        _stats.value = null
        destLat = null
        destLon = null
        autoStarted = false
        pendingStopAtMs = null
        ensureLocationUpdates()
        updateNotification()
    }

    /** (Re)request location updates matching the current mode. */
    private fun ensureLocationUpdates() {
        val tripMode = _stats.value != null
        if (requestedTripMode == tripMode) return
        fusedClient.removeLocationUpdates(locationCallback)
        val request =
            if (tripMode) {
                LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
                    .setMinUpdateIntervalMillis(500L)
                    .build()
            } else {
                LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 20_000L)
                    .setMinUpdateDistanceMeters(30f)
                    .build()
            }
        try {
            fusedClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
            requestedTripMode = tripMode
        } catch (e: SecurityException) {
            stopSelf()
        }
    }

    private fun registerActivityTransitions() {
        if (transitionsRegistered) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.ACTIVITY_RECOGNITION,
            ) != PackageManager.PERMISSION_GRANTED
        ) return

        val transitions = listOf(
            ActivityTransition.Builder()
                .setActivityType(DetectedActivity.IN_VEHICLE)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                .build(),
            ActivityTransition.Builder()
                .setActivityType(DetectedActivity.IN_VEHICLE)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_EXIT)
                .build(),
        )
        val pendingIntent = PendingIntent.getForegroundService(
            this, 1,
            Intent(this, TripTrackingService::class.java).setAction(ACTION_TRANSITION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        try {
            ActivityRecognition.getClient(this)
                .requestActivityTransitionUpdates(
                    ActivityTransitionRequest(transitions), pendingIntent)
                .addOnSuccessListener { transitionsRegistered = true }
        } catch (e: SecurityException) {
            // No activity recognition permission; speed fallback still works.
        }
    }

    private fun handleTransition(intent: Intent) {
        val result = ActivityTransitionResult.extractResult(intent) ?: return
        for (event in result.transitionEvents) {
            if (event.activityType != DetectedActivity.IN_VEHICLE) continue
            when (event.transitionType) {
                ActivityTransition.ACTIVITY_TRANSITION_ENTER -> {
                    pendingStopAtMs = null
                    if (_stats.value == null && Settings.autoDetectDrives.value) {
                        beginTrip(auto = true)
                    }
                }
                ActivityTransition.ACTIVITY_TRANSITION_EXIT -> {
                    // Don't end immediately — could be a fuel stop. Grace
                    // period is checked against speed in onLocation.
                    if (_stats.value != null && autoStarted) {
                        pendingStopAtMs = System.currentTimeMillis()
                    }
                }
            }
        }
    }

    private fun onLocation(location: Location) {
        val speed = speedOf(location)
        _lastFix.value = Fix(
            lat = location.latitude,
            lon = location.longitude,
            speedMps = speed,
            bearingDeg = if (location.hasBearing()) location.bearing else null,
            accuracyMeters = location.accuracy,
            timeMs = location.time,
        )
        val stats = _stats.value
        if (stats == null) {
            onIdleLocation(location, speed)
        } else {
            onTripLocation(location, speed, stats)
        }
        lastLocation = location
    }

    /** Idle mode: extend the explored trace, watch for a drive starting. */
    private fun onIdleLocation(location: Location, speed: Double) {
        if (location.accuracy <= 50f) {
            addTracePoint(LatLon(location.latitude, location.longitude))
        }
        if (speed >= FAST_SPEED_MPS && Settings.autoDetectDrives.value) {
            consecutiveFastFixes++
            if (consecutiveFastFixes >= FAST_FIXES_TO_START) beginTrip(auto = true)
        } else {
            consecutiveFastFixes = 0
        }
    }

    private fun onTripLocation(location: Location, speed: Double, stats: TripStats) {
        val now = System.currentTimeMillis()

        var distance = stats.distanceMeters
        val last = lastLocation
        // Only accumulate distance for accurate, recent fixes to avoid GPS jumps.
        if (last != null && location.accuracy <= 50f &&
            location.time - last.time in 1..15_000
        ) {
            distance += last.distanceTo(location).toDouble()
        }

        if (location.accuracy <= 50f) {
            val p = LatLon(location.latitude, location.longitude)
            addTracePoint(p)

            // Auto-stop when back at the starting point after a real trip.
            if (origin == null) origin = p
            origin?.let { start ->
                val fromStart = RoadRoulette.distanceMeters(p, start)
                if (fromStart > 400) awayFromOrigin = true
                if (awayFromOrigin && fromStart < 120 &&
                    now - stats.startTimeMs > 5 * 60_000
                ) {
                    notifyTripEnded()
                    endTrip()
                    return
                }
            }
        }

        if (speed > 2.0) lastMovingMs = now

        // Left the vehicle and stayed slow through the grace period: trip over.
        pendingStopAtMs?.let { exitedAt ->
            if (speed > 5.0) {
                pendingStopAtMs = null
            } else if (now - exitedAt > EXIT_GRACE_MS) {
                notifyTripEnded()
                endTrip()
                return
            }
        }
        // Fallback if the vehicle-exit event never arrives.
        if (autoStarted && now - lastMovingMs > STATIONARY_END_MS) {
            notifyTripEnded()
            endTrip()
            return
        }

        _stats.value = stats.copy(
            durationMs = now - stats.startTimeMs,
            distanceMeters = distance,
            currentSpeedMps = speed,
            topSpeedMps = maxOf(stats.topSpeedMps, speed),
        )
    }

    private fun speedOf(location: Location): Double {
        if (location.hasSpeed()) return location.speed.toDouble()
        // Coarse fixes often lack speed; derive it from the previous fix.
        val last = lastLocation ?: return 0.0
        val dtSec = (location.time - last.time) / 1000.0
        if (dtSec !in 1.0..120.0) return 0.0
        return last.distanceTo(location) / dtSec
    }

    /** Trace for the fog-of-war map, decimated to ~25 m spacing. */
    private fun addTracePoint(p: LatLon) {
        val lastTrace = tracePoints.lastOrNull()
        if (lastTrace != null) {
            val gap = RoadRoulette.distanceMeters(lastTrace, p)
            if (gap < 25.0) return
            // Big jump (location off for a while): close this segment first.
            if (gap > 500.0) flushTrace()
        }
        tracePoints.add(p)
        if (tracePoints.size >= 200) flushTrace(keepLast = true)
        _liveTrace.value = tracePoints.toList()
    }

    private fun flushTrace(keepLast: Boolean = false) {
        TraceStore.append(this, tracePoints)
        val last = tracePoints.lastOrNull()
        tracePoints.clear()
        if (keepLast && last != null) tracePoints.add(last)
        _liveTrace.value = tracePoints.toList()
    }

    override fun onDestroy() {
        if (::fusedClient.isInitialized) {
            fusedClient.removeLocationUpdates(locationCallback)
        }
        endTrip()
        flushTrace()
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
            .setContentText("Trip ended — saved to history.")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(2, notification)
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): android.app.Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val text =
            if (_stats.value != null) "Tracking your drive…"
            else "Watching for trips"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Map Roulette")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .build()
    }
}
