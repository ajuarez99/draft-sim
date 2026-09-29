#!/usr/bin/env python3
"""
Read-only: compare a league-season's stored weeks against Sleeper, week by week.

specs/009-auto-data-refresh T054 (SC-008). For each regular-season week it checks:
  * completed waiver/free-agent adds: the app's /api/leagues/{id}/transactions adds list
    (completed only) vs Sleeper's /league/{id}/transactions/{week};
  * each roster's points: the app's /api/leagues/{id}/weekly-report/{week} vs Sleeper's
    /league/{id}/matchups/{week}.

Rosters Sleeper lists with no matchup that week (matchup_id null, e.g. teams outside a playoff
bracket) are reported separately as UNPAIRED and don't count as a mismatch: the weekly report
only shows paired games. Anything else that differs is a failure.

Usage:
  python scripts/check-weeks-vs-sleeper.py <app-base-url> <sleeper-league-id> [--through WEEK]
      [--header "X-Sleeper-User: <id>"]

Exit status 0 only when every checked week matches.
"""
import argparse
import collections
import json
import sys
import time
import urllib.request

SLEEPER = "https://api.sleeper.app/v1"


def get(url, headers=None, tries=6):
    for attempt in range(tries):
        try:
            req = urllib.request.Request(url, headers=headers or {})
            with urllib.request.urlopen(req, timeout=120) as r:
                return json.load(r)
        except Exception as e:  # a cold-starting app answers 502 for a few minutes
            if attempt == tries - 1:
                raise
            print(f"  retrying {url} after {e}", file=sys.stderr)
            time.sleep(30)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("base")
    ap.add_argument("league")
    ap.add_argument("--through", type=int, default=None,
                    help="last week to check (default: the league's playoff_week_start - 1, else last_scored_leg)")
    ap.add_argument("--header", action="append", default=[])
    a = ap.parse_args()
    headers = dict(h.split(": ", 1) for h in a.header)
    base = a.base.rstrip("/")

    league = get(f"{SLEEPER}/league/{a.league}")
    settings = league.get("settings") or {}
    last_scored = settings.get("last_scored_leg") or 0
    pws = settings.get("playoff_week_start") or 0
    # playoff_week_start missing/0 must not become -1 (truthy, zero weeks checked, PASS).
    through = a.through or ((pws - 1) if pws > 1 else last_scored)
    through = min(through, last_scored or through)
    if through < 1:
        print(f"INCONCLUSIVE: no weeks to check (playoff_week_start={pws}, last_scored_leg={last_scored})")
        sys.exit(2)
    print(f"{league.get('name')} ({league.get('sport')} {league.get('season')}), weeks 1..{through}")

    app_tx = get(f"{base}/api/leagues/{a.league}/transactions", headers)
    app_adds = collections.Counter(x["week"] for x in app_tx.get("adds", [])
                               if x.get("status") == "complete" and x.get("type") in ("WAIVER", "FREE_AGENT"))

    failures = []
    checked = 0
    for w in range(1, through + 1):
        checked += 1
        sleeper_tx = get(f"{SLEEPER}/league/{a.league}/transactions/{w}")
        s_adds = sum(1 for t in sleeper_tx
                     if t.get("type") in ("waiver", "free_agent") and t.get("status") == "complete" and (t.get("adds") or {}))

        report = get(f"{base}/api/leagues/{a.league}/weekly-report/{w}", headers)
        app_pts = {}
        for m in report.get("matchups", []):
            for side in ("home", "away"):
                s = m.get(side) or {}
                if s.get("rosterId") is not None:
                    app_pts[s["rosterId"]] = s.get("points") or 0.0
        matchups = get(f"{SLEEPER}/league/{a.league}/matchups/{w}")
        paired = {m["roster_id"]: m.get("points") or 0.0 for m in matchups if m.get("matchup_id") is not None}
        unpaired = sorted(m["roster_id"] for m in matchups if m.get("matchup_id") is None)

        bad_pts = {rid: (app_pts.get(rid), pts) for rid, pts in paired.items()
                   if app_pts.get(rid) is None or abs(app_pts[rid] - pts) > 0.005}
        ok = app_adds[w] == s_adds and not bad_pts
        note = f"  UNPAIRED rosters {unpaired} (not compared)" if unpaired else ""
        print(f"week {w:2}: adds app={app_adds[w]:3} sleeper={s_adds:3}  points "
              f"{'ok' if not bad_pts else 'MISMATCH ' + str(bad_pts)}{note}  {'OK' if ok else '<-- FAIL'}")
        if not ok:
            failures.append(w)

    if not checked:
        print("result: INCONCLUSIVE (zero weeks checked)")
        sys.exit(2)
    print("result:", "PASS" if not failures else f"FAIL on weeks {failures}")
    sys.exit(0 if not failures else 1)


if __name__ == "__main__":
    main()
