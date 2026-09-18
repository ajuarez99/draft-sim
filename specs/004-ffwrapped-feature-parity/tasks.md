---

description: "Task list for 004-ffwrapped-feature-parity"
---

# Tasks: Down the ffwrapped list, in both sports

**Input**: Design documents from `specs/004-ffwrapped-feature-parity/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md),
[data-model.md](data-model.md), [contracts/](contracts/), [quickstart.md](quickstart.md)

**Tests**: Test tasks ARE included. The spec requires them by name — SC-003 ("a test asserts value/report
agreement for every registered sport"), SC-004 (the conservation invariant), SC-006 ("fails a test") —
and the contracts specify assertions rather than merely describing shapes.

**Organization**: Grouped by user story so each ships and is verified independently.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependency on an incomplete task)
- **[Story]**: US1–US6, mapping to the user stories in spec.md
- Exact file paths are given in every task

## Path Conventions

Web application, per plan.md:

- Backend main: `backend/src/main/java/com/ballknowers/draftsim/`
- Backend test: `backend/src/test/java/com/ballknowers/draftsim/`
- Migrations: `backend/src/main/resources/db/migration/` (latest existing is `V16`)
- Frontend: `web/src/`

## Three rules that apply to every task here

1. **A green backend build is not evidence.** The suite prints `BUILD SUCCESSFUL` with ~52 integration
   tests skipped when Postgres is unreachable. Read the skip count, never the build result.
2. **Several Claude sessions share this tree, DB and server.** Measure current state before each
   verification instead of trusting what an earlier task left behind.
3. **Migration versions follow execution order, not story order.** Flyway's `outOfOrder` is unset in
   `application.yml` and therefore false: a lower version added after a higher one has been applied
   fails at boot. US4 ships first and takes `V17`; US5 takes `V18`; US6 takes `V19`. If these stories
   are staffed in parallel, whoever merges second takes the next free version rather than a reserved one.

---

## Phase 1: Setup

**Purpose**: Establish a trustworthy baseline before anything is changed.

- [X] T001 Start Postgres and confirm reachable: `docker compose up -d`, then verify `backend/src/main/resources/application.yml` points at localhost:5433 and `curl localhost:8080/api/health` returns `weightsLoaded: true`
- [X] T002 Record a baseline test run with an explicit skip count: `cd backend && ./gradlew test 2>&1 | grep -iE "tests?.*(completed|skipped|failed)"` — skip count MUST be 0 before any story is called done; capture the number in the PR description
- [X] T003 [P] Record a baseline frontend test run: `cd web && npm test`, capturing pass/fail counts
- [ ] T004 Ingest the reference league so later reconciliation has data: `curl -X POST localhost:8080/api/ingest/all/1346366555759341568`, then confirm `select count(*) from roster_week_points` is non-zero

**Checkpoint**: Baseline captured; skip count is 0.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Resolve the one open question and build the shared NBA fixture that US1, US2, US3, US4 and
US5 all verify against.

**⚠️ CRITICAL**: T005 resolves the plan's only NEEDS CLARIFICATION (research.md R10). It gates the NBA
half of every story below.

- [X] T005 Resolve NBA lineup cadence (research.md R10): fetch a real Sleeper NBA league's settings and determine whether lineups are set weekly or daily. Record the finding in `specs/004-ffwrapped-feature-parity/research.md` under R10. If daily, an optimal *weekly* lineup is not well defined — scope this release to weekly-lineup NBA leagues and add an explicit refusal rather than computing a number whose definition does not hold
- [X] T006 Capture a real Sleeper NBA matchup payload (including `players_points` and `starters`) as a test fixture in `backend/src/test/resources/fixtures/nba-matchup-week.json`, so US1–US5 are verifiable now without waiting on a live NBA league with scored weeks
- [X] T007 [P] Capture a real Sleeper NFL matchup payload from league 1346366555759341568 as `backend/src/test/resources/fixtures/nfl-matchup-week.json`, for the football side of the same assertions
- [ ] T008 [P] Add a shared test helper building a `RosterState` + `LeagueSettings` pair from a fixture file in `backend/src/test/java/com/ballknowers/draftsim/sport/LineupFixtures.java`, used by both sports' lineup tests

**Checkpoint**: Cadence question answered, fixtures available — story work can begin.

---

## Phase 3: User Story 1 - A basketball roster can name its own starting lineup (Priority: P1) 🎯 MVP

**Goal**: `SportRules.startingLineup` works for every sport, with one implementation per sport rather
than two, so every backward-looking view below is sport-agnostic by construction.

**Independent Test**: Call `startingLineup` on an NBA roster with a value function; assert it returns
seated players with slots and that their values sum to `lineupValue(prepareLineup(...))` with the same
function. No UI.

### Tests for User Story 1 ⚠️

> Write these FIRST and confirm they fail (US1.1 currently throws `UnsupportedOperationException`).

- [X] T009 [P] [US1] Test that `startingLineup` on an NBA roster returns seated players with slots and does not throw, in `backend/src/test/java/com/ballknowers/draftsim/sport/BasketballRulesTest.java` (US1.1)
- [X] T010 [P] [US1] Test value/report agreement — `sum(startingLineup(r, s, sportValue).value()) == startingLineupValue(r, s)` for every registered sport, in `backend/src/test/java/com/ballknowers/draftsim/sport/SportRulesAgreementTest.java` (US1.2, SC-003). **Correction, measured during implementation**: this cannot be asserted bit-exactly against a `DoubleStream.sum()`, which is Kahan-compensated, while the implementation accumulates naively — NBA differs by one ULP over the identical players in identical order. The test asserts bit-exact against the same accumulation, within float noise against the compensated sum, and exact equality of the named players and their per-player values, which is the arm that would actually catch a reintroduced second implementation
- [X] T011 [P] [US1] Test that a deliberately ADP-inverted value function yields a lineup optimal for *that* function in both sports, in `backend/src/test/java/com/ballknowers/draftsim/sport/SportRulesAgreementTest.java` — the R5 case, where board-order shortcuts silently produce a wrong answer (US1.3)
- [X] T012 [P] [US1] Characterization test pinning `FootballRules.startingLineup`'s current output for a fixture roster, in `backend/src/test/java/com/ballknowers/draftsim/sport/FootballRulesTest.java`, so US1.4's "unchanged" is asserted rather than assumed
- [X] T013 [P] [US1] Test asserting no `SportRules` implementation throws `UnsupportedOperationException` for `startingLineup`, by reflecting over every registered sport in `backend/src/test/java/com/ballknowers/draftsim/sport/SportRulesAgreementTest.java` (SC-003)

### Implementation for User Story 1

- [X] T014 [US1] Implement `startingLineup(RosterState, LeagueSettings, ToDoubleFunction<BoardEntry>)` in `backend/src/main/java/com/ballknowers/draftsim/sport/BasketballRules.java` by reading the seated players and their slots off the `Lineup` that `prepareLineup` already builds. **Do not write a new solver** — `prepareLineup` already takes an arbitrary value function and already solves the maximum-weight independent set over the transversal matroid, provably optimal by the matroid greedy theorem (research.md R4)
- [X] T015 [US1] Redefine `startingLineupValue` in `backend/src/main/java/com/ballknowers/draftsim/sport/BasketballRules.java` to delegate to `startingLineup`, matching `FootballRules` — replacing the current standalone `lineupValue(prepareLineup(roster, settings, this::value))` so "what is this roster's starting lineup" has exactly one implementation per sport (FR-002)
- [X] T016 [US1] Ensure the basketball read-out sorts candidates by the supplied `valueOf` before slots are filled, in `backend/src/main/java/com/ballknowers/draftsim/sport/BasketballRules.java` — the matroid greedy is optimal only for a pass in non-increasing order of the value being maximized, and realized points are not monotone in ADP (FR-003, R5)
- [X] T017 [US1] Remove the throwing `default` implementation of `startingLineup` from `backend/src/main/java/com/ballknowers/draftsim/sport/SportRules.java`, making it an abstract interface method. A throwing default is inheritable; removing it turns the same mistake into a compile error instead of a runtime failure (FR-001)
- [X] T018 [US1] Update the `startingLineup` javadoc in `backend/src/main/java/com/ballknowers/draftsim/sport/SportRules.java` to state the realized-vs-projected distinction: the method values a lineup with whatever function it is handed and must not assume that function is a projection. The current text justifies throwing in terms of projections, which is what wrongly locked basketball out of backward-looking views (R4)
- [X] T019 [US1] Run `cd backend && ./gradlew test --tests '*SportRules*' --tests '*BasketballRules*' --tests '*FootballRules*'` and confirm all pass with 0 skips
- [X] T020 [US1] Confirm the default is gone: `grep -n "UnsupportedOperationException" backend/src/main/java/com/ballknowers/draftsim/sport/SportRules.java` returns no match (quickstart.md US1)

**Checkpoint**: Basketball can name its own starting lineup; US2 and US5 are unblocked.

---

## Phase 4: User Story 2 - Roster Management, for both sports (Priority: P2)

**Goal**: The view the user pointed at — Total Points, Potential Points and Efficiency per team, plus
the Points vs Potential chart, in both sports, from data already in the database.

**Independent Test**: For an ingested league with ≥1 scored week, each roster's total equals the sum of
its stored weekly `starters_points`, and its potential equals the sum of per-week optimal lineups
computed from `players_points`. Reconcilable against ffwrapped's published numbers.

**Depends on**: US1 (T014–T017).

### Tests for User Story 2 ⚠️

- [X] T021 [P] [US2] Test that per-week optimal lineup value is computed from `players_points` for a fixture week in both sports, in `backend/src/test/java/com/ballknowers/draftsim/engine/RealizedLineupServiceTest.java`
- [X] T022 [P] [US2] Test that a week with empty or missing `players_points` is **excluded** and surfaced in `weeksExcluded`, never summed as zero, in `backend/src/test/java/com/ballknowers/draftsim/engine/RosterManagementServiceTest.java` (FR-007, US2.5)
- [X] T023 [P] [US2] Test that `efficiency` is **null, not 1.0**, when `potentialPoints` is 0, in `backend/src/test/java/com/ballknowers/draftsim/engine/RosterManagementServiceTest.java` (US2.4, data-model.md)
- [ ] T024 [P] [US2] Test that a league with zero scored weeks returns `teams: []` plus `"reason": "no scored weeks"` rather than zeros, in `backend/src/test/java/com/ballknowers/draftsim/engine/RosterManagementServiceTest.java` (US2.4)
- [X] T025 [P] [US2] Test that an NBA league produces the same three columns through the same code path, in `backend/src/test/java/com/ballknowers/draftsim/engine/RosterManagementServiceTest.java` (US2.3, SC-002)
- [ ] T026 [P] [US2] Contract test for `GET /api/leagues/{sleeperId}/roster-management` asserting the response shape in contracts/league-analytics-api.md — `rosterId`, `managerId`, `teamName`, `totalPoints`, `potentialPoints`, `efficiency`, `weeksCounted`, `weeksExcluded`, sorted by `totalPoints` descending — in `backend/src/test/java/com/ballknowers/draftsim/api/RosterManagementControllerTest.java` (US2.1)

### Implementation for User Story 2

- [X] T027 [US2] Create `RealizedLineupService` in `backend/src/main/java/com/ballknowers/draftsim/engine/RealizedLineupService.java` computing, for one roster-week, the optimal lineup under `SportRules.startingLineup` valued by that week's actual points from `players_points`. Split out from `RosterManagementService` deliberately: US2, US5 and later efficiency-based awards all need it, and three copies is the bug class this feature exists to remove (plan.md Complexity Tracking)
- [X] T028 [US2] In `RealizedLineupService`, sort candidates by realized points before filling slots — realized points are not monotone in ADP (FR-003, R5)
- [X] T029 [US2] In `RealizedLineupService`, return validity alongside the value so an excluded week is carried out of the service rather than silently dropped (FR-007)
- [X] T030 [US2] Add a query to `backend/src/main/java/com/ballknowers/draftsim/store/RosterWeekPointsRepository.java` returning all `(week, roster_id, starters_points, players_points)` rows for a league-season, so the page loads in one round trip
- [X] T031 [US2] Create `RosterManagementService` in `backend/src/main/java/com/ballknowers/draftsim/engine/RosterManagementService.java` computing per roster: `totalPoints` (sum of `starters_points`), `potentialPoints` (sum of realized lineups), `efficiency` (total ÷ potential, **null when potential is 0**), `weeksCounted`, `weeksExcluded`. Computed per week and summed, never from a season aggregate (FR-006)
- [X] T032 [US2] Ensure `RosterManagementService` contains no sport branch — sport-specific behaviour reaches it only through `SportRules`. Verify with `grep -rn "Sport.NBA\|Sport.NFL" backend/src/main/java/com/ballknowers/draftsim/engine/RosterManagementService.java` returning no match (FR-004, quickstart.md US2)
- [X] T033 [US2] Create `RosterManagementController` in `backend/src/main/java/com/ballknowers/draftsim/api/RosterManagementController.java` exposing `GET /api/leagues/{sleeperId}/roster-management` under the existing `/api` prefix. Named after the page rather than `LeagueAnalyticsController`, which is one letter from the existing `LeagueAnalysisController` (plan.md Structure decision)
- [ ] T034 [US2] Add the `rosterManagement` row to `web/src/destinations.ts` with route `/leagues/:sleeperLeagueId/roster-management`, an explicit `sports: ['nfl', 'nba']` (written out, never defaulted — FR-005), a `match` regex and `idKind: 'league'` so the rail keeps league context (contracts/destinations.md)
- [ ] T035 [US2] Register the route in `web/src/App.tsx` and confirm `web/src/destinations.test.ts` passes — it fails if a league-scoped route exists without a `destinations.ts` row (FR-012, SC-006)
- [ ] T036 [US2] Create `web/src/pages/RosterManagement.tsx` rendering the standings table sorted by total points, with `weeksExcluded` visible to the reader rather than silently absent (FR-007)
- [ ] T037 [US2] Add the Points vs Potential grouped bar chart to `web/src/pages/RosterManagement.tsx` with the **exact value printed beside each bar** and a labelled axis — one encoding per mark (FR-011, US2.2)
- [ ] T038 [P] [US2] Add frontend tests in `web/src/pages/RosterManagement.test.tsx` covering the populated table, the zero-scored-weeks message, and a null efficiency rendering as "—" rather than 100%
- [X] T039 [US2] Reconcile against ffwrapped (SC-001): compare `totalPoints` for league 1346366555759341568 against the 2026-09-18 figures in quickstart.md (Master Bates 164.96, Play with the Klittle 158.36, Dart has hit anotha Bower 157.4, Hunter? I Barkley Goedert 92.56). Totals must match exactly. A `potentialPoints` gap is **informative, not automatically wrong** — ffwrapped applies Sleeper's optimal-lineup rule, this app applies its own. Investigate gaps beyond rounding; do **not** tune the formula to match (R3)
- [X] T040 [US2] Cross-check computed potential against Sleeper's own `roster_season.points_possible` per quickstart.md. A large divergence is a signal to read, not a test to fail (R3)

**Checkpoint**: Roster Management ships for both sports, with no new ingest.

---

## Phase 5: User Story 3 - Expected Wins and schedule luck (Priority: P3)

**Goal**: Expected wins against a uniformly random opponent, wins above expected, strength of schedule,
and the weeks where luck swung a result.

**Independent Test**: Each team's expected wins equals its all-play win rate times weeks played, and
expected wins across the league sum to actual wins.

**Depends on**: Nothing in US1 or US2 — runs in parallel with Phase 4.

### Tests for User Story 3 ⚠️

- [ ] T041 [P] [US3] Test that a team's weekly contribution is the fraction of other teams it outscored that week, in `backend/src/test/java/com/ballknowers/draftsim/engine/ExpectedWinsServiceTest.java` (US3.1)
- [ ] T042 [P] [US3] Test the conservation invariant — `sum(expectedWins) == sum(actualWins)` across the league within floating-point tolerance — in `backend/src/test/java/com/ballknowers/draftsim/engine/ExpectedWinsServiceTest.java`. This is the check that proves the model rather than merely exercising it (SC-004, US3.2)
- [ ] T043 [P] [US3] Test that `luckSource` is exactly one of `SWING_WEEKS` or `CONSISTENT_OPPONENT_SCORING`, with `swingWeeks` non-empty only for the first — alternatives, never both (US3.4)
- [ ] T044 [P] [US3] Test that an NBA league computes identically, in `backend/src/test/java/com/ballknowers/draftsim/engine/ExpectedWinsServiceTest.java` (US3.5)
- [ ] T045 [P] [US3] Contract test for `GET /api/leagues/{sleeperId}/expected-wins` asserting `expectedWins`, `actualWins`, `winsAboveExpected`, `strengthOfSchedule`, `luckSource`, `swingWeeks` and top-level `leagueAveragePpg`, in `backend/src/test/java/com/ballknowers/draftsim/api/ExpectedWinsControllerTest.java`

### Implementation for User Story 3

- [ ] T046 [US3] Create `ExpectedWinsService` in `backend/src/main/java/com/ballknowers/draftsim/engine/ExpectedWinsService.java` computing per roster: `expectedWins` (sum over weeks of the fraction of other teams outscored), `actualWins`, `winsAboveExpected`
- [ ] T047 [US3] Add `strengthOfSchedule` to `ExpectedWinsService` as the mean of opponents' PPG minus the league-wide PPG, reading pairings from `backend/src/main/java/com/ballknowers/draftsim/store/LeagueMatchupRepository.java`
- [ ] T048 [US3] Add swing-week detection to `ExpectedWinsService`, emitting `luckSource` as a discriminator with `swingWeeks` populated only for `SWING_WEEKS` (US3.4)
- [ ] T049 [US3] Create `ExpectedWinsController` in `backend/src/main/java/com/ballknowers/draftsim/api/ExpectedWinsController.java` exposing `GET /api/leagues/{sleeperId}/expected-wins`
- [ ] T050 [US3] Add the `expectedWins` row to `web/src/destinations.ts` with route `/leagues/:sleeperLeagueId/expected-wins`, explicit `sports: ['nfl', 'nba']`, `match` and `idKind: 'league'` (FR-005)
- [ ] T051 [US3] Register the route in `web/src/App.tsx` and confirm `web/src/destinations.test.ts` passes (FR-012)
- [ ] T052 [US3] Create `web/src/pages/ExpectedWins.tsx` with the standings table and the Actual vs Expected chart, each mark carrying its exact value and a labelled axis (FR-011)
- [ ] T053 [US3] Add the Strength of Schedule chart to `web/src/pages/ExpectedWins.tsx`, **stating the sign convention** — positive means a harder schedule — rather than leaving the reader to infer it (US3.3)
- [ ] T054 [P] [US3] Add frontend tests in `web/src/pages/ExpectedWins.test.tsx` covering both `luckSource` branches rendering different explanations
- [ ] T055 [US3] Verify the conservation invariant live per quickstart.md US3, comparing against ffwrapped's 2026-09-18 figures (jpelwell 0.45 expected / 1 actual; Justice for Wags 0.55 / 0) — a symmetric pair is the conservation law showing through

**Checkpoint**: Expected Wins ships for both sports, with no new ingest.

---

## Phase 6: User Story 4 - Season Forecast and the Playoff picture (Priority: P4)

**Goal**: Surface the distributions `PlayoffOddsSimulator` already computes over 10,000 seasons and
currently discards at serialization.

**Independent Test**: The odds endpoint returns per-seed probabilities and win percentiles whose
marginals reproduce the already-published single playoff-odds number.

**Depends on**: Nothing in US1–US3 — runs in parallel with Phases 4 and 5.

**Migration**: takes `V17` — this story ships before US5, and versions follow execution order.

### Tests for User Story 4 ⚠️

- [ ] T056 [P] [US4] Test that the seed-distribution marginal reproduces the existing single playoff-odds figure, in `backend/src/test/java/com/ballknowers/draftsim/engine/PlayoffOddsSimulatorTest.java` (FR-008)
- [ ] T057 [P] [US4] Test that two successive reads of the forecast endpoint return an identical `snapshotAt` — proving nothing is computed on page load — in `backend/src/test/java/com/ballknowers/draftsim/api/SeasonForecastControllerTest.java` (FR-009, US4.2)
- [ ] T058 [P] [US4] Test that a league with divisions or a non-default `playoff_seed_type` returns `{"available": false, "reason": "UNMODELLED_SEEDING"}`, confirming the new endpoint is not a back door around the existing refusal, in `backend/src/test/java/com/ballknowers/draftsim/engine/PlayoffOddsServiceTest.java` (US4.3)
- [ ] T059 [P] [US4] Test that a league with no scored week returns `{"available": false, "reason": "NO_SCORED_WEEKS"}` (US4.4)
- [ ] T060 [P] [US4] Test that win percentiles p10/p90 derive from the stored histogram rather than a second computation, in `backend/src/test/java/com/ballknowers/draftsim/engine/PlayoffOddsServiceTest.java`
- [ ] T061 [P] [US4] Test that an NBA league whose seeding this app models produces a forecast, driven by weekly scores and pairings — both of which NBA has — using the fixture from T006, in `backend/src/test/java/com/ballknowers/draftsim/engine/PlayoffOddsServiceTest.java` (US4.5, FR-004)

### Implementation for User Story 4

- [ ] T062 [US4] Create migration `backend/src/main/resources/db/migration/V17__playoff_odds_distributions.sql` adding to the playoff-odds snapshot: `seed_counts jsonb` (`seed -> count` over simulated seasons) and `win_counts jsonb` (`wins -> count`), per data-model.md. **`V17`, not `V18`** — this story lands before US5's migration and Flyway will not accept a lower version afterwards
- [ ] T063 [US4] Emit the seed and win distributions from `backend/src/main/java/com/ballknowers/draftsim/engine/PlayoffOddsSimulator.java` — they are already computed over the 10,000 iterations and discarded; this retains them rather than adding a second simulation (R8)
- [ ] T064 [US4] Persist the distributions in `backend/src/main/java/com/ballknowers/draftsim/store/PlayoffOddsRepository.java`, written on the existing commissioner recompute — **no new trigger, no page-load computation** (FR-009)
- [ ] T065 [US4] Derive `averageWins`, `winRange` p10–p90, `averageSeed`, `seedOdds` and `championshipOdds` from the stored distributions in `backend/src/main/java/com/ballknowers/draftsim/engine/PlayoffOddsService.java`, keeping `playoffOdds` the same field the Record cell already reads so the two views cannot disagree (FR-008, SC-005)
- [ ] T066 [US4] Preserve both refusal paths in `PlayoffOddsService` — `UNMODELLED_SEEDING` and `NO_SCORED_WEEKS` returned as explicit `available: false` bodies so the UI can distinguish "no answer" from 0.0 (FR-009, contracts/league-analytics-api.md)
- [ ] T067 [US4] Create `SeasonForecastController` in `backend/src/main/java/com/ballknowers/draftsim/api/SeasonForecastController.java` exposing `GET /api/leagues/{sleeperId}/forecast`
- [ ] T068 [US4] Add the `forecast` row to `web/src/destinations.ts` with route `/leagues/:sleeperLeagueId/forecast`, explicit `sports: ['nfl', 'nba']`, `match` and `idKind: 'league'` (FR-005)
- [ ] T069 [US4] Register the route in `web/src/App.tsx` and confirm `web/src/destinations.test.ts` passes (FR-012)
- [ ] T070 [US4] Create `web/src/pages/SeasonForecast.tsx` with the projected standings table: playoff odds, average wins, win range, average seed, No. 1 seed odds (US4.1)
- [ ] T071 [US4] Add the seed-odds visualization to `web/src/pages/SeasonForecast.tsx`, each mark labelled with its exact probability (FR-011)
- [ ] T072 [US4] Render the refusal states in `web/src/pages/SeasonForecast.tsx` — an unmodelled seeding scheme says why rather than showing "--" with no explanation (US4.3)
- [ ] T073 [P] [US4] Add frontend tests in `web/src/pages/SeasonForecast.test.tsx` covering the populated table and both refusal branches
- [ ] T074 [US4] Verify agreement live per quickstart.md US4: playoff odds from `/forecast` and from `/power` must be the same number from the same snapshot (SC-005)

**Checkpoint**: Season Forecast and the playoff picture ship from the existing simulation, both sports.

---

## Phase 7: User Story 5 - Weekly Report (Priority: P5)

**Goal**: A per-week digest — matchups, top performers, and weekly awards — with awards that need
starter identity degrading honestly where that data was never stored.

**Independent Test**: For one scored week, matchups, top performers and every award not requiring
starter identity render from stored data alone.

**Depends on**: US1 (lineup naming) and US2 (`RealizedLineupService`).

**Migration**: takes `V18`, after US4's `V17`. Numbering by story instead would break the next boot.

**⚠️ The migration is not the finish line.** The ingest skip gate tests whether *rows* exist, not whether
a *column* is populated. Re-running ingest after adding `starters` will skip every settled week and leave
it null, silently. This exact failure already shipped once in this repo with `league_matchup` — 204
scores against pairings for one week (research.md R6).

### Tests for User Story 5 ⚠️

- [ ] T075 [P] [US5] Test that the ingest skip gate refetches a week whose `starters` is null even when scores and pairings are present, in `backend/src/test/java/com/ballknowers/draftsim/ingest/LeagueHistoryIngestServiceTest.java` — the R6 regression guard
- [ ] T076 [P] [US5] Test that top performers rank by that week's actual points from `players_points` with the owning team named, in `backend/src/test/java/com/ballknowers/draftsim/engine/WeeklyReportServiceTest.java` (US5.2)
- [ ] T077 [P] [US5] Test that an award requiring starter identity appears in `awardsOmitted` with `"reason": "STARTERS_NOT_STORED"` when `starters` is null — omitted with a reason, never guessed — in `backend/src/test/java/com/ballknowers/draftsim/engine/WeeklyReportServiceTest.java` (US5.4)
- [ ] T078 [P] [US5] Test that an award naming a bench-for-starter swap identifies the specific players when `starters` is populated, in `backend/src/test/java/com/ballknowers/draftsim/engine/WeeklyReportServiceTest.java` (US5.3)
- [ ] T079 [P] [US5] Test that an NBA league renders matchups, top performers and efficiency-based awards, in `backend/src/test/java/com/ballknowers/draftsim/engine/WeeklyReportServiceTest.java` (US5.5)
- [ ] T080 [P] [US5] Test that every matchup in a scored week reports both teams, each team's record and each team's final score, in `backend/src/test/java/com/ballknowers/draftsim/engine/WeeklyReportServiceTest.java` (US5.1)

### Implementation for User Story 5

- [ ] T081 [US5] Create migration `backend/src/main/resources/db/migration/V18__roster_week_starters.sql` adding `starters jsonb` to `roster_week_points` — an ordered array of `sleeper_player_id`. **Nullable by design**: weeks ingested before this column existed and never refetched stay null, and US5.4 requires the affected award to be omitted with a stated reason (data-model.md). **`V18`, not `V17`** — US4's migration lands first
- [ ] T082 [US5] Extend the skip gate in `backend/src/main/java/com/ballknowers/draftsim/ingest/LeagueHistoryIngestService.ingestWeeklyPoints` from `stored.contains(week) && paired.contains(week)` to also require the new column's presence, following the pattern already used when pairings hit this bug. **Same task as the migration** — a migration without the gate change is the silent failure (FR-010, R6)
- [ ] T083 [US5] Persist the `starters` array in `backend/src/main/java/com/ballknowers/draftsim/store/RosterWeekPointsRepository.java`, reading it from the matchup payload's `starters` field alongside the existing `points` and `players_points`
- [ ] T084 [US5] Re-ingest and verify the backfill **by counting populated rows**, not by a successful build: `select count(*) filter (where starters is not null) as populated, count(*) as total from roster_week_points where league_id = (select id from league where sleeper_league_id = '1346366555759341568');` — `populated` must equal `total` for scored weeks (quickstart.md US5, FR-010)
- [ ] T085 [US5] Create `WeeklyReportService` in `backend/src/main/java/com/ballknowers/draftsim/engine/WeeklyReportService.java` assembling matchups from `LeagueMatchupRepository` with both teams, records and final scores, and top performers ranked from `players_points` (US5.1, US5.2)
- [ ] T086 [US5] Implement the awards in `WeeklyReportService`, split into those computable from totals alone (`GOT_AWAY_WITH_IT`, `DESERVED_BETTER`, `ONE_PLAYER_CARRY`) and those requiring `starters` (`SELF_INFLICTED_WOUND`), reusing `RealizedLineupService` for every efficiency-based award rather than recomputing an optimal lineup
- [ ] T087 [US5] Emit `awardsOmitted` from `WeeklyReportService` carrying the kind and reason for each award that could not be computed (US5.4, contracts/league-analytics-api.md)
- [ ] T088 [US5] Create `WeeklyReportController` in `backend/src/main/java/com/ballknowers/draftsim/api/WeeklyReportController.java` exposing `GET /api/leagues/{sleeperId}/weekly-report/{week}`
- [ ] T089 [US5] Add the `weeklyReport` row to `web/src/destinations.ts` with route `/leagues/:sleeperLeagueId/weekly-report`, explicit `sports: ['nfl', 'nba']`, `match` and `idKind: 'league'` (FR-005)
- [ ] T090 [US5] Register the route in `web/src/App.tsx` and confirm `web/src/destinations.test.ts` passes (FR-012)
- [ ] T091 [US5] Create `web/src/pages/WeeklyReport.tsx` with the week selector, matchup list, awards and top performers
- [ ] T092 [US5] Render `awardsOmitted` visibly in `web/src/pages/WeeklyReport.tsx` — a missing award states why rather than simply not appearing (US5.4)
- [ ] T093 [P] [US5] Add frontend tests in `web/src/pages/WeeklyReport.test.tsx` covering a populated week and a week with omitted awards

**Checkpoint**: Weekly Report ships; the `starters` backfill is verified by row count.

---

## Phase 8: User Story 6 - Transactions, trades and waiver grades (Priority: P6)

**Goal**: The rest of the Roster Management page — transaction counts by type, trades with post-trade
positional grading, waiver/FA adds with FAAB, and Best Adds.

**Independent Test**: Ingest transactions for one league; counts by type per manager match Sleeper's own
transaction log for those weeks.

**Depends on**: US2 (extends the Roster Management page rather than adding a route).

**Migration**: takes `V19`, after US5's `V18`.

### Tests for User Story 6 ⚠️

- [ ] T094 [P] [US6] Test that transaction ingest is idempotent on the natural key `(league_id, sleeper_transaction_id)` — a second ingest leaves counts unchanged — in `backend/src/test/java/com/ballknowers/draftsim/ingest/TransactionIngestServiceTest.java`
- [ ] T095 [P] [US6] Test that waiver claims, free-agent adds/drops and trades are stored per week with manager and FAAB bid where present, in `backend/src/test/java/com/ballknowers/draftsim/ingest/TransactionIngestServiceTest.java` (US6.1)
- [ ] T096 [P] [US6] Test that a league with no trades returns `trades: []` and the UI says no trades have been made rather than rendering an empty chart, in `backend/src/test/java/com/ballknowers/draftsim/engine/TransactionAnalysisServiceTest.java` (US6.4)
- [ ] T097 [P] [US6] Test that post-move positional rank resolves through the sport's own positions, not a football-shaped list, in `backend/src/test/java/com/ballknowers/draftsim/engine/TransactionAnalysisServiceTest.java` (US6.5, FR-004)
- [ ] T098 [P] [US6] Contract test for `GET /api/leagues/{sleeperId}/transactions` asserting `byManager`, `trades`, `adds` and a top-level `rankDirection`, in `backend/src/test/java/com/ballknowers/draftsim/api/RosterManagementControllerTest.java`

### Implementation for User Story 6

- [ ] T099 [US6] Create migration `backend/src/main/resources/db/migration/V19__league_transaction.sql` per data-model.md: `id bigserial primary key`; `league_id bigint not null references league (id) on delete cascade`; `season int`; `week int`; `sleeper_transaction_id text`; `type text` checked to `WAIVER`, `FREE_AGENT`, `TRADE`, `COMMISSIONER`; `status text`; `roster_id int` (null for a multi-roster trade); `manager_id bigint references manager (id)` **nullable**, exactly as `roster_season.manager_id` is, for orphan rosters; `adds jsonb`; `drops jsonb`; `faab_bid int` nullable; `created_at timestamptz`
- [ ] T100 [US6] Add `unique (league_id, sleeper_transaction_id)` and an index on `(league_id, season, week)` to `V19__league_transaction.sql`, mirroring `roster_week_points_lookup_idx` and matching the read pattern (data-model.md)
- [ ] T101 [US6] Create `LeagueTransactionRepository` in `backend/src/main/java/com/ballknowers/draftsim/store/LeagueTransactionRepository.java` with an idempotent upsert on the natural key and a `storedWeeks(leagueId, season)` query for the skip gate
- [ ] T102 [US6] Create `TransactionIngestService` in `backend/src/main/java/com/ballknowers/draftsim/ingest/TransactionIngestService.java` walking weeks 1..`last_scored_leg` and calling the existing `SleeperClient.transactions(leagueId, week)` — which is already implemented and has had **zero callers** until now (R7)
- [ ] T103 [US6] Map Sleeper's payload in `TransactionIngestService`: `type`, `status` (failed waiver bids are what the "Failed bids" section reads), `adds`/`drops` as `player_id -> roster_id`, `settings.waiver_bid` to `faab_bid`, `leg` to `week`, `status_updated` to `created_at`
- [ ] T104 [US6] Apply the same skip discipline as weekly points in `TransactionIngestService`, keyed on the transactions table's own stored weeks — do not reuse `roster_week_points`' gate, which would be the R6 bug in a new place
- [ ] T105 [US6] Wire `TransactionIngestService` into the existing per-league ingest in `backend/src/main/java/com/ballknowers/draftsim/ingest/LeagueHistoryIngestService.java`, and expose `POST /api/ingest/transactions/{leagueId}` in `backend/src/main/java/com/ballknowers/draftsim/api/IngestController.java`
- [ ] T106 [US6] Create `TransactionAnalysisService` in `backend/src/main/java/com/ballknowers/draftsim/engine/TransactionAnalysisService.java` computing counts by type per manager
- [ ] T107 [US6] Add post-move positional grading to `TransactionAnalysisService`: a player's average positional rank over weeks **played** since the move, computed from `players_points` across the league, with `weeksCounted` beside every rank so a one-week sample is not read as a season verdict (data-model.md)
- [ ] T108 [US6] Emit `rankDirection: "LOWER_IS_BETTER"` from `TransactionAnalysisService` — explicit because "4" meaning good is not self-evident (US6.3)
- [ ] T109 [US6] Add `GET /api/leagues/{sleeperId}/transactions` to `backend/src/main/java/com/ballknowers/draftsim/api/RosterManagementController.java` — transactions extend the Roster Management page rather than adding a route, matching ffwrapped where they are sections of one view (contracts/destinations.md)
- [ ] T110 [US6] Add the League Transactions chart to `web/src/pages/RosterManagement.tsx`, broken out by type with the count labelled on each segment (US6.2, FR-011)
- [ ] T111 [US6] Add the League Trades section to `web/src/pages/RosterManagement.tsx`, stating the rank direction, and rendering "no trades have been made" for an empty list (US6.3, US6.4)
- [ ] T112 [US6] Add the Waivers & Free Agent Adds section plus Best Adds to `web/src/pages/RosterManagement.tsx`, showing FAAB spend where present
- [ ] T113 [P] [US6] Add frontend tests in `web/src/pages/RosterManagement.test.tsx` covering the transaction chart, the empty-trades message and the stated rank direction
- [ ] T114 [US6] Verify idempotency live per quickstart.md US6: run the transactions ingest twice and confirm `select type, count(*) from league_transaction group by type` is unchanged

**Checkpoint**: The Roster Management page the user pointed at is complete.

---

## Phase 9: Polish & Cross-Cutting Concerns

- [ ] T115 Run the full backend suite and confirm the skip count is **0**: `cd backend && ./gradlew test 2>&1 | grep -iE "tests?.*(completed|skipped|failed)"`
- [ ] T116 [P] Run the full frontend suite: `cd web && npm test`
- [ ] T117 Verify in the browser that all four new pages appear in the rail's League section and mark themselves current — the 003 defect must not reappear on pages added after it was fixed (contracts/destinations.md)
- [ ] T118 [P] Verify each new page is reachable from the command palette, labelled with its league
- [ ] T119 [P] Verify an NBA league shows all four new pages, none hidden by a sport gate
- [ ] T120 Verify a league switch from each new page lands on the equivalent page for the target league, falling back to History where the page is not offered
- [ ] T121 [P] Update `README.md`'s "Built so far" section with the four new pages and the both-sports claim
- [ ] T122 [P] Update `HANDOFF.md` with current state, what is verified live versus only by test, and what remains
- [ ] T123 Record in `specs/004-ffwrapped-feature-parity/research.md` R10 the resolved NBA cadence finding and whether a live NBA league with scored weeks now exists
- [ ] T124 Run the full `quickstart.md` validation end to end and confirm every expected result

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: no dependencies
- **Foundational (Phase 2)**: depends on Setup; T005 gates the NBA half of every story
- **US1 (Phase 3)**: depends on Foundational — **blocks US2 and US5**
- **US2 (Phase 4)**: depends on US1
- **US3 (Phase 5)**: depends on Foundational only — parallel with US2 and US4
- **US4 (Phase 6)**: depends on Foundational only — parallel with US2 and US3; owns `V17`
- **US5 (Phase 7)**: depends on US1 and US2; owns `V18`
- **US6 (Phase 8)**: depends on US2; owns `V19`
- **Polish (Phase 9)**: depends on the stories being delivered

### Story Dependency Graph

```text
Setup → Foundational ─┬─→ US1 ─┬─→ US2 ─┬─→ US5
                      │        │        └─→ US6
                      │        └─────────────────
                      ├─→ US3  (independent)
                      └─→ US4  (independent)
```

### Migration ordering

`V17` (US4) → `V18` (US5) → `V19` (US6), matching phase order. Flyway's `outOfOrder` is false, so a
story that merges out of this order must take the next free version and update plan.md, data-model.md
and this file — never claim a reserved lower number after a higher one is applied.

### Parallel Opportunities

- **T003, T007, T008** in Setup/Foundational
- **All US1 tests (T009–T013)** — different assertions, two files
- **US2, US3 and US4 can be worked simultaneously** once US1 lands — US3 and US4 touch nothing US2 touches
- **All test tasks within a story** marked [P]
- **`destinations.ts` rows (T034, T050, T068, T089)** all touch the same file — they are sequenced within their own stories and must not be run concurrently with each other
- **Polish T116, T118, T119, T121, T122**

### Within Each Story

Tests first and failing → migration + skip gate together → repository → service → controller →
`destinations.ts` row → route → page → live verification.

---

## Parallel Example: User Story 1

```bash
# All five US1 test tasks together:
Task: "T009 NBA startingLineup returns seated players in BasketballRulesTest.java"
Task: "T010 Value/report agreement across every registered sport in SportRulesAgreementTest.java"
Task: "T011 ADP-inverted value function optimality in SportRulesAgreementTest.java"
Task: "T012 Characterization test pinning FootballRules output in FootballRulesTest.java"
Task: "T013 No throwing startingLineup implementations in SportRulesAgreementTest.java"
```

---

## Implementation Strategy

### MVP scope

**US1 + US2** (T001–T040). US1 alone is invisible to users — it ships no UI — so the smallest
demonstrable increment is US1 plus Roster Management, which is also the view the user actually pointed
at. That is 40 tasks and requires no migration and no new ingest.

### Incremental delivery

1. Setup + Foundational → baseline captured, cadence question answered
2. **US1** → basketball can name its own lineup; no user-visible change, but every later story is now
   both-sport by construction
3. **US2** → Roster Management ships → **MVP, demo this**
4. **US3 and US4 in parallel** → Expected Wins and Season Forecast, neither needing new ingest
5. **US5** → Weekly Report; the first per-week column, and the first place the R6 backfill trap bites
6. **US6** → Transactions; the only new ingest pipeline, completing the page

### Parallel team strategy

After US1 lands, three tracks run independently: US2→US6 (roster management and transactions),
US3 (expected wins), US4 (forecast). US5 joins the first track once US2 is done. If US4 and US5 are in
flight at once, coordinate migration versions at merge time rather than reserving them up front.

---

## Notes

- Tests are included because the spec names them in its success criteria, not as a default.
- `[P]` means different files and no dependency on an incomplete task.
- Two tasks are deliberately **not** splittable: T081/T082 (migration and skip gate) and T099/T100
  (table and its constraints). Splitting either produces a shippable-looking change that silently does
  nothing, which is the failure mode research.md documents.
- Do not tune potential points to match ffwrapped (T039). A gap is a finding about two different
  optimal-lineup rules, not a bug to paper over.
- Commit after each task or logical group; stop at any checkpoint to validate a story independently.
