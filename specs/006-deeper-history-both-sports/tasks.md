---

description: "Task list for 006-deeper-history-both-sports"
---

# Tasks: A history deep enough to argue with

**Input**: Design documents from `/specs/006-deeper-history-both-sports/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md), [data-model.md](data-model.md), [contracts/](contracts/), [quickstart.md](quickstart.md)

**Tests**: Included. The spec asks for them by name — SC-001 ("a test fails if a career figure is
computed across two sports"), SC-003, SC-004 ("a test asserts there is exactly one implementation of
potential points"), SC-006 (the conservation check). These are not optional extras; three of them are
the only thing standing between this feature and the defect class it exists to fix.

**Organization**: grouped by user story, in the spec's priority order. Each story is independently
shippable and independently verifiable via [quickstart.md](quickstart.md).

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel (different files, no dependency on an incomplete task)
- **[Story]**: which user story the task serves (US1…US6)
- Every task names the exact file it touches

## Path Conventions

Web application, two trees at the repository root:

- Backend: `backend/src/main/java/com/ballknowers/draftsim/...`, tests in `backend/src/test/java/...`,
  migrations in `backend/src/main/resources/db/migration/`
- Frontend: `web/src/...`, tests co-located as `*.test.ts(x)`

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: pin the current (wrong) answers before changing anything, so the corrections are provable
rather than asserted. Several Claude sessions share this tree, DB and server — measure, do not assume.

- [X] T001 Bring up Postgres with `docker compose up -d` and confirm container `draftsim-pg` is healthy and reachable as user `draftsim` on `localhost:5433`
- [X] T002 [P] Capture the US1 baseline: record the output of `GET /api/managers/7/history` showing six season rows with no `sport` field, into `specs/006-deeper-history-both-sports/baseline.md`
- [X] T003 [P] Capture the US2 baseline: record the six rows of `roster_season` with a non-null `final_placement` (two of them seasons with a 1-0 record) into `specs/006-deeper-history-both-sports/baseline.md`
- [X] T004 [P] Capture the US3 baseline: record all three efficiency figures for popsharky in (Foot) Ball Knowers 2025 — 92.7% from `points_possible`, 91.6% from `/roster-management`, 87.9% from ffwrapped — into `specs/006-deeper-history-both-sports/baseline.md`

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: store whether a season has finished. Every story below reads it — US2 to stop crowning a
live season, US1 for `ManagerSeason.complete`, and US3/US5/US6 for FR-007's rule that a figure must
state the seasons it covers.

**⚠️ CRITICAL**: no user story work begins until this phase is complete.

- [X] T005 Create `backend/src/main/resources/db/migration/V21__league_status.sql` adding `status text` to `league` (nullable — rows ingested before V21 have none until the next ingest walk); V20 is the latest existing migration
- [X] T006 Add `status` to `LeagueRepository.LeagueRow` and to the `upsert` signature and SQL in `backend/src/main/java/com/ballknowers/draftsim/store/LeagueRepository.java`
- [X] T007 Forward Sleeper's top-level `status` through `LeagueMapper.upsert` in `backend/src/main/java/com/ballknowers/draftsim/ingest/LeagueMapper.java` — it is a top-level field on the league object, not inside `settings`, and is dropped today
- [X] T008 Add a `complete()` helper on `LeagueRow` in `backend/src/main/java/com/ballknowers/draftsim/store/LeagueRepository.java` implementing the rule verbatim from data-model.md: *"A null `status` means not yet known, and is treated as **not complete**. It must never be treated as complete."*
- [X] T009 [P] Write `backend/src/test/java/com/ballknowers/draftsim/store/LeagueStatusTest.java` asserting `complete()` is false for `null`, `pre_draft`, `drafting` and `in_season`, and true only for `complete`
- [X] T010 Re-run the league-history ingest for all three chains so `league.status` is populated, and verify with `select sleeper_id, season, status from league`

**Checkpoint**: every league row knows whether its season finished. User stories can begin.

---

## Phase 3: User Story 1 — A manager's seasons say which sport they were (Priority: P1) 🎯 MVP

**Goal**: every season row a manager's page shows carries its sport and its league, and no displayed
total spans two sports.

**Independent Test**: `GET /api/managers/7/history` returns a sport on all six rows; the page shows
football and basketball in separate blocks with no combined header record.

### Tests for User Story 1

- [X] T011 [P] [US1] Write `backend/src/test/java/com/ballknowers/draftsim/store/ManagerHistorySportIT.java` asserting `RosterSeasonRepository.forManager(7)` returns a non-null `sport` on every row and at least one `nfl` and one `nba` row (SC-001)
- [X] T012 [P] [US1] Write a test in `web/src/pages/ManagerHistory.test.tsx` asserting that given seasons in two sports, no rendered record, points total or season count sums across them (SC-001)

### Implementation for User Story 1

- [X] T013 [US1] Add `sport` and `leagueName` to `RosterSeasonRepository.StandingRow` and select `l.sport, l.name` in `forManager`'s SQL in `backend/src/main/java/com/ballknowers/draftsim/store/RosterSeasonRepository.java` — extend the existing record via the `withSeason` flag on `mapRow`, do **not** add a second row type (research R1)
- [X] T014 [US1] Add `complete` to `StandingRow` in the same file, joined from `league.status` through the `complete()` helper from T008
- [X] T015 [US1] Emit `sport`, `leagueName` and `complete` on every season row from `standingRow(...)` in `backend/src/main/java/com/ballknowers/draftsim/api/LeagueHistoryController.java`
- [X] T016 [US1] Add `sport`, `leagueName` and `complete` to the `StandingRow` type in `web/src/api.ts`
- [X] T017 [US1] Group the season table by sport in `web/src/pages/ManagerHistory.tsx`, one block per sport, following the pattern `draftHistory` already uses in the same file
- [X] T018 [US1] Replace the combined `totalWins`/`totalLosses`/`championships` header computation in `web/src/pages/ManagerHistory.tsx` with per-sport figures — no header total may span sports (FR-002)
- [X] T019 [US1] Show the league name on each season row in `web/src/pages/ManagerHistory.tsx` so the two 2026 football rows (West Coast and (Foot) Ball Knowers) are distinguishable (US1.4)
- [X] T020 [US1] Render a single sport's block without an empty second one for a manager who plays one sport (US1.3), in `web/src/pages/ManagerHistory.tsx`

**Checkpoint**: manager pages no longer mix sports. Shippable on its own.

---

## Phase 4: User Story 2 — The trophy only goes to a season that finished (Priority: P2)

**Goal**: no champion is recorded or rendered for an in-progress season, and the two wrong values
already stored are cleared.

**Independent Test**: after re-ingest, `roster_season` holds four placements, not six; League History
shows no 🏆 on 2026; popsharky's titles read 1.

### Tests for User Story 2

- [X] T021 [P] [US2] Write `backend/src/test/java/com/ballknowers/draftsim/ingest/ChampionOnlyWhenCompleteTest.java` asserting that a league map with `status: "in_season"` and `metadata.latest_league_winner_roster_id: "1"` produces `finalPlacement = null` for every roster, and that the same map with `status: "complete"` produces `1` for roster 1 (SC-003)
- [X] T022 [P] [US2] Extend `backend/src/test/java/com/ballknowers/draftsim/ingest/LeagueHistoryIngestServiceTest.java` with a re-ingest case asserting a previously-stored `final_placement` is **cleared**, not skipped — a skip gate in front of a column never repairs it

### Implementation for User Story 2

- [X] T023 [US2] Gate the champion write on `status == "complete"` in `ingestStandings` in `backend/src/main/java/com/ballknowers/draftsim/ingest/LeagueHistoryIngestService.java`; `metadata.latest_league_winner_roster_id` on an in-season league names the **previous** season's winner (verified: league `1346366555759341568` returns `status: in_season` with that key set to `"1"`)
- [X] T024 [US2] Update the class javadoc in the same file to record why the metadata key alone is not sufficient, replacing the current "plan's own shortcut" note
- [X] T025 [US2] Confirm `RosterSeasonRepository.upsertAll`'s `on conflict … do update set final_placement = excluded.final_placement` writes a null over a stored value in `backend/src/main/java/com/ballknowers/draftsim/store/RosterSeasonRepository.java`, and that the row is still written rather than skipped
- [X] T026 [US2] Derive `champion` as `finalPlacement == 1 && complete` in `standingRow(...)` in `backend/src/main/java/com/ballknowers/draftsim/api/LeagueHistoryController.java`, so a stale stored value cannot resurface on the wire
- [X] T027 [US2] Re-run the league-history ingest for all three chains and verify the placement query returns four rows, none with a 1-0 record
- [X] T028 [US2] Replace the positional `i == 0` in-progress proxy passed to `PowerRankingService.finalRankForSeason` in `backend/src/main/java/com/ballknowers/draftsim/api/LeagueHistoryController.java` with the stored status, so the `IN_PROGRESS` rank reason stays correct once a chain's newest season completes

**Checkpoint**: one trophy per finished season, in both sports. Titles are now safe to aggregate.

---

## Phase 5: User Story 3 — A career profile that spans a manager's whole career, per sport (Priority: P3)

**Goal**: the named request. Record, win rate, points, efficiency, wins above expected, titles and
ranks — one block per sport, each stating the seasons it rests on.

**Independent Test**: popsharky's football career scoped to (Foot) Ball Knowers reads 11-4,
`pointsFor 2200.24`, `winRate 0.733` — reconciling exactly with ffwrapped's published profile.

### Tests for User Story 3

- [X] T029 [P] [US3] Write `backend/src/test/java/com/ballknowers/draftsim/engine/OneEfficiencyImplementationTest.java` asserting no class under `engine/` or `api/` reads `points_possible` / `pointsPossible` outside `RosterSeasonRepository` itself (SC-004)
- [X] T030 [P] [US3] Write `backend/src/test/java/com/ballknowers/draftsim/engine/ManagerCareerServiceTest.java` asserting: a season with zero scored weeks is `counted: false` and contributes to no average (US3.4); `winRate` is null rather than 0 when no games are played; `averageEfficiency` is null rather than 1.0 when potential is 0
- [X] T031 [P] [US3] Add a conservation test in the same file asserting `winsAboveExpected` summed across every manager in one league-season is zero within floating-point tolerance (US3.5)
- [X] T032 [P] [US3] Write a test in `web/src/pages/ManagerHistory.test.tsx` asserting every rendered rank shows its population and league name, never a bare `#2` (FR-008)

### Implementation for User Story 3

- [X] T033 [US3] Hoist the per-sport player map out of `forLeague` in `backend/src/main/java/com/ballknowers/draftsim/engine/RosterManagementService.java` so a career loop does not reload 4386 NFL / 2066 NBA player rows per season (research R4) — measured cost today is 29–120 ms per league-season
- [X] T034 [US3] Create `backend/src/main/java/com/ballknowers/draftsim/engine/ManagerCareerService.java` producing one `CareerProfile` per sport from a manager's roster-seasons, looping `RosterManagementService` and `ExpectedWinsService` per league-season
- [X] T035 [US3] Implement the counting rule in `ManagerCareerService`: `seasonsCounted` is the number of seasons with at least one scored week, and it is the divisor behind every average in the same object — one source for the number and its label
- [X] T036 [US3] Implement `averageEfficiency` in `ManagerCareerService` as the weeks-weighted mean of per-season efficiency from `RosterManagementService`, carrying `weeksCounted` and `weeksExcluded` through from `RosterManagementService.TeamRow`; never derive it from `roster_season.points_possible` (FR-006)
- [X] T037 [US3] Implement `titles` in `ManagerCareerService` as counted seasons with `finalPlacement == 1` **and** `complete` (depends on Phase 4)
- [X] T038 [US3] Implement `Rank` in `ManagerCareerService` per the contract: `figure`, `position`, `population`, `leagueName`, `sleeperLeagueId`, ranked within one league chain and one sport, one entry per chain the manager plays in (research R5)
- [X] T039 [US3] Implement the `unavailable[]` list in `ManagerCareerService` naming `playoffAppearances` ("only the champion's placement is stored; the bracket is not parsed") and `tradesPerSeason` ("trades are not attributed to a manager") — present even when empty, so "no answer" is distinguishable from an older server (FR-011)
- [X] T040 [US3] Emit `careers[]` alongside the retained flat `seasons[]` from `managerHistory(...)` in `backend/src/main/java/com/ballknowers/draftsim/api/LeagueHistoryController.java`, per the migration section of `contracts/manager-profile-api.md`
- [X] T041 [US3] Add the `CareerProfile` and `Rank` types to `web/src/api.ts`
- [X] T042 [US3] Render the career profile panel in `web/src/pages/ManagerHistory.tsx`, one block per sport, with the season count stated beside every average (SC-008)
- [X] T043 [US3] Render `unavailable[]` entries as stated reasons in `web/src/pages/ManagerHistory.tsx`, never as zeros
- [X] T044 [US3] Verify against ffwrapped: scoped to (Foot) Ball Knowers, popsharky reads 11-4 / 2200.24 / 73.3%, and `titles` reads 1 where ffwrapped prints 2 (SC-002, SC-003)
- [X] T045 [US3] Verify the NBA-only path renders every figure through the same code path with no sport branch in the service layer (SC-005)

**Checkpoint**: the requested feature is live for both sports.

---

## Phase 6: User Story 4 — Head to head, and the rivalry record (Priority: P4)

**Goal**: two managers compared side by side, with the head-to-head record ffwrapped leaves at `0-0`.

**Independent Test**: meetings summed across every pair in a season equal that season's paired-fixture
count — 196 roster-rows → 98 meetings for (Foot) Ball Knowers 2025.

### Tests for User Story 4

- [X] T046 [P] [US4] Write `backend/src/test/java/com/ballknowers/draftsim/engine/HeadToHeadServiceTest.java` with the conservation check: meetings across every pair in a season equal that season's paired fixtures (SC-006)
- [X] T047 [P] [US4] Add cases to the same file asserting a tie is counted as a tie rather than folded into losses (US4.5), and that two managers who never shared a league return an empty `sports[]` with `sharedNothing: true` rather than `0-0` (US4.3)

### Implementation for User Story 4

- [X] T048 [US4] Add a pair lookup to `backend/src/main/java/com/ballknowers/draftsim/store/LeagueMatchupRepository.java` returning both rosters of each `matchup_id` within a `league_id, season, week`
- [X] T049 [US4] Create `backend/src/main/java/com/ballknowers/draftsim/engine/HeadToHeadService.java` joining fixtures to `roster_week_points`, resolving each manager's roster per league-season through `roster_season.manager_id` — a manager owns different roster ids in different leagues (popsharky is roster 1 in one and roster 7 in another)
- [X] T050 [US4] Implement the meeting rule in `HeadToHeadService`: a pairing counts only when **both** sides have a stored `starters_points`; the 2026 chains hold 168 and 196 scheduled but unscored fixtures
- [X] T051 [US4] Implement `seasonsExcluded[]` in `HeadToHeadService`, each entry naming its season, league and reason (US4.4)
- [X] T052 [US4] Group results per sport in `HeadToHeadService`, never one combined record (US4.2)
- [X] T053 [US4] Create `backend/src/main/java/com/ballknowers/draftsim/api/ManagerComparisonController.java` serving `GET /api/managers/{aId}/versus/{bId}`, requiring `LeagueMembership#canSeeManager` for **both** managers and 404ing otherwise
- [X] T054 [US4] Include the `comparison` block in the response, each side read from `ManagerCareerService` so two pages never show two numbers for one manager
- [X] T055 [US4] Add the `versus` types and fetch helper to `web/src/api.ts`
- [X] T056 [US4] Create `web/src/pages/ManagerComparison.tsx` rendering the side-by-side table, the meetings list and the excluded seasons
- [X] T057 [US4] ~~Register the comparison page in `web/src/destinations.ts`~~ **Corrected during implementation**: `destinations.ts` is strictly league-scoped (`LeagueDestination.href` takes a `LeagueContext`, and `destinations.test.ts` asserts every non-league route matches no row there — including the existing `/managers/:id/history`). The route is registered in `web/src/App.tsx` beside `ManagerHistory` instead. FR-013's non-defaulted-sports rule does not apply to a manager-scoped page

**Checkpoint**: the argument the league actually has is now answerable, in both sports.

---

## Phase 7: User Story 5 — The league's all-time record book, deeper (Priority: P5)

**Goal**: all-time points leaders and longest streaks beside the existing highest/lowest weeks and
margins.

**Independent Test**: the longest streak's weeks are contiguous in the fixture data, and its start and
end weeks are named.

### Tests for User Story 5

- [X] T058 [P] [US5] Extend `backend/src/test/java/com/ballknowers/draftsim/engine/LeagueRecordServiceTest.java` asserting a streak's weeks are contiguous and its `startWeek`/`endWeek` are named
- [X] T059 [P] [US5] Add a test in `web/src/pages/LeagueHistory.test.tsx` asserting a chain with one played season states that an all-time figure covers one season (US5.4)

### Implementation for User Story 5

- [X] T060 [US5] Add `POINTS_LEADER`, `WIN_STREAK` and `LOSS_STREAK` records to `backend/src/main/java/com/ballknowers/draftsim/engine/LeagueRecordService.java`, each carrying `manager`/`rosterId`, `value`, `spanSeasons`, `startWeek`, `endWeek` and `withinSeasonOnly`
- [X] T061 [US5] Implement streaks within a season by default in the same file, with `withinSeasonOnly` carried to the client so the page can state the rule rather than leave it to the reader (research R7, US5.2)
- [X] T062 [US5] Keep an unowned roster renderable in the new record lists, as the existing record book already does for `managerId == null`
- [X] T063 [US5] Extend `recordBook(...)` in `backend/src/main/java/com/ballknowers/draftsim/api/LeagueHistoryController.java` to emit the new lists, always present even when empty
- [X] T064 [US5] Add the new record types to `web/src/api.ts`
- [X] T065 [US5] Render the new panels in `web/src/pages/LeagueHistory.tsx`, each with a stated reason when it has nothing to show, following the existing `RecordBook` / `MatchupMargins` convention
- [X] T066 [US5] Verify the basketball chain (`1229352720222134272`) renders identically (US5.3)

**Checkpoint**: League History reads as a record book across seasons, in both sports.

---

## Phase 8: User Story 6 — Waiver and FAAB tendencies, honestly scoped (Priority: P6)

**Goal**: moves per season per sport, and FAAB figures for the seasons that actually ran FAAB.

**Independent Test**: NBA 2025 bids show as percentages of 10000; NFL 2025's FAAB block is absent with
a stated reason; trades are omitted with theirs.

### Tests for User Story 6

- [X] T067 [P] [US6] Write a test asserting a season with `waiver_type` 0 is excluded from FAAB aggregation with the reason "used waiver priority, not FAAB", and that seasons with different budgets aggregate as percentages of each season's own budget (SC-007, FR-010)
- [X] T068 [P] [US6] Add a test asserting `tradesPerSeason` is omitted with its reason and never rendered as 0 (FR-011) — 25 trades exist and every one has `manager_id` NULL

### Implementation for User Story 6

- [X] T069 [US6] Add a per-manager aggregation query to `backend/src/main/java/com/ballknowers/draftsim/store/LeagueTransactionRepository.java` returning counts by `type` and bid figures, scoped to a set of league ids
- [X] T070 [US6] Extend `backend/src/main/java/com/ballknowers/draftsim/engine/TransactionAnalysisService.java` — which already computes per-league-season manager counts by type — with a career-grain form, rather than writing a second implementation beside it
- [X] T071 [US6] Implement the FAAB normalisation rule verbatim from data-model.md: all figures are fractions of **each season's own** `settings_json.waiver_budget`, aggregated after normalisation; budgets in this database range 100 → 10000, so dollars do not compare across one manager's seasons
- [X] T072 [US6] Implement `faabExcludedSeasons[]`, each naming its season, league and the reason "used waiver priority, not FAAB"; `waiver_type 0` means no bidding, which is why NFL 2025 holds 321 waiver rows and zero bids — correct format, not an ingest defect
- [X] T073 [US6] Attach `waivers` to each `CareerProfile` in `backend/src/main/java/com/ballknowers/draftsim/engine/ManagerCareerService.java`
- [X] T074 [US6] Add the `WaiverTendency` and `FaabTendency` types to `web/src/api.ts`
- [X] T075 [US6] Render the waiver panel per sport in `web/src/pages/ManagerHistory.tsx`, with excluded seasons and the trade omission shown as stated reasons

**Checkpoint**: all six stories complete.

---

## Phase 9: Polish & Cross-Cutting Concerns

- [ ] T076 Remove the flat `seasons[]` field from `managerHistory(...)` in `backend/src/main/java/com/ballknowers/draftsim/api/LeagueHistoryController.java` and from `web/src/api.ts`, once nothing reads it — step 3 of the migration in `contracts/manager-profile-api.md`, in a change of its own
- [X] T077 [P] Amend `specs/002-league-history-record-book/contracts/league-history-api.md` to record that `champion` is false for any season whose `league.status` is not `complete`
- [X] T078 [P] Verify every new chart carries the exact value beside the mark and a labelled axis; one encoding per mark (FR-014) — **no charts were added**: every new figure is a value+label card or a ranked list, each printing its exact number beside its name. Verified visually at desktop and 375px
- [X] T079 [P] Check the new panels in both light and dark themes and at phone width
- [X] T080 Measure the manager profile's warm response time against the ~250 ms goal for the deepest manager (popsharky, six roster-seasons across three leagues), and confirm the T033 hoist is what carries it
- [ ] T081 Run the full backend suite and **read the skip count**, not the build result: `grep -ho 'skipped="[0-9]*"' backend/build/test-results/test/*.xml | sort | uniq -c` — `BUILD SUCCESSFUL` is printed with integration tests silently skipped when Postgres is down
- [X] T082 Run `web` tests with `npm test` in `web/`
- [ ] T083 Walk every scenario in [quickstart.md](quickstart.md) end to end and record the results
- [ ] T084 Update `README.md`'s status section with what shipped

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: no dependencies — start immediately
- **Foundational (Phase 2)**: depends on Setup — **blocks every user story**, because `league.status`
  is what `complete` is read from and every story's counting rule depends on it
- **US1 (Phase 3)**: depends on Phase 2 only
- **US2 (Phase 4)**: depends on Phase 2 only — independent of US1
- **US3 (Phase 5)**: depends on Phase 2; **requires Phase 4** before `titles` is correct
- **US4 (Phase 6)**: depends on Phase 2; T054 requires `ManagerCareerService` from Phase 5
- **US5 (Phase 7)**: depends on Phase 2 only — independent of US1–US4
- **US6 (Phase 8)**: depends on Phase 2; T073 requires `ManagerCareerService` from Phase 5
- **Polish (Phase 9)**: depends on the stories you chose to ship

### The one hard ordering constraint

US2 before US3. A career `titles` figure computed before the champion gate lands reads 2 for popsharky
instead of 1, and the reference page agrees with the wrong answer — so nothing catches it.

### Parallel Opportunities

- T002–T004 (baseline capture) run together
- Once Phase 2 completes, **US1, US2 and US5 can proceed in parallel** — they touch different files and
  none depends on another
- Within each story, every task marked `[P]` is a different file
- Backend and frontend tasks within a story overlap once the wire shape is fixed (T015→T016, T040→T041,
  T063→T064)

### Parallel Example: User Story 1

```
T011  ManagerHistorySportIT.java          (backend test)
T012  ManagerHistory.test.tsx             (frontend test)
```
Then sequentially: T013 → T014 → T015 → T016, after which T017–T020 are all in
`web/src/pages/ManagerHistory.tsx` and must be done in order.

---

## Implementation Strategy

### MVP

**Phase 1 + Phase 2 + Phase 3 (US1)** — 20 tasks. It stops every populated manager page from summing
football and basketball into one record, which is a wrong number on screen today. It ships without any
new computation.

### Recommended increments

1. **Correctness first**: Phases 1–4 (US1 + US2). Two live defects fixed, 28 tasks, no new surface.
2. **The named request**: Phase 5 (US3). The career profile, both sports.
3. **The argument**: Phase 6 (US4). Head-to-head, now that fixtures exist for every played season.
4. **The long tail**: Phases 7–8 (US5, US6), each independently droppable.

### If you only do one thing

T013 through T019. That is the sport on the row and the split header — the smallest change that makes
an existing page stop lying.

---

## Stopped here (2026-09-21)

Implementation and per-phase verification are complete; four polish tasks remain, all listed above as
unchecked. State at the stop:

- Branch `006-deeper-history-both-sports`, **nothing committed** — every change is in the working tree.
- Backend suite 565 tests / 0 skipped / 0 failures. Web suite 474 / 0 failures.
- The backend is running on :8080 from this session's `gradlew bootRun`.
- **T076 is deliberately not done** and should stay that way for now: `web/src/pages/ManagerHistory.tsx`
  still reads the flat `seasons[]`, and `contracts/manager-profile-api.md` sequences its removal as a
  change of its own, after the page moves to `careers[].seasons`.

### Two defects live verification found that the tests had not

Both were the same class — a number and its label coming from different sources (FR-007), the lesson
this repo already recorded when a live ballot tally printed under a lagging week label:

1. The page header recomputed its season count from `seasons` while the career panel printed
   `seasonsCounted`. For popsharky's basketball that read "NBA 17-21 across 3 seasons" in the header
   and "17-21-1 over 2 seasons" in the panel, eight lines apart — the header counted the
   ingested-but-unplayed NBA 2026 row and dropped the tie. Both now read `careers`.
2. The rail's per-sport season count had the same split, reading "NBA 3" beside the corrected header's
   "2 seasons". Also now reads `seasonsCounted`.

A regression test for the first is in `web/src/pages/ManagerHistory.test.tsx`
(`describe('the header and the career panel agree (FR-007)')`).
