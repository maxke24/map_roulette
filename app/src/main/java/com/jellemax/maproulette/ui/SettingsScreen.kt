package com.jellemax.maproulette.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jellemax.maproulette.BuildConfig
import com.jellemax.maproulette.data.RoutingServer
import com.jellemax.maproulette.data.ServerConfig
import com.jellemax.maproulette.data.Settings
import com.jellemax.maproulette.data.SyncClient
import com.jellemax.maproulette.data.TraceStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val theme by Settings.theme.collectAsStateWithLifecycle()
    val autoDetect by Settings.autoDetectDrives.collectAsStateWithLifecycle()
    val avoidHighways by Settings.avoidHighways.collectAsStateWithLifecycle()
    val fogRadius by Settings.fogRadiusMeters.collectAsStateWithLifecycle()
    var confirmReset by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SettingsSection("Appearance") {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    Settings.Theme.entries.forEachIndexed { index, t ->
                        SegmentedButton(
                            selected = theme == t,
                            onClick = { Settings.setTheme(t) },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index, count = Settings.Theme.entries.size,
                            ),
                            label = {
                                Text(t.name.lowercase().replaceFirstChar { it.uppercase() })
                            },
                        )
                    }
                }
                if (theme == Settings.Theme.AUTO) {
                    Text(
                        "Light by day, dark by night — follows sunrise and " +
                            "sunset at your location.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            SettingsSection("Tracking") {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Auto-detect drives", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Start a trip automatically when driving is detected",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(
                        checked = autoDetect,
                        onCheckedChange = { Settings.setAutoDetectDrives(it) },
                    )
                }
            }

            SettingsSection("Navigation") {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Avoid highways", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "In-app navigation skips motorways (car mode; " +
                                "moto and bike never use them)",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(
                        checked = avoidHighways,
                        onCheckedChange = { Settings.setAvoidHighways(it) },
                    )
                }
            }

            SettingsSection("Fog of war") {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Reveal radius", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "${fogRadius.toInt()} m",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Slider(
                    value = fogRadius,
                    onValueChange = { Settings.setFogRadiusMeters(it) },
                    valueRange = 100f..500f,
                )
                TextButton(onClick = { confirmReset = true }) {
                    Text("Reset explored area", color = MaterialTheme.colorScheme.error)
                }
            }

            ServerSection()

            SyncSection()

            Text(
                "Map Roulette v${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset explored area?") },
            text = { Text("All fog-of-war progress will be permanently deleted. Saved trips are kept.") },
            confirmButton = {
                TextButton(onClick = {
                    TraceStore.clear(context)
                    confirmReset = false
                }) { Text("Reset", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) { Text("Cancel") }
            },
        )
    }
}

/** Backup sync with the owner's server (see server/SYNC_SETUP_GUIDE.md). */
@Composable
private fun SyncSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val syncUrl by Settings.syncUrl.collectAsStateWithLifecycle()
    var urlField by remember { mutableStateOf(syncUrl) }
    var status by remember { mutableStateOf<String?>(null) }
    var syncing by remember { mutableStateOf(false) }

    SettingsSection("Backup sync") {
        Text(
            "Trips and explored area are merged with your server after every " +
                "trip and on app start, so a reinstall restores everything. " +
                "Uses the routing server's Cloudflare Access credentials.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = urlField, onValueChange = { urlField = it },
            label = { Text("Sync server URL") },
            placeholder = { Text("https://…") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = {
                Settings.setSyncUrl(urlField)
                status = "Saved"
            }) { Text("Save") }
            TextButton(
                enabled = !syncing && SyncClient.configured,
                onClick = {
                    syncing = true
                    status = "Syncing…"
                    scope.launch {
                        status = withContext(Dispatchers.IO) {
                            try {
                                val r = SyncClient.sync(context)
                                "Synced: ${r.trips} trips, ${r.traces} trace segments"
                            } catch (e: Exception) {
                                "Sync failed: ${e.message}"
                            }
                        }
                        syncing = false
                    }
                },
            ) { Text("Sync now") }
        }
        status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            content()
        }
    }
}

/**
 * Settings for a custom GraphHopper server. Built-in defaults are never
 * displayed: empty fields mean the built-in server is used.
 */
@Composable
private fun ServerSection() {
    val context = LocalContext.current
    val custom = remember { RoutingServer.loadCustom(context) }
    val builtInAvailable = remember { RoutingServer.bakedDefaults().usable }
    var url by remember { mutableStateOf(custom?.url ?: "") }
    var clientId by remember { mutableStateOf(custom?.clientId ?: "") }
    var clientSecret by remember { mutableStateOf(custom?.clientSecret ?: "") }
    var saved by remember { mutableStateOf(false) }

    SettingsSection("Routing server") {
        Text(
            when {
                custom != null -> "Custom server: ${custom.url}"
                builtInAvailable -> "Using built-in server"
                else -> "Public servers only"
            },
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            "Optional: your own GraphHopper server for spins and round " +
                "trips. Leave empty to use the built-in one. Falls back " +
                "to public servers when unreachable.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = url, onValueChange = { url = it; saved = false },
            label = { Text("Server URL") },
            placeholder = { Text("https://…") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = clientId, onValueChange = { clientId = it; saved = false },
            label = { Text("CF Access Client Id (optional)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = clientSecret, onValueChange = { clientSecret = it; saved = false },
            label = { Text("CF Access Client Secret (optional)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = {
                if (url.isBlank()) {
                    RoutingServer.clearCustom(context)
                } else {
                    RoutingServer.save(
                        context, ServerConfig(url, clientId, clientSecret, enabled = true))
                }
                saved = true
            }) { Text(if (saved) "Saved ✓" else "Save server") }
            if (custom != null) {
                TextButton(onClick = {
                    RoutingServer.clearCustom(context)
                    url = ""; clientId = ""; clientSecret = ""
                    saved = true
                }) { Text("Remove custom server") }
            }
        }
    }
}
