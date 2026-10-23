# 045 baseline capture (T002)

- Commit captured: `5512cd7` (origin/main, built from the unchanged `045-baseline` worktree).
- Captured: 2026-10-23 09:29 CDT, against a server on port 8082 with `--refresh.on-visit.enabled=false`, no ingest or refresh run.
- Two runs, with a full JVM stop and restart between them: `run1/` and `run2/`.
- Files per run: 121 endpoints, each a `<name>.json` (sorted keys) plus a `<name>.status` (HTTP code), 242 files, plus `lock-ins-player-id.txt`.
  - weekly-report: 101 (week 0 for 8 leagues, plus every stored week of the 7 leagues that have any)
  - expected-wins, roster-management, superlatives, refresh, power, history, forecast, analysis: one each for NBA 2026 (`1339351318115946496`) and NFL 2026 (`1391509063170293760`)
  - recap/1 for those two, and `/lock-ins` plus `/lock-ins?player=2455` on NBA 2025 (2455 = the player with the most `rodeOut` in the default response; the default `locks[]` is empty on this data, so there is no "proven lock" to pick).
  - Playoff odds have no route of their own: they ride on `/power` and `/history` (and `/forecast`), so those are captured instead.
- jq is not installed on this machine, so `scripts/045/norm.py` does the `jq -S .` normalisation.

## Non-200 responses

None. All 121 responses in both runs were 200.

## Nondeterminism between run1 and run2

`scripts/045/diff.sh run1 run2` (ignoring `live`, `liveWeek`, `sleeperLeagueId` on weekly-report) reports exactly one differing file:

- `roster-management_1391509063170293760.json` (NFL 2026): `teams[].efficiency` differs in the last 1 to 3 digits of a double
  for 6 of 14 teams (for example 0.9262604084676248 vs 0.9262604084676247). That is floating-point summation order, not a data change.
  Roster management on a branch build must be compared with a tolerance on `efficiency` (or rounded), not byte for byte.
- A raw byte comparison (no key dropping) finds no other differing file, so `live`, `liveWeek` and `sleeperLeagueId` were not
  exercised by this baseline: no stored weekly-report was live at capture time. The `weekly-report/0` for NBA 2026 resolves to
  season 2025, week 21 (`requestedSeason` 2026), which is the fallback this spec changes.
