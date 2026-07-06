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
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TwoWheeler
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
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.jellemax.maproulette.data.LatLon
import com.jellemax.maproulette.data.RoadRoulette
import com.jellemax.maproulette.data.RoundTripPlanner
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
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline

private val TravelMode.icon: ImageVector
    get() = when (this) {
        TravelMode.WALK -> Icons.AutoMirrored.Filled.DirectionsWalk
        TravelMode.BIKE -> Icons.AutoMirrored.Filled.DirectionsBike
        TravelMode.MOTO -> Icons.Default.TwoWheeler
        TravelMode.CAR -> Icons.Default.DirectionsCar
    }

@Composable
fun MapScreen(onOpenHistory: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var mode by rememberSaveable { mutableStateOf(TravelMode.CAR) }
    var radiusKm by rememberSaveable { mutableFloatStateOf(TravelMode.CAR.defaultKm) }
    var myLocation by remember { mutableStateOf<LatLon?>(null) }
    var destination by remember { mutableStateOf<LatLon?>(null) }
    var route by remember { mutableStateOf<List<LatLon>?>(null) }
    var spinning by remember { mutableStateOf(false) }
    var spinJob by remember { mutableStateOf<Job?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val stats by TripTrackingService.stats.collectAsStateWithLifecycle()

    val mapView = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(6.0)
        }
    }
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

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            fetchLocation()
        } else {
            error = "Location permission is required"
        }
    }

    LaunchedEffect(Unit) {
        val needed = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED
        ) {
            fetchLocation()
        } else {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    // Redraw overlays whenever location, radius, destination, or route changes.
    LaunchedEffect(myLocation, destination, route, radiusKm) {
        mapView.overlays.clear()
        val loc = myLocation
        if (loc != null) {
            val center = GeoPoint(loc.lat, loc.lon)
            mapView.overlays.add(Polygon(mapView).apply {
                points = Polygon.pointsAsCircle(center, radiusKm * 1000.0)
                outlinePaint.color = AndroidColor.argb(180, 33, 150, 243)
                outlinePaint.strokeWidth = 3f
                fillPaint.color = AndroidColor.argb(24, 33, 150, 243)
            })
            mapView.overlays.add(Marker(mapView).apply {
                position = center
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                title = "You are here"
            })
        }
        val dest = destination
        if (dest != null) {
            mapView.overlays.add(Marker(mapView).apply {
                position = GeoPoint(dest.lat, dest.lon)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                title = "Destination"
            })
        }
        val loop = route
        if (loop != null && loc != null) {
            // Indicative loop: straight lines between waypoints, not the route.
            mapView.overlays.add(Polyline(mapView).apply {
                setPoints(
                    (listOf(loc) + loop + loc).map { GeoPoint(it.lat, it.lon) }
                )
                outlinePaint.color = AndroidColor.argb(160, 233, 30, 99)
                outlinePaint.strokeWidth = 5f
            })
            loop.forEachIndexed { i, wp ->
                mapView.overlays.add(Marker(mapView).apply {
                    position = GeoPoint(wp.lat, wp.lon)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    title = "Stop ${i + 1}"
                })
            }
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
            try {
                if (mode.roundTrip) {
                    val loop = RoundTripPlanner.plan(loc, radiusKm * 1000.0, mode.highwayRegex)
                    route = loop
                    destination = null
                    mapView.zoomToBoundingBox(
                        BoundingBox.fromGeoPoints(
                            (loop + loc).map { GeoPoint(it.lat, it.lon) }
                        ).increaseByScale(1.3f),
                        true,
                    )
                } else {
                    val dest = withTimeout(30_000) {
                        withContext(Dispatchers.IO) {
                            RoadRoulette.randomRoadPoint(loc, radiusKm * 1000.0, mode.highwayRegex)
                        }
                    }
                    destination = dest
                    route = null
                    mapView.zoomToBoundingBox(
                        BoundingBox.fromGeoPoints(
                            listOf(GeoPoint(loc.lat, loc.lon), GeoPoint(dest.lat, dest.lon))
                        ).increaseByScale(1.4f),
                        true,
                    )
                }
            } catch (e: TimeoutCancellationException) {
                error = "Road servers are slow right now — try again"
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

        SmallFloatingActionButton(
            onClick = onOpenHistory,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(12.dp),
        ) {
            Icon(Icons.Default.History, contentDescription = "Trip history")
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
                        Text("Radius", style = MaterialTheme.typography.labelLarge)
                        Text(
                            if (mode.maxKm <= 10f) "%.1f km".format(radiusKm)
                            else "${radiusKm.toInt()} km",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Slider(
                        value = radiusKm,
                        onValueChange = { radiusKm = it },
                        valueRange = mode.minKm..mode.maxKm,
                    )

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
                            route = route,
                            origin = myLocation,
                            mode = mode,
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

/** "Go" button with a chooser for the navigation app. */
@Composable
private fun NavButton(
    destination: LatLon?,
    route: List<LatLon>?,
    origin: LatLon?,
    mode: TravelMode,
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
                        navigateRoundTrip(context, origin, route)
                    },
                )
            } else {
                DropdownMenuItem(
                    text = { Text("Google Maps") },
                    onClick = {
                        menuOpen = false
                        destination?.let { navigateGoogleMaps(context, it, mode) }
                    },
                )
                DropdownMenuItem(
                    text = { Text("Waze") },
                    onClick = {
                        menuOpen = false
                        destination?.let { navigateWaze(context, it) }
                    },
                )
                DropdownMenuItem(
                    text = { Text("Other app") },
                    onClick = {
                        menuOpen = false
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
