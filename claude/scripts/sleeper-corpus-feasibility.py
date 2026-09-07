"""Feasibility probe: can public Sleeper drafts be enumerated at volume, without auth?

This is the "small feasibility script" that ideas/player-affinity.md step 1 and
claude/borrowed-drafts.md both say to run before anything at corpus scale gets
designed. It answers, with measured numbers rather than assumptions:

  1. Are OTHER users' leagues readable without auth? (the whole crawl depends on it)
  2. What is the branching factor -- leagues per user, drafts per league?
  3. How many of those drafts are actually usable (complete, has picks, snake, PPR)?
  4. What does one BFS level cost in HTTP calls and wall-clock?

Read-only. Public endpoints only. No writes anywhere.
"""
import json, sys, time, urllib.request
from collections import Counter

BASE = "https://api.sleeper.app/v1"
SEASONS = ["2025", "2024"]
SEED_LEAGUE = "1254190892974084096"  # (Foot) Ball Knowers 2025, 12 teams, complete
ALLAN = "1122386008709910528"

calls = 0
def get(path):
    global calls
    calls += 1
    with urllib.request.urlopen(BASE + path, timeout=20) as r:
        return json.load(r)

t0 = time.time()

# --- Level 0: the seed league's members -------------------------------------
users = get(f"/league/{SEED_LEAGUE}/users")
user_ids = [u["user_id"] for u in users]
print(f"seed league members: {len(user_ids)}")

# --- Level 1: every member's other leagues, for each season -----------------
leagues_by_user = {}
for uid in user_ids:
    found = []
    for season in SEASONS:
        try:
            found += [l["league_id"] for l in get(f"/user/{uid}/leagues/nfl/{season}")]
        except Exception as e:
            print(f"  user {uid} season {season}: FAILED {e}")
    leagues_by_user[uid] = sorted(set(found))
    time.sleep(0.05)

counts = [len(v) for v in leagues_by_user.values()]
visible_users = sum(1 for c in counts if c > 0)
print(f"users whose leagues are readable without auth: {visible_users}/{len(user_ids)}")
print(f"leagues per user over {SEASONS}: min {min(counts)} max {max(counts)} "
      f"mean {sum(counts)/len(counts):.1f}")

all_leagues = sorted({lid for v in leagues_by_user.values() for lid in v})
print(f"distinct leagues discovered from one league's members: {len(all_leagues)}")

# --- Level 2: drafts in each discovered league ------------------------------
draft_rows = []
for lid in all_leagues:
    try:
        for d in get(f"/league/{lid}/drafts"):
            draft_rows.append(d)
    except Exception as e:
        print(f"  league {lid}: FAILED {e}")
    time.sleep(0.05)

print(f"\ndrafts discovered: {len(draft_rows)}")
status = Counter(d.get("status") for d in draft_rows)
dtype = Counter(d.get("type") for d in draft_rows)
teams = Counter(d.get("settings", {}).get("teams") for d in draft_rows)
rounds = Counter(d.get("settings", {}).get("rounds") for d in draft_rows)
print(f"  status: {dict(status)}")
print(f"  type:   {dict(dtype)}")
print(f"  teams:  {dict(sorted(teams.items(), key=lambda kv: -kv[1]))}")
print(f"  rounds: {dict(sorted(rounds.items(), key=lambda kv: -kv[1]))}")

# scoring: rec ppr value lives on the league, not the draft
usable = [d for d in draft_rows
          if d.get("status") == "complete" and d.get("type") == "snake"
          and d.get("settings", {}).get("teams") in (8, 10, 12, 14)]
print(f"  complete + snake + 8/10/12/14 teams: {len(usable)}")

# --- Do the picks actually come back? --------------------------------------
sampled = usable[:3]
for d in sampled:
    picks = get(f"/draft/{d['draft_id']}/picks")
    withowner = sum(1 for p in picks if p.get("picked_by"))
    print(f"  draft {d['draft_id']}: {len(picks)} picks, {withowner} carry picked_by")
    time.sleep(0.05)

# --- How many DISTINCT managers does this reach? ----------------------------
# The point of borrowed drafts: more picks per manager we already care about.
mine = set(user_ids)
reach = Counter()
for d in usable:
    for uid in (d.get("draft_order") or {}):
        if uid in mine:
            reach[uid] += 1
print(f"\ndrafts found that contain a seed-league member: {sum(reach.values())} "
      f"across {len(reach)} of {len(mine)} members")
print(f"  per-member draft counts: {sorted(reach.values(), reverse=True)}")
print(f"  Allan specifically: {reach.get(ALLAN, 0)}")

print(f"\n{calls} HTTP calls, {time.time()-t0:.1f}s wall clock")
