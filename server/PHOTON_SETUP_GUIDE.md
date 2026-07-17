# Map Roulette — Geocoder (Photon) Setup Guide

**Audience: a Claude (or human) agent working on the owner's personal server.
You have no prior context; everything you need is in this file.**

## Context

Map Roulette is a personal Android app. Its search box (address / place / POI
lookup) previously called the public OSM Nominatim service, whose ranking is
poor for type-ahead — a famous city could land below local streets of the same
name. The app now speaks the **Photon** API, an OSM-backed geocoder built for
type-ahead that blends the query match with proximity to the user.

**Goal of this task:** run a self-hosted Photon on this server so search is fast,
private, and not rate-limited, exposed through the existing Cloudflare tunnel and
protected with the **same Cloudflare Access service token** the routing server
already uses. The app reuses that one token for routing, sync and the geocoder.

Key facts:
- Photon serves `GET /api?q=<text>&lat=&lon=&limit=` on port **2322** and returns
  GeoJSON features. The app sends `lat`/`lon` to bias ranking toward the user.
- Photon uses **single-country prebuilt indexes** from GraphHopper's mirror. This
  server runs **Belgium** (`be`). Multi-country (e.g. Benelux) has no prebuilt
  index — it needs building Photon from a Nominatim DB and is out of scope here.
- The jar version must match the index's OpenSearch format. Pinned:
  **Photon 1.2.1** (`PHOTON_VERSION`). If startup errors on an index/OpenSearch
  version mismatch, set it to the release the index was built with and re-run.

## Success criteria (verify all before reporting done)

1. `curl "http://localhost:2322/api?q=oostende&lat=51.2&lon=2.9&limit=3"` returns
   HTTP 200 with a GeoJSON `features` array, nearest matches first.
2. Same request works through the Cloudflare tunnel hostname with Access service
   token headers, and is **rejected without** them.
3. Photon survives a reboot (`restart: unless-stopped`).
4. A monthly index-refresh mechanism exists (systemd timer or documented manual step).

## The easy path — the installer does all of it

From a clone of the repo on this server:

```bash
sudo bash server/install.sh --geocoder --geo-country be
```

It installs Docker if needed, downloads the newest Belgium index (~1.6 GB) from
the mirror, extracts it, fetches `photon-1.2.1.jar`, writes a docker-compose
service bound to `127.0.0.1:2322`, enables a monthly refresh timer, and waits for
the API to answer. It binds to localhost only — it does **not** expose anything.
Then do "Step: expose" below. (`--all` installs sync + routing + geocoder together.)

Skip to "Step: expose via Cloudflare" once that finishes.

## Manual path (if not using the installer)

```bash
sudo mkdir -p /opt/photon/data && cd /opt/photon/data

# 1. Newest Belgium index (the -latest symlink 404s on the mirror, so list it):
base="https://download1.graphhopper.com/public/extracts/by-country-code/be/"
file="$(curl -fsS "$base" | grep -oE 'photon-db-be-[0-9]+\.tar\.bz2' | sort -u | tail -1)"
curl -fL# "$base$file" -o "$file"
tar -xjf "$file" && rm -f "$file"     # -> ./photon_data/

# 2. Matching jar:
curl -fL# https://github.com/komoot/photon/releases/download/1.2.1/photon-1.2.1.jar -o photon.jar
```

`/opt/photon/docker-compose.yml`:

```yaml
services:
  photon:
    image: eclipse-temurin:21-jre
    container_name: photon
    working_dir: /photon
    command: java -Xmx2g -jar /photon/photon.jar -data-dir /photon -listen-ip 0.0.0.0 -listen-port 2322
    volumes:
      - ./data:/photon      # holds photon.jar and photon_data/
    ports:
      - "127.0.0.1:2322:2322"
    restart: unless-stopped
```

```bash
cd /opt/photon && docker compose up -d && docker compose logs -f
```

Wait ~1-3 min for the index to load, then verify locally:

```bash
curl -s "http://localhost:2322/api?q=oostende&lat=51.2&lon=2.9&limit=3" | head -c 400
```

## Step: expose via Cloudflare tunnel + Access

1. Add an ingress rule to the existing tunnel mapping a hostname (e.g.
   `photon.<owner's domain>`) to `http://localhost:2322`.
2. Photon has **no authentication**. In Cloudflare Zero Trust, add this hostname
   to an Access application whose policy is Action **Service Auth**, including the
   **same service token already used for the routing server** — do not mint a new
   one; the app sends a single token to all services.
3. Verify from outside:

```bash
# must be 403/302 (blocked):
curl -s -o /dev/null -w "%{http_code}\n" "https://photon.<domain>/api?q=gent"
# must be 200:
curl -s -o /dev/null -w "%{http_code}\n" \
  -H "CF-Access-Client-Id: <token id>" \
  -H "CF-Access-Client-Secret: <token secret>" \
  "https://photon.<domain>/api?q=gent&lat=51.05&lon=3.7&limit=3"
```

## Step: monthly index refresh

The installer sets up a `photon-refresh.timer`. Manual equivalent:

```bash
base="https://download1.graphhopper.com/public/extracts/by-country-code/be/"
file="$(curl -fsS "$base" | grep -oE 'photon-db-be-[0-9]+\.tar\.bz2' | sort -u | tail -1)"
cd /opt/photon/data && curl -fL "$base$file" -o "$file"
cd /opt/photon && docker compose down
rm -rf data/photon_data && tar -xjf "data/$file" -C data && rm -f "data/$file"
docker compose up -d
```

## Step: report back to the owner

```
GEOCODER_URL=https://photon.<domain>
# CF Access token: reuse the routing server's — same CF_ACCESS_CLIENT_ID / SECRET
GEO_COUNTRY=be
PHOTON_VERSION=<jar version that actually started>
```

Then in the app: Settings → Routing server → "Search server URL" = the geocoder
URL. The Cloudflare Access ID/secret entered for routing are reused automatically.

## Out of scope (do not build)

- No multi-country / planet index unless the owner asks (needs a Nominatim build).
- No public/unauthenticated exposure of the geocoder API.
- No changes to the Android app — the search client already speaks Photon.
