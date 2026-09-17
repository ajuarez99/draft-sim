# Quickstart: Validating the league history record book

**Branch**: `002-league-history-record-book` · **Date**: 2026-09-16

How to prove each slice works. Every check here is a query or a page load, not an eye test — the
success criteria in [spec.md](./spec.md) are written to be verifiable, and this is how.

Shapes and field names: [contracts/league-history-api.md](./contracts/league-history-api.md).
Derivation rules: [data-model.md](./data-model.md).

---

## Prerequisites

Postgres must be up. It is the thing that silently ruins a verification run here — the backend
suite reports `BUILD SUCCESSFUL` with integration tests *skipped* when the DB is down, so a green
build proves nothing on its own.

```bash
docker compose up -d postgres
```

Confirm it is healthy and, separately, confirm the seed data this feature depends on exists:

```bash
docker exec draftsim-pg psql -U draftsim -d draftsim -c "select l.id, l.sport, l.season, l.name, (select count(*) from roster_week_points w where w.league_id=l.id) as week_pts from league l order by l.name nulls last, l.season;"
```

Expect (Foot) Ball Knowers 2025 with **204** rows over 17 weeks. If it is 0, run the ingest first:

```bash
curl -X POST http://localhost:8080/api/ingest/league-history/1346366555759341568
```

Run the services:

```bash
cd backend && ./gradlew bootRun
```

```bash
cd web && npm run dev
```

The page under test is `http://localhost:5173/leagues/1346366555759341568/history`.

---

## Slice A — Score extremes (US1, FR-001…FR-003)

### A1. The aggregate is right (SC-002)

Get the truth from the database first, then compare the page to it — not the other way round.

```bash
docker exec draftsim-pg psql -U draftsim -d draftsim -c "select w.season, w.week, w.roster_id, w.starters_points from roster_week_points w join league l on l.id=w.league_id where l.name like '(Foot)%' order by w.starters_points desc limit 5;"
```

Baseline measured 2026-09-16: `205.04` (2025 wk 8, roster 7), `203.24` (wk 7, r5), `192.46`
(wk 12, r5), `191.38` (wk 13, r8), `190.00` (wk 16, r8).

**Pass**: the page's top entry is 205.04 at 2025 week 8, and the endpoint's `records.highestWeeks[0]`
matches the query's first row.

```bash
curl -s http://localhost:8080/api/leagues/1346366555759341568/history | jq '.records.highestWeeks[0], .records.lowestWeeks[0], (.records.highestWeeks|length), (.records.lowestWeeks|length)'
```

**Pass**: both lists are the same length (FR-003).

### A2. Records span the chain, not one season (SC-001)

```bash
curl -s http://localhost:8080/api/leagues/1346366555759341568/history | jq '[.records.highestWeeks[].season] | unique'
```

**Pass**: includes 2025, i.e. a season that is *not* `seasons[0]`. A result of `[2026]` only means
the aggregation is scoped to the head league and FR-001 has failed.

### A3. No Sleeper call on load (SC-005)

Tail the backend log while reloading the page.

**Pass**: no outbound Sleeper request is logged for the `GET .../history` (FR-002). The ingest and
the backfill are the only paths allowed to talk to Sleeper.

### A4. Unit behaviour

```bash
cd backend && ./gradlew test --tests '*LeagueRecordServiceTest*'
```

Cover, per data-model R1–R4: an unowned roster-season still produces a record; a season with zero
stored weeks contributes nothing rather than a `0.00`; a tie inside the returned set renders both
rows; ordering is deterministic across repeat calls.

---

## Slice B — Season final rank (US2, FR-004…FR-006)

### B1. The two traps in the stored data

This database contains both traps live, so they can be tested against reality rather than a fixture:

```bash
docker exec draftsim-pg psql -U draftsim -d draftsim -c "select l.name, p.season, p.week, p.kind, count(e.*) from power_ranking p join league l on l.id=p.league_id left join power_ranking_entry e on e.ranking_id=p.id group by 1,2,3,4 order by 1,2,3;"
```

2026 has a **week 0** `COMPUTED_REALIZED` row and a **week 1 `COMMISSIONER`** row, and nothing else.

**Pass**: the 2026 season's rows report `rankStatus: "IN_PROGRESS"` with `finalRank: null`.

**Fail** if 2026 shows a rank — that means week 0 was taken as a final rank (R9) or the commissioner
snapshot was (R10). Both are silent wrong-answer bugs, not crashes; nothing will flag them but this
check.

### B2. A season that does have one

**Pass**: 2025 reports `rankStatus: "RANKED"` with `finalRankWeek: 17` and a rank on every row.

### B3. The backfill (FR-006)

Three of four played seasons have no snapshot. Pick one with complete week points — NBA 2024
(288 rows, 24 weeks) is the clearest case.

Before: `rankStatus: "NOT_COMPUTED"`, and the page shows a reason **plus a button**.

Click the button (do not curl it — the point of FR-006 is that the page never asks the reader to run
an endpoint; if you can only verify this with curl, the control is missing).

After: `rankStatus: "RANKED"`, `finalRankWeek: 24`.

Then click it a second time.

**Pass**: idempotent — the snapshot is replaced, not duplicated.

```bash
docker exec draftsim-pg psql -U draftsim -d draftsim -c "select count(*) from power_ranking where league_id=212 and kind='COMPUTED_REALIZED';"
```

### B4. No hardcoded season length (FR-011)

The NBA season backfilled at week 24 and the NFL season at week 17, with no football week count
anywhere in the path.

**Pass**: `grep` the changed backend files for a literal `17`, `18` or `14` used as a week boundary
and find none.

---

## Slice C — Matchup margins (US3, FR-007…FR-009)

### C1. Prove the gap before fixing it (SC-003)

```bash
docker exec draftsim-pg psql -U draftsim -d draftsim -c "select l.name, l.season, m.week, count(*) from league_matchup m join league l on l.id=m.league_id where m.matchup_id is not null group by 1,2,3 order by 1,2,3;"
```

Baseline measured 2026-09-16: (Foot) Ball Knowers **2025 → week 17 only, 8 rows**. NBA 2024 → week
24 only. NBA 2025 → week 21 only.

### C2. Re-run the ingest and re-measure

```bash
curl -X POST http://localhost:8080/api/ingest/league-history/1346366555759341568
```

Re-run the C1 query.

**Pass**: 2025 now lists weeks 1–17, 12 paired rosters each.

**This is the gate for the rest of Slice C.** Do not build the margins UI until this query passes —
before the ingest repair it returns one week, and a panel built against it will look correct on an
empty set.

### C3. Margins are correct and each pair appears once

The reference screenshot gives independent expected values for (Foot) Ball Knowers 2025: closest
margin **0.16** in week 17 (132.58 vs 132.42), and **0.36** in week 4; biggest blowout **118.86** in
week 8 (205.04 vs 86.18).

```bash
curl -s http://localhost:8080/api/leagues/1346366555759341568/history | jq '.records.closestMatchups[0], .records.biggestBlowouts[0]'
```

**Pass**: closest is 0.16 at 2025 week 17; biggest is 118.86 at 2025 week 8 with `winner.points ==
205.04` — the same number Slice A reports as the all-time high.

**Pass**: no matchup appears twice with the sides swapped (R6). Check by listing all returned
`(season, week, margin)` triples and confirming no duplicates.

### C4. Byes and unplayed fixtures

2026 has scheduled-but-unplayed fixtures for weeks 1–14 with scores for week 1 only.

**Pass**: unplayed weeks contribute no margins (R7) — not a margin against zero. A roster with a
null `matchup_id` contributes none (R5).

### C5. Ingest test

```bash
cd backend && ./gradlew test --tests '*LeagueHistoryIngestServiceTest*'
```

Must cover the repair specifically: given cached week points and absent pairings, the ingest fetches
those weeks; given both present, it does not re-fetch beyond the last scored leg.

---

## Empty and degraded states (FR-010, SC-004)

Check every panel against a league that lacks its input. `West Coast Fantasy Football` 2025 has 216
week points and **zero** pairings, which makes it the natural test subject.

| Panel | Condition | Pass |
|---|---|---|
| Record book | League with no stored weeks | One stated sentence, not two empty lists |
| Margins | `marginsUnavailableReason` non-null | That sentence renders in place of the cards |
| Rank cell | `NOT_COMPUTED` | Reason **and** a button |
| Rank cell | `IN_PROGRESS` | Reads as "not over yet", not as an error |
| Season ingested but unplayed | NBA 2026, 0 weeks | Contributes nothing; no `0.00` record |

**Pass (SC-004)**: no panel on the page is simultaneously empty and unexplained.

---

## Full suite

```bash
cd backend && ./gradlew test
```

**Check the skip count, not just the exit code.** A run reporting skipped integration tests means
Postgres was unreachable and the DB-backed checks above did not actually run.

```bash
cd web && npm run test
```

```bash
cd web && npm run build
```

---

## Slice D — team names (only if taken)

If [research.md](./research.md) D4 is taken, the visible change is that records read "Justice for
Wags — 205.04" instead of "GraftonCarlson — 205.04".

**Pass**: roster 7 in 2025 renders as `Justice for Wags`, roster 8 as `Puka-Boo`, roster 12 as
`Torta Pounder with Cheese` — matching the reference page. A roster with no stored team name falls
back to the manager's display name rather than rendering blank.
