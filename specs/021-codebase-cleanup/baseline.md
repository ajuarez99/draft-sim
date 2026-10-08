# Baseline (spec 021, Phase 1)

Measured 2026-10-07 on branch `021-codebase-cleanup` @ `47751c0`; the code is identical
to `origin/main` @ `7b7238e`. Postgres is the `draftsim-pg` Docker container on :5433.
The JDK is 24.0.2 on PATH, with a toolchain-provisioned 21 for the build.

## T001: backend

`./gradlew test` gave **BUILD SUCCESSFUL in 2m 29s**. From `build/test-results/test/*.xml`:

| tests | skipped | failures | errors |
|---|---|---|---|
| 1340 | **0** | 0 | 0 |

## T002: frontend

| check | result |
|---|---|
| `npx vitest run` | 85 files, **1074 tests passed** |
| `npx tsc -b` | exit 0 |
| `npm run build` | ✓ built (the >500 kB chunk warning is pre-existing) |
| `dist/assets/index-BEtZrnLk.js` | 513,388 B |
| `dist/assets/index-CubZmfzm.css` | 139,132 B |
| sha256 of the CSS | `55e778a276d857776032d8faaa7724cb197b3b95e2d4e202a074edd735c6bd0a` |
| `dist/pr-reference/` | **present** (removed by US1) |

## T003: duplicate counts and the SC-004 denominator

- Backend files with a `static (double|Double) roundN(` helper: **17**
- `ordinal` definitions: **5**, at `draftGrades.ts:87`, `rankOrder.ts:133`,
  `ExpectedWins.tsx:274`, `Superlatives.tsx:656` and `PowerRankings.tsx:139`
- `new LinkedHashMap|Map.of(` in `api/*.java`: **141**
- Error bodies (`Map.of("error"|"message"`): **30**. This is one more than the 29 in
  tasks.md. The extra one is `SimulationController:130`, an SSE `error` event, so it
  is also counted as SSE.
- SSE builds: SimulationController :98, :102 and :130, plus the LeagueController
  heartbeat at :565. That is 4 builds; 3 of them are not already in the error count.
- **In-scope success-body builds = 141 − 30 − 3 = 108.** This matches the tasks.md
  denominator. The three worst controllers' success bodies are LHC 32 (43 − 11),
  SC 13 (17 − 4) and LC 10 (17 − 6 − 1 heartbeat), so 55/108 = 50.9%.

## T013: after US1 (the new P3 CSS baseline)

| check | result |
|---|---|
| backend | 1346 tests (1340 + 6 GoldenJsonTest), **0 skipped**, 0 failed |
| vitest | 86 files, 1083 tests (1074 + 9 builders) |
| `tsc -b` | exit 0 |
| `dist/pr-reference/` | **absent** |
| JS | `index-CDEVlM9V.js` 513,385 B (-3) |
| CSS | `index-ROkkXWFm.css` 138,080 B (-1,052, the `.verify-*` rules) |
| **CSS sha256 (P3 oracle)** | `3b86420695aba6a42c4a9362570180d8da5eb196c22af70ded9771292c1fc667` |
| `db/migration` diff vs origin/main | empty |

The power rankings screenshot smoke check is deferred to T057's live pass. The only
CSS removed was `.verify-*`, and the grep in removal-evidence.md shows no remaining
user of those selectors anywhere in `web/src`. Not screenshotted yet.
