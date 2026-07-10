# Map Roulette — Multiplayer Sync Server Upgrade Guide

**Audience: a Claude (or human) agent working on the owner's personal server.
You have no prior context; everything you need is in this file.**

> This **replaces** the service described in `SYNC_SETUP_GUIDE.md`. Same hostname,
> same port, same systemd unit, same Cloudflare tunnel — new `sync_server.py`,
> and the data moves from two flat files into SQLite. Read that guide only if the
> sync service is not yet installed at all; otherwise follow this one.

## Context

Map Roulette is a personal Android app (repo: https://github.com/maxke24/map_roulette)
that records rides and a "fog of war" of explored roads. Until now the sync server
had **no authentication**: one user, two flat files (`trips.json`, `traces.jsonl`),
protected only by Cloudflare Access sitting in front of the hostname.

The app now supports **accounts and friends**. The upgrade adds:

- user accounts (username + password), bearer-token sessions;
- per-user trips, traces and badges in SQLite;
- friend requests, and a leaderboard of friends' **aggregate stats**.

**The privacy rule, which is the whole point:** friends can see totals and badges.
They can never see trips or traces. Exactly one function returns another user's
data (`friend_stats`), and it reads only `stats_json` and `badges_json`. If you
change that function, you are changing the privacy guarantee — don't.

### Two credentials, two jobs

Do not confuse these:

| Credential | Set by | Proves | Shared? |
|---|---|---|---|
| CF Access service token | Cloudflare | you may reach the hostname | Yes — baked into every copy of the APK |
| Bearer token | this server | **which user you are** | No — one per login |

The CF service token is a gate on the door. It is the same for the owner and every
friend, because they all run the same APK. It is **not** identity. Identity is the
bearer token, and that is what decides whose trips come back from `/sync`.

## Success criteria (verify all before reporting done)

Run the script in [Step 6](#step-6--verify) — it checks every item below.

1. `GET /health` returns `ok` without any auth.
2. `POST /sync` **without** a bearer token returns `401`.
3. Two different users who both `POST /sync` get back **only their own** trips.
4. `GET /friends/stats` returns a friend's totals and badges, and contains
   **no** `trips` or `traces` key.
5. `GET /friends/stats` returns nothing for a friend request that is still pending.
6. `GET /friends/fog` returns `{"sharing": false, "traces": []}` for a caller who
   has not opted in, returns nothing for a friend who has not opted in, returns
   nothing for a pending friend, and never returns any `trips`.
7. Syncing the same body twice does not duplicate trips, traces or badges.
8. Eleven wrong passwords from one IP get `429`; a correct password before that
   still gets `200`.
9. The owner's pre-existing trips and traces are present after `--import-legacy`.
10. Through the tunnel hostname: works **with** CF Access service-token headers,
    rejected **without** them.
11. Service survives a reboot; the SQLite file is covered by backups.

## Step 0 — back up first, this migration is one-way

```bash
sudo systemctl stop maproulette-sync
sudo tar czf ~/maproulette-sync-backup-$(date +%F).tar.gz /var/lib/maproulette-sync
ls -lh ~/maproulette-sync-backup-*.tar.gz
```

Do not delete `trips.json` / `traces.jsonl` until Step 6 passes. The new server
never reads or writes them except on the explicit `--import-legacy` command.

## Step 1 — install the new server

`sync/sync_server.py` in the app repo is the whole service: Python 3.8+, stdlib
only, no dependencies.

```bash
sudo cp sync/sync_server.py /opt/maproulette-sync/sync_server.py
sudo chown root:root /opt/maproulette-sync/sync_server.py
sudo chmod 0644 /opt/maproulette-sync/sync_server.py
```

## Step 2 — environment

The unit needs these; `DATA_DIR` must be the directory that already holds
`trips.json` (so the legacy import can find it).

| Variable | Default | Meaning |
|---|---|---|
| `DATA_DIR` | `./data` | Where `maproulette.db` lives (and the legacy files) |
| `HOST` | `127.0.0.1` | **Keep localhost.** The tunnel reaches it; the internet must not |
| `PORT` | `8790` | Unchanged from the old server |
| `REGISTRATION_OPEN` | `1` | Set to `0` to refuse all new accounts |
| `INVITE_CODE` | *(empty)* | If set, `/auth/register` requires a matching `invite` field |

Edit the existing unit (`/etc/systemd/system/maproulette-sync.service`) so its
`ExecStart` still points at `sync_server.py`, and add whichever of the above you
want. Leave `REGISTRATION_OPEN=1` for now — you need to register at least once.

```bash
sudo systemctl daemon-reload
sudo systemctl start maproulette-sync
sudo systemctl status maproulette-sync --no-pager
curl -s localhost:8790/health   # expect: ok
```

On first start the server creates `$DATA_DIR/maproulette.db` and its tables.
SQLite runs in WAL mode, so expect `maproulette.db-wal` and `-shm` beside it.

## Step 3 — create the owner's account

Registration is a normal API call. Do it from the server itself; it does not need
the CF Access headers because you are inside the tunnel.

```bash
curl -s -X POST localhost:8790/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"username":"jelle","password":"<a long password>"}'
# -> {"token":"...","username":"jelle"}
```

Use a real password — this now guards every ride the owner has ever recorded.
Do **not** paste it into a file the agent leaves behind, and do not echo it into
shell history if the shell is persisted (prefix the command with a space, or use
`read -s PW`).

## Step 4 — import the old data

This is the only command that reads `trips.json` / `traces.jsonl`. It attributes
every one of them to the named user.

```bash
sudo systemctl stop maproulette-sync
sudo -u maproulette-sync DATA_DIR=/var/lib/maproulette-sync \
  python3 /opt/maproulette-sync/sync_server.py --import-legacy jelle
sudo systemctl start maproulette-sync
```

It prints how many trips and trace lines it moved. It uses `INSERT OR IGNORE`, so
running it twice is harmless. It leaves the old files alone — delete them only
after Step 6 passes and the app has synced once.

## Step 5 — close the door behind you

Once every friend has an account, either close registration outright:

```
Environment=REGISTRATION_OPEN=0
```

or keep it open behind a shared secret, which is friendlier if you add people
occasionally:

```
Environment=INVITE_CODE=some-long-random-string
```

The app has an "Invite code" field on its sign-in screen for exactly this.
`daemon-reload` + `restart` after changing either.

## Step 6 — verify

Save as `/tmp/verify.sh`, run it, read the last line. It creates two throwaway
users on the live database; delete them afterwards if that bothers you (see
[Housekeeping](#housekeeping)).

```bash
#!/usr/bin/env bash
# Verifies the multiplayer sync server against the success criteria.
set -u
B=http://localhost:8790
A="verify_a_$RANDOM"; C="verify_b_$RANDOM"; PW="verify-password-123"
fails=0
ck() { if [ "$2" = "$3" ]; then echo "PASS  $1"; else echo "FAIL  $1 (got=$2 want=$3)"; fails=$((fails+1)); fi; }
jq_() { python3 -c "import sys,json; d=json.load(sys.stdin); print($1)"; }

ck "health"        "$(curl -s $B/health)" "ok"
ck "sync needs auth" "$(curl -s -o /dev/null -w '%{http_code}' -X POST $B/sync -d '{}')" "401"

TA=$(curl -s -X POST $B/auth/register -d "{\"username\":\"$A\",\"password\":\"$PW\"}" | jq_ 'd["token"]')
TB=$(curl -s -X POST $B/auth/register -d "{\"username\":\"$C\",\"password\":\"$PW\"}" | jq_ 'd["token"]')
[ -n "$TA" ] && [ -n "$TB" ] || { echo "FAIL registration (is REGISTRATION_OPEN=0 or INVITE_CODE set?)"; exit 1; }

BODY='{"trips":[{"startTimeMs":1,"distanceMeters":1000.0,"topSpeedMps":30.0}],
       "traces":["[[52.0,5.0],[52.1,5.1]]"],"badges":{"dist_100000":1700000000000},
       "stats":{"totalDistanceMeters":1000.0,"topSpeedKmh":108.0,"tripCount":1}}'
ck "A syncs"  "$(curl -s -X POST $B/sync -H "Authorization: Bearer $TA" -d "$BODY" | jq_ 'len(d["trips"])')" "1"
ck "idempotent" "$(curl -s -X POST $B/sync -H "Authorization: Bearer $TA" -d "$BODY" | jq_ 'len(d["trips"])')" "1"
ck "B sees none of A's trips"  "$(curl -s -X POST $B/sync -H "Authorization: Bearer $TB" -d '{"trips":[],"traces":[]}' | jq_ 'len(d["trips"])')" "0"
ck "B sees none of A's traces" "$(curl -s -X POST $B/sync -H "Authorization: Bearer $TB" -d '{"trips":[],"traces":[]}' | jq_ 'len(d["traces"])')" "0"

curl -s -X POST $B/friends/request -H "Authorization: Bearer $TA" -d "{\"username\":\"$C\"}" >/dev/null
ck "no stats while pending" "$(curl -s $B/friends/stats -H "Authorization: Bearer $TB" | jq_ 'len(d)')" "0"
curl -s -X POST $B/friends/respond -H "Authorization: Bearer $TB" -d "{\"username\":\"$A\",\"accept\":true}" >/dev/null
ck "friend visible"  "$(curl -s $B/friends/stats -H "Authorization: Bearer $TB" | jq_ 'len(d)')" "1"
ck "friend km shared" "$(curl -s $B/friends/stats -H "Authorization: Bearer $TB" | jq_ 'int(d[0]["stats"]["totalDistanceMeters"])')" "1000"
ck "NO trips leaked"  "$(curl -s $B/friends/stats -H "Authorization: Bearer $TB" | jq_ '"trips" in d[0]')" "False"
ck "NO traces leaked" "$(curl -s $B/friends/stats -H "Authorization: Bearer $TB" | jq_ '"traces" in d[0]')" "False"

ck "good login ok" "$(curl -s -o /dev/null -w '%{http_code}' -X POST $B/auth/login -d "{\"username\":\"$A\",\"password\":\"$PW\"}")" "200"
for i in $(seq 1 11); do
  LAST=$(curl -s -o /dev/null -w '%{http_code}' -X POST $B/auth/login -d "{\"username\":\"$A\",\"password\":\"wrongwrong\"}")
done
ck "brute force locked out" "$LAST" "429"

echo; [ $fails -eq 0 ] && echo "ALL PASS" || echo "$fails FAILURES"
exit $fails
```

Then the two checks the script cannot do for you:

```bash
# 9a. through the tunnel WITH service token -> 200
curl -s -o /dev/null -w '%{http_code}\n' https://<sync-hostname>/health \
  -H "CF-Access-Client-Id: <id>" -H "CF-Access-Client-Secret: <secret>"
# 9b. through the tunnel WITHOUT it -> 302/403, never 200
curl -s -o /dev/null -w '%{http_code}\n' https://<sync-hostname>/health

# 10. reboot survival
sudo systemctl is-enabled maproulette-sync   # expect: enabled
```

And confirm the owner's real data survived:

```bash
sudo -u maproulette-sync sqlite3 /var/lib/maproulette-sync/maproulette.db \
  "SELECT u.username, COUNT(t.start_ms) FROM users u
   LEFT JOIN trips t ON t.user_id = u.id GROUP BY u.id;"
```

## API reference

Everything except `/health` and `/auth/*` requires `Authorization: Bearer <token>`.
Errors are `{"error": "..."}` with a real HTTP status; the app shows that string
to the user verbatim.

| Method | Path | Body | Returns |
|---|---|---|---|
| GET | `/health` | — | `ok` |
| POST | `/auth/register` | `{username, password, invite?}` | `{token, username}` |
| POST | `/auth/login` | `{username, password}` | `{token, username}` |
| POST | `/auth/logout` | — | `{}` (revokes this token) |
| GET | `/me` | — | `{username, stats, badges}` |
| POST | `/sync` | `{trips, traces, badges, stats}` | merged `{trips, traces, badges}` |
| GET | `/friends` | — | `{friends, incoming, outgoing}` |
| POST | `/friends/request` | `{username}` | `{status}` |
| POST | `/friends/respond` | `{username, accept}` | `{status}` |
| POST | `/friends/remove` | `{username}` | `{}` |
| GET | `/friends/stats` | — | `[{username, stats, badges}]` |
| GET | `/friends/fog` | — | `{sharing, traces}` (see below) |

Merge semantics, all idempotent:

- **trips** deduplicate on `(user, startTimeMs)`;
- **traces** deduplicate on `(user, sha256(line))`;
- **badges** keep the **earliest** `earnedAtMs` per id, so a reinstall can never
  push an earned date forward;
- **stats** are replaced wholesale by the client's latest — but only when the
  `stats` key is present. A payload without it leaves the stored stats alone.
- **shareFog** likewise: present in `/sync` sets the flag, absent leaves it be,
  so an older client cannot silently opt its user in or out.

### Shared fog of war

`/friends/fog` is the **only** endpoint that returns another user's traces, and
it does so under two conditions that both have to hold:

1. the caller has `share_fog` set, and
2. so has the friend whose traces are being returned.

Sharing is off by default (including for every account that existed before the
column did), reciprocal — a user who stops sharing also stops receiving — and
revocable, taking effect on the very next request. Trips are never returned for
anyone but their owner, by any endpoint. The lines come back as one unattributed
list: it is a combined map, not a per-friend history.

Sending `/friends/request` to someone who already requested you accepts their
request instead of creating a second one.

## Security notes

What the implementation actually does, so you can check it still does:

- **Passwords**: PBKDF2-HMAC-SHA256, 210,000 rounds, 16-byte random per-user salt.
  Never stored or logged in plaintext.
- **Tokens**: 32 random bytes from `secrets`, stored only as a SHA-256 hash. A
  database leak does not hand over live sessions.
- **Comparisons**: `hmac.compare_digest` for both password digests and the invite
  code.
- **Unknown users**: `/auth/login` computes a dummy PBKDF2 hash before failing,
  so response timing does not reveal whether an account exists.
- **Rate limiting**: per client IP (`CF-Connecting-IP` when behind the tunnel),
  10 **failures** per 5 minutes. Successful logins never consume the budget, so a
  busy honest client is never locked out.
- **Input validation**: usernames match `^[A-Za-z0-9_.-]{3,24}$`; badge ids match
  `^[a-z]+_[0-9]+$`; stats accept only seven known keys, only finite numbers, and
  are capped in count. A friend's app cannot push arbitrary content into a payload
  other people read.
- **SQL**: every query is parameterised. No string interpolation into SQL.
- **Errors**: handlers never return a stack trace; they log it and return
  `{"error": "internal error"}`.

Two things this does **not** do, on purpose:

- No password reset. There is no mail server. If someone forgets their password,
  delete the row and let them register again (their data is keyed on `user_id`,
  so it will be orphaned — reassign it with SQL if it matters).
- No token expiry. Tokens live until `/auth/logout`. For a handful of friends on
  a private server this is the right trade; if that changes, add a `last_used_ms`
  sweep (the column already exists).

## Housekeeping

**Backups.** The database is one file. Back it up with SQLite's own command, not
`cp` — a plain copy of a WAL database can be torn:

```bash
sudo -u maproulette-sync sqlite3 /var/lib/maproulette-sync/maproulette.db \
  ".backup '/var/backups/maproulette-$(date +%F).db'"
```

**Remove the verification users:**

```bash
sudo -u maproulette-sync sqlite3 /var/lib/maproulette-sync/maproulette.db \
  "DELETE FROM users WHERE username LIKE 'verify_%';"
```

`ON DELETE CASCADE` cleans up their tokens, trips, traces and friendships.

**Rollback.** If something is wrong, the old server and its flat files still work:
restore `sync_server.py` from git history, `systemctl restart`, and the untouched
`trips.json` / `traces.jsonl` are exactly where they were. This is why Step 4 does
not delete them.

## What the app expects

The Android app (v1.20+) will:

1. refuse to sync at all until the user signs in — `syncQuietly` is a no-op with
   no token, so an un-upgraded phone silently stops syncing rather than erroring;
2. send `trips`, `traces`, `badges` and `stats` on every sync, and overwrite its
   local stores with whatever comes back;
3. show the server's `{"error": ...}` string directly to the user, so error text
   here is user-facing — keep it plain.

`stats.bestCoveragePercent` and `stats.municipalitiesVisited` are computed on the
phone from OpenStreetMap municipality boundaries. The server stores them without
understanding them; it cannot recompute them, because it has no boundaries.
