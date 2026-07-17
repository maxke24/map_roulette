package com.jellemax.maproulette.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun formatSpeedKmh(mps: Double): String = "%.0f km/h".format(mps * 3.6)

fun formatDistanceKm(meters: Double): String =
    if (meters < 1000) "%.0f m".format(meters) else "%.1f km".format(meters / 1000)

// Built once, reused. A fresh SimpleDateFormat per call re-parses the ICU
// pattern (~1-2 ms) — cheap alone, but LazyColumn composes many rows per fling
// frame, so per-row allocation is what made trip-history scrolling stutter.
// Composition is single-threaded (main), so one shared instance is safe here.
private val tripDateFormat = SimpleDateFormat("EEE d MMM yyyy, HH:mm", Locale.getDefault())

fun formatDate(timeMs: Long): String = tripDateFormat.format(Date(timeMs))

fun formatLeanAngle(deg: Double): String = "%.0f°".format(deg)

fun formatGForce(g: Double): String = "%.1f g".format(g)
