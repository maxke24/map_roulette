# Map Roulette — Routing Server Setup Guide

**Audience: a Claude (or human) agent working on the owner's personal server.
You have no prior context; everything you need is in this file.**

## Context

Map Roulette is a personal Android app (repo: https://github.com/maxke24/map_roulette).
Its "Moto" mode generates motorcycle round trips over curvy roads. Today it makes
12–24 queries against public Overpass API mirrors per spin — slow and rate-limited.

**Goal of this task:** run a self-hosted GraphHopper routing engine on this server so
the app can generate a curvy round trip with a single fast request. The server is
already connected to a Cloudflare tunnel; the routing engine must be exposed through
it, protected with Cloudflare Access.

Key design fact: GraphHopper has a built-in `curvature` encoded value per road edge,
and its edges run junction-to-junction — so turns at intersections are structurally
excluded from curviness. That behaviour is required: an intersection turn must never
count as a "curvy road". Do not replace this with heading-change heuristics.

## Success criteria (verify all before reporting done)

1. `curl` round-trip request (below) returns HTTP 200 with a GeoJSON-style points
   polyline of a loop, in under ~3 s.
2. Same request works through the Cloudflare tunnel hostname with Access service
   token headers, and is **rejected without** the headers.
3. GraphHopper survives a server reboot (`restart: unless-stopped` verified or
   equivalent).
4. A refresh mechanism for OSM data exists (cron or documented manual step).

## Prerequisites — check first

- Docker + docker compose available (if not, install per distro docs; no snap).
- ~6 GB free disk, ~4 GB free RAM during import (Belgium-sized region; scale up for
  bigger extracts — Benelux/Germany need more; ask the owner before exceeding).
- Existing `cloudflared` tunnel config — find it (commonly
  `/etc/cloudflared/config.yml` or a dashboard-managed tunnel).

## Step 1 — data + config

```bash
mkdir -p ~/graphhopper/data && cd ~/graphhopper/data
# Region: Belgium by default. If the owner has said otherwise, use that extract.
curl -LO https://download.geofabrik.de/europe/belgium-latest.osm.pbf
```

Create `~/graphhopper/data/config.yml`:

```yaml
graphhopper:
  datareader.file: /data/belgium-latest.osm.pbf
  graph.location: /data/graph
  # curvature: built-in per-edge sinuosity (junction-to-junction, lower = curvier)
  graph.encoded_values: curvature, road_class, road_access, max_speed
  import.osm.ignored_highways: footway,cycleway,path,pedestrian,steps

  profiles:
    - name: moto
      weighting: custom
      custom_model:
        speed:
          - { if: "true", limit_to: "car_average_speed" }
        priority:
          # Custom-model multipliers may only lower priority (0..1):
          # penalize boring roads instead of boosting curvy ones.
          - { if: "road_class == MOTORWAY || road_class == TRUNK", multiply_by: "0.1" }
          - { if: "road_class == RESIDENTIAL", multiply_by: "0.5" }
          - { if: "curvature > 0.95", multiply_by: "0.4" }   # near-straight
          - { else_if: "curvature > 0.85", multiply_by: "0.7" }

  # No CH profiles: round_trip needs flexible routing. Fine at country scale.
  profiles_ch: []

server:
  application_connectors:
    - type: http
      port: 8989
      bind_host: 0.0.0.0
```

**Note:** GraphHopper's config schema shifts between major versions (e.g. how custom
models reference base vehicles like `car_average_speed`). If import fails on config
parsing, check the docs for the image's GH version and adapt — keep the *intent*:
car-like speeds, flexible weighting, curvature + road_class penalties as above.

## Step 2 — run it

`~/graphhopper/docker-compose.yml`:

```yaml
services:
  graphhopper:
    image: israelhikingmap/graphhopper
    command: --config /data/config.yml
    volumes:
      - ./data:/data
    ports:
      - "127.0.0.1:8989:8989"   # localhost only; Cloudflare tunnel does the exposing
    restart: unless-stopped
```

```bash
cd ~/graphhopper && docker compose up -d && docker compose logs -f
```

First start imports the graph (minutes, RAM-heavy). Wait for "Started server".
If the container OOMs, add `JAVA_OPTS=-Xmx4g` (environment) and retry.

Verify locally (coordinates are Brussels; use any point inside the region):

```bash
curl -s "http://localhost:8989/route?profile=moto&point=50.85,4.35&algorithm=round_trip&round_trip.distance=100000&round_trip.seed=42&points_encoded=false" | head -c 400
```

Expect JSON with `paths[0].points.coordinates` (a long lon/lat array) and
`paths[0].distance` near 100000. Different `round_trip.seed` → different loop.

## Step 3 — expose via Cloudflare tunnel + Access

1. Add an ingress rule to the existing tunnel mapping a hostname (e.g.
   `gh.<owner's domain>`) to `http://localhost:8989`. Dashboard-managed tunnel:
   Zero Trust → Networks → Tunnels → add public hostname. File-managed: add to
   `ingress:` in the cloudflared config and restart cloudflared.
2. GraphHopper has **no authentication**. In Cloudflare Zero Trust:
   - Create a **service token** (Access → Service Auth → Service Tokens).
   - Create an Access application for the hostname with a policy: Action
     **Service Auth**, include → the service token.
3. Verify from outside:

```bash
# must be 403/302 (blocked):
curl -s -o /dev/null -w "%{http_code}\n" "https://gh.<domain>/route?profile=moto&point=50.85,4.35"
# must be 200:
curl -s -o /dev/null -w "%{http_code}\n" \
  -H "CF-Access-Client-Id: <token id>" \
  -H "CF-Access-Client-Secret: <token secret>" \
  "https://gh.<domain>/route?profile=moto&point=50.85,4.35&algorithm=round_trip&round_trip.distance=100000&round_trip.seed=1&points_encoded=false"
```

## Step 4 — monthly data refresh

Cron (or systemd timer), monthly:

```bash
cd ~/graphhopper/data \
  && curl -sLO https://download.geofabrik.de/europe/belgium-latest.osm.pbf \
  && rm -rf graph \
  && cd ~/graphhopper && docker compose restart graphhopper
```

(Re-import runs on restart; service is down for the import duration — acceptable.)

## Step 5 — report back to the owner

Provide exactly this, so the app side can be wired up:

```
GRAPHHOPPER_URL=https://gh.<domain>
CF_ACCESS_CLIENT_ID=<token id>
CF_ACCESS_CLIENT_SECRET=<token secret>
REGION=<extract used>
GH_VERSION=<from container logs or /info endpoint>
```

Plus any deviations from this guide (config syntax changes, image swapped, RAM
limits). Do not commit the token secret to any repo.

## Out of scope (do not build)

- No custom Java tag parsers / circumcircle curvature scoring — parked for later.
- No changes to the Android app — happens elsewhere.
- No public/unauthenticated exposure of the routing API.
