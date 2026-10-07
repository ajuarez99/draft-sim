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
