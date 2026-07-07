package com.jellemax.maproulette.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * App settings backed by SharedPreferences, exposed as StateFlows so the
 * UI and the tracking service both react to changes. Call [init] before
 * reading (idempotent; done in MainActivity and the tracking service).
 */
object Settings {

    /** AUTO = light by day, dark by night (sun position at last location). */
    enum class Theme { SYSTEM, LIGHT, DARK, AUTO }

    const val FOG_RADIUS_DEFAULT = 200f

    private lateinit var prefs: SharedPreferences

    private val _theme = MutableStateFlow(Theme.SYSTEM)
    val theme: StateFlow<Theme> = _theme

    private val _autoDetectDrives = MutableStateFlow(true)
    val autoDetectDrives: StateFlow<Boolean> = _autoDetectDrives

    private val _fogRadiusMeters = MutableStateFlow(FOG_RADIUS_DEFAULT)
    val fogRadiusMeters: StateFlow<Float> = _fogRadiusMeters

    /** User-entered sync server URL; blank = use the baked-in default. */
    private val _syncUrl = MutableStateFlow("")
    val syncUrl: StateFlow<String> = _syncUrl

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext
            .getSharedPreferences("settings", Context.MODE_PRIVATE)
        _theme.value = runCatching {
            Theme.valueOf(prefs.getString("theme", Theme.SYSTEM.name)!!)
        }.getOrDefault(Theme.SYSTEM)
        _autoDetectDrives.value = prefs.getBoolean("auto_detect_drives", true)
        _fogRadiusMeters.value = prefs.getFloat("fog_radius_m", FOG_RADIUS_DEFAULT)
        _syncUrl.value = prefs.getString("sync_url", "") ?: ""
    }

    fun setTheme(value: Theme) {
        _theme.value = value
        prefs.edit().putString("theme", value.name).apply()
    }

    fun setAutoDetectDrives(value: Boolean) {
        _autoDetectDrives.value = value
        prefs.edit().putBoolean("auto_detect_drives", value).apply()
    }

    fun setFogRadiusMeters(value: Float) {
        _fogRadiusMeters.value = value
        prefs.edit().putFloat("fog_radius_m", value).apply()
    }

    fun setSyncUrl(value: String) {
        _syncUrl.value = value.trim()
        prefs.edit().putString("sync_url", value.trim()).apply()
    }
}
