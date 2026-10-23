# Verification: spec 045 (Matchups shows the live season, then lock-ins on Matchups)

**When**: 2026-10-23, ~14:10–14:30 CDT, during NBA 2026 week 1 (Sleeper `leg` 1, `last_scored_leg` absent) and
NFL 2026 week 7.

**What ran**: this branch's backend, built from the worktree, on port 8082 with `--refresh.on-visit.enabled=false`.
Its web dev server ran on 5179 (`DRAFTSIM_PROXY_TARGET=http://localhost:8082`). Both used the shared local Postgres
5433. The browser was the built-in pane, signed in as popsharky (a member of every league checked). The other
session's server on 8080 was not touched. Both servers were stopped afterwards.

"Verified" below means it was run against the real thing. Anything not run is listed under **Not verified**.

## Results

| Check | Result | How |
|---|---|---|
| SC-001: NBA 2026 Matchups shows 2026 during a live week, no fallback note | **PASS** | `weekly-report/0` → `season 2026, week 1, live true, liveWeek 1, requestedSeason null, weekFinal false`. The page reads "In progress, live from Sleeper" with no "showing 2025" note. Baseline (main) answered 2025 week 21 with `requestedSeason 2026`. |
| SC-002: live scores equal Sleeper's | **PASS** | All 12 sides compared with `GET /v1/league/1339351318115946496/matchups/1` read in the same minute: 0 mismatches. NFL 2026 week 7 (`1391509063170293760`, 7 matchups): 0 mismatches. |
| FR-003 / code-review B1: no winner on a live week | **PASS** | The live NBA card reads "TRAILING by 11.50", and an NFL live card reads "LEVEL", with no WON/LOST text. A final 2025 week still reads "WON by 8.50". |
| SC-003: past seasons identical | **PASS** | T032 capture: 121 endpoints, `diff.sh` baseline/run1 vs after. Every stored `weekly-report` week of NBA 2024/2025 and NFL 2025/2026 is identical. |
| SC-004: other pages identical | **PASS** (amended scope) | Expected wins, roster management, superlatives, refresh, power, history, forecast, analysis and recap are identical for NBA 2026 and NFL 2026, except roster-management `efficiency` last-digit float wobble, which is pre-existing and present between two baseline runs on main. The home page follows Matchups by design (A3): "Trailing in week 1 · in progress". |
| SC-005: no rule-less resolve | **PASS** | The one-argument overload is deleted, so the compiler enforces it. A grep found no remaining callers. |
| SC-006: Matchups < 2 s warm | **PASS** | T032 measured `weekly-report/0` on NBA 2026 warm at 15.7–16.7 ms. `/lock-ins` warm was 67–72 ms, and lock-ins load after the scores (A16). A true-cold `/lock-ins` was **not** measured on this branch; 044 measured 2.49 s. |
| SC-007: live calls per matchup equal `/lock-ins` | **PASS** | `/lock-ins` on NBA 2026: live status OK, week 1, 96 calls, every `rosterId` on a card. Every baseline kind shown is `LAST_SEASON` ("vs last season's avg"), as expected before any 2026 history. The page renders the calls under each side with the one caption. |
| SC-008: past-week locks equal 044's provable locks | **PASS** | `/lock-ins?week=5` on NBA 2025: MEASURED, `weekCovered true`, 64 locks, all week 5. Spot check of 3 players: the week form equals the `?player=` form's week-5 locks exactly. The page shows "+37% stand-in (1 later game)" etc. per side. |
| FR-012: `/lock-ins` unchanged without `week` | **PASS** | `/lock-ins` and `/lock-ins?player=2455` on NBA 2025 are byte-identical to baseline, with no `weekCovered` key. |
| FR-011: no lock-ins for football | **PASS** | NFL Matchups (live and final weeks) has no lock-in text. |
| FR-016 / B2: live week reachable mid-season | **PASS** | NFL 2026: the default stays the latest final week (2), "Week 7 in progress →" links to the live week, and `?week=7` is not clamped. Vitest covers the refusal case (B2). |
| FR-017: started test | **PASS for "started"** | NFL week 7 on a Friday (Thursday game played) counts as started, with points > 0. The "not started" side was **not** seen live; it's unit-tested only. |
| A18: refresh pill | **Observed** | The NBA home reads "Updated 3 days ago". With refresh off locally, the pill's season naming couldn't be exercised. Accepted per clarify. |

## Seen, not a defect of this change

- **Stale local NFL data shows the S4 trade-off plainly.** The local DB has NFL 2026 league `1391509063170293760` stored
  only through week 3, while Sleeper is on week 7.
  - The first cold view's link read "Week 3 in progress" (no live week known yet, `peek`). After one reload it read
    "Week 7 in progress →". That is S4's documented cost: the first view after a cold start or cache expiry has no
    `liveWeek`.
  - Weeks 4–6 answer "not scored" locally. Prod's ingest keeps weeks through `last_scored_leg` stored, so on prod
    the gap is only the live week itself.
  - The live NFL records "(1-2)" are "before week 7" computed from the 3 stored weeks. That's a local artefact.
- **Records on live cards are "before this week"** and unlabelled (code-review N6). On a live card it's correct,
  since no result is counted yet.

## Not verified

- **True-cold `/lock-ins` latency on this branch.** Calls render after the scores either way (A16).
- **A Sleeper outage or slow read, live** (FR-019). It's covered by `LiveWeekSourceTest`, including a real hung socket
  (S1), and not reproduced against Sleeper.
- **The "week L hasn't started" refusal, live.** It needs a Tuesday or Wednesday NFL check, or the NBA week 2 rollover.
- **The rollover window** (code-review N4, plan A13), NBA week 1 → 2 (~10-26/27). That's also when to measure whether
  NBA `last_scored_leg` tracks the current leg from week 2 on (spec "Owed").
- **Phone width.** The new lock-in rows were checked in a narrow (800 px) pane only.
- **Prod.** Nothing is deployed, and commit/PR are waiting on Allan.
