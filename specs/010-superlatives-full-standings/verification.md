# Verification: 010 superlatives full standings

## Baseline (T001, 2026-09-29, branch at `587fbc3`, before any change)

These results were executed, not assumed.

- **Backend.** `./gradlew test` reported BUILD SUCCESSFUL.
  - **880 tests, 0 failures, 0 errors, 0 skipped.** The counts were summed from
    `backend/build/test-results/test/*.xml`.
  - Postgres was up on 5433 (`draftsim-pg`). `SuperlativesControllerIT` ran; it was not
    skipped.
- **Frontend.**
  - `npx tsc -b` was clean.
  - `npx vitest run src/pages/Superlatives.test.tsx` reported **43 passed / 43**.

## Full checks after the build (T027, 2026-09-29, commit `cfebb71`)

These results were executed by the parent session. They are not the builders' reports.

- **Frontend.** `npx tsc -b` was clean. `npm run build` succeeded. `npx vitest run` passed
  **630 / 630** across 52 files. `Superlatives.test.tsx` was 53 (43 baseline + 10 new).
- **Backend.** `./gradlew cleanTest test` ran **934 tests: 933 passed, 1 failed, 0 skipped**.
  - `SuperlativesStandingsIT` **ran**: 7 tests, 0 skipped, 0 failed.
  - The one failure is `RefreshControllerIT.aChainRunShowsRunningAtTheTopEvenWhenTheShownSeasonIsLoadedComplete`
    (`:267`). **It is pre-existing, not caused by this branch.**
    - The refresh code and test are byte-identical to `main`.
    - On a clean detached checkout of `origin/main` (`587fbc3`), the class ran 5 times in
      isolation. It **failed 4 of 5** runs, at the same test and the same line.
    - The builder's own full run had also hit it once, and passed on a re-run.
    - It is flagged as a separate follow-up task, not fixed here.
  - Note: the first `./gradlew test` after the build reported "up-to-date", which is stale XML
    from the builder's run. The counts above come from a forced `cleanTest test`.
