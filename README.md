# Map Roulette

Don't know where to drive? Set a radius, spin, get a random point on a real road, and go.

## Features

- **Modes**: walk (0.5–8 km), bike (1–30 km), car (5–100 km) — each with fitting road
  types (footpaths for walking, no motorways for cycling, …).
- **Spin**: picks a random point on a road within your chosen radius, using OpenStreetMap
  data via the Overpass API. No API key needed. Samples a random sub-area instead of
  downloading every road in the circle, so it stays fast at large radii.
- **Go**: turn-by-turn navigation via Google Maps, Waze, or any installed maps app.
- **Track**: foreground service records your drive — duration, distance, current speed, top speed.
- **History**: past trips with duration, distance, average and top speed.

## Stack

Kotlin, Jetpack Compose, Material 3, osmdroid (OpenStreetMap tiles), Overpass API,
fused location provider. Trips stored as JSON in app-private storage. Min SDK 26 (Android 8.0).

## Build

```
./gradlew assembleDebug
```

APK lands in `app/build/outputs/apk/debug/app-debug.apk`. Install with
`adb install app/build/outputs/apk/debug/app-debug.apk`.
