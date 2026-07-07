# Map Roulette — Sync Server Setup Guide

**Audience: a Claude (or human) agent working on the owner's personal server.
You have no prior context; everything you need is in this file.**

## Context

Map Roulette is a personal Android app (repo: https://github.com/maxke24/map_roulette).
It records trips and a "fog of war" of explored roads in app-private files. The app
now syncs those to this server (one `POST /sync` merges both ways), so deleting and
reinstalling the app loses nothing.

The GraphHopper routing server from `CLAUDE_SETUP_GUIDE.md` already runs here behind
a Cloudflare tunnel with Cloudflare Access. The sync server must be exposed the same
way — the app sends the **same CF Access service-token headers** it uses for routing,
so protect the sync hostname with the **same Access application/service token** (or
add the existing service token to a new Access app for the sync hostname).

The service itself is `sync/sync_server.py` in this directory: Python 3 stdlib only,
no dependencies, stores two small files. It does no authentication — Cloudflare
Access is the auth boundary, so it must never be reachable except via the tunnel.

## Success criteria (verify all before reporting done)

1. `curl -s -X POST localhost:8790/sync -d '{"trips":[],"traces":[]}'` returns
   HTTP 200 with `{"trips": [...], "traces": [...]}`.
2. Same request through the tunnel hostname works **with** CF Access service-token
   headers and is **rejected without** them.
3. Merging is idempotent: POSTing the same body twice does not duplicate anything.
4. Service survives a reboot.
5. Data directory is covered by whatever backup the owner has for this server
   (or at minimum, document where it lives).

## Step 1 — install

```bash
sudo mkdir -p /opt/maproulette-sync
sudo cp sync/sync_server.py /opt/maproulette-sync/
sudo useradd --system --no-create-home maproulette-sync || true
sudo mkdir -p /var/lib/maproulette-sync
sudo chown maproulette-sync: /var/lib/maproulette-sync
```

Create `/etc/systemd/system/maproulette-sync.service`:

```ini
[Unit]
Description=Map Roulette sync server
After=network.target

[Service]
User=maproulette-sync
Environment=DATA_DIR=/var/lib/maproulette-sync
Environment=HOST=127.0.0.1
Environment=PORT=8790
ExecStart=/usr/bin/python3 /opt/maproulette-sync/sync_server.py
Restart=on-failure

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now maproulette-sync
curl -s localhost:8790/health   # -> ok
```

(If the owner prefers docker compose next to GraphHopper, a `python:3-alpine`
service running the same file with a mounted volume is equivalent — pick whichever
matches how GraphHopper is run on this server.)

## Step 2 — expose through the Cloudflare tunnel

Add an ingress rule to the existing tunnel config (commonly
`/etc/cloudflared/config.yml`, or the Zero Trust dashboard if the tunnel is
dashboard-managed):

```yaml
  - hostname: maproulette-sync.<owner-domain>
    service: http://localhost:8790
```

Then restart/rollout `cloudflared` and create the DNS route:

```bash
cloudflared tunnel route dns <tunnel> maproulette-sync.<owner-domain>
```

## Step 3 — protect with Cloudflare Access

In Zero Trust → Access → Applications, add a self-hosted application for
`maproulette-sync.<owner-domain>` and attach a **Service Auth** policy that allows
the **existing Map Roulette service token** (the one the routing server uses). The
app sends `CF-Access-Client-Id` / `CF-Access-Client-Secret` with every sync request.

## Step 4 — verify

```bash
# through the tunnel, with the service token: 200
curl -s -X POST https://maproulette-sync.<owner-domain>/sync \
  -H "CF-Access-Client-Id: <id>" \
  -H "CF-Access-Client-Secret: <secret>" \
  -H "Content-Type: application/json" \
  -d '{"trips":[{"startTimeMs":1,"endTimeMs":2,"distanceMeters":3.0,"topSpeedMps":1.0,"destinationLat":null,"destinationLon":null}],"traces":["[[50.1,4.1],[50.2,4.2]]"]}'

# repeat the same request: reply must be identical (idempotent merge)

# without the headers: Access must block it (302 to login or 403)
curl -s -o /dev/null -w '%{http_code}\n' \
  -X POST https://maproulette-sync.<owner-domain>/sync -d '{}'
```

## Afterwards — tell the owner

Report the final sync URL (`https://maproulette-sync.<owner-domain>`). The owner
enters it in the app under Settings → Backup sync (or bakes it into the APK via
`sync.url` in `local.properties` / `SYNC_SERVER_URL` in CI secrets).
