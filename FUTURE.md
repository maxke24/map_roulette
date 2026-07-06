# Future ideas

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
