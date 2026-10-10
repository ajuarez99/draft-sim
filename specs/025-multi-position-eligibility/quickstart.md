# Quickstart: verifying spec 025

Verification means driving the real rooms, not just running tests.

## Prerequisites

- A backend and web server from the `draft-sim-025` worktree. Add launch entries on ports outside the Windows excluded ranges (check `netsh interface ipv4 show excludedportrange protocol=tcp`), such as 8096 and 5197.
- Postgres on 5433, with the local NBA 2026 draft `1339351318128517120` (12 teams, pre-draft) and the NBA 2025 draft `1229352720230514688` (complete).

## Automated

```bash
cd backend && ./gradlew test
```

```bash
cd web && npx tsc -b && npx vitest run && npm run build
```

Required tests:
- **Backend `NbaLineupParityFixtureTest`.** It generates the parity cases from `BasketballRules`, and fails if `web/src/__fixtures__/nba-lineup-parity.json` is stale. Prove it can fail: edit one expected value in the file, watch it go red, then restore the file.
- **Web `lineup.parity.test.ts`.** The TS port matches the fixture on every case. Prove it can fail: revert the TS seating to the old two-pass greedy, watch the US3 counterexample case go red, then restore.
- **Web unit tests:**
  - `eligiblePositions` fallback;
  - `positionLabel` order (Edwards → "PG/SG", Durant → "SF/PF", LeBron → "PG/SF/PF");
  - scarcity counted by eligibility;
  - filters, using the SG recall test from SC-002 against a fixture of the top 108 shapes;
  - the pick-run detector.
- **SC-004:** the fixed-seed mock replay test, plus `git diff --stat` showing no change under `engine/`, `profile/` or `sport/` other than tests.

## Live checks, at a 1440×900 viewport

1. **SC-001:** on `/drafts/1339351318128517120/live`, five scarcity chips show, labelled "eligible", and none has a 0 pool. Record the five measured counts.
2. **US2:** in the same room, filter SG and confirm Anthony Edwards and Devin Booker are listed. Filter SF and confirm Jayson Tatum and Kevin Durant are listed. Their pills read "PG/SG" and "SF/PF", with no rank.
3. **US3 / SC-003:** start a 12-team NBA mock and auto-finish it. "Your team" shows the backend's starter count, 9 of 9, where spec 024 left it at 8 of 9.
4. **SC-005:** on a football room (`1346366555776126976`), positions, chips and filters match `main`.
5. **Fit:** in the compact board cells, a three-position pill ("SG/SF/PF") does not overflow the cell. Measure it.

Record everything in `verification.md`, keeping verified and not-run separate.
