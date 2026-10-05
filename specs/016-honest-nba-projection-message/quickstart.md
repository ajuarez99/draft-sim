# Quickstart: verifying the honest NBA projection message

Each check is recorded as **run** (with its actual output) or **not run**, never
assumed. Message text and invariants are in [contracts/messages.md](contracts/messages.md).

## Prerequisites

- Postgres on `localhost:5433` (db/user `draftsim`). That's a different instance
  from the one on 5432; leave that one alone.
- The NBA "Ball Knowers" 2026 league ingested: Sleeper id `1339351318115946496`
  (research R6 explains why this one and not 2025).
- A fresh `bootRun` from this branch. `bootRun` doesn't hot-reload, so kill any
  older process on 8080 first or you'll test stale bytecode (AGENTS.md).

## V1: Unit tests (no DB needed)

```bash
cd backend && ./gradlew test --tests '*LeagueAnalysisServiceTest' --tests '*IngestControllerTest' --tests '*NoIngestHintsInMessagesTest'
```

Expected: all pass. The two new tests (research R3) must **fail on `main`**
first. Run them against the old strings before changing them, and record that.

## V2: No remaining "no equivalent" claims (SC-002)

```bash
grep -rniE "(basketball|nba) equivalent|\" equivalent|basketball has no projection source" backend/src web/src
```

> **Amended during implementation (2026-10-05):** the corrections themselves
> quote the old claim to say it was false, so three new comment lines also match:
> `LeagueAnalysisService.java` (the `projectionsNotBuiltReason` javadoc),
> `IngestControllerTest.java` (the new test's javadoc) and `FootballRulesTest.java:269`
> ("…believed true then, and wrong"). They're expected hits. What must not match
> is any **string literal**, or any comment that states the claim as true.

Expected: the three quoted corrections above, plus two lines about kickers, not projections:
`BasketballRules.java:394` and `MockDraftServiceTest.java:56` ("kickers go last"
equivalent). On `main` (run 2026-10-05) it also returns the four targets:
`IngestController.java:145`, `LeagueAnalysisService.java:450`,
`FootballRulesTest.java:269` and `destinations.ts:180`.

## V3: Analysis endpoint, NBA (SC-003)

```bash
curl -s localhost:8080/api/leagues/1339351318115946496/analysis
```

Expected: `projections.available` is `false` and `projections.reason` and
`matchups.reason` both equal M1's "After" text. If the reason is something
else (a season-over or playoff-week reason), R6's reasoning was wrong. Record
it and amend R6; don't adjust the check.

## V4: Analysis page in the browser, NBA

Open `http://localhost:5173/leagues/1339351318115946496/analysis` (vite dev).
Hard-refresh if a tab was open before the dev server restarted. Expected: the
projection blocks show M1's text. Screenshot it as proof.

Also confirm the gate didn't move: the NBA league's rail and `LeagueHome` still
don't offer Analysis.

## V5: Ingest refusal

```bash
curl -s -X POST "localhost:8080/api/ingest/projections?sport=nba&season=2026&fromWeek=1&toWeek=1"
```

Expected: HTTP 400 with `{"error": "<M2 After text>"}`. Use `curl -D -` to see
the status. If the API token filter is on in this environment, send the token
the same way other ingest calls do.

## V6: NFL unchanged

```bash
curl -s localhost:8080/api/leagues/<nfl 2026 league id>/analysis
```

Expected: projections behave exactly as on `main`. Either available, or the
pre-existing "Projections aren't available for weeks …" reason. Diff the
response shape against `main` if in doubt.

## V7: Full suites

```bash
cd backend && ./gradlew test
```
```bash
cd web && npx tsc -b && npm test
```

Expected: green. **Report the backend skip count**. A green run with ITs skipped
because Postgres was down isn't a full run (memory: suite skips ITs silently).

## Results (2026-10-05, branch `016-honest-nba-projection-message`, local Windows, Postgres 5433)

| Check | Status | Actual |
|---|---|---|
| T001 baseline | **run** | `LeagueAnalysisServiceTest` 22, `IngestControllerTest` 6, `NoIngestHintsInMessagesTest` 2: all pass, 0 skipped |
| SC-001 (T004) | **run** | T002 failed to compile (`cannot find symbol: method projectionsNotBuiltReason(Sport)`). T003 failed on the old ingest text (`AssertionFailedError: expected: <projection ingest is football-only…`). T003 was observed after T005, because T002's compile error blocks the whole test source set. T005 doesn't touch the ingest message, so the failure is against today's ingest text. |
| V1 | **run** | 23 / 7 / 2 pass, 0 skipped. The first attempt failed `NoIngestHintsInMessagesTest`: both strings used the word "ingest", which the scanner's second rule forbids (research "Amended during implementation"). Text fixed, scanner untouched. |
| V2 | **run** | No string-literal hits. Comment hits are the three quoted corrections plus the two "kickers go last" lines (see the amended expectation above). |
| V3 | **run** | 404 without `X-Sleeper-User` (league scoping: `membership.canSee`), which the plan didn't anticipate. As member `popsharky` (`1122386008709910528`): `projections.available=false`, and `projections.reason` = `matchups.reason` = `Roster projections aren't built for nba leagues yet: so far this app only has football projections.` `positionGroups` = PG/SG/SF/PF/C. R6's reasoning confirmed. |
| V4 | **run** | `/leagues/1339351318115946496/analysis` (signed in as popsharky) shows M1 in all five projection-backed blocks. Screenshot taken. The NBA league home has no link to `/analysis` (gate intact). |
| V5 | **run** | On the default backend: 403 `admin_token_required`. The ingest route is admin-gated since the commissioner honour-system change, and blank `ADMIN_TOKEN` fails closed, so the message is unreachable without a token. Re-run on a second backend (launch config `draft-sim-api-8084-admin`, local test token): `HTTP/1.1 400` with `{"error":"projections are football-only for now: the stored columns are Sleeper's pts_ppr / pts_half_ppr / pts_std, which are football scoring totals. Basketball projections need per-game stat lines scored with each league's settings, which isn't built yet (claude/projection-tools.md)."}` |
| V6 | **run** | NFL "(Foot) Ball Knowers" 2026 (`1346366555759341568`, member `1092366167651483648`): `projections.available=true`, reason null, 12 rosters. `matchups.available=true`. Unchanged. |
| V7 backend | **run** | `./gradlew test`: **1,074 tests, 0 skipped**, 0 failures, 0 errors (Postgres up, so ITs ran) |
| V7 web | **run** | `npx tsc -b` clean. `npm test`: 78 files, 973 tests, all pass |

Review finding (T014), not fixed, out of scope: the fan-facing text says "nba" in
lowercase because `Sport` only exposes `code()`. The old message did the same.
A display name for sports would fix it everywhere at once. Noted for later, not
widened into this feature.
