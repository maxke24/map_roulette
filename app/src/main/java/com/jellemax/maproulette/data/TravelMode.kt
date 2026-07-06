package com.jellemax.maproulette.data

/**
 * Travel modes with their own radius range and the OSM highway classes that
 * make sense as a destination for that mode.
 */
enum class TravelMode(
    val label: String,
    val minKm: Float,
    val maxKm: Float,
    val defaultKm: Float,
    val highwayRegex: String,
    /** Google Maps navigation mode: w=walk, b=bike, d=drive. */
    val gmapsMode: String,
) {
    WALK(
        label = "Walk",
        minKm = 0.5f, maxKm = 8f, defaultKm = 2f,
        highwayRegex = "^(footway|path|pedestrian|living_street|residential|" +
            "unclassified|tertiary|track|cycleway)$",
        gmapsMode = "w",
    ),
    BIKE(
        label = "Bike",
        minKm = 1f, maxKm = 30f, defaultKm = 10f,
        highwayRegex = "^(cycleway|living_street|residential|unclassified|" +
            "tertiary|secondary|track|path)$",
        gmapsMode = "b",
    ),
    CAR(
        label = "Car",
        minKm = 5f, maxKm = 100f, defaultKm = 25f,
        highwayRegex = "^(motorway|trunk|primary|secondary|tertiary|unclassified|residential|" +
            "motorway_link|trunk_link|primary_link|secondary_link|tertiary_link)$",
        gmapsMode = "d",
    ),
}
