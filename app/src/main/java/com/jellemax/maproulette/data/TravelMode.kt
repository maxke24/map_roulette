package com.jellemax.maproulette.data

/**
 * Travel modes with their own radius range, the OSM highway classes that
 * make sense as a destination for that mode, and the GraphHopper profile
 * used for routing on the own server.
 */
enum class TravelMode(
    val label: String,
    val minKm: Float,
    val maxKm: Float,
    val defaultKm: Float,
    val highwayRegex: String,
    /** Google Maps navigation mode: w=walk, b=bike, d=drive. */
    val gmapsMode: String,
    /** Profile name on the self-hosted GraphHopper server. */
    val ghProfile: String,
    /** Spin produces a loop of waypoints instead of a single destination. */
    val roundTrip: Boolean = false,
) {
    BIKE(
        label = "Bike",
        minKm = 1f, maxKm = 30f, defaultKm = 10f,
        highwayRegex = "^(cycleway|living_street|residential|unclassified|" +
            "tertiary|secondary|track|path)$",
        gmapsMode = "b",
        ghProfile = "bike",
    ),
    MOTO(
        // For round trips the slider means total trip length, not radius.
        label = "Moto",
        minKm = 30f, maxKm = 400f, defaultKm = 120f,
        // Curvy riding roads live on the rural network; skip motorways/residential.
        highwayRegex = "^(primary|secondary|tertiary|unclassified)$",
        gmapsMode = "d",
        ghProfile = "moto",
        roundTrip = true,
    ),
    CAR(
        label = "Car",
        minKm = 5f, maxKm = 100f, defaultKm = 25f,
        highwayRegex = "^(motorway|trunk|primary|secondary|tertiary|unclassified|residential|" +
            "motorway_link|trunk_link|primary_link|secondary_link|tertiary_link)$",
        gmapsMode = "d",
        ghProfile = "car",
    ),
}
