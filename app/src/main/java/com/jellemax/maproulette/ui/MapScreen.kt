package com.jellemax.maproulette.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TwoWheeler
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jellemax.maproulette.R
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.jellemax.maproulette.data.LatLon
import com.jellemax.maproulette.data.PoiKind
import com.jellemax.maproulette.data.PoiRoulette
import com.jellemax.maproulette.data.RoadRoulette
import com.jellemax.maproulette.data.RoundTripPlanner
import com.jellemax.maproulette.data.RouteResult
import com.jellemax.maproulette.data.RoutingServer
import com.jellemax.maproulette.data.Settings
import com.jellemax.maproulette.data.SyncClient
import com.jellemax.maproulette.data.TraceStore
import com.jellemax.maproulette.data.TravelMode
import com.jellemax.maproulette.tracking.TripStats
import com.jellemax.maproulette.tracking.TripTrackingService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import kotlin.random.Random
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline

private val DIRECTION_NAMES = listOf("North", "North-east", "East", "South-east",
    "South", "South-west", "West", "North-west")

private val TravelMode.icon: ImageVector
    get() = when (this) {
        TravelMode.WALK -> Icons.AutoMirrored.Filled.DirectionsWalk
        TravelMode.BIKE -> Icons.AutoMirrored.Filled.DirectionsBike
        TravelMode.MOTO -> Icons.Default.TwoWheeler
        TravelMode.CAR -> Icons.Default.DirectionsCar
    }

@Composable
fun MapScreen(onOpenHistory: () -> Unit, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var mode by rememberSaveable { mutableStateOf(TravelMode.CAR) }
    var radiusKm by rememberSaveable { mutableFloatStateOf(TravelMode.CAR.defaultKm) }
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
    var tracesVersion by remember { mutableStateOf(0) }
    val traces = remember(tracesVersion) {
        TraceStore.loadAll(context).map { trace -> trace.map { GeoPoint(it.lat, it.lon) } }
    }
    val stats by TripTrackingService.stats.collectAsStateWithLifecycle()
    // Reload explored traces when a trip ends.
    LaunchedEffect(stats == null) { if (stats == null) tracesVersion++ }

    // Pull from the sync server on launch: restores everything after a
    // reinstall and picks up trips recorded while the app was closed.
    LaunchedEffect(Unit) {
        if (SyncClient.configured) {
            val synced = withContext(Dispatchers.IO) {
                try {
                    SyncClient.sync(context)
                    true
                } catch (e: Exception) {
                    false // offline or server down; next launch catches up
                }
            }
            if (synced) tracesVersion++
        }
    }

    // CARTO basemaps (retina): clean modern cartography, light + dark variant.
    val themePref by Settings.theme.collectAsStateWithLifecycle()
    val darkTheme = when (themePref) {
        Settings.Theme.SYSTEM -> isSystemInDarkTheme()
        Settings.Theme.LIGHT -> false
        Settings.Theme.DARK -> true
    }
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
        fogEnabled, fogRadius, traces) {
        mapView.overlays.clear()
        if (fogEnabled) {
            mapView.overlays.add(FogOverlay(
                tracesProvider = { traces },
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
            mapView.overlays.add(Polyline(mapView).apply {
                setPoints(loop.polyline.map { GeoPoint(it.lat, it.lon) })
                outlinePaint.color = AndroidColor.argb(160, 233, 30, 99)
                outlinePaint.strokeWidth = 6f
            })
        }
        mapView.invalidate()
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
                    if (poiKind != PoiKind.ROAD) {
                        val poi = withTimeout(30_000) {
                            withContext(Dispatchers.IO) {
                                PoiRoulette.randomPoi(
                                    loc, radiusKm * 1000.0, poiKind, bearing)
                            }
                        }
                        destination = poi.location
                        destinationName = poi.name
                        route = null
                    } else {
                        val dest = withTimeout(30_000) {
                            withContext(Dispatchers.IO) {
                                // Car can use the own server (snap random point to road,
                                // reachability guaranteed); walk/bike need path/footway
                                // data the server doesn't import, so Overpass only.
                                var d: LatLon? = null
                                if (mode == TravelMode.CAR && serverConfig.usable) {
                                    d = try {
                                        RoutingServer.randomRoadDestination(
                                            serverConfig, loc, radiusKm * 1000.0, bearing)
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        serverError = e.message
                                        null
                                    }
                                }
                                d ?: RoadRoulette.randomRoadPoint(
                                    loc, radiusKm * 1000.0, mode.highwayRegex, bearing)
                            }
                        }
                        destination = dest
                        destinationName = null
                        route = null
                    }
                    val dest = destination!!
                    mapView.zoomToBoundingBox(
                        BoundingBox.fromGeoPoints(
                            listOf(GeoPoint(loc.lat, loc.lon), GeoPoint(dest.lat, dest.lon))
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

        Column(
            Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
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

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            stats?.let { ActiveTripCard(it) }

            Card(
                shape = MaterialTheme.shapes.extraLarge,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        TravelMode.entries.forEachIndexed { index, m ->
                            SegmentedButton(
                                selected = mode == m,
                                onClick = {
                                    mode = m
                                    radiusKm = m.defaultKm
                                    destination = null
                                    destinationName = null
                                    route = null
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

/** "Go" button with a chooser for the navigation app. */
@Composable
private fun NavButton(
    destination: LatLon?,
    route: List<LatLon>?,
    origin: LatLon?,
    mode: TravelMode,
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
