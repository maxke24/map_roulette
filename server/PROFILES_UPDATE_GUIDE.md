# Map Roulette — Add `car` and `bike` Profiles to GraphHopper

**Audience: a Claude (or human) agent working on the owner's personal server.
You have no prior context; everything you need is in this file.**

## Context

A GraphHopper instance already runs on this server (set up per
`CLAUDE_SETUP_GUIDE.md` in this directory): docker compose in `~/graphhopper/`,
bound to `127.0.0.1:8989`, exposed through a Cloudflare tunnel with CF Access.
It currently has a single profile, `moto` (curvy-road weighting, avoids
motorways). **Do not change the moto profile's behavior.**

**Goal:** add two profiles so the Android app can route per travel mode:

- `car` — normal fastest-route driving. Motorways allowed. The app sometimes
  sends a query `custom_model` (POST /route) to avoid motorways; that must work.
- `bike` — cycling. Needs cycleways/paths, which the current import **ignores**
  (`import.osm.ignored_highways` excludes them), so a full re-import is required.

## Success criteria (verify all before reporting done)

1. `GET /info` lists exactly three profiles: `moto`, `car`, `bike`.
2. Car route Brussels→Ghent (`point=50.85,4.35&point=51.05,3.72`) uses motorway:
   request with `&details=road_class`, expect MOTORWAY segments and travel time
   well under the moto profile's (~113 min → expect roughly 40–50 min).
3. Same car request as POST with a custom model avoiding motorways returns a
   route with **zero** motorway segments:

   ```json
   {
     "profile": "car",
     "points": [[4.35, 50.85], [3.72, 51.05]],
     "points_encoded": false,
     "ch.disable": true,
     "details": ["road_class"],
     "custom_model": {"priority": [
       {"if": "road_class == MOTORWAY || road_class == TRUNK", "multiply_by": 0.05}
     ]}
   }
   ```

4. Bike route between two points in a town routes over cycleway/path segments
   where sensible (`&details=road_class` shows CYCLEWAY or PATH somewhere on a
   suitable route) and never over motorways.
5. Moto behavior unchanged: Brussels→Liège (`point=50.85,4.35&point=50.63,5.57`)
   still has zero MOTORWAY meters; `algorithm=round_trip` still works.
6. Everything still works through the tunnel hostname with CF Access headers and
   is rejected without them (no Access changes should be needed).

## Step 1 — config changes

Edit `~/graphhopper/data/config.yml`:

1. **Encoded values** — extend (keep the existing ones):

   ```yaml
   graph.encoded_values: curvature, road_class, road_access, max_speed, car_access, car_average_speed, bike_access, bike_average_speed, bike_priority, roundabout
   ```

2. **Import filter** — bikes need cycleways and paths. Change:

   ```yaml
   import.osm.ignored_highways: footway,pedestrian,steps
   ```

   (i.e. stop ignoring `cycleway` and `path`.)

3. **Profiles** — keep `moto` exactly as is; add:

   ```yaml
   - name: car
     weighting: custom
     custom_model:
       speed:
         - { if: "true", limit_to: "car_average_speed" }
       priority:
         - { if: "car_access == false", multiply_by: "0" }

   - name: bike
     weighting: custom
     custom_model:
       speed:
         - { if: "true", limit_to: "bike_average_speed" }
       priority:
         - { if: "bike_access == false", multiply_by: "0" }
   ```

   Keep `profiles_ch: []` — the app's custom-model queries and `round_trip`
   need flexible routing.

**Note:** GraphHopper's config schema shifts between versions (this instance is
GH 12). If the import rejects an encoded value or custom-model expression, check
the docs for the running image's version and adapt — keep the *intent*: car =
fastest with motorways and query-custom-model support; bike = cycleway-aware,
no motorways; moto untouched.

## Step 2 — re-import

The graph cache was built without bike data, so it must be wiped:

```bash
cd ~/graphhopper
docker compose down
rm -rf data/graph
docker compose up -d && docker compose logs -f
```

Re-import takes minutes and is RAM-heavy (more than last time — bike encoded
values and cycleways grow the graph). If the container OOMs, raise `JAVA_OPTS`
(e.g. `-Xmx6g`) and retry. Wait for "Started server".

Disk check before starting: the old graph is deleted first, but ensure a few GB
free for the new one.

## Step 3 — verify

Run every check in the success criteria, first against `localhost:8989`, then
through the tunnel hostname with the CF Access service-token headers (find them
in the existing cloudflared/Access setup; same token as before).

Then confirm the app's main flows still work end-to-end:

```bash
# moto round trip (app spin):
curl -s "http://localhost:8989/route?profile=moto&point=50.85,4.35&algorithm=round_trip&round_trip.distance=100000&round_trip.seed=42&points_encoded=false" | head -c 300
# car destination snap (app spin in car mode):
curl -s "http://localhost:8989/route?profile=car&point=50.85,4.35&point=50.9,4.4&points_encoded=false" | head -c 300
```

## Report back

State which config lines changed, import duration, final graph size, and paste
the road-class summaries from success criteria 2, 3 and 5.
