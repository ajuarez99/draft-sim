# Quickstart: verifying spec 030

The bar is live verification on the real league, not a green suite (AGENTS.md). Run
the quickstart before the NBA's first scored week. After that, the `DRAFTED_UNSCORED` state
can't be observed on the real league again until next season.

## Prerequisites

- Postgres on `localhost:5433` (check that the backend suite's skip count is 0, not "52 skipped").
- Backend restarted after any Java change (`bootRun` doesn't hot-reload).
- After a frontend dev-server restart, do a hard refresh (Ctrl+Shift+R) of any open tab.
- Leagues: NBA "Ball Knowers" 2026 `1339351318115946496` (the target), NFL "(Foot) Ball
  Knowers" 2026 (regression), NBA "Test" `1414306784801239040` (second NBA scoring).

## V1. Projections ingest

```bash
curl -s -X POST -H "X-Admin-Token: $ADMIN" "localhost:8080/api/ingest/season-projections?sport=nba&season=2026"
```

Expect `rowsStored` ≈ 529 (measured upstream 2026-10-11) and `source: "rotowire"`. Run it
again: `fresh: true`, no fetch. With `sport=nfl`: 400.

## V2. Season state

```bash
curl -s localhost:8080/api/leagues/1339351318115946496/season-start
```

Expect `state: "DRAFTED_UNSCORED"`, `firstWeek.week: 1` and `startDate: "2026-10-20"`. The
NFL league expects `IN_SEASON`.

## V3. Strength numbers are right (SC-003)

For 3 rosters, rebuild `total` by hand: take the `starters` list and look up each player's
stored per-game line. Score it with the league's `scoring_settings`, then check it against
`starters[].points` and their sum to 0.1. Also confirm each starter is eligible for its
slot, and that no bench player with a higher value could legally replace a starter.

## V4. Ordering test (SC-004, bug class #1)

Covered by a unit test on the pure path: move the top projected player from the strongest
roster to the weakest. The weakest roster's rank must rise and the strongest's must not.
A structural test (12 rows, no duplicates) doesn't count for this.

## V5. Home in a real browser (SC-001, SC-002, SC-006)

Open `/leagues/1339351318115946496` signed in as a member. Check block by block:
- No 2025 matchup, power headline or awards.
- The header says the draft is complete and gives the first-games date.
- The favorite headline has no probability words.
- The strength block links to Analysis in one tap.
- Every projected number says "projected" and names the source.
- The footer lists the unprojected bonus categories.

Then do the same at phone width (375 px).

## V6. NFL regression (SC-005)

Save `/analysis` and `/season-start` JSON for the NFL league on `main` and on the branch.
The `projections` blocks must be identical apart from the four new fields, and the NFL
home must render the same blocks.

## V7. Missing inputs (FR-014)

Delete the stored NBA season projections locally and reload the home. The strength block
must say projections aren't loaded yet, with no zeros and no 2025 fallback. Then re-ingest.

## V8. After tip-off (owed; can't be done before 2026-10-20)

Once the first NBA week is scored:
- The state flips to `IN_SEASON`.
- The home returns to today's layout, with the draft-grades summary added.
- The spotlight answers about 2026 (R7).

Record the result in `verification.md` with the date.
