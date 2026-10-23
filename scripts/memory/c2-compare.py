#!/usr/bin/env python
"""C2 response sweep (spec 041 T014): every URL in c2-urls.txt plus a fixed-seed POST /api/sims, old vs new.
Usage: python -I c2-compare.py <old_base> <new_base> [--exclude path ...]   (paths are dotted JSON paths, [] = any list index)
Prints per-endpoint status, body equality, and every differing JSON path."""
import json, sys, urllib.request, urllib.error, os

HERE = os.path.dirname(os.path.abspath(__file__))
old, new = sys.argv[1], sys.argv[2]
excl = set(sys.argv[4:]) if len(sys.argv) > 3 and sys.argv[3] == "--exclude" else set()

def fetch(base, user, path, body=None):
    h = {}
    if user != "-": h["X-Sleeper-User"] = user
    data = None
    if body is not None:
        data = json.dumps(body).encode(); h["Content-Type"] = "application/json"
    r = urllib.request.Request(base + path, data=data, headers=h, method="POST" if body is not None else "GET")
    try:
        with urllib.request.urlopen(r, timeout=300) as x: return x.status, x.read()
    except urllib.error.HTTPError as e: return e.code, e.read()

def diff(a, b, p, out):
    if isinstance(a, dict) and isinstance(b, dict):
        for k in sorted(set(a) | set(b)):
            if k not in a or k not in b: out.append(p + "." + k + " (missing on one side)")
            else: diff(a[k], b[k], p + "." + k, out)
    elif isinstance(a, list) and isinstance(b, list):
        if len(a) != len(b): out.append(f"{p} (length {len(a)} vs {len(b)})")
        for i, (x, y) in enumerate(zip(a, b)): diff(x, y, p + "[]", out)
    elif a != b: out.append(p)

jobs = []
for line in open(os.path.join(HERE, "c2-urls.txt")):
    line = line.rstrip("\n")
    if not line or line.startswith("#"): continue
    u, p = line.split("\t"); jobs.append((u, p, None))
member = "1122386008709910528"
ss = {int(k): v for k, v in json.load(open(os.path.join(HERE, "startstate-nba.json"))).items()}
jobs.append((member, "/api/sims", {"draftSleeperId": "1339351318128517120", "mySlot": 7, "iterations": 500, "seed": 41, "startState": ss}))
bad = 0
for u, p, body in jobs:
    sa, ba = fetch(old, u, p, body); sb, bb = fetch(new, u, p, body)
    name = ("POST " if body is not None else "GET ") + p
    if sa != sb: print(f"DIFF status {sa} vs {sb}: {name}"); bad += 1; continue
    try: ja, jb = json.loads(ba), json.loads(bb)
    except Exception: ja, jb = ba, bb
    out = []
    if isinstance(ja, (dict, list)): diff(ja, jb, "", out)
    elif ja != jb: out.append("(non-JSON body)")
    real = [o for o in out if o.split(" ")[0] not in excl]
    skipped = len(out) - len(real)
    print(f"{'SAME' if not real else 'DIFF'} {sa} {name} bytes={len(ba)}/{len(bb)}" + (f" excluded={skipped}" if skipped else ""))
    if real: bad += 1; [print("    ", o) for o in real[:15]]
print("TOTAL endpoints:", len(jobs), "differing:", bad)
