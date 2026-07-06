# Future ideas

## Real routing engine for Moto round trips (calimoto-style)

Current MVP hands waypoints to Google Maps, which may pick boring connector roads.
Upgrade path with an actual routing engine:

- **Engine: GraphHopper** — JVM, open source, built-in `round_trip` algorithm
  (point + distance + seed → loop), custom models for re-weighting roads.
- **Pipeline**: Geofabrik country extract → run the junction-aware curviness scorer
  (`Curviness.kt` algorithm) over every way in the extract → write `curvy_score` tag
  into the pbf (osmium) → GraphHopper imports it as a custom encoded value.
- **Custom model**: motorcycle profile, priority boost where `curvy_score` high,
  penalize trunk/urban. Round trips then follow curvy roads end to end.
- **Navigation handoff**: sample 9 waypoints from the computed route into a Google
  Maps directions URL — Maps follows essentially our route with voice/traffic.
  In-app turn-by-turn is months of work; skip.
- **Hosting tiers**: (1) self-hosted Docker server, ~€5/mo VPS or home server,
  ~2–4 days work — recommended first step; (2) on-device GraphHopper with prebuilt
  graph (~100–300 MB/country, build in CI, host on GitHub releases), offline, ~1 week+;
  (3) GraphHopper Cloud API — custom models are paywalled, least control.
- Tuning knobs once real: bend radius window (now 25–300 m), score threshold (0.12),
  loop shape, avoid-repeat-roads.

## Android Auto support

Verdict from earlier discussion: possible, moderate effort (~200 lines), parked until phone app is polished.

### What it would look like
- Built with the Android for Cars App Library (`androidx.car.app:app`), **POI category**
  (templated UI only — no free-form Compose on the car screen).
- Car screen flow:
  1. Simple template with travel mode + radius (reuse phone settings, or a car list picker)
  2. "Spin" → pick random road point (existing `RoadRoulette` code, unchanged)
  3. `CarContext.startCarApp(ACTION_NAVIGATE, geo:lat,lon)` → hands off to Google Maps /
     Waze **on the car screen**
- Trip tracking service already runs on the phone; works unchanged with Auto.

### Implementation checklist
- [ ] `androidx.car.app:app` dependency
- [ ] `CarAppService` + `Session` + one or two `Screen`s (Pane/PlaceListMap templates)
- [ ] Manifest: car app service entry, category `androidx.car.app.category.POI`,
      `minCarApiLevel`, link `automotive_app_desc.xml`
- [ ] Test with Desktop Head Unit (DHU) emulator from Android Studio

### Caveats (why parked)
- **Sideload restriction**: Android Auto refuses non-Play-Store apps by default. Workaround
  for personal use: Android Auto app → tap version 10× → developer settings → enable
  "Unknown sources". One-time setup per phone.
- **Driver distraction rules**: limited templates; live speed readout on car screen not
  really allowed. Stats stay on phone.
- Cheap alternative already works today: spin on phone before driving, Go → Google Maps →
  Maps shows up on Auto. Car app only adds spinning from the car screen itself.

## Other ideas (unprioritized)
- Persist chosen nav app as default (skip chooser)
- Avoid destinations too close to start (min distance slider or % of radius)
- Show route distance/ETA to destination before committing (needs routing API)
- Trip detail view: map of tracked route (store GPS trace, draw polyline)
- Export trips (GPX/JSON share)
