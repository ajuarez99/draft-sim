---

description: "Task list for automatic data refresh"
---

# Tasks: Automatic data refresh

**Input**: Design documents from `specs/009-auto-data-refresh/`

**Prerequisites**:
- [plan.md](plan.md)
- [spec.md](spec.md), including its "Amended after planning" section
- [research.md](research.md)
- [data-model.md](data-model.md)
- [contracts/refresh-api.md](contracts/refresh-api.md)
- [quickstart.md](quickstart.md)

**Tests**: included, because the plan names test files. Several rules here need a test that
asserts *behaviour* rather than structure:
- staleness and completion;
- the once-a-day skip;
- single-flight.

The per-game rebuild has a data-level parity gate (T016), which counts as its real test.

**Amended after `/speckit-analyze` (2026-09-28).** T048–T055 were added and several tasks
edited. The findings, with production measured read-only, are in spec amendments 4–8 and research
R14–R15. New tasks keep appended IDs rather than renumbering, so each one's phase and dependencies
are stated where it sits.

**Organization**: by user story, in the plan's build order, which differs from spec priority on
purpose:
1. **US5, the per-game rebuild (P2)**, first: US1's refresh calls it, and SC-003 needs it to be
   cheap.
2. Then US1 (P1), US2 (P1), US4 (P2) and US3 (P2).

**Paths**:
- Backend main: `backend/src/main/java/com/ballknowers/draftsim/`, written below as `…/`.
- Backend tests: `backend/src/test/java/com/ballknowers/draftsim/`, written as `test/…/`.
- Frontend: `web/src/`.

**Standing rules for every task** (AGENTS.md; not repeated per task):
- **No `Map.of(...)`** on a response path with a nullable value. Build maps mutably.
- **No sport-name literal** outside `SportRules`. Per-sport differences in the Sleeper payload
  shape go through the existing `SportRules` methods.
- **No defaulted rule parameter.** These are named constants, each javadoc'd "hand-set,
  arbitrary" with the research entry it came from:
  - staleness 1 h, retry-after-failure 10 min, week finality 48 h (R3, R6, contract);
  - concurrency cap 2 (R4);
  - poll interval 3 s (contract).
- **`web/src/api.ts`** changes in the same task as the Java record it mirrors.
- **Coding subagents run on Sonnet.** The parent session reads the diff before ticking a task.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel (different files, no dependency on an incomplete task)
- **[Story]**: the user story the task belongs to

---

## Phase 1: Setup — capture what the rebuild will destroy

**Purpose**: quickstart §1. Once T014 replaces the per-player walk, these baselines can't be
regenerated.

- [X] T001 Start Postgres on 5433 (`docker compose up -d postgres`; Docker Desktop is at
  `~/AppData/Local/Programs/DockerDesktop/`) and the backend on the current tree.
  1. Run `POST /api/ingest/transactions/{id}`, then `POST /api/ingest/player-games/{id}`, for NBA
     2025 (`1229352720222134272`) and NFL 2025 (`1254190892974084096`).
  2. Export `player_game` and `player_absence` for `(nba, 2025)` and `(nfl, 2025)` to CSV in the
     session scratchpad, restricted to `sleeper_player_id`s that appear in either league's
     `roster_week_points.players_points`. Sort every column deterministically.
  3. Record each table's row count and size (`pg_total_relation_size`) in a new
     `specs/009-auto-data-refresh/verification.md`, under "Before the change".
- [X] T002 Save `GET /api/leagues/{id}/superlatives` for both leagues as JSON in the scratchpad.
  Record the `JOEL_EMBIID` headline figures in `verification.md`. Expect Jokić 16 and Embiid 26
  missed games (spec 008).
- [X] T003 [P] Measure research R7's owed costs:
  - `POST /api/ingest/adp?sport=nba`, `…?sport=nfl`, `POST /api/ingest/board?sport=nba` and
    `…?sport=nfl`;
  - record wall time and backend CPU for each, using the Budget's method: the Java process's CPU
    before and after, via PowerShell `Get-Process -Id <pid on 8080>`;
  - write them into `verification.md` and into the spec's Budget table, marked "measured
    2026-MM-DD".

**Checkpoint**: the baselines exist on disk and in `verification.md`.

---

## Phase 2: Foundational

- [X] T004 Create `backend/src/main/resources/db/migration/V24__data_refresh.sql`. First confirm
  V23 is still the highest, because migrations are append-only and sessions run concurrently.
  The tables, per data-model.md:
  - `league_refresh`:
    - `league_id bigint primary key references league(id) on delete cascade`
    - `last_success_at timestamptz null`
    - `last_failure_at timestamptz null`
    - `last_failure text null`
    - `loaded_complete boolean not null default false`
  - `sport_week_stats`:
    - `sport text not null`, `season int not null`, `week int not null`
    - `fetched_at timestamptz not null`
    - `final boolean not null default false`
    - `primary key (sport, season, week)`
  - `league_week_fetch` (added after analysis, FR-016):
    - `league_id bigint not null references league(id) on delete cascade`
    - `kind text not null check (kind in ('TRANSACTIONS','POINTS'))`
    - `week int not null`
    - `fetched_at timestamptz not null`
    - `final boolean not null default false`
    - `primary key (league_id, kind, week)`
  - `daily_capture`:
    - `sport text not null`, `capture_date date not null`
    - `kind text not null check (kind in ('PLAYERS','BOARD'))` (ADP runs inside BOARD, R15)
    - `completed_at timestamptz not null`
    - `detail text null`
    - `primary key (sport, capture_date, kind)`

  Add a column comment on `loaded_complete` quoting research R3's rule verbatim.
- [X] T005 [P] Create `…/store/LeagueRefreshRepository.java`:
  - `record Row(long leagueId, Instant lastSuccessAt, Instant lastFailureAt, String lastFailure,
    boolean loadedComplete)`;
  - `Optional<Row> find(long leagueId)`;
  - `void recordSuccess(long leagueId, Instant at, boolean loadedComplete)`, which never flips
    `loaded_complete` from true back to false;
  - `void recordFailure(long leagueId, Instant at, String reason)`.

  All writes are upserts on `league_id`. Bind `Instant` through `java.sql.Timestamp.from(...)`
  (lessons: JDBC bind types).
- [X] T006 [P] Create `…/store/SportWeekStatsRepository.java`:
  - `record Row(Sport sport, int season, int week, Instant fetchedAt, boolean fin)`;
  - `List<Row> forSeason(Sport, int)`;
  - `void upsert(Row)`, which never flips `final` back to false.
- [X] T007 [P] Create `…/store/DailyCaptureRepository.java`:
  - `boolean exists(Sport, LocalDate, String kind)`;
  - `void record(Sport, LocalDate, String kind, Instant completedAt, String detail)`, an upsert on
    the key.
- [X] T008 [P] Create `…/refresh/RefreshProperties.java` as `@ConfigurationProperties("refresh")`
  with:
  - `boolean onVisitEnabled` (from `refresh.on-visit.enabled`), default **true**;
  - `String secret` (from `refresh.secret: ${REFRESH_SECRET:}`), blank meaning the daily route
    doesn't exist.

  In the same class, javadoc the public static constants, each "hand-set, arbitrary" with its
  source:
  - `STALE_AFTER = Duration.ofHours(1)` (FR-002)
  - `RETRY_AFTER_FAILURE = Duration.ofMinutes(10)` (contract)
  - `WEEK_FINAL_AFTER = Duration.ofHours(48)` (R6)
  - `MAX_CONCURRENT_REFRESHES = 2` (R4)

  Wire the properties into `backend/src/main/resources/application.yml` under `refresh:`, and set
  `refresh.on-visit.enabled: false` in `backend/src/test/resources/application.yml`, or the test
  profile's equivalent (R12).
- [X] T009 [P] Add a test for T005–T007 as a Postgres-backed IT,
  `test/…/store/RefreshRepositoriesIT.java`, following the existing IT setup:
  - `recordSuccess(…, true)` then `recordSuccess(…, false)` leaves `loaded_complete` true;
  - `SportWeekStats` `final` never reverts;
  - `DailyCapture.exists` is true only for the exact `(sport, date, kind)`.

**Checkpoint**: migration applies on backend start, and T009 is green with 0 skipped.

---

## Phase 3: User Story 5 — Per-game stats from per-week data (P2, built first)

**Goal**: rebuild per-game ingest on Sleeper's per-week stats plus the season schedule (research
R6). It becomes shared per sport-season, and an up-to-date season fetches nothing.

**Independent Test**: T016's parity diff is empty, and T017's second run on NBA 2025 takes under
10.1 s (SC-004).

- [X] T010 [P] [US5] Add two calls to `…/ingest/SleeperPlayerStatsClient.java`, both on the
  existing base URL:
  - `List<Map<String,Object>> week(String sport, int season, int week)`, calling
    `GET /stats/{sport}/{season}/{week}?season_type=regular`;
  - `List<Map<String,Object>> schedule(String sport, int season)`, calling
    `GET /schedule/{sport}/regular/{season}`.

  Keep the existing defensive typing: never trust the declared generic type (see the comment at
  `PlayerGameIngestService.java:99–107`).
- [X] T011 [P] [US5] Create test fixtures under `backend/src/test/resources/sleeper/`, cut from the
  measured payloads (research R6):
  - NBA 2025 week 10: all of LaRavia's (`2444`) entries, three empty-stats entries, and the three
    games `1261814783951241216`, `1261029465123725312`, `1261814802817220608`, including their
    schedule rows (LAL away at PHX for the first);
  - NFL 2025 week 5: McCaffrey (`4034`, game `202510532`), one no-`gp` DNP entry, and a bye team's
    schedule absence.

  Fetch them fresh from Sleeper with `curl` and trim them. Don't hand-write the JSON.
- [X] T012 [US5] Write `test/…/ingest/PlayerGameWeekIngestTest.java` (pure, fixture-driven) for
  the rule research R6 fixes:
  - a played entry becomes a `player_game` row whose `is_away` comes from the schedule. LaRavia's
    game `1261814783951241216` gives `is_away = true`;
  - an empty-stats entry dated in the past becomes an `ENTRY_WITHOUT_PLAY` absence;
  - an empty-stats entry dated today or later writes nothing, as now (`isFutureOrToday`);
  - football: a rostered player with no entry in a week his team played (per the schedule) gives
    `TEAM_PLAYED_NO_ENTRY`;
  - a week his team didn't play (bye) writes nothing;
  - his team unknown gives `UNCLASSIFIED`;
  - a no-entry week for a player **not** rostered in any league of the sport-season writes nothing;
  - week finality: `final` only when every scheduled game in the week is `complete` and the fetch
    happened at least `WEEK_FINAL_AFTER` after the week's last game date.
- [X] T013 [US5] Rebuild `…/ingest/PlayerGameIngestService.java` on per-week data (research R6).
  - **New entry point**: `Result refreshSportSeason(Sport sport, int season, Instant now)`.
    1. Fetch the schedule once.
    2. For every week from 1 to the schedule's last week with a started game, skip weeks whose
       `sport_week_stats` row is `final`. Otherwise fetch `week(...)`, write rows, and upsert
       `sport_week_stats` with `final` per T012's rule.
    3. Then run the no-entry classification (`TEAM_PLAYED_NO_ENTRY` / `UNCLASSIFIED`) for players
       rostered in **any** league of that sport-season. Use `RosterWeekPointsRepository` across
       those leagues.
  - **Keep, unchanged in behaviour**:
    - the `SportRules.playedIn` routing;
    - the `absences.deleteByGame` / `deleteWeeklyBasis` clean-up;
    - the `Result` record, with `playersWalked` renamed `weeksFetched`. Mirror it wherever it's
      serialized.
  - **The existing `ingest(sleeperLeagueId, requestedSeason)`** (the manual endpoint) now resolves
    the league's sport and season and calls `refreshSportSeason`. Remove the per-player walk; don't
    keep it as a second path (research R9).
  - **Single-flight**: guard `refreshSportSeason` per `sport:season` with the same mechanism as
    T020. If this lands before T020, use a local `ConcurrentHashMap<String, CompletableFuture>`
    that T020 then generalizes.
- [X] T014 [US5] Make T012 pass. Run `./gradlew test --tests '*PlayerGame*'` and report the real
  counts.
- [X] T015 [US5] Update `…/api/IngestController.java`'s `/player-games/{id}` response for the renamed
  `Result` field, and any `web/src/api.ts` type that mirrors it (search for `playersWalked`).
- [X] T016 [US5] **Parity gate** (quickstart §2), with the backend restarted on the new code:
  1. `truncate player_game, player_absence, sport_week_stats`.
  2. `POST /api/ingest/player-games/{id}` for NBA 2025 and NFL 2025.
  3. Export as in T001 (rostered players only, same sort) and diff against T001's CSVs.
  4. Superlatives for both leagues must be byte-identical to T002's JSON. `JOEL_EMBIID` stays
     Jokić 16 and Embiid 26.
  5. Record the Sleeper call count (backend logs) and wall time for each full-season rebuild.

  **Pass: zero differences.** If any difference appears, stop and write it up in
  `verification.md` for Allan: which rows, and why. Don't adjust the ingest to hide it (AGENTS.md).

  One likely, legitimate source of football differences (analysis A2): byes now come from the
  schedule instead of being inferred from walked players' entries. So a
  `TEAM_PLAYED_NO_ENTRY`/bye difference may be the new ingest being *more* correct. Show those
  rows against Sleeper's schedule before deciding.
- [X] T017 [US5] Run SC-004 and measure storage:
  - Re-run `POST /api/ingest/player-games/1229352720222134272` on the now-complete NBA 2025. Every
    week is `final`, so no week is fetched and it must finish in well under 10.1 s. Record wall
    time and CPU.
  - Record `player_game` row counts per `(sport, season)` and the table size, next to research
    R6's 26 MB estimate.

**Checkpoint**: parity holds, SC-004 is measured, and there's one per-game ingest in the codebase.

---

## Phase 4: User Story 1 — Pages refresh themselves when opened (P1) 🎯 MVP

**Goal**: opening a league page refreshes that league's stale seasons in the background. The rail
shows the state, and the page refetches when the refresh is done.

**Independent Test**: quickstart §3.1. With one NBA 2025 transaction week deleted and the refresh
row made stale, opening the Superlatives page puts the Jabari Smith Jr. Award back to LaRavia and
Sensabaugh at 14 without a manual ingest.

**Week finality (added after analysis, FR-016, research R14).** Build these before T021, which
depends on them.

- [X] T048 [P] [US1] Write `test/…/refresh/WeekFinalityTest.java`, then implement
  `…/refresh/WeekFinality.java` as a pure static
  `boolean isFinal(int week, int lastScoredLegAtFetch, String leagueStatusAtFetch)`.
  - True iff `lastScoredLegAtFetch > week`, or `leagueStatusAtFetch` is `complete`. A null status
    is never complete (V21).
  - Test cases:
    - week 10 fetched at leg 10 → not final;
    - at leg 11 → final;
    - at leg 10 with status `complete` → final;
    - null status → not final;
    - week 0 or below → not final.
- [X] T049 [P] [US1] Create `…/store/LeagueWeekFetchRepository.java`:
  - `Set<Integer> finalWeeks(long leagueId, String kind)`;
  - `void record(long leagueId, String kind, int week, Instant fetchedAt, boolean fin)`, an upsert
    that never flips `final` back to false.

  Add a case to `RefreshRepositoriesIT` (T009).
- [X] T050 [US1] In `…/ingest/TransactionIngestService.java`, replace the "stored" skip
  (`stored.contains(week) && week != lastScoredLeg`, lines 62–66) with "skip iff the week is in
  `finalWeeks(leagueId, "TRANSACTIONS")`".
  - After each fetched week, `record(...)` with `WeekFinality.isFinal(week, lastScoredLeg,
    league.status())`, using the status from this walk's league payload.
  - `storedWeeks` stops being used for skipping. Remove it if nothing else calls it.
- [X] T051 [US1] Make the same change to `…/ingest/LeagueHistoryIngestService.java`'s
  `ingestWeeklyPoints`, lines 231–233.
  - Keep the existing `settled` conditions (paired and with starters) as *additional* reasons to
    refetch. Skip only if the week is final **and** settled.
  - Use kind `POINTS`.
- [X] T052 [US1] Postgres-backed IT, `test/…/ingest/PartialWeekHealsIT.java`, with Sleeper mocked:
  1. Ingest week 10 while `last_scored_leg = 10`, with 24 of 53 transactions and partial points.
  2. Ingest again with `last_scored_leg = 11` and the full payloads.
  3. Expect week 10 to be refetched, to hold all 53 moves and the full points, and to be
     recorded `final`.
  4. A third ingest makes no call for week 10.

  Before T050/T051, this test must fail against today's code.

- [X] T018 [P] [US1] Write `test/…/refresh/LeagueRefreshRulesTest.java` (pure) for a static
  `RefreshDecision.decide(Row stateOrNull, String leagueStatus, Instant now)`, which returns
  `START` or `SKIP_FRESH`, `SKIP_COMPLETE` or `SKIP_RECENT_FAILURE`:
  - no row gives `START`;
  - `loadedComplete` gives `SKIP_COMPLETE`, even with a very old success;
  - a success 59 minutes old gives `SKIP_FRESH`, and 61 minutes old gives `START`;
  - a failure 9 minutes old, newer than the last success, gives `SKIP_RECENT_FAILURE`, and 11
    minutes old gives `START`;
  - a null `league.status` is never treated as complete (V21's comment);
  - `completeAfter(statusAtStart, succeeded)` is true only for `status = complete` and success.
- [X] T019 [US1] Implement `…/refresh/RefreshDecision.java` to pass T018.
- [X] T020 [P] [US1] Write `test/…/refresh/SingleFlightTest.java`, then implement
  `…/refresh/SingleFlight.java`: `CompletableFuture<T> run(String key, Supplier<T> work)`.
  - It runs on a virtual-thread executor capped by a `Semaphore(MAX_CONCURRENT_REFRESHES)`.
  - A second call with the same key while the first runs returns **the same future**.
  - The key is released on completion, success or failure, so the next call runs again.

  Test cases:
  - 10 concurrent calls with one key → the supplier runs once;
  - 3 keys submitted together → at most 2 run at once (observed with latches);
  - a thrown exception still releases the key.
- [X] T021 [US1] Create `…/refresh/LeagueRefreshService.java`.
  - **`Status trigger(String sleeperLeagueId)`**:
    - resolve the chain seasons (research R2): the URL's league row, what
      `LeagueSeasonResolver.resolve` shows, and every earlier `previous_league_id` season;
    - apply `RefreshDecision.decide` to each season;
    - if any season is `START`, submit **one** refresh for the whole chain:
      `SingleFlight.run("chain:" + newestLeagueSleeperId, …)`;
    - return the status without waiting.

    *(Amended after analysis, C2 and research R2: the unit is the chain, not the season.)*
  - **One chain refresh** runs, in order:
    1. `LeagueHistoryIngestService.ingestChain(sport, newestLeagueSleeperId, skip)`, once. It
       includes transactions (R2).
       - `skip` is the set of chain seasons already `loaded_complete`: a **new, required**
         parameter that's never defaulted.
       - Every existing caller (`IngestController`, `LeagueIngestService` and any others found by
         search) passes `Set.of()` explicitly.
       - `ingestChain` skips standings, points, fixtures and transactions for a skipped season.
    2. `PlayerGameIngestService.refreshSportSeason(sport, season, now)` for each non-skipped
       season, single-flighted per `sport:season` (T013).

    Then, for each non-skipped season, it calls `recordSuccess(leagueId, now,
    RefreshDecision.completeAfter(statusSeenInThisWalk, true))`. Any exception records a failure
    against every non-skipped season, with a short reason, logs the full one, and leaves stored
    data untouched (FR-006).
  - **`Status status(String sleeperLeagueId)`**: the same view with nothing started.
    `RUNNING` = the single-flight key is in flight.
  - **`refresh.on-visit.enabled = false`**: `trigger` behaves like `status`.
- [X] T022 [US1] Create `…/refresh/RefreshController.java` with:
  - `POST /api/leagues/{sleeperId}/refresh`;
  - `GET /api/leagues/{sleeperId}/refresh`.

  Both take `X-Sleeper-User` and return **404** when `membership.visibleLeague(sleeperId,
  sleeperUserId)` is empty, the same pattern as `SuperlativesController.java:56–57`. The body is
  per the contract, with mutable maps, since `lastSuccessAt` and `lastFailureAt` are nullable.
- [X] T023 [P] [US1] Add `test/…/refresh/RefreshControllerIT.java`, visit half (Postgres-backed,
  ingest services mocked):
  - a stranger gets 404;
  - a stale league returns `RUNNING`, and a second POST during the run starts no second run
    (mock invocation count 1);
  - with `on-visit.enabled=false`, a POST starts nothing.
- [X] T024 [US1] Mirror the contract in `web/src/api.ts`:
  - `type RefreshState = 'FRESH' | 'RUNNING' | 'FAILED' | 'COMPLETE'`;
  - `type RefreshStatus = { state; leagueSleeperId; season; lastSuccessAt: string | null;
    lastFailureAt: string | null; seasons: { leagueSleeperId: string; season: number;
    state: RefreshState }[] }`;
  - `refreshLeague(id)` (POST) and `getLeagueRefresh(id)` (GET).
- [X] T025 [US1] Create `web/src/leagueDataVersion.tsx`:
  - a context holding a `Map<string, number>`;
  - `LeagueDataVersionProvider`, wrapped around the route table in `web/src/components/AppShell.tsx`
    so both the rail and the pages sit inside it;
  - `useLeagueDataVersion(sleeperLeagueId): number`;
  - `useBumpLeagueDataVersion(): (id) => void`.
- [X] T026 [US1] In `web/src/components/LeagueRailSection.tsx`:
  - when the resolved league id changes, call `refreshLeague(id)` once;
  - while `state === 'RUNNING'`, poll `getLeagueRefresh(id)` every 3 s, and stop on unmount or
    league change;
  - on the transition from `RUNNING` to `FRESH` or `COMPLETE`, call `bump(id)`;
  - render the indicator under the league name, per the contract's indicator table ("Updating…",
    "Updated 12 min ago", "Couldn't reach Sleeper — data from 3 h ago"), in the rail's existing
    small-muted style.

  A failed POST (network) shows nothing and doesn't retry in a loop.
- [X] T027 [US1] Add `useLeagueDataVersion(sleeperLeagueId)` to the data-fetch effect dependencies
  of each league page, a one-line change per page:
  - `web/src/pages/LeagueHistory.tsx`
  - `PowerRankings.tsx`
  - `LeagueAnalysis.tsx`
  - `RosterManagement.tsx`
  - `ExpectedWins.tsx`
  - `SeasonForecast.tsx`
  - `WeeklyReport.tsx`
  - `Superlatives.tsx`

  Don't touch `/power/verify` (research R1).
- [X] T028 [P] [US1] Vitest:
  - `web/src/components/LeagueRailSection.test.tsx`:
    - changing league calls `refreshLeague` once;
    - `RUNNING` then `FRESH` polls, bumps the version and stops polling;
    - `FAILED` renders the "Couldn't reach Sleeper" line with the age.
  - `web/src/pages/Superlatives.test.tsx`: a version bump refetches `fetchSuperlatives`.
- [X] T029 [US1] Live-check quickstart §3.1–3.7 in the real browser (hard refresh after any vite
  restart). This includes the POST's CORS preflight (lessons #6) and SC-003's timing on NFL 2026.
  Also:
  - **count the Sleeper calls** one visit refresh makes (backend logs) and confirm they're well
    under 1,000 a minute (FR-014, analysis G3);
  - run T054's week-by-week check **locally** against Sleeper for NBA 2025 and NFL 2025.

  Record everything in `verification.md`.

**Checkpoint**: MVP. Pages keep themselves current, and production's NBA 2025 would fix itself on
its first visit.

---

## Phase 5: User Story 2 — No page tells a manager to run an endpoint (P1)

**Goal**: plain wording everywhere, and a guard test so a new hint can't ship.

**Independent Test**: SC-002. The guard test passes, and every league page with missing data reads
correctly in the browser.

- [X] T030 [P] [US2] Write `test/…/api/NoIngestHintsInMessagesTest.java`, modelled on
  `NoSportNameInSuperlativesTest`:
  - scan every `.java` under `…/api/` and `…/engine/`;
  - strip comments;
  - fail if a string literal contains `/api/ingest` or `POST /api/`.

  It must fail on today's tree, at the 9 locations in research R10, before T031.
- [X] T031 [US2] Replace each string, per the contract's "Replaced message strings" table:
  - `…/api/LeagueController.java:560`
  - `…/engine/LeagueAnalysisService.java:301`, `:446` (projections, stated as a fact, not a
    loading state), `:476`
  - `…/engine/PowerRankingService.java:270`
  - `…/engine/SeasonSuperlativesService.java:609`, `:699`, `:811`, `:863`

  Backend strings never say "updating" (spec amendment 2). T030 must now pass.
- [X] T032 [US2] Update tests that assert the old strings. `web/src/pages/Superlatives.test.tsx:523`
  is known; search both suites for `api/ingest` and fix each.
- [X] T033 [US2] Browser check (quickstart §7): on a league with no transactions or per-game rows,
  open all eight league pages and record what each says in `verification.md`. None may mention
  `/api/` or "ingest".

---

## Phase 6: User Story 4 — A new league is loaded completely, once (P2)

**Goal**: setup ends with every chain season loaded (research R2).

**Independent Test**: quickstart §4. After adding the reference league to a clean database, every
chain season reaches `loaded_complete` or `FRESH` with no page visit.

- [X] T053 [US4] Add `POST /api/refresh/players?sport=` to `…/refresh/RefreshController.java` per
  the contract (added after analysis, G1). It runs `DailyRefreshService`'s `PLAYERS` step for
  one sport, skipped if today's `daily_capture` exists, and returns `{ outcome, detail }`.
  - Mirror it in `web/src/api.ts` as `refreshPlayers(sport)`.
  - In `DraftPicker.tsx:38`, change the "Fetching players" step from `ingestPlayers` to
    `refreshPlayers`.
  - `/api/ingest/players` stays as it is (FR-015).

  Depends on T037.
- [X] T034 [US4] In `web/src/pages/DraftPicker.tsx`'s staged setup (the step list at lines 38–41),
  add a final step, "Loading past seasons", that calls `refreshLeague(l.sleeperLeagueId)`.
  - It doesn't wait for the background refresh; the rail shows progress once a league page opens.
  - The step's label stays in the existing plain style.
- [X] T035 [US4] Live-check quickstart §4 and record it.

---

## Phase 7: User Story 3 — A daily capture of what can't be recovered later (P2)

**Goal**: one daily call refreshes the player list (and suspensions), ADP and the board. It's
guarded by its own secret, and it survives a cold start.

**Independent Test**: quickstart §5 locally, and §6 against production after deploy.

- [X] T036 [P] [US3] Write `test/…/refresh/DailyRefreshServiceTest.java` (repositories and ingests
  mocked):
  - `PLAYERS` runs and records a row;
  - a second call the same UTC day returns `SKIPPED_ALREADY_TODAY` and doesn't call
    `PlayerIngestService`;
  - a `PLAYERS` exception gives `FAILED` for that step while `BOARD` still runs;
  - an FFC result marked "fetch failed" gives `DONE_ADP_FAILED_BEST_EFFORT`, which **doesn't**
    count as a failure (spec amendment 8);
  - FFC's football-only skip for NBA gives plain `DONE`;
  - the overall result reports failure iff any non-skipped step is `FAILED`.
- [X] T055 [US3] Extract `…/ingest/BoardRefresh.java` with `Result run(Sport)`. It runs
  `ffcAdp.ingest`, then `boards.rebuild`, then `profiles.persistFitted`, the exact sequence of
  `IngestController.board` (`IngestController.java:147–152`, research R15).
  `IngestController.board` then calls it. The response shape of `/api/ingest/board` is unchanged.
- [X] T037 [US3] Create `…/refresh/DailyRefreshService.java`. For each `Sport` value, in order:
  1. `PLAYERS` via `PlayerIngestService.ingest`, skipped if `DailyCaptureRepository.exists`;
  2. `BOARD` via `BoardRefresh.run` (T055). This reads FFC's result flag, not an exception, per
     T036.

  Each step records a `daily_capture` row on success, with the ingest's result summary as
  `detail`. It returns the contract's `{ date, steps[] }`. Also expose
  `StepResult players(Sport)` for T053.
- [X] T038 [US3] Add `POST /api/refresh/daily` to `…/refresh/RefreshController.java`:
  - **404** when `RefreshProperties.secret` is blank;
  - **401** unless `X-Refresh-Secret` matches, compared with
    `MessageDigest.isEqual(bytes, bytes)`;
  - **200** or **500** per `DailyRefreshService`'s overall result, with the body always present.
- [X] T039 [US3] Exempt `/api/refresh/daily` in `…/api/ApiTokenFilter.java`'s `shouldNotFilter`,
  next to `/api/health`. Update the class javadoc's list of open routes and say why (research R8).
- [X] T040 [P] [US3] Extend `test/…/refresh/RefreshControllerIT.java` with the daily half:
  - no secret configured gives 404;
  - a wrong secret gives 401;
  - the right one gives 200 with steps;
  - with `api.security.token` set, the daily route works **without** a bearer token, while
    `GET /api/leagues/{id}/superlatives` without one is still 401.
- [X] T041 [US3] Create `.github/workflows/daily-refresh.yml`:
  - `on: { schedule: [{ cron: "0 11 * * *" }], workflow_dispatch: {} }`;
  - one job on `ubuntu-latest` with `timeout-minutes: 20`, and one step:
    `curl -fsS -X POST https://api.ballknowers.co/api/refresh/daily -H "X-Refresh-Secret: ${{
    secrets.REFRESH_SECRET }}" --retry 30 --retry-all-errors --retry-delay 30 --retry-max-time 900
    --max-time 180`, printing the response body. Production's cold start was measured at about 4.7
    minutes (research R7, amended);
  - `permissions: {}`;
  - nothing else in the file. The repo is public (research R7).
- [X] T042 [US3] Live-check quickstart §5 locally, with `REFRESH_SECRET` and then `API_TOKEN` set in
  the backend's environment, and record it.

---

## Phase 8: Polish, deploy and production verification

- [X] T043 Run `cd backend && ./gradlew cleanTest test` and **read the skip count**, which must be
  0 with Postgres up. Then run `cd web && npx tsc -b && npm run build && npx vitest run`. Record
  the real numbers.
- [X] T044 Run the bug-hunting review (`/code-review`, not a style pass) over the branch diff, and
  fix what it confirms. Pay particular attention to:
  - the single-flight release path;
  - `loaded_complete` never being set by a failed or in-season run;
  - the deletes in the rebuilt ingest (research R11).
- [X] T045 Update `HANDOFF.md` and `README.md` with:
  - what refreshes and when;
  - the two secrets to set at deploy;
  - the GitHub 60-day inactivity rule for scheduled workflows;
  - what's verified versus assumed.

  Update memory: add a project memory for spec 009, and the Railway memory with the serverless
  and cost facts.
- [ ] T046 **Ask Allan** before any of this: merging to `main` deploys both Railway services.
  After deploy:
  - he sets `REFRESH_SECRET` in the Railway backend's variables and in GitHub's repository secrets;
  - run quickstart §6: `workflow_dispatch` from a sleeping backend, then record the retries and
    the time to first success, and confirm the backend sleeps again;
  - run quickstart §8: production NBA 2025 shows LaRavia and Sensabaugh at 14 and KATastrophe
    Krew at 1,587.5 after a visit (SC-001);
  - **first-load cost (analysis A1)**: the first visit per sport after deploy fetches every week of
    every chain season. With Allan's go-ahead, trigger each production league once through its
    page, ideally NBA first, off-peak, and record how long each took. **SC-003 is measured on a
    second, ordinary stale visit**, not the first load;
  - **SC-003 on production (analysis G2)**: time a stale NFL 2026 page from open to refreshed
    figures. Target: under 30 s. Record it, then run T054.
- [ ] T054 **Production week-by-week check (SC-008, added after analysis at Allan's request)**:
  - Add `scripts/check-weeks-vs-sleeper.py`, read-only. For a league id it compares, per
    regular-season week:
    - production's completed waiver/FA add count (`/api/leagues/{id}/transactions`) against
      Sleeper's (`/league/{id}/transactions/{w}`);
    - each roster's points (`/api/leagues/{id}/weekly-report/{w}`) against Sleeper's
      (`/league/{id}/matchups/{w}`).
  - It prints the failing weeks, and exits 0 only when every week matches.
  - Take the base URL as an argument, so the same script serves T029 locally.
  - **Baseline, measured 2026-09-28 on NBA 2025 before any fix**: adds match for weeks 1–9; week
    10 has 24 of 53; weeks 11–21 have 0. Points match except weeks 8 (up to 20.5) and 9 (up to 31);
    weeks 19 and 21 show 8 of 12 teams, probably playoff bracket. The script must say whether those
    two are real or an artifact, not skip them silently.
  - Run it on production NBA 2025 and NFL 2025 after T046's visits. **Pass: exit 0 for both**, or
    any remaining difference explained in `verification.md`.
- [ ] T047 Record in `HANDOFF.md` the month-later checks, which can't run now:
  - SC-005: Railway usage within $5, and under $1 over the ~$1.20 pace;
  - SC-006: at least 29 of 30 green scheduled runs;
  - SC-007: the backend sleeps between runs.

---

## Dependencies & execution order

### Phase dependencies

- **Setup (T001–T003)** must finish before T013, or the parity baselines are lost.
- **Foundational (T004–T009)** blocks everything after it.
- **US5 (T010–T017)** comes before US1, whose refresh calls `refreshSportSeason`. **T016's parity
  gate must pass** before any later phase is verified live.
- **US1 (T018–T029)** comes after US5. T020 generalizes T013's interim single-flight. **T048–T052
  (week finality) come before T021.**
- **US2 (T030–T033)** needs only Foundational, and can run alongside US1. It shares no file except
  `Superlatives.test.tsx` (T028/T032): serialize those two edits.
- **US4 (T034–T035)** comes after US1 (it calls `refreshLeague`). **T053** comes after T037.
- **US3 (T036–T042)** needs only Foundational. It shares `RefreshController.java` and
  `RefreshControllerIT.java` with US1: serialize those edits.
- **US3**: T055 comes before T037.
- **Polish (T043–T047, T054)** comes last. T046 needs Allan's go-ahead, and T054 runs after it.

### Within each story

Tests first, written to fail. Then the pure rule, then the service, then the endpoint, then the
UI, then the live check. The live check is the story's done condition, not the tests.

---

## Parallel examples

- **Foundational**: once T004 is in, T005, T006, T007, T008 and T009 can go together.
- **US5**: T010 and T011 go together. T012 needs T011. T013 needs T010 and T012.
- **US1**: T018, T020 and T023 are separate test files, and go together. T024 and T025 are
  separate web files, and go together.
- **Across stories**: with Foundational and US5 done, US1's backend (T018–T023), US2 (T030–T031)
  and US3's backend (T036–T039) touch different files, apart from the two noted above.

---

## Implementation strategy

### MVP

T001–T029 give a working MVP: per-game rebuild, then refresh on visit. On deploy, production's
NBA 2025 fixes itself on its first visit. Most developer-text messages would still show, but the
states they describe become rare.

### Incremental delivery

1. MVP.
2. US2 (wording).
3. US4 (setup).
4. US3 (daily job). Worth shipping before the next NBA season starts (2026-10-20), so suspension
   captures and ADP cover the draft window.

### This repo's pipeline

Build (Sonnet subagents) → T044's bug-hunting review → the live checks (T029, T033, T035, T042,
T046).

**This plan hasn't had the adversarial review pass** the AGENTS.md convention calls for. Run
`/speckit-analyze`, or a cold review of plan.md and research.md, before T010. R6 in particular
replaces a verified ingest.
