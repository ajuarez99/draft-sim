"""JSON normaliser standing in for `jq -S .` (jq is not installed here).
usage: norm.py [--drop key,key,...]   stdin -> stdout. Top-level keys only. Non-JSON passes through."""
import json, sys, io
sys.stdin = io.TextIOWrapper(sys.stdin.buffer, encoding="utf-8")
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", newline=chr(10))
drop = set()
if len(sys.argv) > 2 and sys.argv[1] == "--drop":
    drop = set(sys.argv[2].split(","))
raw = sys.stdin.read()
try:
    d = json.loads(raw)
except ValueError:
    sys.stdout.write(raw if raw.endswith("\n") or not raw else raw + "\n")
    sys.exit(0)
if isinstance(d, dict):
    for k in drop:
        d.pop(k, None)
print(json.dumps(d, indent=2, sort_keys=True, ensure_ascii=False))
