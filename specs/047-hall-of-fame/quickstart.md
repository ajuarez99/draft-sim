# Quickstart / validation: spec 047

## Prerequisites

- Local Postgres on 5433 (`draftsim`). Use a scratch copy for verification, as specs 045/046 did
  (`docker` on a free port), so the shared DB isn't mutated.
- Backend `bootRun` from this worktree. Check the classpath (memory: a worktree preview serves main).
- `cd web && npm run dev`.

## Before building: capture the baseline

Capture these on main `ebf4ee8` into `specs/047-hall-of-fame/baseline/`:

- `/rivalries` for one NFL and one NBA league;
- `/leagues/{id}/history`;
- `/managers/{id}/history` for 3 managers.

## Scenarios

1. **SC-001.** For each complete season (4 locally), `trophy-case.seasons[].champion.rosterId` equals
   the `champion: true` row of `/history` for that season.
2. **Runner-up.** Ball Knowers NBA 2024 → roster 6, 2025 → roster 7, West Coast FF 2025 → roster 3
   (research R1). (Foot) Ball Knowers 2025 shows `runnerUpUnavailable` until
   `BracketIngestService.backfillChain` runs on the scratch DB. After the backfill it shows the bracket
   loser.
3. **Points leaders.** Each equals the max `points_for` in that season's standings.
4. **Discovery parity (SC-003).** Every `discovery` entry equals its `matrix.pairs` entry, and none of
   closest/lopsided has `meetings < 3` (SC-002).
5. **Rivals.** For a current manager, every nemesis/victim record equals the matrix cell. A former
   opponent appears with `former: true`.
6. **SC-006.** Re-capture the baseline. The diff shows only the added `discovery` field.
7. **UI.** Check History, Rivalries and the manager page at desktop and 375px, for one NFL and one NBA
   league. Expand the Awards on one season and compare it against `/superlatives` for that season.

## Commands

    cd backend && ./gradlew test     # check the skip count (memory: ITs skip silently when Postgres is down)
    cd web && npx tsc -b && npx vitest run && npm run build
