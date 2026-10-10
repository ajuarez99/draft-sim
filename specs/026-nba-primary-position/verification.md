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

## Integration and live verification (parent session, 2026-10-10 ~16:00 UTC, merged `main` with 027 + 028)

- **Backend full suite:** **1,568 tests, 0 failed, 0 errors, 0 skipped**, with Postgres on 5433 up.
- **Web:** `tsc -b` clean, **1,729/1,729** (110 files), production build OK.
- **Local re-ingest:** `POST /api/ingest/players?sport=nba` (admin token) wrote 2,119 players. Measured on 5433:
  - **Reordered:** Edwards `{PG,SG}`→`{SG,PG}`, Booker `{PG,SG}`→`{SG,PG}`, Durant `{PF,SF}`→`{SF,PF}`, Tatum `{PF,SF}`→`{SF,PF}`.
  - **Unchanged, as designed:** LeBron `{PF,PG,SF}`, and Herbert Jones `{SF,SG}` (whose `position` is PF, not eligible).
  - **First position across multi-position NBA players:** C 81 · PF 457 · PG 401 · SF 341 · SG 424.
- **Tilts, before and after** (`measure/tilts-before.txt`, `measure/tilts-after.txt`):
  - **Before:** no fitted manager had an SG tilt at all.
  - **After:** all 12 "Ball Knowers" NBA managers have one, ranging 0.67–1.53.
  - **Caveat on the comparison:** "before" was read from the stored `manager_profile.feature_json`, and "after" from the live `/seats` fit (`ProfileService.fit` per request). They're the same fitting code over the same two drafts, but not the same read path.
  - The 0.67 floor appears in both. `weights.yml` is untouched.
- **Browser** (local dev build, `/drafts/1339351318128517120`, signed in as popsharky):
  - The simulation ran and the board rendered.
  - Edwards's pill still reads "PG/SG", so labels are unchanged.
  - The format line reads "12 teams · 14 rounds · 1 min 30 s · snake · order reverses from round 3". That's 024 R7 from branch 027.
  - No console errors.
- **Still not verified:** prod. That needs the deploy, then Allan's `POST /api/ingest/players?sport=nba` with `X-Admin-Token`, then a check that Edwards reads `{SG,PG}`.
