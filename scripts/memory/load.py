#!/usr/bin/env python
"""Load generator for spec 041 memory measurement. Python stdlib only; run as `python -I load.py`.

Every call is POST /api/sims/stream (SimulationController) with its own X-Sleeper-User, read to the
final `result` event (events: started, progress, result, error). The request body is a
SimulationRequest: draftSleeperId, mySlot, iterations, temperature, startState, excludePlayerIds, seed.
A repeated identity for the same draft cancels its own earlier run (SimulationPermits), so concurrent
callers must be distinct league members; realistic mode rotates members and waits for each call.

Modes
  realistic  one call every --interval seconds for --duration seconds, members rotating
  stress     --rounds rounds; in each round every member in --members calls at once
  worst      --rounds rounds of 2 concurrent calls (members[0], members[1]); use --iterations 5000

Output: CSV (--out) with one row per call, and a summary on stdout.
"""
import argparse, csv, http.client, json, statistics, sys, threading, time, urllib.parse


def one_call(base, member, body, token, timeout):
    u = urllib.parse.urlparse(base)
    t0 = time.time()
    status, got_result, err = 0, False, ""
    try:
        c = http.client.HTTPConnection(u.hostname, u.port or 80, timeout=timeout)
        h = {"Content-Type": "application/json", "Accept": "text/event-stream", "X-Sleeper-User": member}
        if token:
            h["Authorization"] = "Bearer " + token
        c.request("POST", "/api/sims/stream", json.dumps(body), h)
        r = c.getresponse()
        status = r.status
        if status == 200:
            event = None
            while True:
                line = r.readline()
                if not line:
                    break
                line = line.decode("utf-8", "replace").rstrip("\r\n")
                if line.startswith("event:"):
                    event = line[6:].strip()
                elif line.startswith("data:") and event == "result":
                    got_result = True
                    break
                elif line.startswith("data:") and event == "error":
                    err = line[5:].strip()[:120]
                    break
        else:
            err = r.read(200).decode("utf-8", "replace")
        c.close()
    except Exception as e:  # noqa: BLE001 - record and move on
        err = type(e).__name__ + ": " + str(e)[:100]
    return {"member": member, "status": status, "ms": int((time.time() - t0) * 1000),
            "result": int(got_result), "error": err}


def main():
    a = argparse.ArgumentParser()
    a.add_argument("--base", default="http://localhost:8098")
    a.add_argument("--league", default="", help="informational only; the draft id selects the league")
    a.add_argument("--draft", required=True)
    a.add_argument("--members", required=True, help="comma separated Sleeper user ids")
    a.add_argument("--iterations", type=int, default=500)
    a.add_argument("--seed", type=int, default=41)
    a.add_argument("--mode", choices=["realistic", "stress", "worst"], default="stress")
    a.add_argument("--rounds", type=int, default=5)
    a.add_argument("--interval", type=float, default=30.0)
    a.add_argument("--duration", type=float, default=1200.0, help="realistic mode only, seconds")
    a.add_argument("--slot", type=int, default=7)
    a.add_argument("--startstate-file", default="")
    a.add_argument("--token", default="")
    a.add_argument("--timeout", type=float, default=600.0)
    a.add_argument("--out", default="load.csv")
    o = a.parse_args()

    members = [m for m in o.members.split(",") if m]
    body = {"draftSleeperId": o.draft, "mySlot": o.slot, "iterations": o.iterations,
            "seed": o.seed}
    if o.startstate_file:
        with open(o.startstate_file) as f:
            body["startState"] = {int(k): v for k, v in json.load(f).items()}
    rows, lock = [], threading.Lock()

    def call(m, rnd):
        r = one_call(o.base, m, body, o.token, o.timeout)
        r["round"] = rnd
        r["t"] = round(time.time(), 1)
        with lock:
            rows.append(r)

    if o.mode == "realistic":
        end, i, nxt = time.time() + o.duration, 0, time.time()
        while time.time() < end:
            call(members[i % len(members)], i)
            i += 1
            nxt += o.interval
            time.sleep(max(0.0, nxt - time.time()))
    else:
        group = members if o.mode == "stress" else members[:2]
        for rnd in range(o.rounds):
            ts = [threading.Thread(target=call, args=(m, rnd)) for m in group]
            [t.start() for t in ts]
            [t.join() for t in ts]

    with open(o.out, "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=["round", "t", "member", "status", "ms", "result", "error"])
        w.writeheader()
        w.writerows(rows)
    ok = [r for r in rows if 200 <= r["status"] < 300]
    n429 = sum(1 for r in rows if r["status"] == 429)
    other = len(rows) - len(ok) - n429
    lat = sorted(r["ms"] for r in ok if r["result"])
    def pct(p):
        return lat[min(len(lat) - 1, int(round(p * (len(lat) - 1))))] if lat else -1
    print(f"calls={len(rows)} 2xx={len(ok)} 429={n429} other={other} results={len(lat)} "
          f"p50_ms={pct(0.5)} p95_ms={pct(0.95)} max_ms={max(lat) if lat else -1}")
    for r in rows:
        if r["status"] not in range(200, 300) and r["status"] != 429:
            print("other:", r["status"], r["error"])
            break


if __name__ == "__main__":
    sys.exit(main())
