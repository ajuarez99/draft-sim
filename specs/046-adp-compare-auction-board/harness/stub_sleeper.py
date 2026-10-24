"""Spec 046 T035b: a stub Sleeper for the SC-003 live-latency check.

Serves draft 1415422395308609536 as `drafting`, with a /picks list that starts at START picks and grows by one
every STEP seconds from the recorded fixture (backend/src/test/resources/fixtures/046/auction-picks.json). Every
add is logged with its epoch-ms time to stdout, so the page-side timestamps can be diffed against it. Any
other path is proxied read-only to the real api.sleeper.app, so the backend's other calls still work.

Run:  python -I stub_sleeper.py <fixtures-dir> [port=8199] [start=200] [step=4]
Point the backend at it with SLEEPER_BASE_URL=http://localhost:<port>/v1
"""
import json, sys, time, threading, urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

FIX = sys.argv[1]
PORT = int(sys.argv[2]) if len(sys.argv) > 2 else 8199
START = int(sys.argv[3]) if len(sys.argv) > 3 else 200
STEP = float(sys.argv[4]) if len(sys.argv) > 4 else 4.0
DRAFT = "1415422395308609536"

draft = json.load(open(f"{FIX}/auction-draft.json", encoding="utf-8"))
draft["status"] = "drafting"
picks = sorted(json.load(open(f"{FIX}/auction-picks.json", encoding="utf-8")), key=lambda p: p["pick_no"])
state = {"n": START}


def grow():
    while state["n"] < len(picks):
        time.sleep(STEP)
        state["n"] += 1
        p = picks[state["n"] - 1]
        print(json.dumps({"added_pick_no": p["pick_no"], "player_id": p["player_id"], "amount": p["metadata"]["amount"],
                          "t_ms": int(time.time() * 1000)}), flush=True)


class H(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def _json(self, obj):
        body = json.dumps(obj).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        path = self.path.split("?")[0]
        if path == f"/v1/draft/{DRAFT}":
            return self._json(draft)
        if path == f"/v1/draft/{DRAFT}/picks":
            return self._json(picks[: state["n"]])
        try:
            with urllib.request.urlopen("https://api.sleeper.app" + self.path, timeout=10) as r:
                body = r.read()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        except Exception as e:
            self.send_response(502)
            self.end_headers()
            self.wfile.write(str(e).encode())


if __name__ == "__main__":
    print(json.dumps({"stub": PORT, "start": START, "step": STEP}), flush=True)
    threading.Thread(target=grow, daemon=True).start()
    ThreadingHTTPServer(("127.0.0.1", PORT), H).serve_forever()
