# Quickstart: verifying spec 063

Prerequisites: Postgres on `localhost:5433` (db/user `draftsim`) with historical data imported for at least one
full NBA season, one short NBA season (1998-99 or 2011-12) and one NFL season. Signed in locally.

Before building anything: capture the finish of every completed Time Machine game on the local DB
(`GET /api/history/games`) into `specs/063-time-machine-draft-room/baseline-finishes.json`, and one full
`result` JSON into the test resources. These are the SC-006 and FR-020 baselines.

Deploy order when this ships: **web first, then backend** (plan, PR-07).

## Automated

    cd backend && ./gradlew test        # check the skipped count: ITs skip silently without Postgres
    cd web && npx tsc -b && npx vitest run && npm run build

## Live (see plan.md "Acceptance for the verification pass" for the full list)

1. Start backend and web through the Browser tool's launch config. Check the bootRun classpath is this
   worktree's (a preview started from a worktree has served main's code before).
2. `/history` → start an NBA game → pick → watch the reveal; change speed; skip; reload mid-reveal.
3. Queue three players; let one be taken; Auto-pick.
4. Auto-finish; read the bracket; hand-check seeds and every bracket score against the week picker.
5. Repeat 2 and 4 for NFL and for a short NBA season.
6. Open a baseline game; compare with `baseline-finishes.json`.
7. 1366×768 and 375 px; keyboard only; reduced motion.
8. Open one mock draft and the live room: unchanged.
