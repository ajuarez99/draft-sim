"""Level-2 growth probe + league-suite endpoint probe.

Level 1 (measured): 12 members -> 11 distinct leagues -> 10 usable drafts, 39 calls.
The corpus-scale claim needs the GROWTH RATE, not one level. This expands one more
level and measures overlap, which is what decides whether "thousands of drafts" is
reachable or whether the graph collapses back onto itself.
"""
import json, time, urllib.request
from collections import Counter

BASE = "https://api.sleeper.app/v1"
SEASONS = ["2025", "2024"]
SEED_LEAGUE = "1254190892974084096"

calls = 0
def get(path):
    global calls
    calls += 1
    try:
        with urllib.request.urlopen(BASE + path, timeout=20) as r:
            return json.load(r)
    except Exception as e:
        print(f"  FAIL {path}: {e}")
        return None

t0 = time.time()

# ---- rebuild level 1 --------------------------------------------------------
seed_users = {u["user_id"] for u in get(f"/league/{SEED_LEAGUE}/users")}
l1_leagues = set()
for uid in seed_users:
    for s in SEASONS:
        for l in (get(f"/user/{uid}/leagues/nfl/{s}") or []):
            l1_leagues.add(l["league_id"])

# ---- level 2: members of those leagues, then THEIR leagues ------------------
l2_users = set()
for lid in l1_leagues:
    for u in (get(f"/league/{lid}/users") or []):
        l2_users.add(u["user_id"])
    time.sleep(0.03)
new_users = l2_users - seed_users
print(f"L1 leagues: {len(l1_leagues)}  L2 users: {len(l2_users)} ({len(new_users)} new)")

l2_leagues = set()
for uid in sorted(new_users):
    for s in SEASONS:
        for l in (get(f"/user/{uid}/leagues/nfl/{s}") or []):
            l2_leagues.add(l["league_id"])
    time.sleep(0.03)
new_leagues = l2_leagues - l1_leagues
print(f"L2 leagues: {len(l2_leagues)} ({len(new_leagues)} new beyond L1)")
print(f"growth: {len(l1_leagues)} -> {len(l1_leagues) + len(new_leagues)} leagues, "
      f"branching {len(new_leagues)/max(1,len(new_users)):.2f} new leagues per new user")
print(f"cost so far: {calls} calls, {time.time()-t0:.0f}s")

# ---- what fraction of discovered leagues are even the right SHAPE? ---------
sample = sorted(new_leagues)[:40]
shape = Counter()
ppr = Counter()
for lid in sample:
    l = get(f"/league/{lid}")
    if not l:
        continue
    st = l.get("settings", {}) or {}
    sc = l.get("scoring_settings", {}) or {}
    shape[(st.get("num_teams"), l.get("season_type"))] += 1
    ppr[sc.get("rec")] += 1
    time.sleep(0.03)
print(f"\nsample of {len(sample)} newly-discovered leagues:")
print(f"  (num_teams, season_type): {dict(shape)}")
print(f"  scoring_settings.rec (1.0 = full PPR): {dict(ppr)}")

print(f"\nTOTAL {calls} calls, {time.time()-t0:.0f}s")
