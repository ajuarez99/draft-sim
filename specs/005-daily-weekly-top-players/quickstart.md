# Quickstart: verifying Best nights, beside best weeks

**Feature**: `005-daily-weekly-top-players` | **Date**: 2026-09-19

How to prove each story actually works, against real data rather than fixtures. Shapes live in
[contracts/](contracts/weekly-report-sections.md); entities in [data-model.md](data-model.md).

## Prerequisites

```bash
docker compose up -d
```

Confirm Postgres is reachable and the app is up before trusting any result below:

```bash
curl -s localhost:8080/api/health
```

Expect `{"weightsLoaded":true,"status":"up"}`.

**Reference leagues** (both already ingested with scored weeks):

| League | Sport | Season | Weeks | Distinct players |
|---|---|---|---|---|
| `1229352720222134272` | nba | 2025 | 1–21 | 331 |
| `1141438340626231296` | nba | 2024 | 1–24 | 280 |
| `1346366555759341568` | nfl | 2026 | 1+ | — |

## The rule that governs every check here

**A green build is not evidence.** The backend suite prints `BUILD SUCCESSFUL` with integration tests
silently skipped when Postgres is down. Read the skip count, never the build result:

```bash
python -c "import glob,xml.etree.ElementTree as ET; t=f=s=0
for p in glob.glob('backend/build/test-results/test/*.xml'):
    r=ET.parse(p).getroot(); t+=int(r.get('tests',0)); f+=int(r.get('failures',0))+int(r.get('errors',0)); s+=int(r.get('skipped',0))
print('tests',t,'failures',f,'skipped',s)"
```

The skip count MUST be `0` before any story is called done.

Several Claude sessions share this tree, database and server. Measure current state before each check
rather than trusting what an earlier step left behind.

---

## Story 1 — Best Nights

### 1a. Backfill, and count what landed

```bash
curl -s -X POST "localhost:8080/api/ingest/player-games/1229352720222134272?season=2025"
```

Expect `playersWalked` near 331 and `gamesStored` in the low thousands. **`playersFailed` must be 0**;
a non-zero count is a finding, not a rounding error.

Verify by counting rows, not by the response:

```bash
docker exec draftsim-pg psql -U draftsim -d draftsim -t -A -c \
  "select count(*), count(distinct sleeper_player_id) from player_game where sport='nba' and season=2025;"
```

### 1b. Idempotency

Run the same POST again and re-count. The row count MUST be unchanged — the natural key
`(sleeper_player_id, game_id)` upserts. This is the check that would catch a duplicate-insert bug that
a single run cannot.

### 1c. The section renders with nights

```bash
curl -s "localhost:8080/api/leagues/1229352720222134272/weekly-report/5"
```

Every `bestNights` entry must carry a `date` and an `opponent`, ordered by `points` descending. Spot
the known value: Jokić (`playerId` `1658`) scored **58.5 on 2025-11-17 vs CHI** — measured 2026-09-19
and the anchor for this whole feature.

### 1d. A night is never a sum

Confirm no `bestNights` entry equals a player's week total. Jokić's week 5 games were
58.5 / 34.0 / 44.0 / 45.5; **182.0 must not appear in `bestNights`**.

---

## Story 2 — Best Week

### 2a. Totals reconcile

For each `bestWeek` entry, `totalPoints` must equal the sum of that player's `player_game` rows for the
week and `gamesPlayed` their count (SC-003). Jokić week 5: **182.0 across 4 games**.

### 2b. The two lists actually differ

Compare the top three of `bestNights` and `bestWeek` for the same week. They must differ for at least
one week (SC-002) — otherwise the pair is carrying no information a single list did not, and the
feature has not earned its place.

### 2c. The disclosure is present

`basis` must be `ALL_GAMES_PLAYED`, and the page must state that these totals are real-world production
rather than points that decided a matchup (FR-005). Jokić's 182.0 never touched a standing; the league
counted 58.5.

### 2d. Zero-game players are absent

Pick a rostered player who played no games in the chosen week and confirm they appear in neither array —
not with `totalPoints: 0`.

---

## Story 3 — Football is untouched

```bash
curl -s "localhost:8080/api/leagues/1346366555759341568/weekly-report/1"
```

The response MUST contain `topPerformers` and MUST contain neither `bestNights` nor `bestWeek`
(SC-004). `playersPlayMultiplePerPeriod` must be `false`.

Then confirm the page itself is unchanged in a browser — the Weekly Report for an NFL league should look
exactly as it did before this feature.

**The stronger check**: `grep -rn "'nba'\|\"nba\"\|NBA" backend/src/main/java/com/ballknowers/draftsim/engine/WeeklyReportService.java` and the
same over `web/src/pages/WeeklyReport.tsx` must return nothing. The choice of form comes from the sport
rule, never a sport name compared in a service or component (FR-004).

---

## Cross-cutting

### Nothing is fetched on a page load (FR-008)

Request the same week twice and confirm no upstream calls occur — the endpoint reads stored rows only.
The second response must be identical to the first.

### Ordering is deterministic (FR-009)

```bash
for i in 1 2; do curl -s "localhost:8080/api/leagues/1229352720222134272/weekly-report/5" \
  | python -c "import json,sys; d=json.load(sys.stdin); print([e['playerId'] for e in d['bestNights']])"; done
```

Both lines must be identical, including where points tie exactly.

### Re-assert the findings on the second league

[research.md](research.md) risk 4: the scoring reproduction (R3) and week alignment (R6) were measured
on `1229352720222134272` only. Backfill `1141438340626231296` (season 2024) and confirm a spot-checked
player's league-scored game equals the value stored in `roster_week_points` for a week where that game
is the counted one. If it does not, the scoring or the week alignment differs per league and the plan's
R3/R6 need revisiting before the feature is trusted.

### Full suites

```bash
cd backend && ./gradlew test
```

Read the skip count with the command at the top of this file — it MUST be 0.

```bash
cd web && npm test
```

Baseline before this feature: backend `489 tests, 0 failures, 0 skipped`; frontend `42 files, 441
tests`.
