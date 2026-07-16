package com.jellemax.maproulette.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/**
 * App settings backed by SharedPreferences, exposed as StateFlows so the
 * UI and the tracking service both react to changes. Call [init] before
 * reading (idempotent; done in MainActivity and the tracking service).
 */
object Settings {

    /** AUTO = light by day, dark by night (sun position at last location). */
    enum class Theme { SYSTEM, LIGHT, DARK, AUTO }

    const val FOG_RADIUS_DEFAULT = 200f
    const val DEFAULT_ZOOM_DEFAULT = 16f
    const val DEFAULT_ZOOM_MIN = 12f
    const val DEFAULT_ZOOM_MAX = 19f

    private lateinit var prefs: SharedPreferences

    private val _theme = MutableStateFlow(Theme.AUTO)
    val theme: StateFlow<Theme> = _theme

    private val _autoDetectDrives = MutableStateFlow(true)
    val autoDetectDrives: StateFlow<Boolean> = _autoDetectDrives

    private val _fogRadiusMeters = MutableStateFlow(FOG_RADIUS_DEFAULT)
    val fogRadiusMeters: StateFlow<Float> = _fogRadiusMeters

    /** Draw the fog of war over the map. On by default; the map toolbar's eye
     *  toggles it, and the choice sticks across launches. */
    private val _fogEnabled = MutableStateFlow(true)
    val fogEnabled: StateFlow<Boolean> = _fogEnabled

    /** Baseline map zoom while following/navigating; speed and turn proximity
     *  shift the camera up to two levels either side of it. */
    private val _defaultZoom = MutableStateFlow(DEFAULT_ZOOM_DEFAULT)
    val defaultZoom: StateFlow<Float> = _defaultZoom

    /** In-app navigation avoids motorways/trunks (matters for car mode). */
    private val _avoidHighways = MutableStateFlow(false)
    val avoidHighways: StateFlow<Boolean> = _avoidHighways

    /** The mode tab the user is on. The tracking service reads it to decide
     *  which motion sensors are worth registering, and stamps it on the trip —
     *  including an auto-detected one, which has no other way to know. */
    private val _tripMode = MutableStateFlow(TravelMode.CAR)
    val tripMode: StateFlow<TravelMode> = _tripMode

    /** Bluetooth devices mapped to a vehicle: address → mode. When a mapped
     *  device connects, the tracking service switches the trip mode to it, so a
     *  drive auto-logs under the right vehicle. Empty = feature off. These are
     *  Bluetooth Classic bonds (a Cardo intercom, a car's infotainment), not BLE. */
    private val _vehicleDevices = MutableStateFlow<Map<String, TravelMode>>(emptyMap())
    val vehicleDevices: StateFlow<Map<String, TravelMode>> = _vehicleDevices

    /** Opt in to the shared fog of war. Off by default, and the server only
     *  hands a friend's traces to someone who is also sharing theirs. */
    private val _shareFog = MutableStateFlow(false)
    val shareFog: StateFlow<Boolean> = _shareFog

    /** User-entered sync server URL; blank = use the baked-in default. */
    private val _syncUrl = MutableStateFlow("")
    val syncUrl: StateFlow<String> = _syncUrl

    /** Bearer token for the sync server; blank = signed out. App-private prefs. */
    private val _authToken = MutableStateFlow("")
    val authToken: StateFlow<String> = _authToken

    private val _authUsername = MutableStateFlow("")
    val authUsername: StateFlow<String> = _authUsername

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext
            .getSharedPreferences("settings", Context.MODE_PRIVATE)
        _theme.value = runCatching {
            Theme.valueOf(prefs.getString("theme", Theme.AUTO.name)!!)
        }.getOrDefault(Theme.AUTO)
        _autoDetectDrives.value = prefs.getBoolean("auto_detect_drives", true)
        _avoidHighways.value = prefs.getBoolean("avoid_highways", false)
        _tripMode.value = TravelMode.of(prefs.getString("trip_mode", null))
        _shareFog.value = prefs.getBoolean("share_fog", false)
        _fogEnabled.value = prefs.getBoolean("fog_enabled", true)
        _fogRadiusMeters.value = prefs.getFloat("fog_radius_m", FOG_RADIUS_DEFAULT)
        _defaultZoom.value = prefs.getFloat("default_zoom", DEFAULT_ZOOM_DEFAULT)
        _syncUrl.value = prefs.getString("sync_url", "") ?: ""
        _authToken.value = prefs.getString("auth_token", "") ?: ""
        _authUsername.value = prefs.getString("auth_username", "") ?: ""
        _vehicleDevices.value = readVehicleDevices()
    }

    private fun readVehicleDevices(): Map<String, TravelMode> {
        val raw = prefs.getString("vehicle_devices", null) ?: return emptyMap()
        return runCatching {
            val json = JSONObject(raw)
            json.keys().asSequence().associateWith { TravelMode.of(json.getString(it)) }
        }.getOrDefault(emptyMap())
    }

    /** Map [address] to [mode], or unmap it when [mode] is null. */
    fun setVehicleForDevice(address: String, mode: TravelMode?) {
        val next = _vehicleDevices.value.toMutableMap()
        if (mode == null) next.remove(address) else next[address] = mode
        _vehicleDevices.value = next
        val json = JSONObject()
        next.forEach { (addr, m) -> json.put(addr, m.name) }
        prefs.edit().putString("vehicle_devices", json.toString()).apply()
    }

    fun setAuth(token: String, username: String) {
        _authToken.value = token
        _authUsername.value = username
        prefs.edit().putString("auth_token", token)
            .putString("auth_username", username).apply()
    }

    fun setTheme(value: Theme) {
        _theme.value = value
        prefs.edit().putString("theme", value.name).apply()
    }

    fun setAutoDetectDrives(value: Boolean) {
        _autoDetectDrives.value = value
        prefs.edit().putBoolean("auto_detect_drives", value).apply()
    }

    fun setAvoidHighways(value: Boolean) {
        _avoidHighways.value = value
        prefs.edit().putBoolean("avoid_highways", value).apply()
    }

    fun setTripMode(value: TravelMode) {
        _tripMode.value = value
        prefs.edit().putString("trip_mode", value.name).apply()
    }

    fun setShareFog(value: Boolean) {
        _shareFog.value = value
        prefs.edit().putBoolean("share_fog", value).apply()
    }

    fun setFogEnabled(value: Boolean) {
        _fogEnabled.value = value
        prefs.edit().putBoolean("fog_enabled", value).apply()
    }

    fun setFogRadiusMeters(value: Float) {
        _fogRadiusMeters.value = value
        prefs.edit().putFloat("fog_radius_m", value).apply()
    }

    fun setDefaultZoom(value: Float) {
        _defaultZoom.value = value
        prefs.edit().putFloat("default_zoom", value).apply()
    }

    fun setSyncUrl(value: String) {
        _syncUrl.value = value.trim()
        prefs.edit().putString("sync_url", value.trim()).apply()
    }
}
