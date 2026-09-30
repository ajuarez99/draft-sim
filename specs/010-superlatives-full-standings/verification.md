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
