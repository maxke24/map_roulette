# Map Roulette

Don't know where to drive? Set a radius, spin, get a random point on a real road, and go.

## Features

- **Spin**: picks a random point on a drivable road within your chosen radius (1–50 km),
  weighted by road length, using OpenStreetMap data via the Overpass API. No API key needed.
- **Go**: hands the destination off to Google Maps (or any installed maps app) for turn-by-turn navigation.
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
