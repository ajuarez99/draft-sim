# Baseline: what these pages answered before 006

Captured 2026-09-21 against local Postgres (`draftsim-pg`, healthy) and the backend on `:8080`, before
any task in [tasks.md](tasks.md) was executed. These are the "before" halves of the corrections, kept
so the fixes are provable rather than asserted.

Follows the precedent of [`specs/002-league-history-record-book/baseline.md`](../002-league-history-record-book/baseline.md).

---

## T002 — US1: a manager's seasons carry no sport

`GET /api/managers/7/history` (popsharky), season rows only:

| season | sleeperLeagueId | W | L | pointsFor | `sport` field |
|---|---|---|---|---|---|
| 2026 | 1389361939561332736 | 1 | 0 | 189.90 | **absent** |
| 2026 | 1339351318115946496 | 0 | 0 | 0.00 | **absent** |
| 2026 | 1346366555759341568 | 1 | 0 | 157.40 | **absent** |
| 2025 | 1229352720222134272 | 8 | 10 | 4370.00 | **absent** |
| 2025 | 1254190892974084096 | 10 | 4 | 2042.84 | **absent** |
| 2024 | 1141438340626231296 | 9 | 11 | 5146.00 | **absent** |

Six rows, three leagues, two sports. Three rows say `2026` and two say `2025`, with nothing to tell
them apart. `pointsFor` puts NBA's 5146.00 in the same column as NFL's 2042.84.

`ManagerHistory.tsx` derives its header by summing all six:

```
totalWins   = 1 + 0 + 1 + 8 + 10 + 9 = 29
totalLosses = 0 + 0 + 0 + 10 + 4 + 11 = 25
→ "29-25 across 6 seasons"
```

Eleven of the twelve Ball Knowers managers are the same Sleeper user in both leagues:

| manager | nfl seasons | nba seasons |
|---|---|---|
| popsharky | 3 | 3 |
| ChedddaBob, jpelwell, GraftonCarlson, njerickson, jstrobe, BamAddABio, theadambomb98, ImReallyHarry | 2 | 3 |
| xonicboom | 1 | 3 |
| wags707 | 1 | 2 |

**After US1**: every row carries `sport` and `leagueName`; no rendered total spans both.

---

## T003 — US2: two champions of seasons that have played one week

Every `roster_season` row with a non-null `final_placement`:

| sport | season | league | roster | manager | W-L | placement |
|---|---|---|---|---|---|---|
| nba | 2024 | Ball Knowers | 2 | njerickson | 17-4 | 1 |
| nba | 2025 | Ball Knowers | 12 | GraftonCarlson | 10-8 | 1 |
| nfl | 2025 | (Foot) Ball Knowers | 1 | popsharky | 10-4 | 1 |
| **nfl** | **2026** | **(Foot) Ball Knowers** | **1** | **popsharky** | **1-0** | **1** ← wrong |
| nfl | 2025 | West Coast Fantasy Football | 6 | gregmullen | 7-7 | 1 |
| **nfl** | **2026** | **West Coast Fantasy Football** | **6** | **gregmullen** | **1-0** | **1** ← wrong |

Cause, verified against the live source:

```
GET https://api.sleeper.app/v1/league/1346366555759341568
  "season": "2026",
  "status": "in_season",
  "metadata": { "latest_league_winner_roster_id": "1" }
```

Sleeper carries that key on the **new** season's league object, meaning *most recent winner* — last
season's. `LeagueHistoryIngestService#ingestStandings` reads it unconditionally.

`league.status` is not stored anywhere: it is a top-level field on the league object, dropped by
`LeagueMapper`, and `select settings_json ? 'status' from league` is false for all nine rows.

ffwrapped's own profile shows popsharky **TITLES 2**, which is what this mistake produces — so the
reference page is not a check on it.

**After US2**: four placements, none on a season with a 1-0 record; popsharky's titles read 1.

---

## T004 — US3: three efficiencies for one manager-season

popsharky, (Foot) Ball Knowers 2025 (`1254190892974084096`), roster 1:

| Source | Total | Potential | Efficiency |
|---|---|---|---|
| `roster_season.points_possible` (Sleeper `ppts`) | 2042.84 | 2204.34 | **92.7%** |
| `GET /leagues/{id}/roster-management` (per-week optimal lineup, 17 weeks) | 2468.50 | 2695.18 | **91.6%** |
| ffwrapped Manager Profiles (`AVG EFFICIENCY`) | — | — | **87.9%** |

The totals differ because the app sums every stored week while Sleeper's `fpts`/`ppts` are regular
season only — the same reconciliation already recorded for NBA weekly points.

`points_possible` is populated for every played roster-season in both sports and is read by **no
service**: `grep -rn "points_possible\|pointsPossible"` over `backend/src/main/java` matches only
`RosterSeasonRepository`'s own upsert and row mapper.

**After US3**: the career figure traces to the second row, and a test asserts the first stays unread
(SC-004).

---

## Supporting measurements

Data coverage at baseline, per league-season:

| League | Sport | Season | `roster_week_points` | weeks | with `starters` | paired fixtures | transactions |
|---|---|---|---|---|---|---|---|
| (Foot) Ball Knowers | nfl | 2025 | 204 | 17 | 204 | 196 | 471 |
| (Foot) Ball Knowers | nfl | 2026 | 12 | 1 | 12 | 168 | 16 |
| Ball Knowers | nba | 2024 | 288 | 24 | 288 | 280 | 635 |
| Ball Knowers | nba | 2025 | 252 | 21 | 252 | 244 | 1618 |
| Ball Knowers | nba | 2026 | 0 | 0 | 0 | 0 | 0 |
| West Coast | nfl | 2025 | 216 | 18 | 216 | 204 | 378 |
| West Coast | nfl | 2026 | 14 | 1 | 14 | 196 | 29 |

Note the contrast with 002's baseline, which measured **8 paired fixtures per season**. The skip gate
that caused it has since been fixed and the seasons backfilled, which is what makes US4 buildable.

Endpoint latency, warm (relevant to R4's hoist decision):

| League | `/roster-management` | `/expected-wins` |
|---|---|---|
| (Foot) Ball Knowers 2025 | 120 ms | 27 ms |
| Ball Knowers 2025 (nba) | 49 ms | 20 ms |
| Ball Knowers 2024 (nba) | 48 ms | 15 ms |
| West Coast 2025 | 29 ms | 15 ms |

Player rows reloaded per call by `RosterManagementService.forLeague`: 4386 (nfl), 2066 (nba).
