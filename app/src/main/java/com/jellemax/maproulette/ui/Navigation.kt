package com.jellemax.maproulette.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ForkLeft
import androidx.compose.material.icons.filled.ForkRight
import androidx.compose.material.icons.filled.RoundaboutLeft
import androidx.compose.material.icons.filled.SportsScore
import androidx.compose.material.icons.filled.Straight
import androidx.compose.material.icons.filled.TurnLeft
import androidx.compose.material.icons.filled.TurnRight
import androidx.compose.material.icons.filled.TurnSharpLeft
import androidx.compose.material.icons.filled.TurnSharpRight
import androidx.compose.material.icons.filled.TurnSlightLeft
import androidx.compose.material.icons.filled.TurnSlightRight
import androidx.compose.material.icons.filled.UTurnLeft
import androidx.compose.material.icons.filled.UTurnRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jellemax.maproulette.data.NavEngine
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** GraphHopper sign code → maneuver arrow. */
private fun signIcon(sign: Int): ImageVector = when (sign) {
    -98, -8 -> Icons.Default.UTurnLeft
    8 -> Icons.Default.UTurnRight
    -7 -> Icons.Default.ForkLeft
    7 -> Icons.Default.ForkRight
    -3 -> Icons.Default.TurnSharpLeft
    -2 -> Icons.Default.TurnLeft
    -1 -> Icons.Default.TurnSlightLeft
    1 -> Icons.Default.TurnSlightRight
    2 -> Icons.Default.TurnRight
    3 -> Icons.Default.TurnSharpRight
    4, 5 -> Icons.Default.SportsScore
    6 -> Icons.Default.RoundaboutLeft
    else -> Icons.Default.Straight
}

/** Top banner during navigation: next maneuver, distance to it. */
@Composable
fun NavigationBanner(
    progress: NavEngine.Progress?,
    rerouting: Boolean,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Row(
            Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val instruction = progress?.nextInstruction
            Icon(
                signIcon(instruction?.sign ?: 0),
                contentDescription = null,
                Modifier.size(44.dp),
            )
            Column {
                Text(
                    when {
                        rerouting -> "Rerouting…"
                        progress == null -> "Waiting for GPS…"
                        else -> formatDistanceKm(progress.distanceToTurnMeters)
                    },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    instruction?.text ?: "",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                )
            }
        }
    }
}

/** Bottom bar during navigation: remaining distance, arrival time, exit. */
@Composable
fun NavigationBottomBar(
    progress: NavEngine.Progress?,
    offRoute: Boolean,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    progress?.let { formatDistanceKm(it.remainingMeters) } ?: "—",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    when {
                        offRoute -> "Off route"
                        else -> progress?.remainingTimeMs?.let { "Arrival ${eta(it)}" } ?: ""
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (offRoute) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onExit) {
                Icon(Icons.Default.Close, contentDescription = "Exit navigation")
            }
        }
    }
}

private fun eta(remainingMs: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault())
        .format(Date(System.currentTimeMillis() + remainingMs))
