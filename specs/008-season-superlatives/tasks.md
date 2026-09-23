---

description: "Task list for season superlatives"
---

# Tasks: Season superlatives, so far

**Input**: Design documents from `specs/008-season-superlatives/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md),
[data-model.md](data-model.md), [contracts/superlatives-api.md](contracts/superlatives-api.md),
[quickstart.md](quickstart.md)

**Tests**: included. The plan names test files. AGENTS.md recurring bug #1 requires a
*preference-ordering* test for any scoring or ranking change, because structural tests don't catch a
sign inversion. Every superlative that picks a "most" or "least" gets one.

**Organization**: by user story, in spec priority order: US1 (P1), US2 (P2), US3 (P2), US4 (P3),
US6 (P3). **US5 (weekly award trophy case) isn't built** (research R13), so it has no phase.

**Paths**:
- Backend main: `backend/src/main/java/com/ballknowers/draftsim/`, written below as `…/`.
- Backend tests: `backend/src/test/java/com/ballknowers/draftsim/`, written as `test/…/`.
- Frontend: `web/src/`.

**Standing rules for every task** (from AGENTS.md; not repeated per task):
- **No `Map.of(...)` in a response path** with a possibly-null value. Build maps mutably, as
  `ExpectedWinsController` does.
- **No sport-name literal** (`"nfl"`, `"nba"`, `Sport.NFL`, `Sport.NBA`) in the new service or
  controller. Decisions go through `SportRules`.
- **No defaulted rule parameter.** The new week bound is passed explicitly at every call site.
- **`web/src/api.ts`** changes in the same task as the Java record it mirrors.
- **Coding subagents run on Sonnet**; the parent session reads the diff before marking a task done.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel (different files, no dependency on an incomplete task)
- **[Story]**: which user story the task belongs to

---

## Phase 1: Setup

**Purpose**: get a real database up and capture the numbers a later phase will change.

- [ ] T001 Confirm the branch and migration state before writing anything:
  - `git status` is clean apart from `ideas/`.
  - The branch is `008-season-superlatives`.
  - The highest file in `backend/src/main/resources/db/migration/` is still `V21__league_status.sql`.
    If a concurrent session has added V22, renumber this feature's migration to the next free
    version and update data-model.md's note.
- [ ] T002 Start Postgres on 5433 (`docker compose up -d`; start Docker Desktop first if `docker ps`
  can't reach the engine) and the backend via `preview_start {name: "draft-sim-api"}` (kill anything
  already on 8080 first). Then run the quickstart "Prerequisites" ingests for `1346366555759341568`,
  `1254190892974084096` and `1229352720222134272`.
- [ ] T003 Take the **before** measurements from quickstart §1 and write them to a new
  `specs/008-season-superlatives/verification.md` under "Before the change":
  - **R3**: count `league_matchup` rows with `week >= 15` for the NFL 2025 league row, and save the
    full JSON of `GET /api/leagues/1254190892974084096/expected-wins` (the 2025 league's own id;
    there is no `?season=` parameter).
  - **Career luck** (analysis H1): save the full JSON of `GET /api/managers/{managerId}/history` for
    one manager with seasons in both sports. Its `winsAboveExpected` comes from the same service.
  - **R8**: record one waiver add's stored `week` against the first week that player appears in the
    adding roster's `starters`.
  - **R11**: whether any rostered player in the three reference leagues is suspended or in an IR
    slot (Sleeper `/league/{id}/rosters` → `reserve`), and whether he appears as a key in
    `players_points` for a week he sat there.

  Record the actual numbers. Where a check can't be made, record "not checkable", not "passed".

**Checkpoint**: DB up; before-numbers on disk. T003's R3 capture **must** precede T028–T029.

---

## Phase 2: Foundational (blocking prerequisites)

**Purpose**: schema, sport rules, the shared week bound, the membership move, and a page and
endpoint skeleton that every story fills in.

**⚠️ CRITICAL**: no user story work starts before this phase is complete.

- [ ] T004 Create `backend/src/main/resources/db/migration/V22__season_superlatives.sql` with four
  tables exactly as data-model.md specifies, each with a header comment in this repo's migration
  style saying why it exists:
  - **`player_absence`**:
    - Columns: `sport text not null`, `season int not null`, `week int not null`,
      `sleeper_player_id text not null`, `game_id text null`, `game_date date null`,
      `team text null`, `basis text not null` with a check constraint
      `basis in ('ENTRY_WITHOUT_PLAY','TEAM_PLAYED_NO_ENTRY')`.
    - Unique index on `(sport, season, sleeper_player_id, week, coalesce(game_id, ''))`.
  - **`status_capture`**: `sport text not null`, `season int not null`, `week int not null`,
    `captured_at timestamptz not null`; primary key `(sport, season, week)`.
  - **`player_suspension`**: `sport`, `season`, `week`, `sleeper_player_id text not null`; primary
    key `(sport, season, week, sleeper_player_id)`; FK `(sport, season, week)` →
    `status_capture`.
  - **`league_conduct_entry`**:
    - `id bigserial primary key`
    - `league_id bigint not null references league (id) on delete cascade`
    - `sleeper_player_id text not null`
    - `reason text not null` with `check (char_length(reason) between 1 and 140)`
    - `applies_from_week int not null check (applies_from_week >= 1)`
    - `added_by_manager_id bigint null references manager (id)`
    - `created_at timestamptz not null default now()`
    - `unique (league_id, sleeper_player_id)`

  Restart the backend and confirm Flyway applied V22 (log line, or `flyway_schema_history`).
- [ ] T005 Add three abstract methods (no `default` body) to `…/sport/SportRules.java`, each
  javadoc'd with its research entry:
  - `boolean playedIn(Map<String, ?> stats)`. R9: whether a Sleeper per-game or per-week stats
    entry means the player actually played.
  - `boolean isSuspended(Player player)`. R11.
  - `double closeGameMargin()`. R5. Javadoc: "hand-set, arbitrary: each sport's 2025
    regular-season 25th-percentile margin, measured 2026-09-22".
- [ ] T006 [P] Implement them in `…/sport/FootballRules.java`:
  - `playedIn`: `stats.get("gp")` is a Number > 0. `gms_active` must NOT count, per R9's McCaffrey
    weeks 2–8.
  - `isSuspended`: `"Sus".equalsIgnoreCase(trim(injuryStatus))`.
  - `closeGameMargin`: `10.0`.
- [ ] T007 [P] Implement them in `…/sport/BasketballRules.java`:
  - `playedIn`: stats non-null and non-empty. Embiid's missed games are `stats: {}`, and `gp` is
    absent even when he played.
  - `isSuspended`: `"SUS".equalsIgnoreCase(trim(status))`.
  - `closeGameMargin`: `15.0`.
- [ ] T008 [P] Add `test/…/sport/SuperlativeSportRulesTest.java` with fixtures copied from research
  R9 and R11's measured shapes:
  - Football: `{gms_active:1.0}` is not played; `{gp:1.0, pts_ppr:16.7}` is played.
  - Basketball: `{}` is not played; a box score without `gp` is played.
  - Football `injury_status "Sus"` and basketball `status "SUS"` are suspended; football `status
    "Suspended"` alone is not, since it doesn't occur in Sleeper's data.
  - Margins are 10.0 and 15.0.
- [ ] T009 Move `visibleLeague(String sleeperId, String sleeperUserId)` and
  `canCommission(long leagueId, String sleeperUserId)` from `…/api/LeagueHistoryController.java`
  into `…/store/LeagueMembership.java` as public methods, next to `canSee`:
  - Carry their javadocs.
  - Replace the controller's private copies with calls. **Don't** leave a copy behind.
  - `canCommission` needs `OwnerProperties`, `ManagerRepository` (for `idsBySleeperUserId`) and
    `LeagueMemberRepository`: inject them into `LeagueMembership`.
  - Behaviour-preserving: `test/…/api/` ballot/commissioner tests and every
    `LeagueControllerSeats*IT` must pass unchanged.
- [ ] T010 Create `…/store/WeekBound.java`: `public record WeekBound(Integer throughWeek)` with
  `static final WeekBound ALL_WEEKS = new WeekBound(null)` and `static WeekBound through(int week)`.
  Javadoc cites the memory "optional params that encode rules": callers must pass one, and there's
  no overload without it.
- [ ] T011 Thread `WeekBound` through the score and pairing queries:
  - Add a required `WeekBound` parameter to `RosterWeekPointsRepository.extremes(...)`,
    `RosterWeekPointsRepository.allScores(...)` and `LeagueMatchupRepository.pairedWithScores(...)`,
    in `…/store/RosterWeekPointsRepository.java` and `…/store/LeagueMatchupRepository.java`.
  - When `throughWeek` is non-null, the SQL adds `and week <= :throughWeek`.
  - Update **every** existing caller (find them with a grep for each method name) to pass
    `WeekBound.ALL_WEEKS` explicitly, so their behaviour is unchanged.
- [ ] T012 Thread `WeekBound` through `…/engine/LeagueRecordService.java`:
  - `forChain`, `highestWeeks`, `lowestWeeks`, `closestMatchups`, `biggestBlowouts`, `margins`,
    `pointsLeaders`, `streaks` and `marginsUnavailableReason` all take it.
  - `LeagueHistoryController`'s record-book call passes `WeekBound.ALL_WEEKS`.
  - Extend `test/…/engine/LeagueRecordServiceTest.java` with one case: a season with scores in
    weeks 1–16, bounded `through(14)`, never returns a week-15 or week-16 record.
- [ ] T013 Create `…/engine/SeasonSuperlativesService.java` with the payload records from
  data-model.md and the contract:
  - `Result(available, reason, season, requestedSeason, sport, throughWeek, weeksScored,
    regularSeasonEnd, early, earlyThresholdWeeks, closeGameMargin, suspensionWeeksObserved,
    commissionerListAvailable, superlatives)`
  - `Superlative(kind, available, reason, early, value, unit, holders, emptyReason, detail, coverage)`
  - `Holder(rosterId, managerId, teamName, avatarId)`
  - `Coverage(weeksCovered, weeksExcluded, reasons)`
  - `Kind`: the twelve kinds, in contract order.
  - `static final int EARLY_THRESHOLD_WEEKS = 4`, javadoc'd "hand-set, arbitrary (research R6)".

  `forLeague(String sleeperLeagueId)` resolves the league-season via
  `LeagueSeasonResolver.resolve(sleeperLeagueId)`, carrying its `requestedSeason` into the result.
  There is no season parameter: a past season is chosen by its own league id (contract header). It
  then computes the window per research R2:
  - Scored weeks = `RosterWeekPointsRepository.storedWeeks`, filtered to
    `< playoff_week_start` when `LeagueRepository.playoffFormat` gives one ≥ 2.
  - `throughWeek` = the max of those; `regularSeasonEnd` = `playoff_week_start - 1`, or null.
  - No scored weeks → `available: false`, reason "no week of this season has been scored yet".

  Every kind returns `available: false, reason: "not built yet"` for now. Team names and avatars
  are resolved the way `ExpectedWinsService.forLeague` does (member team name, else manager name,
  else "Roster N").
- [ ] T014 Create `…/api/SuperlativesController.java` with
  `GET /api/leagues/{sleeperId}/superlatives` (no query parameters; header `X-Sleeper-User`):
  - `LeagueMembership.visibleLeague` → 404 when not visible.
  - Body built mutably, field-for-field per the contract.
- [ ] T015 [P] Mirror T013's records in `web/src/api.ts`:
  - `SuperlativesResponse`, `Superlative`, `SuperlativeKind` (string union of the twelve),
    `SuperlativeHolder`, `SuperlativeCoverage`, and a `SuperlativeDetail` discriminated union on
    `type` (`WEEK_SCORE | GAME | LUCK | BENCH_TOTAL | PICKUP | ABSENCE | CONDUCT`) with the fields
    in the contract's detail table.
  - A `fetchSuperlatives(sleeperLeagueId)` using `apiFetch`.
- [ ] T016 [P] Add the page shell:
  - Route `/leagues/:sleeperLeagueId/superlatives` → `<Superlatives />` in `web/src/App.tsx`,
    placed after the weekly-report route.
  - A "Superlatives" destination in `web/src/destinations.ts` directly after "Weekly report",
    following its `href`/`match` pattern.
  - Update `web/src/destinations.test.ts` for the new entry.
- [ ] T017 Create `web/src/pages/Superlatives.tsx`:
  - Fetch via `fetchSuperlatives` and render the header "Season so far · through week N", with
    "regular season" stated and, when `regularSeasonEnd` is null, "this league has no playoff start
    set, so every scored week counts".
  - One card per kind, in payload order. An unavailable kind shows its `reason`; an empty one shows
    its `emptyReason`. Never render a kind as blank.
  - Match the existing analytics pages' card/avatar style (`WeeklyReport.tsx`, `ExpectedWins.tsx`),
    per memory "avoid flat uniform cards".
  - No horizontal scroll at 375 px.
- [ ] T018 Create `web/src/pages/Superlatives.test.tsx` checking that an `available: false` payload
  shows its reason, and that a kind with `available: false` renders its reason, not an empty card.

- [ ] T019 Decide roster membership for the Embiid and Unethical awards from T003's R11 result
  (research R11, amended after analysis), and record the decision in
  `specs/008-season-superlatives/verification.md`:
  - **Reserve-slot players are keys in `players_points`**: record "verified". Membership = those
    keys, as planned.
  - **They aren't**: add `…/engine/RosterTenure.java`, a pure function reconstructing
    (league-season, roster, player) → weeks rostered from draft picks plus `league_transaction`
    adds/drops/trades, applied in `(week, created_at)` order. Add `test/…/engine/RosterTenureTest.java`,
    and a live check that it agrees with `players_points` keys for every non-reserve player in NFL
    2025. Use it for US4 and US6 membership only after that check passes.
  - **No IR player exists to measure**: record "not checkable today". Keep the `players_points` rule,
    and add a standing coverage reason to both awards: "players in an IR slot may not be visible in
    Sleeper's weekly data".

**Checkpoint**: V22 applied; rules tested; the membership move is green; the page loads and shows
twelve "not built yet" cards for a real league.

---

## Phase 3: User Story 1 — The season's extremes and close games (Priority: P1) 🎯 MVP

**Goal**: highest week, lowest week, biggest blowout and closest game, from the record book bounded
to the regular season, plus per-team close-win and close-loss records.

**Independent Test**: quickstart §2's SC-001 and SC-007 hand checks on `1346366555759341568`, and
the NFL 2025 league's own id (`1254190892974084096`) shows `throughWeek: 14`.

- [ ] T020 [P] [US1] Add ordering tests in `test/…/engine/SeasonSuperlativesCloseGamesTest.java`,
  using an in-memory list of `PairedGame`s so they run without Postgres:
  - (a) With margin 10, team C with three wins under 10 and team D with two: `CLOSE_WINS` names C
    with value 3, not D.
  - (b) The mirror case for `CLOSE_LOSSES`.
  - (c) C and E tied at 3: both are holders.
  - (d) No game under the margin: `holders` is empty and `emptyReason` is "no close games yet".
  - (e) A margin exactly equal to 10.0 is **not** close (strict `<`, per FR-011's "under").
  - (f) Detail lists every qualifying game, with week, opponent and margin.
- [ ] T021 [US1] Implement `CLOSE_WINS` and `CLOSE_LOSSES` in
  `…/engine/SeasonSuperlativesService.java`:
  - Read `LeagueMatchupRepository.pairedWithScores(List.of(leagueId), WeekBound.through(throughWeek))`
    and count per roster games won or lost by `< rules.closeGameMargin()`.
  - Echo the margin into `Result.closeGameMargin`.
  - Put the pure counting in a package-private static method so T020 can call it directly.
- [ ] T022 [US1] Implement `HIGHEST_WEEK`, `LOWEST_WEEK`, `BIGGEST_BLOWOUT` and `CLOSEST_GAME` in
  `…/engine/SeasonSuperlativesService.java` by calling `LeagueRecordService.highestWeeks`,
  `lowestWeeks`, `biggestBlowouts` and `closestMatchups` with `List.of(leagueId)` and
  `WeekBound.through(throughWeek)`:
  - Ask for enough rows to detect ties at the top (e.g. limit 20), and make every row equal to the
    first a holder (FR-003).
  - **Don't** re-sort or recompute margins here (FR-004, SC-007).
- [ ] T023 [US1] Add coverage for weeks with scores but no pairings (US1 scenario 7): weeks in the
  scored set with no `pairedWithScores` rows are excluded from `BIGGEST_BLOWOUT`, `CLOSEST_GAME`,
  `CLOSE_WINS` and `CLOSE_LOSSES`, each carrying
  `Coverage(weeksCovered, weeksExcluded, ["week N: no pairings stored", …])`. Score-only kinds
  are unaffected. Code goes in `…/engine/SeasonSuperlativesService.java`; add a test case to T020's
  file.
- [ ] T024 [US1] Render the six US1 kinds in `web/src/pages/Superlatives.tsx`:
  - Team avatar and name, the exact figure with its unit, and the week(s).
  - Close-game cards title as "Closest wins" / "Closest losses", with the working names "Escape
    artist" / "Heartbreak kid" as subtitles, and state "by under {closeGameMargin} points" from the
    payload field (never a literal 10 or 15).
  - Expandable detail lists the games.
- [ ] T025 [US1] Extend `web/src/pages/Superlatives.test.tsx`: a two-holder tie renders both names;
  the close-wins card prints the payload's margin; a coverage note renders "N of M weeks".
- [ ] T026 [US1] Live-check US1 per quickstart §2 (SC-001 and SC-007 by hand against the stored
  rows) and the browser page. Record the numbers in `specs/008-season-superlatives/verification.md`.

**Checkpoint**: MVP. US1 is complete and independently shippable; the other kinds still say "not
built yet".

---

## Phase 4: User Story 2 — Luckiest, unluckiest, most bench points (Priority: P2)

**Goal**: luck read from the (now regular-season-bounded) Expected wins service, bench points from
`bestLineup`, and the early-season caveat.

**Independent Test**: SC-002: `LUCKIEST`/`UNLUCKIEST` detail equals the bounded expected-wins row to
the cent, for the same roster.

- [ ] T027 [P] [US2] Add bound tests in `test/…/engine/ExpectedWinsServiceTest.java`: with games in
  weeks 1–16, `forLeague(id, WeekBound.through(14))` counts no week-15/16 game, and the
  conservation invariant (sum expected == sum actual) still holds.
- [ ] T028 [US2] Change `…/engine/ExpectedWinsService.java` so `forLeague(String sleeperLeagueId,
  WeekBound bound)` is the only entry point (no unbounded overload), and add a helper that resolves a
  league-season's regular-season bound from `LeagueRepository.playoffFormat` (`through(pws - 1)`
  when `pws >= 2`, else `ALL_WEEKS`). **Approved by Allan 2026-09-23 (research R3).**
- [ ] T029 [US2] Update `…/api/ExpectedWinsController.java` to pass the league's regular-season
  bound via the T028 helper. The Expected wins page now shows regular-season luck.
- [ ] T030 [US2] Update `…/engine/ManagerCareerService.java:225`
  (`expectedWins.forLeague(s.sleeperLeagueId())`) to pass each season's regular-season bound via the
  T028 helper. That's the second caller found by analysis (H1). Career `winsAboveExpected` then sums
  regular-season luck only. Extend `test/…/engine/ManagerCareerServiceTest.java` with a season that
  has playoff-week games, asserting they don't reach the career sum. Grep for any other
  `ExpectedWinsService` caller before closing this task.
- [ ] T031 [P] [US2] Add ordering tests in `test/…/engine/SeasonSuperlativesLuckTest.java`:
  - (a) `LUCKIEST` is the max `winsAboveExpected` and `UNLUCKIEST` the min, never swapped.
  - (b) Ties at the extreme name all holders.
  - (c) `MOST_BENCH_POINTS` picks the largest summed (optimal − actual); a team that left more
    points on the bench outranks one that left fewer.
  - (d) A team with an invalid-lineup week reports `weeksCounted` < weeks scored.
- [ ] T032 [US2] Implement `LUCKIEST` and `UNLUCKIEST` in `…/engine/SeasonSuperlativesService.java`
  by calling `ExpectedWinsService.forLeague` with the same bound as the window:
  - Copy `actualWins`, `expectedWins` and `winsAboveExpected` into `LUCK` detail unmodified
    (FR-004).
  - Unit `WINS`, and a one-line reading built from `winsAboveExpected`, e.g. "2.40 more wins than
    their scores earned".
- [ ] T033 [US2] Implement `MOST_BENCH_POINTS` in `…/engine/SeasonSuperlativesService.java`:
  - For each regular-season week × roster from `RosterWeekPointsRepository.breakdownsFor`, compute
    `RealizedLineupService.bestLineup(...)`, the same call `WeeklyReportService` makes at
    `WeeklyReportService.java:215-219`.
  - Add `best.points() - startersPoints` when `best.valid()`, and count covered weeks per team.
  - `BENCH_TOTAL` detail; coverage when any week is invalid for the holder.
- [ ] T034 [US2] Set `early = true` on `LUCKIEST`, `UNLUCKIEST` and `MOST_BENCH_POINTS` (and, later,
  the US3, US4 and US6 kinds) when `weeksScored < EARLY_THRESHOLD_WEEKS`, in
  `…/engine/SeasonSuperlativesService.java`.
- [ ] T035 [US2] Render the US2 kinds in `web/src/pages/Superlatives.tsx`: the figure with its
  one-line reading, and the "early — this is mostly noise" caveat **beside** the card title when
  `early` (FR-006, SC-003). Add a test to `web/src/pages/Superlatives.test.tsx` that an early kind
  shows the caveat.
- [ ] T036 [US2] Live-check SC-002 on both NFL leagues. Then compare the Expected wins page's NFL
  2025 numbers **and** the captured manager's career `winsAboveExpected` against T003's
  before-captures, and write both before/after tables into
  `specs/008-season-superlatives/verification.md`. The career change wasn't in what Allan approved,
  so flag it to him explicitly (spec amendment 11).

**Checkpoint**: US1 and US2 work together; the Expected wins page is regular-season-bounded.

---

## Phase 5: User Story 3 — Waiver Wire Warrior (Priority: P2)

**Goal**: starting-lineup points from players whose most recent arrival on that roster was a
completed WAIVER or FREE_AGENT add.

**Independent Test**: SC-003, a hand sum for the holder per quickstart §2.

- [ ] T037 [P] [US3] Add rule tests in `test/…/engine/WaiverPickupAttributionTest.java`, one per case:
  - drafted player started (not counted);
  - waiver add, then started (counted);
  - FA add, then started (counted);
  - waiver add, later traded to another team and started there (not counted for either);
  - added, dropped, re-added by the same team (counted once per started week, never twice);
  - commissioner move (not counted);
  - failed waiver bid (not counted);
  - add recorded in week *w* and started in week *w* (counted, since `week <= w`).

  Plus an ordering test: the team with more pickup points is the holder.
- [ ] T038 [US3] Implement `…/engine/WaiverPickupAttribution.java` as a pure static function over
  (`List<LeagueTransactionRepository.Row>`, the per-week starters and players_points by roster) →
  per-roster totals and per-player contributions, implementing research R8's rule:
  - For each (week, roster, starter), take the most recent transaction by `(week, created_at)`, with
    `week <= w` and `status = 'complete'`, whose `adds` maps the player to this roster.
  - Count only when its type is `WAIVER` or `FREE_AGENT`.
- [ ] T039 [US3] Wire `WAIVER_WIRE_WARRIOR` into `…/engine/SeasonSuperlativesService.java`:
  - Transactions come from `LeagueTransactionRepository.forSeason`; starters and points from
    `breakdownsFor`, bounded to the window.
  - `PICKUP` detail: top 3 per holder, with `addedWeek`, `addType`, `startedWeeks` and `points`.
  - Unavailable with reason "no transactions stored for this season — run POST
    /api/ingest/transactions/{id}" when the season has none.
  - Coverage lists weeks with null `starters`, and never falls back to all rostered points (US3
    scenario 4).
  - `early` per T034.
- [ ] T040 [US3] Render the pickup card in `web/src/pages/Superlatives.tsx` (total, top pickups
  with week added and how acquired), and add a test to `web/src/pages/Superlatives.test.tsx`.
- [ ] T041 [US3] Live-check SC-003 by hand for the NFL 2026 holder, and settle research R8's `leg`
  question from T003's measurement. Record both in `specs/008-season-superlatives/verification.md`.

**Checkpoint**: US1–US3 work independently.

---

## Phase 6: User Story 4 — The Joel Embiid Award (Priority: P3)

**Goal**: every missed game of a regular contributor, costed at his mean points per game played
(research R10, amended 2026-09-23). Measured from stored absences; never from the injury tag.

**Independent Test**: quickstart §3, a hand count of the holder's top absence from `player_absence`,
and a hand-scored `pointsPerGame`.

- [ ] T042 [P] [US4] Create `…/store/PlayerAbsenceRepository.java`: `record Row(Sport sport, int
  season, int week, String playerId, String gameId, LocalDate gameDate, String team, String basis)`,
  `upsert(Row)` (conflict target = the V22 unique index), and `forPlayers(Sport, int season,
  Collection<String> playerIds)`.
- [ ] T043 [P] [US4] Add ingest tests in `test/…/ingest/PlayerGameIngestServiceTest.java` using
  research R9's measured shapes as fixtures (`PlayerGameIngestService.toRow` is already static):
  - An NBA entry with `stats: {}` produces no `player_game` row and one `player_absence` row
    (`ENTRY_WITHOUT_PLAY`, with `game_id`).
  - An NFL week with `{gms_active:1.0}` and no `gp` produces no `player_game` row and an absence
    (`ENTRY_WITHOUT_PLAY`). This is the latent-bug regression.
  - An NFL `None` week for a player whose team appears with an entry in that week among the walked
    players produces an absence with `TEAM_PLAYED_NO_ENTRY` (Mahomes week 18).
  - An NFL `None` week for a team with no walked entry that week is a bye: no row. It's counted as
    unclassified only when the player's own team can't be determined.
- [ ] T044 [US4] Change `…/ingest/PlayerGameIngestService.java`:
  - Resolve `SportRules` for the league's sport, and store a `player_game` row only when
    `rules.playedIn(stats)`.
  - Write a `player_absence` row (`ENTRY_WITHOUT_PLAY`) for an entry with a date/week that fails
    `playedIn`.
  - Build a `(team, week)` set from every walked entry, including DNP entries, which still carry
    `team`.
  - After the walk, classify each player's `None` weeks:
    - if his team (from his nearest non-null entry) played that week → absence
      (`TEAM_PLAYED_NO_ENTRY`);
    - if his team didn't play → bye, write nothing;
    - if his team is unknown → count as unclassified.
  - Add `absencesStored` and `weeksUnclassified` to `Result`, and log them with the existing counts.
  - Keep the per-player try/catch as it is.
- [ ] T045 [US4] Run `POST /api/ingest/player-games/1254190892974084096` and
  `…/1229352720222134272` on the live server. Record the wall-clock time for the NFL walk (research
  R9's ~1.5 min is an estimate), `absencesStored` by basis, and `weeksUnclassified` in
  `specs/008-season-superlatives/verification.md`. Check the McCaffrey-style and bye cases by hand
  (quickstart §1 R9).
- [ ] T046 [P] [US4] Add ordering and rule tests in `test/…/engine/AbsenceCostTest.java`:
  - (a) A regular who missed 5 games outranks one who missed 2 at the same points per game.
  - (b) A player who missed three nights but played the fourth in the same week costs three games.
    This is Allan's rule; week-level forgiveness is the rejected alternative.
  - (c) A stash (started in < half his played weeks, or < 2 such weeks) costs nothing.
  - (d) A missed game while on another fantasy roster doesn't count for this one.
  - (e) `estimatedPointsLost == gamesMissed * pointsPerGame`.
  - (f) Nobody missed anything: empty with "nobody's been bitten yet".
- [ ] T047 [US4] Implement `…/engine/AbsenceCost.java` as a pure function per research R10
  (amended):
  - **Regular contributor** (javadoc'd "hand-set, arbitrary"): in the roster's `starters` in ≥ half
    of the weeks that roster held him and he played at least one game, with ≥ 2 such weeks.
  - **Points per game**: the mean of `GameScoringService.score(league scoring, stats)` over his
    `player_game` rows for the season.
  - **Cost**: each `player_absence` row in a regular-season week where he's a key in that roster's
    `players_points` costs `pointsPerGame`.
  - **Output per roster**: total, plus per-player `gamesMissed`, `weeksAffected`, `pointsPerGame`
    and `estimatedPointsLost`.
- [ ] T048 [US4] Wire `JOEL_EMBIID` into `…/engine/SeasonSuperlativesService.java`:
  - `ABSENCE` detail, top 3 per holder.
  - Unavailable with reason "per-game records not ingested — run POST /api/ingest/player-games/{id}"
    when the season has no `player_game` rows.
  - Coverage reports football unclassified weeks.
  - Never reads `Player.injuryStatus` (FR-013).
  - `early` per T034.
- [ ] T049 [US4] Render the Embiid card in `web/src/pages/Superlatives.tsx`:
  - Title "The Joel Embiid Award", with the one line "Games missed, cause unknown" (FR-013).
  - `estimatedPointsLost` marked "estimated", and each player's games missed and points per game.
  - Add a test to `web/src/pages/Superlatives.test.tsx` that the "cause unknown" line and
    "estimated" label render.
- [ ] T050 [US4] Live-check quickstart §3 on the NBA 2025 league. Hand-count the holder's top
  player's missed nights, and hand-score one played game against the league's scoring settings.
  Then run quickstart §3's **football scoring check** (analysis M1): for one NFL 2025 regular,
  `GameScoringService`'s value for two played weeks must equal his stored `players_points` for those
  weeks. A mismatch blocks US4 for football; report it rather than widening a tolerance. Record
  everything in `specs/008-season-superlatives/verification.md`.

**Checkpoint**: US4 works for both sports; football unclassified weeks are visible, not guessed.

---

## Phase 7: User Story 6 — The Unethical Award (Priority: P3)

**Goal**: suspensions captured from Sleeper from now on, plus the commissioner's list; each row
shows its source.

**Independent Test**: quickstart §4 run from the page, and a `status_capture` row after one player
ingest.

- [ ] T051 [P] [US6] Create `…/store/StatusCaptureRepository.java`:
  - `recordCapture(Sport, int season, int week, Instant at)`, an upsert on `(sport, season, week)`
    that sets `captured_at`.
  - `recordSuspended(Sport, int season, int week, Collection<String> playerIds)`.
  - `capturedWeeks(Sport, int season)`.
  - `suspendedIn(Sport, int season)` → `Map<Integer, Set<String>>` by week.
- [ ] T052 [US6] Change `…/ingest/PlayerIngestService.java`: after `players.upsertAll`, call
  `sleeper.state(sport.code())`.
  - Parse `season` (a string in Sleeper's payload) and `week`.
  - Record a capture, and the suspended ids from `rules.isSuspended(player)` over `toWrite`.
  - A failure of the state call must not fail the player ingest: log it and add
    `suspensionCaptured: false` to `Result`. Extend `Result` with `suspensionCaptured` and
    `suspendedCount`.
  - Update `web/src/api.ts` if any client reads this result's type.
- [ ] T053 [P] [US6] Add a test in `test/…/ingest/PlayerIngestServiceSuspensionTest.java`: given a
  raw player map with one NFL `injury_status "Sus"` player, a capture row and one suspension row
  are recorded for the state's week. When the state call throws, the ingest still returns its
  counts, with `suspensionCaptured: false`.
- [ ] T054 [P] [US6] Create `…/store/LeagueConductRepository.java`:
  - `record Entry(long id, long leagueId, String playerId, String reason, int appliesFromWeek,
    Long addedByManagerId, Instant createdAt)`.
  - `forLeague(long leagueId)`.
  - `upsert(long leagueId, String playerId, String reason, int appliesFromWeek, Long addedBy)`, an
    upsert on `unique (league_id, sleeper_player_id)` that replaces reason and week.
  - `delete(long leagueId, long entryId)`: returns whether a row in **that** league was deleted.
- [ ] T055 [US6] Add the conduct-list endpoints to `…/api/SuperlativesController.java` per
  contracts/superlatives-api.md:
  - **`GET /api/leagues/{sleeperId}/conduct-list`** returns `canEdit` (from
    `LeagueMembership.canCommission`), `commissionerKnown` (`anyCommissioner`) and `entries`
    (with player names).
  - **`POST`** validates:
    - reason is trimmed and must be "1–140 chars, trimmed";
    - `appliesFromWeek` "≥ 1";
    - `playerId` must be a known player for the league's sport.

    Failures return 400 with `{message}`. A non-commissioner gets 403 with `{message,
    commissionerKnown}`, using the same messages as `POST /power/commissioner`. Success returns 200
    with the entry.
  - **`DELETE /api/leagues/{sleeperId}/conduct-list/{entryId}`**: 204 on success. 404 when the
    entry isn't in this league, so ids can't reach across leagues.
  - Every endpoint returns 404 when the league isn't visible.
- [ ] T056 [P] [US6] Add `test/…/api/SuperlativesControllerIT.java` (Postgres-backed, same setup
  as the existing `*IT` tests):
  - not visible → 404 on all four endpoints;
  - non-commissioner POST → 403;
  - a reason of 141 chars → 400;
  - DELETE of another league's entry id → 404;
  - the commissioner's POST then GET round-trips.

  Read the skip count; a skipped IT isn't a pass.
- [ ] T057 [P] [US6] Add ordering and rule tests in `test/…/engine/UnethicalAwardTest.java`:
  - (a) The team with more qualifying player-weeks is the holder.
  - (b) A suspended player rostered only in weeks he wasn't tagged doesn't count (FR-018).
  - (c) A commissioner entry from week 6 counts only for teams rostering him in week ≥ 6.
  - (d) A removed entry counts for nobody.
  - (e) Every detail row carries a `source`.
  - (f) Nothing qualifies: empty with a reason naming both sources.
- [ ] T058 [US6] Wire `UNETHICAL` into `…/engine/SeasonSuperlativesService.java`:
  - A player-week qualifies for roster *T* when he's a key in *T*'s `players_points` that week and
    either:
    - (i) that week is in `capturedWeeks` and he's in `suspendedIn`, or
    - (ii) a `league_conduct_entry` for this league has `applies_from_week <=` that week.
  - `CONDUCT` detail with `source`, `weeks` and `reason`.
  - Set `suspensionWeeksObserved` (regular-season captured weeks) and `commissionerListAvailable`
    (`anyCommissioner`).
  - `early` per T034.
- [ ] T059 [US6] Mirror the conduct-list payloads in `web/src/api.ts` (`ConductList`,
  `ConductEntry`, and `fetchConductList`, `saveConductEntry` and `deleteConductEntry` via
  `apiFetch`, sending `X-Sleeper-User` like the ballot calls do).
- [ ] T060 [US6] Render the Unethical Award and the list in `web/src/pages/Superlatives.tsx`:
  - **Award card**: each row labelled "Suspended, weeks …" or "Commissioner's call: {reason}", with
    the reason as plain text (no `dangerouslySetInnerHTML`). A "tracking began week N" line comes
    from `suspensionWeeksObserved[0]`, or "suspension tracking hasn't captured a week yet".
  - **The list**: visible to every member. Add/edit/remove controls only when `canEdit`. The form
    is a player picker, reason input with `maxLength={140}`, and week number. When
    `commissionerListAvailable` is false, a note says why the list isn't available.
  - Add tests to `web/src/pages/Superlatives.test.tsx`: controls hidden when `canEdit` is false,
    and a reason containing `<b>` renders as text.
- [ ] T061 [US6] Live-check quickstart §4 **from the browser page** (POST/DELETE preflight,
  lessons #6) as the commissioner and as a non-commissioner. Also check quickstart §1's R11 capture
  rows after `POST /api/ingest/players?sport=nfl`. Record the results in
  `specs/008-season-superlatives/verification.md`.

**Checkpoint**: all five built stories work; every kind on the page is live.

---

## Phase 8: Polish & cross-cutting

- [ ] T062 [P] Add `test/…/engine/NoSportNameInSuperlativesTest.java`, modelled on
  `NoSportNameInWeeklyReportTest`: `SeasonSuperlativesService.java`, `WaiverPickupAttribution.java`,
  `AbsenceCost.java` and `SuperlativesController.java` contain no `"nfl"`/`"nba"` literal and no
  `Sport.NFL`/`Sport.NBA`.
- [ ] T063 [P] Grep the new and changed Java files for `Map.of(` on a response path and replace any
  that can hold a null (`reason`, `coverage`, `emptyReason`, `gamesMissed`, `regularSeasonEnd`,
  `addedBy`).
- [ ] T064 Run the full backend suite (`cd backend && ./gradlew test`) with Postgres up, and **read
  the skip count** from `backend/build/test-results/test/*.xml`. Record passed, failed and skipped
  in `specs/008-season-superlatives/verification.md`; skipped must be 0.
- [ ] T065 Run `cd web && npx tsc -b && npm run build && npx vitest run` and record the result.
- [ ] T066 Run quickstart §5 in the browser on `1346366555759341568` and `1229352720222134272`:
  - Every item on its list, including 375 px width, and a hard refresh (Ctrl+Shift+R) if the tab
    predates a frontend restart.
  - Measure the superlatives endpoint's response time on both leagues (plan's performance goal is
    a guess; report the number).
  - Take a screenshot for `verification.md`.
- [ ] T067 Run the pipeline's bug-hunting code review over the branch diff (`/code-review`, not a
  style pass), fix confirmed findings, and note any correction to research, plan or spec as an
  "amended after review" entry, not a silent rewrite.
- [ ] T068 Update `HANDOFF.md`: what shipped, what was verified vs assumed, the Expected wins
  behaviour change (regular-season bound) with T036's before/after, **including the career-profile
  change**, the membership decision from the R11 decision task, the football player-games
  ingest cost from T045, and the open item that suspension capture depends on player ingest being
  run (no scheduler).
- [ ] T069 Move `ideas/ongoing-superlatives.md` out of `ideas/` (it's now spec 008), updating
  `ideas/README.md`'s entry to say where it went, per `ideas/README.md`'s own convention. Confirm
  with Allan first, since those two files predate this session.
- [ ] T070 Update the memory `project_season_superlatives.md` from "planned, not built" to its real
  state, and add any durable lesson found during the build to `claude/lessons.md`.

---

## Dependencies & execution order

### Phase dependencies

- **Setup (Phase 1)**: none. **T003 must run before T028 and T029**, or the before-numbers are lost.
- **Foundational (Phase 2)**: after Setup; blocks every story.
- **US1 (Phase 3)**: after Foundational. No other story dependency.
- **US2 (Phase 4)**: after Foundational. Independent of US1 in code (both touch
  `SeasonSuperlativesService.java`, so the same file means sequential edits, not a logical
  dependency).
- **US3 (Phase 5)**: after Foundational.
- **US4 (Phase 6)**: after Foundational (needs T005–T007's `playedIn`).
- **US6 (Phase 7)**: after Foundational (needs T009's `canCommission` move and T005's
  `isSuspended`).
- **Polish (Phase 8)**: after the stories being shipped are complete.

### Within each story

Tests first, written to fail. Then the pure rule (`WaiverPickupAttribution`, `AbsenceCost`), then
wiring into the service, then UI, then the live check. The live check is the story's done
condition, not the tests.

### Shared-file sequencing

`SeasonSuperlativesService.java`, `Superlatives.tsx` and `Superlatives.test.tsx` are touched by
every story. Parallel story work must serialize edits to those three files. The pure rule classes,
repositories, ingest changes and tests are separate files and parallelize freely.

---

## Parallel examples

**Phase 2**: after T005, run T006, T007 and T008 together. T015 and T016 run alongside T013/T014.

**US1**: T020 (tests) runs in parallel with T022's record-book wiring, since the tests call a
static method.

**US4**:

```text
T042 PlayerAbsenceRepository     T043 ingest tests      T046 AbsenceCost tests
        └──────────────┬─────────────┘                          │
                  T044 ingest change → T045 live ingest          │
                                        └──────── T047 AbsenceCost → T048 wiring → T049 UI → T050
```

**US6**: T051, T053, T054, T056 and T057 are all separate files and run together. T052 follows T051;
T055 follows T054.

**Across stories** (with Foundational done): US3's T037/T038, US4's T042–T044 and US6's
T051–T054 touch no shared file and can proceed at once.

---

## Implementation strategy

### MVP first (US1 only)

1. Phase 1: capture the before-numbers.
2. Phase 2: foundation.
3. Phase 3: US1. **Stop and validate** (T026). The page shows six real superlatives and six "not
   built yet" cards. That's shippable; the other cards say so honestly.

### Incremental delivery

- **US2**: luck and bench. Ships with the Expected wins bound, so that page's change goes out with
  the reason for it.
- **US3**: waiver warrior.
- **US4**: Embiid. Needs a player-games ingest per league before it shows anything.
- **US6**: Unethical. Needs a player ingest per week to accumulate suspension captures, and a
  commissioner to populate the list.

Each story ends at its live check, and "verified" in `verification.md` means that check ran.

### This repo's pipeline

Build (Sonnet subagents, per AGENTS.md) → bug-hunting review (T067) → live verification (the
per-story checks and T066). The plan itself has not had the adversarial review pass the AGENTS.md
convention calls for. Run `/speckit-analyze` or an explicit cold review of plan.md before starting
T004.
