#!/usr/bin/env python3
"""Map Roulette sync + social server.

Stores each user's trip history, fog-of-war traces and badges in SQLite, and
lets users befriend each other and compare aggregate stats.

Privacy rules, each enforced in exactly one place:

  - **Trips are never returned for anyone but their owner.** No endpoint reads
    another user's `trips` rows at all.
  - **Traces leave their owner only through `friend_fog`,** and only when both
    users have set `share_fog`. Sharing is off by default, reciprocal (a user
    who does not share sees nothing), and revocable — clearing the flag stops
    the traces being served from the next request on.
  - Friends otherwise see only the aggregate numbers the owner's app computed
    (total km, top speed, badges, …), via `friend_stats`.

Protocol
  GET  /health                                  -> "ok"
  POST /auth/register {username, password, invite?}  -> {token, username}
  POST /auth/login    {username, password}      -> {token, username}
  POST /auth/logout                             -> {} (revokes the bearer token)
  GET  /me                                      -> {username, stats, badges}
  POST /sync {trips, traces, badges, stats, shareFog?} -> merged {trips, traces, badges}
  GET  /friends                                 -> {friends, incoming, outgoing}
  POST /friends/request {username}              -> {status}
  POST /friends/respond {username, accept}      -> {status}
  POST /friends/remove  {username}              -> {}
  GET  /friends/stats                           -> [{username, stats, badges}]
  GET  /friends/fog                             -> {sharing, traces: [line, …]}

Everything except /health and /auth/* needs `Authorization: Bearer <token>`.

Merging is idempotent:
  - trips key on (user, startTimeMs); a re-upload updates the stored copy, so
    an edit like a corrected vehicle mode propagates instead of being ignored;
  - traces deduplicate on (user, sha256 of the line);
  - badges keep the *earliest* earnedAtMs seen for each id.

Auth notes
  - Passwords: PBKDF2-HMAC-SHA256, per-user random salt, ITERATIONS rounds.
  - Tokens: 32 random bytes, stored only as a SHA-256 hash. A database leak
    does not hand over live sessions.
  - Comparisons use hmac.compare_digest.
  - Login and register are rate limited per client IP.

This still expects to sit behind the Cloudflare tunnel + Access, exactly as the
old version did. Access is a gate on the hostname; the bearer token is identity.
Bind to localhost.

Python 3.8+ stdlib only. DATA_DIR env var sets the storage directory.

CLI:
  python3 sync_server.py                      run the server
  python3 sync_server.py --import-legacy USER import old trips.json/traces.jsonl
"""
import hashlib
import hmac
import json
import os
import re
import secrets
import sqlite3
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

DATA_DIR = os.environ.get("DATA_DIR", os.path.join(os.path.dirname(__file__), "data"))
DB_FILE = os.path.join(DATA_DIR, "maproulette.db")
LEGACY_TRIPS = os.path.join(DATA_DIR, "trips.json")
LEGACY_TRACES = os.path.join(DATA_DIR, "traces.jsonl")

MAX_BODY = 64 * 1024 * 1024
ITERATIONS = 210_000
TOKEN_BYTES = 32

USERNAME_RE = re.compile(r"^[A-Za-z0-9_.-]{3,24}$")
BADGE_ID_RE = re.compile(r"^[a-z]+_[0-9]+$")
MAX_BADGES = 200

# Only these stat keys are stored, and only as finite numbers. A friend's app
# cannot push arbitrary blobs into a payload other people will read.
STAT_KEYS = (
    "totalDistanceMeters",
    "topSpeedKmh",
    "longestTripMeters",
    "maxLeanDeg",
    "municipalitiesVisited",
    "bestCoveragePercent",
    "tripCount",
)

# Registration can be closed once your friends have accounts (env), or gated on
# a shared invite code.
REGISTRATION_OPEN = os.environ.get("REGISTRATION_OPEN", "1") != "0"
INVITE_CODE = os.environ.get("INVITE_CODE", "")

# Per-IP rate limit on the auth endpoints.
AUTH_MAX_ATTEMPTS = 10
AUTH_WINDOW_SEC = 300

_local = threading.local()
_attempts = {}
_attempts_lock = threading.Lock()
_write_lock = threading.Lock()


class HttpError(Exception):
    def __init__(self, code, message):
        super().__init__(message)
        self.code = code
        self.message = message


# --------------------------------------------------------------------------
# database


def db():
    """One connection per thread; WAL so readers never block the writer."""
    conn = getattr(_local, "conn", None)
    if conn is None:
        conn = sqlite3.connect(DB_FILE, timeout=30)
        conn.row_factory = sqlite3.Row
        conn.execute("PRAGMA journal_mode=WAL")
        conn.execute("PRAGMA foreign_keys=ON")
        conn.execute("PRAGMA busy_timeout=30000")
        _local.conn = conn
    return conn


def init_db():
    conn = db()
    conn.executescript(
        """
        CREATE TABLE IF NOT EXISTS users (
            id         INTEGER PRIMARY KEY,
            username   TEXT NOT NULL UNIQUE COLLATE NOCASE,
            pw_salt    BLOB NOT NULL,
            pw_hash    BLOB NOT NULL,
            iterations INTEGER NOT NULL,
            created_ms INTEGER NOT NULL,
            stats_json TEXT NOT NULL DEFAULT '{}',
            badges_json TEXT NOT NULL DEFAULT '{}',
            share_fog  INTEGER NOT NULL DEFAULT 0
        );
        CREATE TABLE IF NOT EXISTS tokens (
            token_hash   TEXT PRIMARY KEY,
            user_id      INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            created_ms   INTEGER NOT NULL,
            last_used_ms INTEGER NOT NULL
        );
        CREATE TABLE IF NOT EXISTS trips (
            user_id  INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            start_ms INTEGER NOT NULL,
            json     TEXT NOT NULL,
            PRIMARY KEY (user_id, start_ms)
        );
        CREATE TABLE IF NOT EXISTS traces (
            user_id   INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            line_hash TEXT NOT NULL,
            line      TEXT NOT NULL,
            PRIMARY KEY (user_id, line_hash)
        );
        -- One row per pair, with low_id < high_id so a pair can never be
        -- represented twice. requested_by says who has to accept.
        CREATE TABLE IF NOT EXISTS friendships (
            low_id       INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            high_id      INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            status       TEXT NOT NULL CHECK (status IN ('pending', 'accepted')),
            requested_by INTEGER NOT NULL,
            created_ms   INTEGER NOT NULL,
            PRIMARY KEY (low_id, high_id)
        );
        CREATE INDEX IF NOT EXISTS idx_tokens_user ON tokens(user_id);
        """
    )
    # Added after the first release; CREATE TABLE IF NOT EXISTS won't add it to
    # a database that already exists.
    columns = {r["name"] for r in conn.execute("PRAGMA table_info(users)")}
    if "share_fog" not in columns:
        conn.execute("ALTER TABLE users ADD COLUMN share_fog INTEGER NOT NULL DEFAULT 0")
    conn.commit()


def now_ms():
    return int(time.time() * 1000)


# --------------------------------------------------------------------------
# auth


def hash_password(password, salt=None, iterations=ITERATIONS):
    salt = salt or secrets.token_bytes(16)
    digest = hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), salt, iterations)
    return salt, digest, iterations


def token_hash(raw):
    return hashlib.sha256(raw.encode("utf-8")).hexdigest()


def issue_token(user_id):
    raw = secrets.token_urlsafe(TOKEN_BYTES)
    with _write_lock:
        conn = db()
        conn.execute(
            "INSERT INTO tokens (token_hash, user_id, created_ms, last_used_ms)"
            " VALUES (?, ?, ?, ?)",
            (token_hash(raw), user_id, now_ms(), now_ms()),
        )
        conn.commit()
    return raw


def authenticate(headers):
    header = headers.get("Authorization", "")
    if not header.startswith("Bearer "):
        raise HttpError(401, "missing bearer token")
    row = db().execute(
        "SELECT u.* FROM tokens t JOIN users u ON u.id = t.user_id"
        " WHERE t.token_hash = ?",
        (token_hash(header[7:].strip()),),
    ).fetchone()
    if row is None:
        raise HttpError(401, "invalid token")
    return row


def rate_limit(ip):
    """Only *failures* count, so a busy honest client is never locked out while
    someone guessing passwords is stopped after AUTH_MAX_ATTEMPTS."""
    cutoff = time.time() - AUTH_WINDOW_SEC
    with _attempts_lock:
        hits = [t for t in _attempts.get(ip, []) if t > cutoff]
        if hits:
            _attempts[ip] = hits
        else:
            _attempts.pop(ip, None)  # keep the table from growing forever
        if len(hits) >= AUTH_MAX_ATTEMPTS:
            raise HttpError(429, "too many attempts; wait a few minutes")


def note_failure(ip):
    with _attempts_lock:
        _attempts.setdefault(ip, []).append(time.time())


def find_user(username):
    return db().execute(
        "SELECT * FROM users WHERE username = ? COLLATE NOCASE", (username,)
    ).fetchone()


def do_register(body, ip):
    if not REGISTRATION_OPEN:
        raise HttpError(403, "registration is closed")
    rate_limit(ip)
    username = str(body.get("username", "")).strip()
    password = str(body.get("password", ""))
    if not USERNAME_RE.match(username):
        raise HttpError(400, "username must be 3-24 chars: letters, digits, . _ -")
    if not 8 <= len(password) <= 200:
        raise HttpError(400, "password must be 8-200 characters")
    if INVITE_CODE and not hmac.compare_digest(str(body.get("invite", "")), INVITE_CODE):
        note_failure(ip)  # guessing the invite code is an attack, not a typo
        raise HttpError(403, "invalid invite code")
    if find_user(username):
        raise HttpError(409, "username already taken")

    salt, digest, iterations = hash_password(password)
    with _write_lock:
        conn = db()
        try:
            cur = conn.execute(
                "INSERT INTO users (username, pw_salt, pw_hash, iterations, created_ms)"
                " VALUES (?, ?, ?, ?, ?)",
                (username, salt, digest, iterations, now_ms()),
            )
        except sqlite3.IntegrityError:
            raise HttpError(409, "username already taken")
        conn.commit()
        user_id = cur.lastrowid
    return {"token": issue_token(user_id), "username": username}


def do_login(body, ip):
    rate_limit(ip)
    username = str(body.get("username", "")).strip()
    password = str(body.get("password", ""))
    user = find_user(username)
    if user is None:
        # Spend the same work as a real check so timing doesn't reveal whether
        # the account exists.
        hash_password(password, salt=b"\x00" * 16)
        note_failure(ip)
        raise HttpError(401, "wrong username or password")
    _, digest, _ = hash_password(password, bytes(user["pw_salt"]), user["iterations"])
    if not hmac.compare_digest(digest, bytes(user["pw_hash"])):
        note_failure(ip)
        raise HttpError(401, "wrong username or password")
    return {"token": issue_token(user["id"]), "username": user["username"]}


def do_logout(user, headers):
    raw = headers.get("Authorization", "")[7:].strip()
    with _write_lock:
        conn = db()
        conn.execute("DELETE FROM tokens WHERE token_hash = ?", (token_hash(raw),))
        conn.commit()
    return {}


# --------------------------------------------------------------------------
# sync


def clean_stats(raw):
    """Keep only known numeric keys, and only finite ones."""
    out = {}
    if not isinstance(raw, dict):
        return out
    for key in STAT_KEYS:
        value = raw.get(key)
        if isinstance(value, bool) or not isinstance(value, (int, float)):
            continue
        if value != value or value in (float("inf"), float("-inf")):
            continue
        out[key] = value
    return out


def clean_badges(raw):
    out = {}
    if not isinstance(raw, dict):
        return out
    for badge_id, earned in list(raw.items())[:MAX_BADGES]:
        if not BADGE_ID_RE.match(str(badge_id)):
            continue
        if isinstance(earned, bool) or not isinstance(earned, int):
            continue
        out[str(badge_id)] = earned
    return out


def do_sync(user, body):
    uid = user["id"]
    trips_in = body.get("trips") or []
    traces_in = body.get("traces") or []
    if not isinstance(trips_in, list) or not isinstance(traces_in, list):
        raise HttpError(400, "trips and traces must be arrays")

    badges = clean_badges(json.loads(user["badges_json"]))
    for badge_id, earned in clean_badges(body.get("badges")).items():
        # First time earned wins, so a reinstall can't reset the date forward.
        if badge_id not in badges or earned < badges[badge_id]:
            badges[badge_id] = earned

    # Absent means "no update", not "clear". A client that syncs only trips must
    # not blank out the stats its friends are reading.
    stats = (
        clean_stats(body["stats"])
        if "stats" in body
        else json.loads(user["stats_json"])
    )

    # Absent means "leave it alone", so an old client that knows nothing about
    # shared fog can't silently turn a user's sharing off (or on).
    share_fog = user["share_fog"]
    if "shareFog" in body:
        share_fog = 1 if body["shareFog"] else 0

    with _write_lock:
        conn = db()
        for trip in trips_in:
            if not isinstance(trip, dict) or "startTimeMs" not in trip:
                raise HttpError(400, "trip missing startTimeMs")
            # Upsert, not INSERT OR IGNORE: a trip re-uploaded with edited fields
            # (e.g. a corrected vehicle mode) must replace the stored copy, or the
            # stale row would come back in the merge and revert the edit.
            conn.execute(
                "INSERT INTO trips (user_id, start_ms, json) VALUES (?, ?, ?) "
                "ON CONFLICT(user_id, start_ms) DO UPDATE SET json = excluded.json",
                (uid, int(trip["startTimeMs"]), json.dumps(trip)),
            )
        for line in traces_in:
            line = str(line).strip()
            if not line:
                continue
            json.loads(line)  # reject broken lines instead of storing them
            conn.execute(
                "INSERT OR IGNORE INTO traces (user_id, line_hash, line) VALUES (?, ?, ?)",
                (uid, hashlib.sha256(line.encode()).hexdigest(), line),
            )
        conn.execute(
            "UPDATE users SET badges_json = ?, stats_json = ?, share_fog = ? WHERE id = ?",
            (json.dumps(badges), json.dumps(stats), share_fog, uid),
        )
        conn.commit()

    trips = [
        json.loads(r["json"])
        for r in db().execute(
            "SELECT json FROM trips WHERE user_id = ? ORDER BY start_ms DESC", (uid,)
        )
    ]
    traces = [
        r["line"]
        for r in db().execute("SELECT line FROM traces WHERE user_id = ?", (uid,))
    ]
    return {"trips": trips, "traces": traces, "badges": badges}


def do_me(user):
    return {
        "username": user["username"],
        "stats": json.loads(user["stats_json"]),
        "badges": json.loads(user["badges_json"]),
    }


# --------------------------------------------------------------------------
# friends


def pair(a, b):
    return (a, b) if a < b else (b, a)


def friendship(a, b):
    low, high = pair(a, b)
    return db().execute(
        "SELECT * FROM friendships WHERE low_id = ? AND high_id = ?", (low, high)
    ).fetchone()


def other_user(body):
    username = str(body.get("username", "")).strip()
    if not USERNAME_RE.match(username):
        raise HttpError(400, "bad username")
    row = find_user(username)
    if row is None:
        raise HttpError(404, "no such user")
    return row


def do_friend_request(user, body):
    target = other_user(body)
    if target["id"] == user["id"]:
        raise HttpError(400, "you are already your own friend")

    existing = friendship(user["id"], target["id"])
    if existing and existing["status"] == "accepted":
        return {"status": "accepted"}
    if existing and existing["status"] == "pending":
        if existing["requested_by"] == user["id"]:
            return {"status": "pending"}
        # They asked us first; asking back is the same as accepting.
        return do_friend_respond(user, {"username": target["username"], "accept": True})

    low, high = pair(user["id"], target["id"])
    with _write_lock:
        conn = db()
        conn.execute(
            "INSERT INTO friendships (low_id, high_id, status, requested_by, created_ms)"
            " VALUES (?, ?, 'pending', ?, ?)",
            (low, high, user["id"], now_ms()),
        )
        conn.commit()
    return {"status": "pending"}


def do_friend_respond(user, body):
    target = other_user(body)
    existing = friendship(user["id"], target["id"])
    if existing is None or existing["status"] != "pending":
        raise HttpError(404, "no pending request from that user")
    if existing["requested_by"] == user["id"]:
        raise HttpError(403, "you sent this request; they must accept it")

    low, high = pair(user["id"], target["id"])
    accept = bool(body.get("accept"))
    with _write_lock:
        conn = db()
        if accept:
            conn.execute(
                "UPDATE friendships SET status = 'accepted'"
                " WHERE low_id = ? AND high_id = ?",
                (low, high),
            )
        else:
            conn.execute(
                "DELETE FROM friendships WHERE low_id = ? AND high_id = ?", (low, high)
            )
        conn.commit()
    return {"status": "accepted" if accept else "declined"}


def do_friend_remove(user, body):
    target = other_user(body)
    low, high = pair(user["id"], target["id"])
    with _write_lock:
        conn = db()
        conn.execute(
            "DELETE FROM friendships WHERE low_id = ? AND high_id = ?", (low, high)
        )
        conn.commit()
    return {}


def _friend_rows(uid):
    return db().execute(
        "SELECT f.status, f.requested_by, u.id, u.username"
        " FROM friendships f"
        " JOIN users u ON u.id = CASE WHEN f.low_id = ? THEN f.high_id ELSE f.low_id END"
        " WHERE f.low_id = ? OR f.high_id = ?",
        (uid, uid, uid),
    ).fetchall()


def do_friends(user):
    friends, incoming, outgoing = [], [], []
    for row in _friend_rows(user["id"]):
        if row["status"] == "accepted":
            friends.append(row["username"])
        elif row["requested_by"] == user["id"]:
            outgoing.append(row["username"])
        else:
            incoming.append(row["username"])
    return {"friends": friends, "incoming": incoming, "outgoing": outgoing}


def friend_stats(user):
    """The only endpoint that returns another user's data. Aggregates only:
    it reads stats_json and badges_json, and never touches trips or traces."""
    out = []
    for row in _friend_rows(user["id"]):
        if row["status"] != "accepted":
            continue
        friend = db().execute(
            "SELECT username, stats_json, badges_json FROM users WHERE id = ?",
            (row["id"],),
        ).fetchone()
        out.append(
            {
                "username": friend["username"],
                "stats": json.loads(friend["stats_json"]),
                "badges": json.loads(friend["badges_json"]),
            }
        )
    out.sort(key=lambda f: -f["stats"].get("totalDistanceMeters", 0))
    return out


def friend_fog(user):
    """The only endpoint that returns another user's traces.

    Two conditions, both required, both checked here: the caller shares their
    own fog, and so does the friend whose traces are about to be handed over.
    A user who turns sharing off therefore both stops contributing and stops
    receiving, which is what makes the trade legible.

    Lines come back unattributed — the union is a map, not a per-friend history.
    """
    if not user["share_fog"]:
        return {"sharing": False, "traces": []}

    friend_ids = [
        row["id"] for row in _friend_rows(user["id"]) if row["status"] == "accepted"
    ]
    if not friend_ids:
        return {"sharing": True, "traces": []}

    placeholders = ",".join("?" * len(friend_ids))
    rows = db().execute(
        "SELECT t.line FROM traces t JOIN users u ON u.id = t.user_id"
        " WHERE t.user_id IN (%s) AND u.share_fog = 1" % placeholders,
        friend_ids,
    )
    return {"sharing": True, "traces": [r["line"] for r in rows]}


# --------------------------------------------------------------------------
# http


AUTHED_GET = {
    "/me": do_me,
    "/friends": do_friends,
    "/friends/stats": friend_stats,
    "/friends/fog": friend_fog,
}


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def client_ip(self):
        # Behind the Cloudflare tunnel the socket peer is always localhost.
        return self.headers.get("CF-Connecting-IP") or self.address_string()

    def do_GET(self):
        if self.path == "/health":
            self._reply(200, b"ok", "text/plain")
            return
        try:
            handler = AUTHED_GET.get(self.path)
            if handler is None:
                raise HttpError(404, "not found")
            self._json(200, handler(authenticate(self.headers)))
        except HttpError as e:
            self._json(e.code, {"error": e.message})
        except Exception as e:  # noqa: BLE001 - never leak a stack trace
            self._json(500, {"error": "internal error"})
            print("ERROR %s: %r" % (self.path, e))

    def do_POST(self):
        try:
            body = self._body()
            if self.path == "/auth/register":
                self._json(200, do_register(body, self.client_ip()))
            elif self.path == "/auth/login":
                self._json(200, do_login(body, self.client_ip()))
            elif self.path == "/auth/logout":
                self._json(200, do_logout(authenticate(self.headers), self.headers))
            elif self.path == "/sync":
                self._json(200, do_sync(authenticate(self.headers), body))
            elif self.path == "/friends/request":
                self._json(200, do_friend_request(authenticate(self.headers), body))
            elif self.path == "/friends/respond":
                self._json(200, do_friend_respond(authenticate(self.headers), body))
            elif self.path == "/friends/remove":
                self._json(200, do_friend_remove(authenticate(self.headers), body))
            else:
                raise HttpError(404, "not found")
        except HttpError as e:
            self._json(e.code, {"error": e.message})
        except (ValueError, KeyError, TypeError) as e:
            self._json(400, {"error": "bad request: %s" % e})
        except Exception as e:  # noqa: BLE001
            self._json(500, {"error": "internal error"})
            print("ERROR %s: %r" % (self.path, e))

    def _body(self):
        length = int(self.headers.get("Content-Length", 0))
        if length == 0:
            return {}
        if length > MAX_BODY:
            raise HttpError(413, "body too large")
        return json.loads(self.rfile.read(length))

    def _json(self, code, payload):
        self._reply(code, json.dumps(payload).encode(), "application/json")

    def _reply(self, code, body, content_type):
        self.send_response(code)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, fmt, *args):
        print("%s %s" % (self.address_string(), fmt % args))


# --------------------------------------------------------------------------
# cli


def import_legacy(username):
    """Move the pre-auth trips.json / traces.jsonl into a user's rows."""
    user = find_user(username)
    if user is None:
        print("No such user %r. Register in the app first." % username)
        return 1
    uid = user["id"]
    conn = db()
    trips = traces = 0
    if os.path.exists(LEGACY_TRIPS):
        with open(LEGACY_TRIPS, encoding="utf-8") as f:
            for trip in json.load(f):
                conn.execute(
                    "INSERT OR IGNORE INTO trips (user_id, start_ms, json) VALUES (?, ?, ?)",
                    (uid, int(trip["startTimeMs"]), json.dumps(trip)),
                )
                trips += 1
    if os.path.exists(LEGACY_TRACES):
        with open(LEGACY_TRACES, encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                json.loads(line)
                conn.execute(
                    "INSERT OR IGNORE INTO traces (user_id, line_hash, line) VALUES (?, ?, ?)",
                    (uid, hashlib.sha256(line.encode()).hexdigest(), line),
                )
                traces += 1
    conn.commit()
    print("Imported %d trips and %d trace lines into %s." % (trips, traces, username))
    print("Old files left in place; delete them once the app has synced.")
    return 0


if __name__ == "__main__":
    os.makedirs(DATA_DIR, exist_ok=True)
    init_db()

    if len(sys.argv) > 2 and sys.argv[1] == "--import-legacy":
        raise SystemExit(import_legacy(sys.argv[2]))

    host = os.environ.get("HOST", "127.0.0.1")
    port = int(os.environ.get("PORT", "8790"))
    print("maproulette-sync on %s:%s, db at %s" % (host, port, DB_FILE))
    print("registration: %s" % ("open" if REGISTRATION_OPEN else "closed"))
    ThreadingHTTPServer((host, port), Handler).serve_forever()
