# Spec 026: build verification (build stage)

Written 2026-10-10 by the build stage. Local unit tests only.

## Built
- `PlayerIngestService.fantasyPositions`: for NBA, after mapping and `.distinct()`, Sleeper's `position` moves to index 0 if it is already in the list. Never inserted; null, "G" or ineligible leaves order unchanged. NFL path unchanged. No migration, `weights.yml` untouched.
- `DraftGradesService`: new `positionsDisplay` helper joins NBA positions in PG,SG,SF,PF,C order (enum order), so the label does not flip with the reorder (S3).
- S2 wording: `OnBrandPanel.tsx` tooltip, comments in `onBrand.ts`, `SimulationResult.java`, `api.ts`, `posRank.ts`.

## Tests (all passing, 0 skipped)
| Class | Tests |
|---|---|
| PlayerIngestServiceTest | 10 (7 new: Edwards, LeBron, Durant, Herbert Jones, null, "G", no fantasy_positions; plus NFL) |
| PickScorerTest | 5 (1 new, S5) |
| DraftGradesServiceTest | 33 (1 new) |
| PickDeciderTest | 4 |
| Spec025SimBaselineTest / NbaLineupParityFixtureTest / EngineOutputFrozenTest | 1 / 2 / 2 |
| Web vitest | 1704 passed (110 files); `tsc -b` clean |

S5 test: seat tilt SG 2.0, PG 0.5. A `[SG,PG]` player's positional term beats a pure `[PG]`; the same player built in the old alphabetical order `[PG,SG]` gets exactly the pure-PG term (asserted equal), which is the direction the change fixes. It was not run against a reverted ingest; it pins the scorer's use of `primary()`, with the ingest tests pinning the order.
The optional fit test (completed picks produce tilt(SG) > 1) was not written.

## Not evidence (S4)
`Spec025SimBaselineTest`, `EngineOutputFrozenTest` and `NbaLineupParityFixtureTest` build `Player`s directly, bypassing ingest. They did not move, and that says nothing for or against this change.

## Not verified yet
Local re-ingest before/after tilts, live UI, prod. The full backend suite was deliberately not run (shared Postgres on 5433). Prod needs `POST /api/ingest/players?sport=nba` after deploy (B1).
