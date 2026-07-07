#!/usr/bin/env python3
"""Map Roulette sync server.

Stores the app's trip history (trips.json) and fog-of-war traces
(traces.jsonl) and merges every upload with the stored copy, so the app can
delete/reinstall and get everything back with one request.

Protocol (single endpoint):
  POST /sync   body: {"trips": [<trip objects>], "traces": ["<jsonl line>"]}
               reply: same shape, containing the merged union.
  GET  /health "ok" — for monitoring.

Merging is append-only and idempotent:
  - trips deduplicate on startTimeMs (a device never records two trips
    starting the same millisecond), newest first;
  - traces deduplicate on the exact line string (the app writes each
    decimated polyline once and never edits it).

No auth here: bind to localhost and expose only through the Cloudflare
tunnel with Cloudflare Access in front (see ../SYNC_SETUP_GUIDE.md).

Python 3.8+ stdlib only. DATA_DIR env var sets the storage directory.
"""
import json
import os
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

DATA_DIR = os.environ.get("DATA_DIR", os.path.join(os.path.dirname(__file__), "data"))
TRIPS_FILE = os.path.join(DATA_DIR, "trips.json")
TRACES_FILE = os.path.join(DATA_DIR, "traces.jsonl")
MAX_BODY = 64 * 1024 * 1024


def atomic_write(path: str, text: str) -> None:
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        f.write(text)
    os.replace(tmp, path)


def load_trips() -> list:
    try:
        with open(TRIPS_FILE, encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError):
        return []


def load_traces() -> list:
    try:
        with open(TRACES_FILE, encoding="utf-8") as f:
            return [line.strip() for line in f if line.strip()]
    except OSError:
        return []


def merge(body: dict) -> dict:
    trips_by_start = {t["startTimeMs"]: t for t in load_trips()}
    for trip in body.get("trips", []):
        trips_by_start.setdefault(trip["startTimeMs"], trip)
    trips = sorted(trips_by_start.values(), key=lambda t: -t["startTimeMs"])

    traces = load_traces()
    seen = set(traces)
    for line in body.get("traces", []):
        line = line.strip()
        if line and line not in seen:
            json.loads(line)  # reject broken lines instead of storing them
            traces.append(line)
            seen.add(line)

    atomic_write(TRIPS_FILE, json.dumps(trips))
    atomic_write(TRACES_FILE, "\n".join(traces) + ("\n" if traces else ""))
    return {"trips": trips, "traces": traces}


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path == "/health":
            self._reply(200, b"ok", "text/plain")
        else:
            self._reply(404, b"not found", "text/plain")

    def do_POST(self):
        if self.path != "/sync":
            self._reply(404, b"not found", "text/plain")
            return
        try:
            length = int(self.headers.get("Content-Length", 0))
            if not 0 < length <= MAX_BODY:
                self._reply(413, b"body too large or missing", "text/plain")
                return
            body = json.loads(self.rfile.read(length))
            merged = merge(body)
            self._reply(200, json.dumps(merged).encode(), "application/json")
        except (ValueError, KeyError, TypeError) as e:
            self._reply(400, f"bad request: {e}".encode(), "text/plain")

    def _reply(self, code: int, body: bytes, content_type: str):
        self.send_response(code)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, fmt, *args):  # quiet: one line per request is enough
        print("%s %s" % (self.address_string(), fmt % args))


if __name__ == "__main__":
    os.makedirs(DATA_DIR, exist_ok=True)
    host = os.environ.get("HOST", "127.0.0.1")
    port = int(os.environ.get("PORT", "8790"))
    print(f"maproulette-sync on {host}:{port}, data in {DATA_DIR}")
    ThreadingHTTPServer((host, port), Handler).serve_forever()
