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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.jellemax.maproulette.data.LatLon
import com.jellemax.maproulette.data.RoadRoulette
import com.jellemax.maproulette.tracking.TripStats
import com.jellemax.maproulette.tracking.TripTrackingService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon

@Composable
fun MapScreen(onOpenHistory: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var radiusKm by rememberSaveable { mutableFloatStateOf(10f) }
    var myLocation by remember { mutableStateOf<LatLon?>(null) }
    var destination by remember { mutableStateOf<LatLon?>(null) }
    var spinning by remember { mutableStateOf(false) }
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

    // Redraw overlays whenever location, radius, or destination changes.
    LaunchedEffect(myLocation, destination, radiusKm) {
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
        mapView.invalidate()
    }

    fun spin() {
        val loc = myLocation ?: run {
            error = "Waiting for your location…"
            fetchLocation()
            return
        }
        scope.launch {
            spinning = true
            error = null
            try {
                val dest = withContext(Dispatchers.IO) {
                    RoadRoulette.randomRoadPoint(loc, radiusKm * 1000.0)
                }
                destination = dest
                mapView.zoomToBoundingBox(
                    BoundingBox.fromGeoPoints(
                        listOf(GeoPoint(loc.lat, loc.lon), GeoPoint(dest.lat, dest.lon))
                    ).increaseByScale(1.4f),
                    true,
                )
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
                .padding(12.dp),
        ) {
            Icon(Icons.Default.History, contentDescription = "Trip history")
        }

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            stats?.let { ActiveTripCard(it) }

            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall)
                    }
                    Text("Radius: ${radiusKm.toInt()} km", style = MaterialTheme.typography.labelLarge)
                    Slider(
                        value = radiusKm,
                        onValueChange = { radiusKm = it },
                        valueRange = 1f..50f,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = ::spin, enabled = !spinning, modifier = Modifier.weight(1f)) {
                            if (spinning) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Default.Casino, contentDescription = null)
                            }
                            Text("  Spin")
                        }
                        OutlinedButton(
                            onClick = { destination?.let { navigateTo(context, it) } },
                            enabled = destination != null,
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Default.Navigation, contentDescription = null)
                            Text("  Go")
                        }
                        if (stats == null) {
                            OutlinedButton(
                                onClick = {
                                    TripTrackingService.start(
                                        context, destination?.lat, destination?.lon)
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null)
                                Text("  Track")
                            }
                        } else {
                            Button(
                                onClick = { TripTrackingService.stop(context) },
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(Icons.Default.Stop, contentDescription = null)
                                Text("  End")
                            }
                        }
                    }
                }
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
    Card {
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
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}

private fun navigateTo(context: Context, dest: LatLon) {
    val gmaps = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${dest.lat},${dest.lon}"))
        .setPackage("com.google.android.apps.maps")
    try {
        context.startActivity(gmaps)
    } catch (e: ActivityNotFoundException) {
        // No Google Maps installed: let any maps app handle a geo: URI.
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse("geo:${dest.lat},${dest.lon}?q=${dest.lat},${dest.lon}"))
        )
    }
}
