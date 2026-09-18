# Quickstart: validating ffwrapped parity

**Branch**: `004-ffwrapped-feature-parity` | **Date**: 2026-09-18

Runnable checks that prove each story works end to end. Design details live in [plan.md](plan.md),
[data-model.md](data-model.md) and [contracts/](contracts/); this file is only how to run and confirm.

---

## Two traps that make a green run meaningless

Read both before trusting any result here.

**1. The backend suite reports success with integration tests skipped.** When Postgres is not reachable,
Gradle prints `BUILD SUCCESSFUL` with ~52 tests skipped. A green build is not evidence. Always read the
skip count:

```bash
cd backend && ./gradlew test 2>&1 | grep -iE "tests?.*(completed|skipped|failed)"
```

If the skip count is non-zero, Postgres is down — start Docker Desktop and `docker compose up -d`, then
re-run. Every verification below assumes zero skips.

**2. Several Claude sessions share this tree, this database and this server.** Do not assume the state a
previous step left. Measure before each check rather than trusting an earlier run.

---

## Prerequisites

```bash
docker compose up -d                                       # Postgres 17 on localhost:5433
cd backend && ./gradlew bootRun                            # http://localhost:8080
curl localhost:8080/api/health                             # weightsLoaded must be true
curl -X POST localhost:8080/api/ingest/all/1346366555759341568
cd web && npm install && npm run dev                       # http://localhost:5173
```

Ingest order matters on a cold database: players -> leagues -> board. `/api/ingest/all/{leagueId}` does
all three and is idempotent.

---

## US1 — Basketball can name its own starting lineup

The gate for US2 and US5. No UI; verified by test.

```bash
cd backend && ./gradlew test --tests '*SportRules*' --tests '*BasketballRules*'
```

**Expected:**

- `startingLineup` on an NBA roster returns seated players with slots and does not throw (US1.1).
- For every registered sport, `sum(startingLineup(r, s, sportValue).value()) == startingLineupValue(r, s)`
  exactly (US1.2, SC-003).
- Fed a deliberately ADP-inverted value function, both sports return the lineup optimal for **that**
  function, not for board order (US1.3, R5).
- Football's existing values are byte-identical to current output (US1.4).

**Also confirm the default is gone**, since this is the part that stops the bug recurring:

```bash
grep -n "UnsupportedOperationException" backend/src/main/java/com/ballknowers/draftsim/sport/SportRules.java
```

Expected: no match. A throwing default is inheritable; its absence turns the same mistake into a compile
error (FR-001).

**On the NBA open question** (R10): before writing the fixture, inspect a real NBA league's settings for
lineup cadence. If lineups are set daily, an optimal *weekly* lineup is not well defined — scope the
release to weekly-lineup leagues and refuse the others explicitly rather than computing a number whose
definition does not hold.

---

## US2 — Roster Management

```bash
curl -s localhost:8080/api/leagues/1346366555759341568/roster-management | jq '.teams[0]'
```

**Expected:** teams sorted by `totalPoints` descending, each with `totalPoints`, `potentialPoints`,
`efficiency`, `weeksCounted`, `weeksExcluded`.

**Reconcile against ffwrapped** (SC-001) — the point of the exercise. On 2026-09-18, ffwrapped published
for this league:

| Team | Total | Potential | Efficiency |
|---|---|---|---|
| Master Bates | 164.96 | 174 | 94.3% |
| Play with the Klittle | 158.36 | 167 | 94.6% |
| Dart has hit anotha Bower | 157.4 | 189 | 83.1% |
| Hunter? I Barkley Goedert | 92.56 | 121 | 76.0% |

`totalPoints` must match exactly — both sides read Sleeper's own weekly total. `potentialPoints` may
differ slightly, and a difference is **informative rather than automatically wrong**: ffwrapped applies
Sleeper's optimal-lineup rule, this app applies its own `SportRules` rule (R3). Investigate any gap
larger than a rounding difference; do not tune the formula to match.

**Cross-check against Sleeper's own aggregate:**

```bash
psql -h localhost -p 5433 -c "select roster_id, points_for, points_possible from roster_season where league_id = (select id from league where sleeper_league_id = '1346366555759341568');"
```

A large divergence from computed potential is a signal to read, not a test to fail (R3).

**Refusal and exclusion paths:**

- A league with zero scored weeks returns `teams: []` with a reason — not zeros (US2.4).
- A roster-week with empty `players_points` appears in `weeksExcluded` and is visible in the UI, never
  summed as zero (US2.5, FR-007).

**Both sports** (SC-002): run the same curl against an ingested NBA league and confirm the same three
columns. Confirm no sport branch was introduced in the service layer:

```bash
grep -rn "Sport.NBA\|Sport.NFL" backend/src/main/java/com/ballknowers/draftsim/engine/RosterManagementService.java
```

Expected: no match. Sport-specific behaviour reaches this service only through `SportRules` (FR-004).

**UI:** open `/leagues/1346366555759341568/roster-management`. The Points vs Potential chart must print
the exact value beside each bar and label its axis — one encoding per mark (FR-011).

---

## US3 — Expected Wins

```bash
curl -s localhost:8080/api/leagues/1346366555759341568/expected-wins \
  | jq '[.teams[] | {teamName, expectedWins, actualWins}] , ([.teams[].expectedWins] | add), ([.teams[].actualWins] | add)'
```

**The check that proves the model** (SC-004): the two sums must be equal within floating-point tolerance.
Expected wins that do not conserve are a model bug, not a rounding artifact.

Against ffwrapped on 2026-09-18 after week 1: jpelwell 0.45 expected / 1 actual (+0.55), Justice for
Wags 0.55 expected / 0 actual (-0.55) — a symmetric pair, which is the conservation law showing through.

Also confirm `luckSource` is exactly one of `SWING_WEEKS` or `CONSISTENT_OPPONENT_SCORING`, with
`swingWeeks` non-empty only in the first case (US3.4).

---

## US4 — Season Forecast and Playoffs

Figures come from the stored snapshot. Confirm nothing is computed on read:

```bash
curl -s localhost:8080/api/leagues/1346366555759341568/forecast | jq '.snapshotAt, .iterations'
curl -s localhost:8080/api/leagues/1346366555759341568/forecast | jq '.snapshotAt'   # identical
```

Two calls, same `snapshotAt`. A changing value means a page load is computing, which breaks the existing
rule (FR-009).

**The agreement check** (SC-005, FR-008): playoff odds on this view and in the Record cell must be the
same number from the same snapshot.

```bash
curl -s localhost:8080/api/leagues/1346366555759341568/forecast | jq '.teams[] | {teamName, playoffOdds}'
curl -s localhost:8080/api/leagues/1346366555759341568/power    | jq '.teams[] | {teamName, playoffOdds}'
```

**Refusals:** a league with divisions or a non-default `playoff_seed_type` returns
`{"available": false, "reason": "UNMODELLED_SEEDING"}`. Verify the new endpoint did not become a back
door around the existing refusal (US4.3).

Recompute is commissioner-triggered, as today — no new trigger.

---

## US5 — Weekly Report

**The migration is not the finish line.** `V18` adds `roster_week_points.starters`, and the ingest skip
gate is keyed on rows existing, not columns being populated (R6). Re-running ingest will skip every
settled week and leave the column null, silently. This already happened once in this repo with
`league_matchup`.

After migrating, extending the gate and re-ingesting, **count populated rows**:

```bash
psql -h localhost -p 5433 -c "select count(*) filter (where starters is not null) as populated, count(*) as total from roster_week_points where league_id = (select id from league where sleeper_league_id = '1346366555759341568');"
```

`populated` must equal `total` for scored weeks. If `populated` is small and `total` is large, the gate
was not extended — the exact 2026-09-14 failure, which measured 204 scores against pairings for one week.

```bash
curl -s localhost:8080/api/leagues/1346366555759341568/weekly-report/1 | jq '.awards, .awardsOmitted'
```

Awards needing starter identity appear in `awardsOmitted` with `"reason": "STARTERS_NOT_STORED"` for any
week that was never backfilled — omitted with a reason, never guessed (US5.4).

---

## US6 — Transactions

```bash
curl -X POST localhost:8080/api/ingest/transactions/1346366555759341568
curl -s localhost:8080/api/leagues/1346366555759341568/transactions | jq '.byManager, .rankDirection'
```

**Expected:** counts by type per manager matching Sleeper's own log; `rankDirection` present and stated
in the UI, because "4" meaning good is not self-evident (US6.3).

Confirm idempotency — the natural key is `(league_id, sleeper_transaction_id)`:

```bash
curl -X POST localhost:8080/api/ingest/transactions/1346366555759341568
psql -h localhost -p 5433 -c "select type, count(*) from league_transaction group by type;"
```

Counts must be unchanged after the second ingest.

A league with no trades returns `trades: []` and the UI says no trades have been made, rather than
rendering an empty chart (US6.4).

---

## Full regression before merge

```bash
cd backend && ./gradlew test 2>&1 | grep -iE "tests?.*(completed|skipped|failed)"   # skips must be 0
cd web && npm test
```

Then confirm in the browser, not only in tests:

- All four new pages appear in the rail's League section and mark themselves current (contracts/destinations.md).
- Each is reachable from the command palette.
- League context survives on every new route — the 003 defect must not reappear on pages added after it
  was fixed.
- An NBA league shows all four pages, none hidden by a sport gate.
