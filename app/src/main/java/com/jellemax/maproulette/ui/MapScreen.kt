package com.jellemax.maproulette.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.graphics.Paint as AndroidPaint
import java.io.IOException
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LocationSearching
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TwoWheeler
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jellemax.maproulette.R
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.jellemax.maproulette.data.ExploredArea
import com.jellemax.maproulette.data.GeocodeResult
import com.jellemax.maproulette.data.Geocoder
import com.jellemax.maproulette.data.LatLon
import com.jellemax.maproulette.data.NavEngine
import com.jellemax.maproulette.data.PoiKind
import com.jellemax.maproulette.data.PoiRoulette
import com.jellemax.maproulette.data.RecentSearchStore
import com.jellemax.maproulette.data.RoadRoulette
import com.jellemax.maproulette.data.RoundTripPlanner
import com.jellemax.maproulette.data.RouteResult
import com.jellemax.maproulette.data.RoutingServer
import com.jellemax.maproulette.data.ServerConfig
import com.jellemax.maproulette.data.Settings
import com.jellemax.maproulette.data.SyncClient
import com.jellemax.maproulette.data.TraceStore
import com.jellemax.maproulette.data.TravelMode
import com.jellemax.maproulette.tracking.TripStats
import com.jellemax.maproulette.tracking.TripTrackingService
import com.jellemax.maproulette.wear.NavRelay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import kotlin.random.Random
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline

private val DIRECTION_NAMES = listOf("North", "North-east", "East", "South-east",
    "South", "South-west", "West", "North-west")

private val TravelMode.icon: ImageVector
    get() = when (this) {
        TravelMode.BIKE -> Icons.AutoMirrored.Filled.DirectionsBike
        TravelMode.MOTO -> Icons.Default.TwoWheeler
        TravelMode.CAR -> Icons.Default.DirectionsCar
    }

/** Exponentially smooths a compass bearing toward [target], taking the
 *  shortest way round the 0/360 wrap, so heading-up rotation eases instead
 *  of snapping to each noisy raw GPS fix. */
private fun smoothBearing(current: Float?, target: Float, alpha: Float = 0.3f): Float {
    if (current == null) return target
    var delta = (target - current) % 360f
    if (delta > 180f) delta -= 360f
    if (delta < -180f) delta += 360f
    return (current + delta * alpha + 360f) % 360f
}

/** One spin result awaiting a pick; [route] is null when the routing server
 *  couldn't be reached — the card then shows straight-line distance only. */
private data class RouteCandidate(
    val destination: LatLon,
    val name: String?,
    val route: RouteResult?,
    val straightLineMeters: Double,
)

/** Picks one destination candidate and eagerly routes to it, so the card list
 *  can show real road distance/ETA instead of a straight line. */
private suspend fun pickCandidate(
    config: ServerConfig,
    loc: LatLon,
    radiusMeters: Double,
    minRadiusMeters: Double,
    mode: TravelMode,
    poiKind: PoiKind,
    bearing: Double?,
    explored: ExploredArea,
): RouteCandidate {
    val (dest, name) = if (poiKind != PoiKind.ROAD) {
        val poi = PoiRoulette.randomPoi(loc, radiusMeters, poiKind, bearing, explored, minRadiusMeters)
        poi.location to poi.name
    } else {
        // Own server snaps a random point to a road reachable in this mode's
        // profile; Overpass fallback below.
        val server = if (config.usable) {
            try {
                RoutingServer.randomRoadDestination(
                    config, loc, radiusMeters, bearing, explored, mode.ghProfile, minRadiusMeters)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        } else null
        val d = server ?: RoadRoulette.randomRoadPoint(
            loc, radiusMeters, mode.highwayRegex, bearing, explored, minRadiusMeters)
        d to null
    }
    val route = try {
        RoutingServer.route(config, loc, dest, mode.ghProfile, Settings.avoidHighways.value)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
    return RouteCandidate(
        destination = dest,
        name = name,
        route = route,
        straightLineMeters = RoadRoulette.distanceMeters(loc, dest),
    )
}

@Composable
fun MapScreen(onOpenHistory: () -> Unit, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var mode by rememberSaveable { mutableStateOf(TravelMode.CAR) }
    var radiusKm by rememberSaveable { mutableFloatStateOf(TravelMode.CAR.defaultKm) }
    var minRadiusKm by rememberSaveable { mutableFloatStateOf(0f) }
    var candidates by remember { mutableStateOf<List<RouteCandidate>>(emptyList()) }
    var myLocation by remember { mutableStateOf<LatLon?>(null) }
    var destination by remember { mutableStateOf<LatLon?>(null) }
    var route by remember { mutableStateOf<RouteResult?>(null) }
    var spinning by remember { mutableStateOf(false) }
    var spinJob by remember { mutableStateOf<Job?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val serverConfig = remember { RoutingServer.load(context) }
    var poiKind by rememberSaveable { mutableStateOf(PoiKind.ROAD) }
    var directionDeg by rememberSaveable { mutableStateOf<Float?>(null) }
    var destinationName by remember { mutableStateOf<String?>(null) }
    var fogEnabled by rememberSaveable { mutableStateOf(false) }
    var searchOpen by remember { mutableStateOf(false) }
    // Stored traces reload on every store write; the live trace and fix come
    // straight from the tracking service, so fog and position update in real
    // time instead of only when a trip is saved.
    val storeVersion by TraceStore.version.collectAsStateWithLifecycle()
    val traces = remember(storeVersion) {
        TraceStore.loadAll(context).map { trace -> trace.map { GeoPoint(it.lat, it.lon) } }
    }
    val stats by TripTrackingService.stats.collectAsStateWithLifecycle()
    val liveFix by TripTrackingService.lastFix.collectAsStateWithLifecycle()
    val liveTrace by TripTrackingService.liveTrace.collectAsStateWithLifecycle()

    var navigating by remember { mutableStateOf(false) }
    var navProgress by remember { mutableStateOf<NavEngine.Progress?>(null) }
    var rerouting by remember { mutableStateOf(false) }
    var lastRerouteMs by remember { mutableLongStateOf(0L) }
    var lastCenteredPos by remember { mutableStateOf<LatLon?>(null) }
    var followMe by remember { mutableStateOf(false) }
    var settingsCollapsed by rememberSaveable { mutableStateOf(false) }
    var ambientSpeedLimitKmh by remember { mutableStateOf<Double?>(null) }
    var lastSpeedLimitQueryPos by remember { mutableStateOf<LatLon?>(null) }
    var lastSpeedLimitQueryMs by remember { mutableLongStateOf(0L) }
    var speedLimitMisses by remember { mutableIntStateOf(0) }
    // Raw GPS bearing jitters between fixes; smoothed heading feeds mapOrientation
    // so heading-up rotation doesn't snap every ~500ms-1s update.
    var smoothedBearingDeg by remember { mutableStateOf<Float?>(null) }

    LaunchedEffect(liveFix) {
        liveFix?.takeIf { it.accuracyMeters <= 100f }?.let {
            myLocation = LatLon(it.lat, it.lon)
        }
    }

    // Keep the min-distance floor from exceeding the radius as the slider moves.
    LaunchedEffect(radiusKm) {
        if (minRadiusKm > radiusKm) minRadiusKm = radiusKm
    }

    // Pull from the sync server on launch: restores everything after a
    // reinstall and picks up trips recorded while the app was closed.
    LaunchedEffect(Unit) {
        if (SyncClient.configured) {
            withContext(Dispatchers.IO) {
                try {
                    SyncClient.sync(context)
                } catch (e: Exception) {
                    // offline or server down; next launch catches up
                }
            }
        }
    }

    // CARTO basemaps (retina): clean modern cartography, light + dark variant.
    val themePref by Settings.theme.collectAsStateWithLifecycle()
    val darkTheme = isAppDarkTheme(themePref)
    val fogRadius by Settings.fogRadiusMeters.collectAsStateWithLifecycle()
    val tileSource = remember(darkTheme) {
        val style = if (darkTheme) "dark_all" else "rastertiles/voyager"
        XYTileSource(
            if (darkTheme) "CartoDarkMatter" else "CartoVoyager",
            0, 20, 512, "@2x.png",
            arrayOf(
                "https://a.basemaps.cartocdn.com/$style/",
                "https://b.basemaps.cartocdn.com/$style/",
                "https://c.basemaps.cartocdn.com/$style/",
            ),
            "© OpenStreetMap contributors © CARTO",
        )
    }
    val mapView = remember {
        MapView(context).apply {
            setTileSource(tileSource)
            setMultiTouchControls(true)
            controller.setZoom(6.0)
        }
    }
    LaunchedEffect(tileSource) { mapView.setTileSource(tileSource) }
    DisposableEffect(Unit) {
        mapView.onResume()
        onDispose {
            mapView.onPause()
            mapView.onDetach()
        }
    }

    fun fetchLocation() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) return
        scope.launch {
            try {
                val client = LocationServices.getFusedLocationProviderClient(context)
                val loc = client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null).await()
                    ?: client.lastLocation.await()
                if (loc != null) {
                    myLocation = LatLon(loc.latitude, loc.longitude)
                    mapView.controller.setZoom(12.0)
                    mapView.controller.setCenter(GeoPoint(loc.latitude, loc.longitude))
                } else {
                    error = "Could not get location; is GPS on?"
                }
            } catch (e: SecurityException) {
                error = "Location permission missing"
            }
        }
    }

    // Background location must be requested separately from fine location,
    // after it is granted (system requirement on Android 11+).
    val bgLocationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    fun onLocationGranted() {
        fetchLocation()
        TripTrackingService.startMonitoring(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            bgLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            onLocationGranted()
        } else {
            error = "Location permission is required"
        }
    }

    LaunchedEffect(Unit) {
        val needed = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(Manifest.permission.ACTIVITY_RECOGNITION)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        val missing = needed.any {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (!missing) {
            onLocationGranted()
        } else {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    // Redraw overlays whenever location, radius, destination, or route changes.
    LaunchedEffect(myLocation, destination, route, radiusKm, mode, directionDeg,
        fogEnabled, fogRadius, traces, liveTrace, navigating) {
        mapView.overlays.clear()
        if (!navigating) {
            // Long-press drops a destination pin anywhere.
            mapView.overlays.add(MapEventsOverlay(object : MapEventsReceiver {
                override fun singleTapConfirmedHelper(p: GeoPoint?) = false
                override fun longPressHelper(p: GeoPoint?): Boolean {
                    if (p == null) return false
                    destination = LatLon(p.latitude, p.longitude)
                    destinationName = "Dropped pin"
                    route = null
                    return true
                }
            }))
        }
        if (fogEnabled) {
            val live = liveTrace.map { GeoPoint(it.lat, it.lon) }
            val all = if (live.size >= 2) traces + listOf(live) else traces
            mapView.overlays.add(FogOverlay(
                tracesProvider = { all },
                currentLocationProvider = {
                    myLocation?.let { GeoPoint(it.lat, it.lon) }
                },
                corridorMeters = fogRadius,
            ))
        }
        val loc = myLocation
        if (loc != null) {
            val center = GeoPoint(loc.lat, loc.lon)
            val reachMeters = if (mode.roundTrip) radiusKm * 250.0 else radiusKm * 1000.0
            if (!navigating) {
                mapView.overlays.add(Polygon(mapView).apply {
                    // For round trips the slider is trip length; reach ≈ length / 4.
                    points = Polygon.pointsAsCircle(center, reachMeters)
                    outlinePaint.color = AndroidColor.argb(180, 33, 150, 243)
                    outlinePaint.strokeWidth = 3f
                    fillPaint.color = AndroidColor.argb(24, 33, 150, 243)
                })
                directionDeg?.let { dir ->
                    // Wedge showing the chosen spin direction (±45°).
                    val arc = (-45..45 step 5).map { d ->
                        GeoPoint(loc.lat, loc.lon).destinationPoint(
                            reachMeters, (dir + d).toDouble())
                    }
                    mapView.overlays.add(Polygon(mapView).apply {
                        points = listOf(center) + arc + center
                        outlinePaint.color = AndroidColor.argb(150, 255, 152, 0)
                        outlinePaint.strokeWidth = 2f
                        fillPaint.color = AndroidColor.argb(28, 255, 152, 0)
                    })
                }
            }
            mapView.overlays.add(Marker(mapView).apply {
                position = center
                icon = ContextCompat.getDrawable(context, R.drawable.ic_map_dot)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                title = "You are here"
            })
        }
        val dest = destination
        if (dest != null) {
            mapView.overlays.add(Marker(mapView).apply {
                position = GeoPoint(dest.lat, dest.lon)
                icon = ContextCompat.getDrawable(context, R.drawable.ic_map_pin)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                title = "Destination"
            })
        }
        val loop = route
        if (loop != null) {
            val points = loop.polyline.map { GeoPoint(it.lat, it.lon) }
            // Dark casing under the colored line so the route reads clearly over any basemap.
            mapView.overlays.add(Polyline(mapView).apply {
                setPoints(points)
                outlinePaint.color = AndroidColor.argb(200, 0, 0, 0)
                outlinePaint.strokeWidth = 16f
                outlinePaint.strokeCap = AndroidPaint.Cap.ROUND
            })
            mapView.overlays.add(Polyline(mapView).apply {
                setPoints(points)
                outlinePaint.color = AndroidColor.argb(255, 233, 30, 99)
                outlinePaint.strokeWidth = 10f
                outlinePaint.strokeCap = AndroidPaint.Cap.ROUND
            })
        }
        mapView.invalidate()
    }

    fun stopNavigation() {
        navigating = false
        navProgress = null
        mapView.mapOrientation = 0f
        smoothedBearingDeg = null
        mapView.invalidate()
        NavRelay.clear(context)
    }

    fun startNavigation() {
        val loc = myLocation ?: run {
            error = "Waiting for your location…"
            return
        }
        if (stats == null) {
            TripTrackingService.start(context, destination?.lat, destination?.lon)
        }
        error = null
        val dest = destination
        if (dest == null) {
            // Round trip: the spin already fetched the loop with instructions.
            if (route?.instructions?.isNotEmpty() == true) {
                navigating = true
            } else {
                error = "No turn data for this loop — spin again with the routing server reachable"
            }
            return
        }
        rerouting = true
        scope.launch {
            try {
                route = withContext(Dispatchers.IO) {
                    RoutingServer.route(serverConfig, loc, dest, mode.ghProfile,
                        Settings.avoidHighways.value)
                }
                navigating = true
            } catch (e: Exception) {
                error = "Navigation failed: ${e.message}"
            } finally {
                rerouting = false
            }
        }
    }

    // Ambient speed-limit badge while just driving (not navigating): throttled
    // so we don't hammer Overpass every GPS fix — requery only after moving
    // ~150m or 20s, matching NavEngine's own speedLimitKmh via GraphHopper.
    // Keyed on `navigating` only (not `liveFix`) and collecting the fix flow
    // ourselves: keying on liveFix restarted this effect on every GPS update
    // (every ~0.5-1s), which cancelled the in-flight Overpass request before
    // a slower mirror could ever answer — the badge could go stale for as
    // long as the network round-trip kept losing that race.
    LaunchedEffect(navigating) {
        if (navigating) return@LaunchedEffect
        TripTrackingService.lastFix.collect { fix ->
            fix ?: return@collect
            if (fix.speedMps < 2.0) return@collect
            val pos = LatLon(fix.lat, fix.lon)
            val moved = lastSpeedLimitQueryPos?.let { RoadRoulette.distanceMeters(it, pos) }
                ?: Double.MAX_VALUE
            val now = System.currentTimeMillis()
            if (moved < 150.0 && now - lastSpeedLimitQueryMs < 20_000) return@collect
            lastSpeedLimitQueryPos = pos
            lastSpeedLimitQueryMs = now
            val result = withContext(Dispatchers.IO) { RoadRoulette.nearestSpeedLimitKmh(pos) }
            if (result != null) {
                ambientSpeedLimitKmh = result
                speedLimitMisses = 0
            } else if (++speedLimitMisses >= 3) {
                // One missed lookup is usually a gap in tagged ways or a network
                // blip, not the limit actually ending — only clear the sign after
                // a few in a row so it doesn't flicker away and back.
                ambientSpeedLimitKmh = null
            }
        }
    }

    // Recenter on the rider while driving with no destination set — same jitter
    // guard as turn-by-turn, but no route progress/reroute logic.
    LaunchedEffect(followMe, navigating, liveFix) {
        if (!followMe || navigating) return@LaunchedEffect
        val fix = liveFix ?: return@LaunchedEffect
        val pos = LatLon(fix.lat, fix.lon)
        val moved = lastCenteredPos?.let { RoadRoulette.distanceMeters(it, pos) } ?: Double.MAX_VALUE
        if (moved > 3.0) {
            mapView.controller.animateTo(GeoPoint(pos.lat, pos.lon), mapView.zoomLevelDouble.coerceAtLeast(15.0), 350L)
            lastCenteredPos = pos
        }
        if (fix.bearingDeg != null && fix.speedMps > 2.0) {
            val smoothed = smoothBearing(smoothedBearingDeg, fix.bearingDeg)
            smoothedBearingDeg = smoothed
            mapView.mapOrientation = -smoothed
        }
    }

    // Follow the route while navigating: progress, camera, arrival, reroute.
    LaunchedEffect(navigating, liveFix, route) {
        if (!navigating) return@LaunchedEffect
        val fix = liveFix ?: return@LaunchedEffect
        val r = route ?: return@LaunchedEffect
        val pos = LatLon(fix.lat, fix.lon)
        val progress = NavEngine.progress(r, pos) ?: return@LaunchedEffect
        navProgress = progress
        NavRelay.send(context, progress, currentSpeedKmh = fix.speedMps * 3.6)

        // GPS fixes can arrive faster than a full-length animation completes (min
        // interval 500ms vs the old 800ms flight), which restarted the animation
        // mid-flight and looked choppy. A shorter flight avoids the overlap, and
        // skipping sub-3m moves ignores GPS jitter while stopped at a light.
        val moved = lastCenteredPos?.let { RoadRoulette.distanceMeters(it, pos) } ?: Double.MAX_VALUE
        if (moved > 3.0) {
            val zoom = NavEngine.cameraZoom(fix.speedMps, progress.distanceToTurnMeters)
            mapView.controller.animateTo(GeoPoint(pos.lat, pos.lon), zoom, 350L)
            lastCenteredPos = pos
        }
        if (fix.bearingDeg != null && fix.speedMps > 2.0) {
            // Heading-up while moving; osmdroid rotates counterclockwise.
            val smoothed = smoothBearing(smoothedBearingDeg, fix.bearingDeg)
            smoothedBearingDeg = smoothed
            mapView.mapOrientation = -smoothed
        }

        // Arrived (point-to-point; loops end back at the start on their own).
        if (destination != null && progress.remainingMeters < 40 &&
            progress.offRouteMeters < 60
        ) {
            stopNavigation()
            return@LaunchedEffect
        }

        // Off route → fresh route to the destination. Launched on the screen
        // scope so the next GPS fix doesn't cancel the request; loops keep
        // their drawn line (rerouting a loop would change the whole trip).
        val dest = destination
        val now = System.currentTimeMillis()
        if (dest != null && progress.offRouteMeters > 60 &&
            !rerouting && now - lastRerouteMs > 15_000
        ) {
            rerouting = true
            lastRerouteMs = now
            scope.launch {
                try {
                    route = withContext(Dispatchers.IO) {
                        RoutingServer.route(serverConfig, pos, dest, mode.ghProfile,
                            Settings.avoidHighways.value)
                    }
                } catch (e: Exception) {
                    // stay on the old line; retried after the cooldown
                } finally {
                    rerouting = false
                }
            }
        }
    }

    fun spin() {
        val loc = myLocation ?: run {
            error = "Waiting for your location…"
            fetchLocation()
            return
        }
        spinJob = scope.launch {
            spinning = true
            error = null
            var serverError: String? = null
            try {
                // Bias destinations toward territory the fog hasn't uncovered.
                val explored = withContext(Dispatchers.IO) { ExploredArea.load(context) }
                if (mode.roundTrip) {
                    // Prefer the self-hosted routing server (single fast request,
                    // real road-following loop); fall back to Overpass sampling.
                    val tripMeters = radiusKm * 1000.0
                    var result: RouteResult? = null
                    if (serverConfig.usable) {
                        result = try {
                            withContext(Dispatchers.IO) {
                                RoutingServer.roundTrip(
                                    serverConfig, loc, tripMeters, Random.nextLong(),
                                    headingDeg = directionDeg?.toDouble())
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            serverError = e.message ?: e.javaClass.simpleName
                            null // fall back to Overpass below, but say why
                        }
                    }
                    if (result == null) {
                        val wps = RoundTripPlanner.plan(
                            loc, tripMeters / 4.0, mode.highwayRegex,
                            bearingDeg = directionDeg?.toDouble())
                        result = RouteResult(
                            polyline = listOf(loc) + wps + loc,
                            waypoints = wps,
                            distanceMeters = null,
                        )
                        if (serverError != null) {
                            error = "Server route failed ($serverError) — approximate loop instead"
                        }
                    }
                    route = result
                    destination = null
                    destinationName = null
                    mapView.zoomToBoundingBox(
                        BoundingBox.fromGeoPoints(
                            (result.polyline + loc).map { GeoPoint(it.lat, it.lon) }
                        ).increaseByScale(1.3f),
                        true,
                    )
                } else {
                    val bearing = directionDeg?.toDouble()
                    val minMeters = minRadiusKm.toDouble() * 1000.0
                    val picks = withTimeout(30_000) {
                        coroutineScope {
                            (1..3).map {
                                async(Dispatchers.IO) {
                                    runCatching {
                                        pickCandidate(
                                            serverConfig, loc, radiusKm.toDouble() * 1000.0,
                                            minMeters, mode, poiKind, bearing, explored)
                                    }
                                }
                            }.awaitAll()
                        }
                    }
                    val results = picks.mapNotNull { it.getOrNull() }
                    if (results.isEmpty()) {
                        throw picks.firstNotNullOfOrNull { it.exceptionOrNull() }
                            ?: IOException("Failed to find a destination")
                    }
                    candidates = results
                    mapView.zoomToBoundingBox(
                        BoundingBox.fromGeoPoints(
                            (results.map { it.destination } + loc).map { GeoPoint(it.lat, it.lon) }
                        ).increaseByScale(1.4f),
                        true,
                    )
                }
            } catch (e: TimeoutCancellationException) {
                // Don't let a fallback timeout hide why the own server failed.
                error = serverError
                    ?.let { "Server route failed ($it); fallback timed out too" }
                    ?: if (mode.roundTrip && !serverConfig.usable) {
                        "No routing server configured — public servers timed out"
                    } else {
                        "Road servers are slow right now — try again"
                    }
            } catch (e: CancellationException) {
                throw e // user cancelled or screen left; finally still resets state
            } catch (e: Exception) {
                error = e.message ?: "Failed to find a road"
            } finally {
                spinning = false
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())

        if (navigating) {
            NavigationBanner(
                progress = navProgress,
                rerouting = rerouting,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(12.dp),
            )
        } else {
            liveFix?.takeIf { it.speedMps >= 2.0 }?.let { fix ->
                AmbientSpeedBadge(
                    speedKmh = fix.speedMps * 3.6,
                    limitKmh = ambientSpeedLimitKmh,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .statusBarsPadding()
                        .padding(16.dp),
                )
            }
            Column(
                Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SmallFloatingActionButton(onClick = { followMe = !followMe }) {
                    Icon(
                        if (followMe) Icons.Default.MyLocation else Icons.Default.LocationSearching,
                        contentDescription = if (followMe) "Stop following my location"
                            else "Follow my location",
                    )
                }
                SmallFloatingActionButton(onClick = { searchOpen = true }) {
                    Icon(Icons.Default.Search, contentDescription = "Search destination")
                }
                SmallFloatingActionButton(onClick = onOpenHistory) {
                    Icon(Icons.Default.History, contentDescription = "Trip history")
                }
                SmallFloatingActionButton(onClick = onOpenSettings) {
                    Icon(Icons.Default.Settings, contentDescription = "Settings")
                }
                SmallFloatingActionButton(onClick = { fogEnabled = !fogEnabled }) {
                    Icon(
                        if (fogEnabled) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                        contentDescription = "Fog of war",
                    )
                }
            }
        }

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            stats?.let { ActiveTripCard(it) }

            if (navigating) NavigationBottomBar(
                progress = navProgress,
                offRoute = (navProgress?.offRouteMeters ?: 0.0) > 60,
                onExit = { stopNavigation() },
            ) else if (candidates.isNotEmpty()) CandidatesCard(
                candidates = candidates,
                onPick = { c ->
                    destination = c.destination
                    destinationName = c.name
                    route = c.route
                    candidates = emptyList()
                    val loc = myLocation
                    if (loc != null) {
                        mapView.zoomToBoundingBox(
                            BoundingBox.fromGeoPoints(
                                listOf(GeoPoint(loc.lat, loc.lon),
                                    GeoPoint(c.destination.lat, c.destination.lon))
                            ).increaseByScale(1.4f),
                            true,
                        )
                    }
                },
                onReroll = { candidates = emptyList(); spin() },
                onCancel = { candidates = emptyList() },
            ) else if (settingsCollapsed) CollapsedSettingsBar(
                mode = mode,
                radiusKm = radiusKm,
                onExpand = { settingsCollapsed = false },
            ) else Card(
                shape = MaterialTheme.shapes.extraLarge,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        IconButton(
                            onClick = { settingsCollapsed = true },
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(Icons.Default.ExpandMore, contentDescription = "Minimize")
                        }
                    }
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        TravelMode.entries.forEachIndexed { index, m ->
                            SegmentedButton(
                                selected = mode == m,
                                onClick = {
                                    mode = m
                                    radiusKm = m.defaultKm
                                    minRadiusKm = 0f
                                    destination = null
                                    destinationName = null
                                    route = null
                                    candidates = emptyList()
                                },
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = index, count = TravelMode.entries.size,
                                ),
                                icon = {
                                    Icon(m.icon, contentDescription = null,
                                        Modifier.size(SegmentedButtonDefaults.IconSize))
                                },
                                label = { Text(m.label, maxLines = 1) },
                            )
                        }
                    }

                    error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall)
                    }

                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            if (mode.roundTrip) "Trip length" else "Radius",
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Text(
                            if (mode.maxKm <= 10f) "%.1f km".format(radiusKm)
                            else "${radiusKm.toInt()} km",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    route?.distanceMeters?.let {
                        Text(
                            "Loop found: ${formatDistanceKm(it)}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    destinationName?.let {
                        Text("→ $it", style = MaterialTheme.typography.bodySmall)
                    }
                    Slider(
                        value = radiusKm,
                        onValueChange = { radiusKm = it },
                        valueRange = mode.minKm..mode.maxKm,
                    )

                    if (!mode.roundTrip) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text("Min distance", style = MaterialTheme.typography.labelLarge)
                            Text(
                                if (minRadiusKm <= 0f) "Off"
                                else if (mode.maxKm <= 10f) "%.1f km".format(minRadiusKm)
                                else "${minRadiusKm.toInt()} km",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        Slider(
                            value = minRadiusKm,
                            onValueChange = { minRadiusKm = it },
                            valueRange = 0f..radiusKm,
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!mode.roundTrip) {
                            SelectorChip(
                                icon = Icons.Default.Place,
                                label = poiKind.label,
                                options = PoiKind.entries.map { it.label },
                                onSelect = { poiKind = PoiKind.entries[it] },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        SelectorChip(
                            icon = Icons.Default.Explore,
                            label = directionDeg?.let { DIRECTION_NAMES[(it / 45f).toInt()] }
                                ?: "Any direction",
                            options = listOf("Any direction") + DIRECTION_NAMES,
                            onSelect = { i ->
                                directionDeg = if (i == 0) null else (i - 1) * 45f
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }

                    Button(
                        onClick = { if (spinning) spinJob?.cancel() else spin() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                    ) {
                        if (spinning) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Casino, contentDescription = null,
                                Modifier.size(20.dp))
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(
                            if (spinning) "Cancel" else "Spin",
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NavButton(
                            destination = destination,
                            route = route?.waypoints,
                            origin = myLocation,
                            mode = mode,
                            inAppAvailable = serverConfig.usable &&
                                (destination != null ||
                                    route?.instructions?.isNotEmpty() == true),
                            onNavigateInApp = { startNavigation() },
                            onNavigate = {
                                // Heading out = start tracking automatically.
                                if (stats == null) {
                                    TripTrackingService.start(
                                        context, destination?.lat, destination?.lon)
                                }
                            },
                            modifier = Modifier.weight(1f),
                        )
                        if (stats == null) {
                            OutlinedButton(
                                onClick = {
                                    TripTrackingService.start(
                                        context, destination?.lat, destination?.lon)
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null,
                                    Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Track", maxLines = 1)
                            }
                        } else {
                            Button(
                                onClick = { TripTrackingService.stop(context) },
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(Icons.Default.Stop, contentDescription = null,
                                    Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("End trip", maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
    }

    if (searchOpen) {
        SearchDialog(
            near = myLocation,
            onPick = { r ->
                searchOpen = false
                destination = r.location
                destinationName = r.name
                route = null
                mapView.controller.animateTo(
                    GeoPoint(r.location.lat, r.location.lon), 14.0, 800L)
            },
            onDismiss = { searchOpen = false },
        )
    }
}

/** Address/place search; a picked result becomes the destination. */
@Composable
private fun SearchDialog(
    near: LatLon?,
    onPick: (GeocodeResult) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<GeocodeResult>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val recents = remember { RecentSearchStore.load(context) }

    fun pick(r: GeocodeResult) {
        RecentSearchStore.save(context, r)
        onPick(r)
    }

    fun run() {
        if (query.isBlank() || searching) return
        searching = true
        error = null
        scope.launch {
            try {
                // Explicit search: unbounded, so the user can still reach places far from home.
                results = withContext(Dispatchers.IO) { Geocoder.search(query, near) }
                if (results.isEmpty()) error = "No results"
            } catch (e: Exception) {
                error = e.message ?: "Search failed"
            }
            searching = false
        }
    }

    // Live suggestions as the user types, debounced so we don't hammer Nominatim per keystroke.
    // Recent picks that match are surfaced first; the network lookup is bounded to nearby places
    // so a short query like "kru" doesn't suggest a same-named place on the other side of the world.
    LaunchedEffect(query) {
        if (query.isBlank()) {
            results = recents
            error = null
            return@LaunchedEffect
        }
        val recentMatches = recents.filter { it.name.contains(query, ignoreCase = true) }
        if (query.length < 3) {
            results = recentMatches
            error = null
            return@LaunchedEffect
        }
        results = recentMatches
        delay(400)
        searching = true
        error = null
        try {
            val nearby = withContext(Dispatchers.IO) { Geocoder.search(query, near, bounded = near != null) }
            val fresh = nearby.filter { hit -> recentMatches.none { it.name == hit.name } }
            results = recentMatches + fresh
            if (results.isEmpty()) error = "No results"
        } catch (e: Exception) {
            error = e.message ?: "Search failed"
        }
        searching = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Where to?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Address or place") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { run() }),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (searching) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                }
                results.forEach { r ->
                    Text(
                        r.name,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { pick(r) }
                            .padding(vertical = 10.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { run() }, enabled = !searching) { Text("Search") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/** Compact dropdown selector for destination kind and direction. */
@Composable
private fun SelectorChip(
    icon: ImageVector,
    label: String,
    options: List<String>,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Icon(icon, contentDescription = null, Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(label, maxLines = 1)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEachIndexed { i, option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        open = false
                        onSelect(i)
                    },
                )
            }
        }
    }
}

/** Spin results awaiting a pick: distance/ETA per candidate, tap one to commit to it. */
@Composable
private fun CandidatesCard(
    candidates: List<RouteCandidate>,
    onPick: (RouteCandidate) -> Unit,
    onReroll: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Pick a destination", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold)
            candidates.forEachIndexed { index, c ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onPick(c) }
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(
                            c.name ?: "Option ${index + 1}",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        val distanceMeters = c.route?.distanceMeters ?: c.straightLineMeters
                        val distanceLabel = (if (c.route?.distanceMeters == null) "~" else "") +
                            formatDistanceKm(distanceMeters)
                        val etaLabel = c.route?.timeMs?.let { " · %.0f min".format(it / 60_000.0) } ?: ""
                        Text(
                            distanceLabel + etaLabel,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(Icons.Default.Navigation, contentDescription = "Choose this destination")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
                    Text("Cancel")
                }
                Button(onClick = onReroll, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Casino, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Reroll")
                }
            }
        }
    }
}

/** Slim stand-in for the settings card while just cruising — tap to expand. */
@Composable
private fun CollapsedSettingsBar(
    mode: TravelMode,
    radiusKm: Float,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onExpand() },
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(mode.icon, contentDescription = null)
                Text(
                    "${mode.label} · ${if (mode.maxKm <= 10f) "%.1f".format(radiusKm) else radiusKm.toInt()} km",
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            Icon(Icons.Default.ExpandLess, contentDescription = "Expand")
        }
    }
}

/** Current-speed readout with a color cue against the nearest road's posted
 *  limit, shown while driving without an active route (mirrors the watch's
 *  color-cue treatment instead of duplicating the nav banner's full sign). */
@Composable
private fun AmbientSpeedBadge(speedKmh: Double, limitKmh: Double?, modifier: Modifier = Modifier) {
    val speeding = limitKmh?.let { speedKmh > it + 5 } ?: false
    Card(
        modifier = modifier,
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Crossfade(targetState = limitKmh, animationSpec = tween(300), label = "speedLimit") {
                SpeedLimitSign(it)
            }
            Text(
                "%.0f".format(speedKmh),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = if (speeding) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/** "Go" button with a chooser for the navigation app. */
@Composable
private fun NavButton(
    destination: LatLon?,
    route: List<LatLon>?,
    origin: LatLon?,
    mode: TravelMode,
    inAppAvailable: Boolean,
    onNavigateInApp: () -> Unit,
    onNavigate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    Box(modifier) {
        FilledTonalButton(
            onClick = { menuOpen = true },
            enabled = destination != null || (route != null && origin != null),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.Navigation, contentDescription = null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Go", maxLines = 1)
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            if (inAppAvailable) {
                DropdownMenuItem(
                    text = { Text("Navigate in app") },
                    onClick = {
                        menuOpen = false
                        onNavigateInApp()
                    },
                )
            }
            if (route != null && origin != null) {
                // Waze can't take multi-waypoint routes; Google Maps only.
                DropdownMenuItem(
                    text = { Text("Google Maps (round trip)") },
                    onClick = {
                        menuOpen = false
                        onNavigate()
                        navigateRoundTrip(context, origin, route)
                    },
                )
            } else {
                DropdownMenuItem(
                    text = { Text("Google Maps") },
                    onClick = {
                        menuOpen = false
                        onNavigate()
                        destination?.let { navigateGoogleMaps(context, it, mode) }
                    },
                )
                DropdownMenuItem(
                    text = { Text("Waze") },
                    onClick = {
                        menuOpen = false
                        onNavigate()
                        destination?.let { navigateWaze(context, it) }
                    },
                )
                DropdownMenuItem(
                    text = { Text("Other app") },
                    onClick = {
                        menuOpen = false
                        onNavigate()
                        destination?.let { navigateGeo(context, it) }
                    },
                )
            }
        }
    }
}

@Composable
private fun ActiveTripCard(stats: TripStats) {
    // Tick every second so duration counts up even without GPS updates.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(stats.startTimeMs) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            StatItem("Time", formatDuration(now - stats.startTimeMs))
            StatItem("Distance", formatDistanceKm(stats.distanceMeters))
            StatItem("Speed", formatSpeedKmh(stats.currentSpeedMps))
            StatItem("Top", formatSpeedKmh(stats.topSpeedMps))
        }
    }
}

@Composable
private fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}

private fun navigateRoundTrip(context: Context, origin: LatLon, waypoints: List<LatLon>) {
    // Directions URL: origin = destination = start, curvy roads as via points.
    // Google Maps supports up to 9 waypoints in this form.
    val wp = waypoints.joinToString("|") { "${it.lat},${it.lon}" }
    val uri = Uri.parse(
        "https://www.google.com/maps/dir/?api=1" +
            "&origin=${origin.lat},${origin.lon}" +
            "&destination=${origin.lat},${origin.lon}" +
            "&travelmode=driving" +
            "&waypoints=" + Uri.encode(wp)
    )
    context.startActivity(Intent(Intent.ACTION_VIEW, uri))
}

private fun navigateGoogleMaps(context: Context, dest: LatLon, mode: TravelMode) {
    val uri = Uri.parse("google.navigation:q=${dest.lat},${dest.lon}&mode=${mode.gmapsMode}")
    val intent = Intent(Intent.ACTION_VIEW, uri).setPackage("com.google.android.apps.maps")
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        navigateGeo(context, dest)
    }
}

private fun navigateWaze(context: Context, dest: LatLon) {
    try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse("waze://?ll=${dest.lat},${dest.lon}&navigate=yes"))
        )
    } catch (e: ActivityNotFoundException) {
        // Waze not installed: universal link opens install page or web.
        context.startActivity(
            Intent(Intent.ACTION_VIEW,
                Uri.parse("https://waze.com/ul?ll=${dest.lat},${dest.lon}&navigate=yes"))
        )
    }
}

private fun navigateGeo(context: Context, dest: LatLon) {
    context.startActivity(
        Intent(Intent.ACTION_VIEW,
            Uri.parse("geo:${dest.lat},${dest.lon}?q=${dest.lat},${dest.lon}"))
    )
}
