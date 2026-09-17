# Baseline: before 002-league-history-record-book

**Measured**: 2026-09-16, local Postgres (`draftsim-pg`, `docker compose up -d postgres`)
**Branch**: `002-league-history-record-book` at `9b9014a`

These are the "before" numbers. SC-002 and SC-003 are checked against them, so they are recorded
here rather than re-derived later. Re-measured at the start of implementation and found identical
to the figures taken during planning — the container had been recreated in between, so the `pgdata`
volume is confirmed to persist.

---

## Q1 — Per-league-season coverage

```bash
docker exec draftsim-pg psql -U draftsim -d draftsim -c "
select l.id, l.sport, l.season, l.name,
       (select count(*) from roster_week_points w where w.league_id=l.id) as week_pts,
       (select count(distinct w.week) from roster_week_points w where w.league_id=l.id) as weeks,
       (select count(*) from league_matchup m where m.league_id=l.id and m.matchup_id is not null) as paired,
       (select count(*) from power_ranking p where p.league_id=l.id) as pr
from league l order by l.name nulls last, l.season;"
```

| id | sport | season | name | week_pts | weeks | paired | pr |
|---|---|---|---|---|---|---|---|
| 5 | nfl | 2025 | (Foot) Ball Knowers | 204 | 17 | 8 | 3 |
| 4 | nfl | 2026 | (Foot) Ball Knowers | 12 | 1 | 168 | 2 |
| 212 | nba | 2024 | Ball Knowers | 288 | 24 | 8 | 0 |
| 211 | nba | 2025 | Ball Knowers | 252 | 21 | 8 | 0 |
| 210 | nba | 2026 | Ball Knowers | 0 | 0 | 0 | 0 |
| 2 | nfl | 2025 | West Coast Fantasy Football | 216 | 18 | 0 | 0 |
| 1 | nfl | 2026 | West Coast Fantasy Football | 0 | 0 | 0 | 0 |
| 3 | nfl | 2026 | fantasy😍 | 0 | 0 | 0 | 0 |
| 460 | nfl | 2026 | test | 0 | 0 | 0 | 0 |

Week points are complete for every played season (204 = 12×17, 288 = 12×24, 252 = 12×21,
216 = 12×18 — no partial weeks). Pairings and power rankings are not.

## Q2 — Score extremes, football chain (the SC-002 target)

**Top 5**

| season | week | roster | starters_points |
|---|---|---|---|
| 2025 | 8 | 7 | **205.04** |
| 2025 | 7 | 5 | 203.24 |
| 2025 | 12 | 5 | 192.46 |
| 2025 | 13 | 8 | 191.38 |
| 2025 | 16 | 8 | 190.00 |

**Bottom 5**

| season | week | roster | starters_points |
|---|---|---|---|
| 2025 | 17 | 12 | **40.68** |
| 2025 | 2 | 4 | 73.14 |
| 2025 | 8 | 3 | 73.82 |
| 2025 | 17 | 4 | 76.90 |
| 2025 | 5 | 12 | 77.02 |

**SC-002 passes when** the page's top entry reads 205.04 at 2025 week 8.

## Q3 — Pairing coverage (the SC-003 target)

| league | season | weeks with pairings |
|---|---|---|
| (Foot) Ball Knowers | 2025 | **week 17 only, 8 rosters** |
| (Foot) Ball Knowers | 2026 | weeks 1–14, 12 rosters each (scheduled, unplayed) |
| Ball Knowers (NBA) | 2024 | week 24 only, 8 rosters |
| Ball Knowers (NBA) | 2025 | week 21 only, 8 rosters |
| West Coast Fantasy Football | 2025 | none |

**SC-003 passes when** (Foot) Ball Knowers 2025 lists weeks 1–17 with 12 paired rosters each,
after T029's ingest repair and T030's re-ingest.

## Q4 — Power-ranking snapshots

| league | season | week | kind | entries |
|---|---|---|---|---|
| (Foot) Ball Knowers | 2025 | 0 | COMPUTED_REALIZED | 12 |
| (Foot) Ball Knowers | 2025 | 8 | COMPUTED_REALIZED | 12 |
| (Foot) Ball Knowers | 2025 | 17 | COMPUTED_REALIZED | 12 |
| (Foot) Ball Knowers | 2026 | 0 | COMPUTED_REALIZED | 12 |
| (Foot) Ball Knowers | 2026 | 1 | COMMISSIONER | 12 |

Three of four played seasons have zero. Both R9/R10 traps are live here: 2026's only
`COMPUTED_REALIZED` row is **week 0**, and its greatest week is a **`COMMISSIONER`** row.

---

## T002 — Environment

- `docker compose up -d postgres` → container recreated, `pg_isready` after 1s.
- 9 leagues present; (Foot) Ball Knowers 2025 holds 204 `roster_week_points` rows over 17 weeks, so
  no re-ingest was needed before starting.

---

## After

Re-measurements recorded as slices land.

### T030 — after the ingest gate repair (Slice C)

Ingest re-run 2026-09-16 against the repaired gate:
`POST /api/ingest/league-history/1346366555759341568` →
`{"seasons":2,"rostersUpserted":24,"weeksIngested":216,"fixturesIngested":0}`

(`fixturesIngested` counts only the *future*-fixture path, which had nothing new
to add; the backfilled pairings are counted under `weeksIngested`.)

| league | season | weeks with pairings | paired rows | before |
|---|---|---|---|---|
| (Foot) Ball Knowers | 2025 | **17** | **196** | 1 week, 8 rows |
| (Foot) Ball Knowers | 2026 | 14 | 168 | unchanged |

Per-week for 2025: 12 paired rosters in weeks 1–14 and 16; 8 in weeks 15 and 17,
which are playoff weeks where the consolation side is not paired. **SC-003 passes.**

Margins verified against the reference product's own page for this league — every
value matches:

| | ours | reference |
|---|---|---|
| closest | 0.16, 2025 wk17 (132.58 / 132.42) | 0.16, 2025 wk17 (132.58 / 132.42) |
| closest | 0.36, 2025 wk4 (118.12 / 117.76) | 0.36, 2025 wk4 (118.12 / 117.76) |
| blowout | 118.86, 2025 wk8 (205.04 / 86.18) | 118.86, 2025 wk8 (205.04 / 86.18) |
| blowout | 88.28, 2025 wk6 (176.58 / 88.3) | 88.28, 2025 wk6 (176.58 / 88.3) |
| blowout | 84.22, 2025 wk12 (162.36 / 78.14) | 84.22, 2025 wk12 (162.36 / 78.14) |
| blowout | 77.08, 2025 wk15 (184.42 / 107.34) | 77.08, 2025 wk15 (184.42 / 107.34) |

One deliberate difference: the 4th-closest is a genuine tie at 2.46 (2025 wk9 and
wk14 both). We break it by earlier week; the reference picks wk14. Both are 2.46.

**Why this mattered.** Before the repair the endpoint still returned ten margins
— drawn from 2025 wk17 and 2026 wk1 only — and reported the biggest blowout as
**67.6**. The true answer is 118.86. `marginsUnavailableReason` was null, because
the lists were not empty. The panel was confidently wrong rather than visibly
empty, which is exactly why tasks.md made T030 a gate rather than a checkbox.
