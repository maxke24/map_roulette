package com.jellemax.maproulette

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.jellemax.maproulette.ui.HistoryScreen
import com.jellemax.maproulette.ui.MapScreen
import org.osmdroid.config.Configuration

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // osmdroid requires a distinct user agent for its tile servers.
        Configuration.getInstance().load(this, getSharedPreferences("osmdroid", MODE_PRIVATE))
        Configuration.getInstance().userAgentValue = packageName
        setContent {
            val context = LocalContext.current
            val dark = isSystemInDarkTheme()
            // Material You dynamic color on Android 12+, static fallback below.
            val colorScheme = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && dark ->
                    dynamicDarkColorScheme(context)
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                    dynamicLightColorScheme(context)
                dark -> darkColorScheme()
                else -> lightColorScheme()
            }
            MaterialTheme(colorScheme = colorScheme) {
                Surface { AppRoot() }
            }
        }
    }
}

@Composable
private fun AppRoot() {
    var showHistory by remember { mutableStateOf(false) }
    if (showHistory) {
        HistoryScreen(onBack = { showHistory = false })
    } else {
        MapScreen(onOpenHistory = { showHistory = true })
    }
}
