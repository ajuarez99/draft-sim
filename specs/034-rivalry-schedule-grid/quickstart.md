# Quickstart / verification guide: spec 034

## Prerequisites

- Local Postgres on 5433 (`draftsim`/`draftsim`), with the three chains already ingested
  (local ids 4/5, 9465/9466, 210/211/212). Not the 5432 cluster.
- Kill any stale `bootRun` on 8080 before testing a backend change (AGENTS.md), then start
  through `preview_start`.

## 1. Backfill brackets: scores must not move

```bash
curl -s -X POST -H "X-Admin-Token: $ADMIN_TOKEN" localhost:8080/api/ingest/brackets/1346366555759341568
curl -s -X POST -H "X-Admin-Token: $ADMIN_TOKEN" localhost:8080/api/ingest/brackets/1339351318115946496
curl -s -X POST -H "X-Admin-Token: $ADMIN_TOKEN" localhost:8080/api/ingest/brackets/1389361939561332736
```

Expected: 7 matches for each completed season (6-team brackets), and 7 *provisional* rows for each 2026 season (amended after review F2: Sleeper seeds the bracket from current standings).
**Before and after, run a `sum(starters_points)` per league from `roster_week_points`. They
must be identical** (research R10, NBA drift memory).

## 2. Matrix parity (SC-001, SC-002)

Integration test: for every current pair in each chain, the cell's W-L-T equals
`HeadToHeadService.compute(a, b)` filtered to the chain's league ids, and `byKind` sums to
the total.

Spot check: on "(Foot) Ball Knowers" 2025, rosters 1 and 3 show a PLAYOFF meeting in week 17
with `finals` set. The pairs (5, 12) and (6, 7) in week 15 are CONSOLATION (research R2's
live bracket).

## 3. Season grid (SC-003)

For each of the three 2026 leagues, every OPPONENT cell is mirrored, and no `priorRecord`
counts its own week. Week 1's `priorRecord` equals the full pre-2026 record.

## 4. Recommendation (SC-004, SC-006)

- Each league pairs every manager exactly once (12, 14 and 12 managers: even, so nobody is
  unpaired). The odd case is covered by a unit test with 11.
- Unit tests for preference ordering (research R6), and for thin labelling at N.
- Expected on live data, as a *prediction from R5's counts, not a measurement*: most
  "(Foot) Ball Knowers" pairings will be thin. Record the actual `thinCount` in
  verification.md either way.

## 5. Browser (SC-005, SC-007)

Open `/leagues/{id}/rivalries` for one NFL and one NBA league in the preview, signed in as a
member.

- The matrix renders, and a cell clicks through to `/managers/:a/versus/:b`.
- Your row's next-week cell shows the opponent and prior record.
- The rivalry section prints N as hand-set, and the Sleeper note.
- The rail has "Rivalries" under History for both sports.

Check a phone-width (375px) render: the matrix scrolls inside its panel, not the page.

## 6. Suites

```bash
cd backend && ./gradlew test
cd web && npx tsc -b && npm run build && npx vitest run
```

Check the skipped-test count too. ITs skip silently when Postgres is down
(memory `project_backend_suite_skips_its_silently`).
