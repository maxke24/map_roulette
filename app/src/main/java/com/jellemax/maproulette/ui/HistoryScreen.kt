package com.jellemax.maproulette.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.jellemax.maproulette.data.SyncClient
import com.jellemax.maproulette.data.TravelMode
import com.jellemax.maproulette.data.Trip
import com.jellemax.maproulette.data.TripStore

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var trips by remember { mutableStateOf(TripStore.load(context)) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Trip history") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        if (trips.isEmpty()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("No trips yet", style = MaterialTheme.typography.bodyLarge)
            }
        } else {
            LazyColumn(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(trips) { trip ->
                    TripCard(trip) { newMode ->
                        TripStore.updateMode(context, trip.startTimeMs, newMode)
                        trips = TripStore.load(context)
                        // Push the correction so it survives a reinstall / other devices.
                        SyncClient.syncQuietly(context)
                    }
                }
            }
        }
    }
}

/** One trip, with a tap-to-correct vehicle picker for false auto-classifications. */
@Composable
private fun TripCard(trip: Trip, onChangeMode: (TravelMode) -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${trip.mode.label} · ${formatDate(trip.startTimeMs)}",
                    style = MaterialTheme.typography.titleSmall,
                )
                Box {
                    IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Edit, contentDescription = "Change vehicle",
                            Modifier.size(18.dp))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        TravelMode.entries.forEach { m ->
                            DropdownMenuItem(
                                text = { Text(m.label + if (m == trip.mode) " ✓" else "") },
                                onClick = { menuOpen = false; if (m != trip.mode) onChangeMode(m) },
                            )
                        }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TripStat("Duration", formatDuration(trip.durationMs))
                TripStat("Distance", formatDistanceKm(trip.distanceMeters))
                TripStat("Avg", formatSpeedKmh(trip.avgSpeedMps))
                TripStat("Top", formatSpeedKmh(trip.topSpeedMps))
            }
            // A trip only carries the readings its vehicle has; printing "0°"
            // for a car is worse than printing nothing.
            if (trip.mode.tracksMotion) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    if (trip.mode.tracksLean) {
                        TripStat("Max lean", formatLeanAngle(trip.maxLeanAngleDeg))
                    }
                    if (trip.mode.tracksGForce) {
                        TripStat("Max G", formatGForce(trip.maxGForce))
                    }
                }
            }
        }
    }
}

@Composable
private fun TripStat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
