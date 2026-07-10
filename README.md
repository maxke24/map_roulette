# Map Roulette

Don't know where to drive? Set a radius, spin, get a random point on a real road, and go.

## Features

- **Modes**: walk (0.5–8 km), bike (1–30 km), moto (5–60 km), car (5–100 km) — each with
  fitting road types (footpaths for walking, no motorways for cycling, …).
- **Moto round trips**: spins a loop of waypoints through the curviest roads around you
  and hands it to Google Maps as a multi-waypoint route. Curviness is junction-aware:
  turn radius is estimated from road geometry (circumcircle per vertex triple), and
  vertices at intersections are excluded — so a left turn at a crossroads doesn't count
  as a "curve", only sweeping bends within the road do.
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

## Self-hosting the server

The app can sync to your own server (accounts, trips, fog of war, friends) and
route against your own GraphHopper instance. One script installs either or both
— on a Proxmox host it builds an LXC for you, anywhere else it installs in place.

```
bash server/install.sh
```

See [`server/INSTALL.md`](server/INSTALL.md) for exposing it safely, choosing an
OSM region, backups, and the API. Verify a running install with
`bash server/verify.sh`.
