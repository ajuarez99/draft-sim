# C3: Split equivalence (P3, one file per change)

## Backend splits (`SeasonSuperlativesService`, `LeagueHistoryController`)

- The public surface is unchanged. The same endpoints, paths and method signatures
  are visible to callers, so the existing tests compile untouched. A
  controller split may move `@GetMapping`s between classes, but the route table, as
  listed from Spring's mappings in a test or at startup, must be identical.
- If the file feeds a payload, the C2 characterization test for it (when one exists)
  passes unedited. Otherwise the existing ITs/tests pass with the same counts.

## `api.ts` split

- `api.ts` re-exports everything. `npx tsc -b` passes with **0** changes to any
  importing file.
- The production bundle size stays within ±1% (a re-export adds no runtime code).

## `styles.css` split

- Files are cut on contiguous ranges in original order, and the index `@import`s
  them in that order.
- `useNarrow.test.ts` is updated in the same commit to read the concatenated sheets
  (research R5).
- Visual check: every top-level route is screenshotted at 1280 px and 375 px before
  and after, using the same data, and the screenshots match. This is done in the
  browser pane against `vite dev`, with a hard refresh after any dev-server restart
  (AGENTS.md HMR note).

## Frontend page splits (`PowerRankings.tsx`, `LeagueAnalysis.tsx`)

- The existing page tests pass unedited, except for import paths of moved helpers.
- The same visual check as above, for that page only.

## Amended after review (2026-10-07)

Per [plan-review.md](../plan-review.md) findings 14, 15, 17 and 18:
- **CSS:** the primary gate is byte-identical built `dist/assets/index-*.css`;
  screenshots become a smoke check.
- **SeasonSuperlativesService:** a service-level golden test (stubbed repositories →
  `Result` JSON) is added **before** the split, and the units stay in package `engine`.
- **LeagueHistoryController:** split only after its P2b conversion.
- **`api.ts`:** no `api/*.ts` imports `'../api'`, and the "mirror `web/src/api.ts`"
  references are updated in the same PR.
