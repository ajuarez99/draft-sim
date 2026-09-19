---

description: "Task list for 005-daily-weekly-top-players"
---

# Tasks: Best nights, beside best weeks

**Input**: Design documents from `specs/005-daily-weekly-top-players/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md),
[data-model.md](data-model.md), [contracts/](contracts/weekly-report-sections.md),
[quickstart.md](quickstart.md)

**Tests**: Test tasks ARE included. [contracts/weekly-report-sections.md](contracts/weekly-report-sections.md)
states its ten items as assertions rather than descriptions ("A contract test must hold each of these"),
and the spec's success criteria are stated as things to verify (SC-002 the two lists differ, SC-003
totals reconcile, SC-004 football unchanged, SC-006 ordering is stable).

**Organization**: Grouped by user story so each ships and is verified independently.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependency on an incomplete task)
- **[Story]**: US1–US3, mapping to the user stories in spec.md
- Exact file paths are given in every task

## Path Conventions

Web application, per plan.md:

- Backend main: `backend/src/main/java/com/ballknowers/draftsim/`
- Backend test: `backend/src/test/java/com/ballknowers/draftsim/`
- Migrations: `backend/src/main/resources/db/migration/` (latest existing is `V19`)
- Frontend: `web/src/`

## Five rules that apply to every task here

1. **A green backend build is not evidence.** The suite prints `BUILD SUCCESSFUL` with integration
   tests silently skipped when Postgres is down. Read the skip count from
   `backend/build/test-results/test/*.xml`, never the build result. Baseline entering this feature:
   backend **489 tests, 0 failures, 0 skipped**; frontend **42 files, 441 tests**.
2. **Football must end this feature unchanged.** Not "mostly unchanged" — SC-004 says the Weekly Report
   shows exactly what it shows today for an NFL league. Any task that touches a shared path owes a check
   that football's response still carries `topPerformers` and neither new array.
3. **No sport name outside `SportRules`.** FR-004. `grep -rn "nba\|NBA" backend/.../engine/WeeklyReportService.java web/src/pages/WeeklyReport.tsx`
   returning a hit is a failure, not a style note.
4. **Several Claude sessions share this tree, DB and server.** Measure current state before each
   verification rather than trusting what an earlier task left behind. Restarting the backend affects
   other sessions — prefer checks that do not need one.
5. **Migration versions follow execution order.** Flyway's `outOfOrder` is unset in `application.yml`
   and therefore false: a lower version added after a higher one has been applied fails at boot. `V19`
   is the latest existing, so this feature takes **`V20`** — but take the next free number at merge
   time rather than reserving one.

---

## Phase 1: Setup

**Purpose**: Establish a trustworthy baseline and confirm the upstream data is still shaped the way
research measured it.

- [X] T001 Start Postgres and confirm the app answers: `docker compose up -d`, then `curl -s localhost:8080/api/health` returns `{"weightsLoaded":true,"status":"up"}`
- [X] T002 Record a baseline backend run with an explicit skip count by reading `backend/build/test-results/test/*.xml` after `cd backend && ./gradlew test` — expect `489 tests, 0 failures, 0 skipped`; capture the numbers in the PR description
- [X] T003 [P] Record a baseline frontend run: `cd web && npm test` (expect 42 files, 441 tests) and `npx tsc --noEmit` clean

**Checkpoint**: Baseline captured; skip count is 0.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: The data spine both ranking sections sit on, plus the sport rule that decides which form
renders, plus the one research finding that has only ever been measured on a single league.

**⚠️ CRITICAL**: T004 can invalidate the plan's approach. Do it before writing the ingest, not after.

- [X] T004 **GATE PASSED.** Re-asserted R3 and R6 against `1141438340626231296` (season 2024): the stored weekly value equalled a computed game in **10 of 10 weeks**, including a week whose only two games were both 0.0. Stronger than a repeat, because that league's scoring differs — `dd: 1.0` and `td: 2.0` against 2025's `2.0`/`3.0`, and no `bonus_ast_15p` or `bonus_reb_20p` at all. One generic key-by-key sum reproduced both, which is the evidence R3 claimed and had not yet earned. Outcome recorded in `specs/005-daily-weekly-top-players/research.md` risk 4
- [X] T005 Create migration `backend/src/main/resources/db/migration/V20__player_game.sql` per data-model.md: `id bigserial primary key`, `sport text not null`, `season int not null`, `week int not null`, `sleeper_player_id text not null`, `game_id text not null`, `game_date date not null`, `opponent text` (nullable), `is_away boolean` (nullable), `stats jsonb not null`, `fetched_at timestamptz not null`
- [X] T006 Add the constraints to `V20__player_game.sql`: `unique (sleeper_player_id, game_id)` as the natural key, plus `index on (sport, season, week)` for the read path and `index on (sport, season, sleeper_player_id)` for the backfill's own "what do I already have" check
- [X] T007 Write the migration's comment block in `V20__player_game.sql` explaining why the table is **not league-scoped** and why `stats` stays raw — two leagues in a sport and season share every game and differ only in scoring, the argument `V15__player_projection.sql` already makes in its own comment
- [X] T008 Create `PlayerGameRepository` in `backend/src/main/java/com/ballknowers/draftsim/store/PlayerGameRepository.java` with an upsert on the natural key `(sleeper_player_id, game_id)` and a read returning all rows for `(sport, season, week)`
- [X] T009 [P] **Deviation, on the repo's own precedent**: created `backend/src/main/java/com/ballknowers/draftsim/ingest/SleeperPlayerStatsClient.java` rather than adding a method to `SleeperClient.java` as this task first said. `SleeperProjectionClient` already argues the case in its javadoc — `SleeperClient` is pinned to `/v1`, and folding a versionless undocumented endpoint into it "would lend it the stability the documented surface has and hide, at the call site, that this is the one thing here that can vanish without a deprecation". This endpoint is exactly that, so it gets the same treatment. Measured shape recorded in its javadoc
- [X] T010 [P] Create `GameScoringService` in `backend/src/main/java/com/ballknowers/draftsim/engine/GameScoringService.java` computing a game's points as `sum(scoring_json[k] * stats[k])` over the league's scoring keys, generically key-by-key — **never** reading Sleeper's precomputed `pts_std`, which is standard scoring and does not match this league (research R3)
- [X] T011 [P] Test `GameScoringService` against the measured values in `backend/src/test/java/com/ballknowers/draftsim/engine/GameScoringServiceTest.java`: with the reference league's scoring, Jokić's week-5 games must compute to **58.5, 34.0, 44.0, 45.5** and Harden's week-1 games to **21.5, 29.5, 31.0**
- [X] T012 [P] Test in `GameScoringServiceTest` that a scoring key absent from a game's stats contributes zero rather than throwing, and that a stat key absent from the league's scoring is ignored rather than counted
- [X] T013 Add a non-defaulted method to `backend/src/main/java/com/ballknowers/draftsim/sport/SportRules.java` answering whether a player can play more than once per scoring period. **No `default` implementation** (research R7): a future sport must answer it to compile, the same forcing function feature 004 applied to `startingLineup`
- [X] T014 [P] Implement the cadence rule in `backend/src/main/java/com/ballknowers/draftsim/sport/FootballRules.java` — cannot play more than once per scoring period — with a javadoc line saying why
- [X] T015 [P] Implement the cadence rule in `backend/src/main/java/com/ballknowers/draftsim/sport/BasketballRules.java` — can — with a javadoc line saying why
- [X] T016 [P] **Deviation on file path**: added to the existing `backend/src/test/java/com/ballknowers/draftsim/sport/SportRulesAgreementTest.java` rather than a new `SportRulesCadenceTest.java`. That class already exists to assert "the obligations SportRules owes for every sport, asserted once over all of them rather than per implementation" — a file per rule would fragment exactly what it was built to hold. Three tests: every sport answers without throwing, the two sports **disagree** (football false, basketball true), and — guarding the interface rather than an implementation — `Method.isDefault()` is false, so a `default` added later cannot silently give a new sport football's shape

**Checkpoint**: R3/R6 hold on a second league; the table, the scoring and the sport rule exist and are tested.

---

## Phase 3: User Story 1 - Best nights of the week (Priority: P1) 🎯 MVP

**Goal**: The Weekly Report shows a basketball league's biggest single-game performances, each naming
the night and the opponent — the thing "Jokić 58.5" currently fails to say.

**Independent Test**: Open a scored week of NBA league `1229352720222134272` and confirm every
`bestNights` entry carries a `date` and an `opponent`, ordered by points descending, with the owning
team named. Ships without US2 and is useful alone.

### Tests for User Story 1 ⚠️

- [ ] T017 [P] [US1] Test that the per-game ingest is **idempotent on the natural key** — a second run over the same season inserts no rows and leaves the count unchanged — in `backend/src/test/java/com/ballknowers/draftsim/ingest/PlayerGameIngestServiceTest.java`
- [ ] T018 [P] [US1] Test that a game's week comes from the entry's own `week` field and is **never derived from `game_date`** (research R6, which also settles the spec's postponed-game edge case), in `PlayerGameIngestServiceTest.java`
- [ ] T019 [P] [US1] Test that a player whose fetch fails increments `playersFailed` and leaves every other player's rows stored, in `PlayerGameIngestServiceTest.java`
- [ ] T020 [P] [US1] Test that every `bestNights` entry's points equal **one game's** league-scored value and no entry is a sum, using a fixture where a player played more than once, in `backend/src/test/java/com/ballknowers/draftsim/engine/WeeklyReportBestNightsTest.java`
- [ ] T021 [P] [US1] Test the ordering rule from data-model.md verbatim — points descending, then `sleeper_player_id` ascending, then `game_id` ascending — and that two assemblies of the same week produce identical orderings including on exact ties (FR-009), in `WeeklyReportBestNightsTest.java`
- [ ] T022 [P] [US1] Test that when per-game detail is missing, `sectionsUnavailable` carries `{"section": "BEST_NIGHTS", "reason": ...}` and the array is **empty rather than populated from the stored single-game value** (FR-006), in `WeeklyReportBestNightsTest.java`
- [ ] T023 [P] [US1] Contract test for `GET /api/leagues/{sleeperId}/weekly-report/{week}` asserting the basketball shape in contracts/weekly-report-sections.md — `bestNights` present, `topPerformers` **absent**, `playersPlayMultiplePerPeriod: true` — in `backend/src/test/java/com/ballknowers/draftsim/api/WeeklyReportControllerIT.java`
- [ ] T024 [P] [US1] Contract test that two successive reads of the same week make **no upstream calls** (FR-008), in `WeeklyReportControllerIT.java`

### Implementation for User Story 1

- [ ] T025 [US1] Create `PlayerGameIngestService` in `backend/src/main/java/com/ballknowers/draftsim/ingest/PlayerGameIngestService.java` walking the players that appear in a league-season's stored `players_points` and fetching each player's whole season in **one call** (research R1), upserting through `PlayerGameRepository`
- [ ] T026 [US1] In `PlayerGameIngestService`, return `{playersWalked, gamesStored, playersFailed}` rather than only logging — the count is how anyone running a backfill learns whether it worked, the argument feature 004's `transactionsIngested` settled
- [ ] T027 [US1] Add `POST /api/ingest/player-games/{sleeperLeagueId}?season={season}` to `backend/src/main/java/com/ballknowers/draftsim/api/IngestController.java`, exposing the backfill as its own endpoint
- [ ] T028 [US1] **Do not** call `PlayerGameIngestService` from `backend/src/main/java/com/ballknowers/draftsim/ingest/LeagueHistoryIngestService.java` — ~331 calls per league-season do not belong in a routine per-league ingest. This is the deliberate opposite of the transactions decision in 004; add a comment at the chain walk recording why, so the next reader does not "fix" the inconsistency
- [ ] T029 [US1] Assemble `bestNights` in `backend/src/main/java/com/ballknowers/draftsim/engine/WeeklyReportService.java` from `PlayerGameRepository` rows scored through `GameScoringService`, attributing the owning team from `roster_week_points` exactly as `topPerformers` already does
- [ ] T030 [US1] In `WeeklyReportService`, emit `basis: "ALL_GAMES_PLAYED"` as a **required, never-defaulted** field on the basketball shape — the page's FR-005 disclosure is driven by it rather than by prose hardcoded in a component
- [ ] T031 [US1] Emit `sectionsUnavailable` from `WeeklyReportService` as `{section, reason}` where `section` is one of `BEST_NIGHTS`, `BEST_WEEK` and `reason` is a discriminator rather than a sentence, so the reader-facing words can change without a contract change
- [ ] T032 [US1] Add the response types for `bestNights`, `basis`, `sectionsUnavailable` and `playersPlayMultiplePerPeriod` to `web/src/api.ts`, leaving the existing `topPerformers` type in place
- [ ] T033 [US1] Render the Best Nights section in `web/src/pages/WeeklyReport.tsx` with each entry's exact value beside the name (FR-007), the date and the opponent, and a row rendered as unknown rather than guessed when `opponent` is null
- [ ] T034 [P] [US1] Add frontend tests in `web/src/pages/WeeklyReport.test.tsx` covering a populated Best Nights section, a null `opponent`, and a `sectionsUnavailable` entry rendering its reason rather than an empty list
- [ ] T035 [US1] Verify live per quickstart.md 1a–1d: backfill league `1229352720222134272` season 2025 (expect `playersWalked` near **331**, `playersFailed` **0**), re-run it and confirm the row count is unchanged, then confirm Jokić appears at **58.5 on 2025-11-17 vs CHI** and that **182.0 does not appear in `bestNights`**

**Checkpoint**: Best Nights renders from real data with real nights; US1 is independently demoable.

---

## Phase 4: User Story 2 - Best weeks, beside them (Priority: P2)

**Goal**: A second ranking beside the first, by everything a player scored across the whole fantasy
week — the comparison that makes the pair worth having.

**Independent Test**: For one scored week of `1229352720222134272`, every `bestWeek` total equals the
sum of that player's games that week, and the two sections disagree in their top three.

**Depends on**: US1 — needs no new storage, because US1's table already holds every game. Best Week is a
different aggregation of the same rows.

### Tests for User Story 2 ⚠️

- [ ] T036 [P] [US2] Test that each `bestWeek` entry's `totalPoints` equals the sum of that player's games for the week and `gamesPlayed` equals their count (SC-003), in `backend/src/test/java/com/ballknowers/draftsim/engine/WeeklyReportBestWeekTest.java`
- [ ] T037 [P] [US2] Test that a player who played **zero games** in the week is **absent from both arrays** — never present with `totalPoints: 0` or `gamesPlayed: 0` (a spec edge case), in `WeeklyReportBestWeekTest.java`
- [ ] T038 [P] [US2] Test that a week whose per-game detail is incomplete marks `complete` false and takes the `sectionsUnavailable` path with `{"section": "BEST_WEEK", "reason": "PER_GAME_DETAIL_MISSING"}` rather than reporting a quiet undercount (FR-006), in `WeeklyReportBestWeekTest.java`
- [ ] T039 [P] [US2] Test that the two rankings are **distinct lists** for a fixture week where they differ, so neither can be mistaken for the other (FR-003), in `WeeklyReportBestWeekTest.java`

### Implementation for User Story 2

- [ ] T040 [US2] Add the player-week aggregation to `backend/src/main/java/com/ballknowers/draftsim/engine/WeeklyReportService.java`: group the week's game rows by player, sum the league-scored values, and carry `gamesPlayed` and `complete` alongside the total
- [ ] T041 [US2] Emit `bestWeek` from `WeeklyReportService` ordered by `totalPoints` descending then `sleeper_player_id` ascending, and **omit** `topPerformers` on the basketball shape so three overlapping rankings never appear on one page
- [ ] T042 [US2] Add the `bestWeek` response type to `web/src/api.ts` with `totalPoints` and `gamesPlayed`
- [ ] T043 [US2] Render the Best Week section beside Best Nights in `web/src/pages/WeeklyReport.tsx`, showing **the number of games each total covers** next to the total so the figure is never read without its denominator, and stating from `basis` that these are real-world production rather than points that decided a matchup (FR-005)
- [ ] T044 [P] [US2] Add frontend tests in `web/src/pages/WeeklyReport.test.tsx` covering the populated pair, the games-played label, the FR-005 disclosure being present, and a `BEST_WEEK` entry in `sectionsUnavailable`
- [ ] T045 [US2] Verify live per quickstart.md 2a–2d: Jokić week 5 totals **182.0 across 4 games**, the two top-threes differ for at least one week (SC-002), `basis` is `ALL_GAMES_PLAYED`, and a rostered player who played no games appears in neither array

**Checkpoint**: The pair renders, disagrees usefully, and says what the bigger number means.

---

## Phase 5: User Story 3 - Football is left exactly as it is (Priority: P3)

**Goal**: An NFL league's Weekly Report shows precisely what it shows today. This story is satisfied by
*not* acting, so its tasks are the guards that prove nothing moved.

**Independent Test**: Open a scored week of NFL league `1346366555759341568` and confirm the response
carries `topPerformers`, carries neither new array, and the page looks as it did before this feature.

**Depends on**: Foundational (the cadence rule). Can otherwise run in parallel with US1 and US2.

- [ ] T046 [P] [US3] Contract test asserting football's response shape is **unchanged** — `topPerformers` present, `bestNights` and `bestWeek` **absent rather than empty**, `playersPlayMultiplePerPeriod: false` (SC-004) — in `backend/src/test/java/com/ballknowers/draftsim/api/WeeklyReportControllerIT.java`
- [ ] T047 [US3] Ensure `backend/src/main/java/com/ballknowers/draftsim/engine/WeeklyReportService.java` selects the form through the `SportRules` cadence rule and that the two shapes are mutually exclusive — `topPerformers` **or** the pair, never both (contract assertions 1 and 2)
- [ ] T048 [US3] Branch the rendering in `web/src/pages/WeeklyReport.tsx` on `playersPlayMultiplePerPeriod` from the response rather than inferring the sport from which arrays happen to be populated
- [ ] T049 [P] [US3] Add a frontend test in `web/src/pages/WeeklyReport.test.tsx` asserting that a football payload renders today's Top performers list and neither new section
- [ ] T050 [US3] Add the sport-name guard as a test rather than a convention: assert no sport literal appears in `WeeklyReportService.java` or `WeeklyReport.tsx` (FR-004). A grep in a test is ugly; three shipped instances of this defect class in this repo are uglier
- [ ] T051 [US3] Verify live per quickstart.md Story 3 that `GET /api/leagues/1346366555759341568/weekly-report/1` carries `topPerformers` and neither new array, and that the NFL Weekly Report page is visually unchanged in a browser

**Checkpoint**: Football provably untouched; all three stories independently verified.

---

## Phase 6: Polish & Cross-Cutting Concerns

- [ ] T052 Run the full backend suite and confirm the skip count is **0** by reading `backend/build/test-results/test/*.xml`, not the build result; the total must exceed the 489 baseline by the tests this feature added
- [ ] T053 [P] Run the full frontend suite (`cd web && npm test`) and `npx tsc --noEmit`, both clean
- [ ] T054 [P] Backfill the second NBA league `1141438340626231296` season 2024 (expect ~**280** players per research R2) and confirm Best Nights renders for one of its scored weeks
- [ ] T055 [P] Confirm ordering determinism live per quickstart.md: two successive requests for the same week return identical `bestNights` player-id sequences (SC-006, FR-009)
- [ ] T056 [P] Update `README.md`'s "Built so far" section with the pair and the basketball-only scope
- [ ] T057 [P] Update `HANDOFF.md` with current state, what is verified live versus only by test, and the per-game backfill's cost so the next reader does not run it casually
- [ ] T058 Record in `specs/005-daily-weekly-top-players/research.md` the T004 outcome and anything the second league revealed that the first did not
- [ ] T059 Run the full `quickstart.md` validation end to end and confirm every expected result

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — start immediately
- **Foundational (Phase 2)**: Depends on Setup — **blocks all user stories**. T004 is a gate that can
  invalidate the approach
- **US1 (Phase 3)**: Depends on Foundational
- **US2 (Phase 4)**: Depends on US1 — same rows, different aggregation
- **US3 (Phase 5)**: Depends on Foundational only; parallel with US1 and US2
- **Polish (Phase 6)**: Depends on the stories being done

### Story Dependency Graph

```text
Setup -> Foundational -+-> US1 (Best Nights) --> US2 (Best Week) -+-> Polish
                       |                                          |
                       +-> US3 (football untouched) --------------+
```

### Within Each Story

- Tests before implementation
- Storage before services, services before endpoints, endpoints before UI
- Live verification last, and never by re-reading a number an earlier task wrote

### Parallel Opportunities

- T003 with T002
- T009, T010 after T008; T011 and T012 with each other
- T014, T015 after T013; T016 after both
- All of T017–T024 together (different test files, no shared state)
- All of T036–T039 together
- T046 and T049 with each other, and with all of US1
- T053–T057 together

---

## Parallel Example: User Story 1

```bash
# Launch US1's tests together, before its implementation:
Task: "Idempotency on the natural key in PlayerGameIngestServiceTest.java"
Task: "Week comes from the entry's own field, never from game_date, in PlayerGameIngestServiceTest.java"
Task: "A night is one game, never a sum, in WeeklyReportBestNightsTest.java"
Task: "Ordering is deterministic including exact ties, in WeeklyReportBestNightsTest.java"
Task: "Contract: basketball shape carries bestNights and omits topPerformers, in WeeklyReportControllerIT.java"
```

---

## Implementation Strategy

### MVP scope

Phase 1 + Phase 2 + Phase 3 (US1). That delivers the thing the user actually asked for first — best
daily players, with the night named — and is demoable on real NBA data without US2 existing.

### Incremental delivery

1. Setup + Foundational → the spine, and T004 either confirms the approach or stops it
2. US1 → Best Nights ships; demo on `1229352720222134272` week 5
3. US2 → the pair; the comparison becomes the point
4. US3 → football proven untouched (can land any time after Foundational)
5. Polish → second league, docs, full quickstart

### Parallel team strategy

After Foundational, one developer can take US1→US2 while another takes US3; they share only
`WeeklyReportService` and `WeeklyReport.tsx`, and US3's changes there are the branch rather than either
section's contents.

---

## Notes

- `[P]` tasks touch different files and depend on nothing incomplete
- Every task names its file path; every user-story task carries its story label
- Commit after each task or logical group
- Stop at any checkpoint to validate a story independently
- The two numbers this feature must never confuse: **58.5** is what the league counted for Jokić in week
  5, **182.0** is what he actually produced. Both are true; only one decided a matchup
