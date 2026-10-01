# Tasks: Player spotlight on the league home

**Input**: Design documents from `specs/014-home-player-spotlight/`
**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md), [data-model.md](data-model.md),
[contracts/player-spotlight-api.md](contracts/player-spotlight-api.md), [quickstart.md](quickstart.md)

**Tests**: included. The spec does not ask for TDD, but plan.md's AGENTS.md gate requires a
preference-ordering test for every ranking (lesson 1) and Postgres-backed ITs for the real SQL and JDBC
binds (lessons 2–3). Every IT follows `backend/src/test/java/com/ballknowers/draftsim/api/AccessControlMvcIT.java`:
real Postgres on `localhost:5433` (`draftsim`/`draftsim`/`draftsim`), and an `@BeforeAll` that **skips**
when it is unreachable. After every backend run, read the skip count from
`backend/build/test-results/test/*.xml`, never only `BUILD SUCCESSFUL`.

**Who builds**: coding tasks run on a Sonnet subagent (AGENTS.md). The parent session reads every diff before
marking a task done.

**Story order**: US1 (P1) → US4 (P2) → US2 (P2) → US3 (P3). US4 comes before US2 at equal priority because it
needs no new data and is the only story that is **live-verifiable today**: NBA 2026 has not started (tip-off
2026-10-20).

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel (different files, no dependency on an incomplete task)
- **[Story]**: US1–US4 from spec.md

Paths: backend `backend/src/main/java/com/ballknowers/draftsim/…` (abbreviated `…/draftsim/`), tests
`backend/src/test/java/com/ballknowers/draftsim/…`, web `web/src/…`.

---

## Phase 1: Setup

- [X] T001 Confirm the worktree base and the migration number: `git -C .claude/worktrees/014-home-player-spotlight log --oneline -1` is at or after `origin/main` 2dd47f2, and `git ls-tree` across all local branches plus `origin/main` shows no `V26__*.sql` in `backend/src/main/resources/db/migration/` (research R12). If V26 is taken, use the next free number everywhere `V26` appears below, and note the change in research.md R12 under "amended".
- [X] T002 Confirm the test environment per quickstart.md "Prerequisites": Postgres reachable on `localhost:5433` (`PGPASSWORD=draftsim psql -w -h localhost -p 5433 -U draftsim -d draftsim -c "select 1"`), NFL 2026 `player_game` rows present for weeks 1–3, and **no** backend from another worktree on 8081. Record the baseline: run `cd backend && ./gradlew test` once and write the pass/fail/skip counts into a `## Baseline` note at the bottom of this file, so later failures can be told apart from pre-existing ones (memory: `RefreshControllerIT` fails on base).

---

## Phase 2: Foundational (blocks every story)

**Purpose**: the endpoint, the shared week rule, ownership, period selection, and the empty home section group. After this phase the endpoint returns the right `applies`/`period` shape with empty sections, and the home renders nothing new for an inapplicable league.

- [X] T003 Extract the default-week rule from `WeeklyReportService.forWeek` (`requestedWeek > 0 ? requestedWeek : scored.latestFinal() > 0 ? scored.latestFinal() : scored.latestStored()`) into `public static int defaultWeek(ScoredWeeks.Snapshot scored, int requestedWeek)` in `…/draftsim/engine/WeeklyReportService.java`, and make `forWeek` call it. Behavior must not change: run `WeeklyReportServiceTest` and `WeeklyReportShapeTest` before and after, and both must pass both times (research R9).
- [X] T004 [P] Add a `SpotlightOwnership` lookup in `…/draftsim/engine/SpotlightOwnership.java`: given a league row and the caller's `X-Sleeper-User`, build `Map<String sleeperPlayerId, Ownership>` from the **latest stored week's** `roster_week_points.players_points` keys (research R10). Name owners exactly as `WeeklyReportService.forWeek` does: `league_member.team_name`, else `roster_season` manager name, else `"Roster N"`. Set `isMe` via `managers.idsBySleeperUserId()`. `Ownership` is a record `(boolean rostered, String teamName, boolean isMe)`, with `teamName` nullable. Use mutable `HashMap` only, never `Map.of` (hard rule: nullable values). Return an empty map when the league has no stored week (pre-draft).
- [X] T005 [P] Unit-test `SpotlightOwnership` in `backend/src/test/java/com/ballknowers/draftsim/engine/SpotlightOwnershipTest.java`. Cover: a player on the latest week maps to that roster's team name; a player only on an *earlier* week is unrostered; `isMe` is true only for the caller's roster; no stored weeks gives an empty map; a roster with no member row and no manager name yields `"Roster N"`, without an NPE.
- [X] T006 Create `…/draftsim/engine/PlayerSpotlightService.java` with the result records from data-model.md (`Result`, `Period(kind NIGHT|WEEK, date, gamesCount, week, weekFinal)`, `Performance`, `TrendingEntry`, `Section`, `TrendingSection`) and method `Optional<Result> forLeague(String sleeperLeagueId, String sleeperUserId)`. It must:
  - (a) resolve the league via `LeagueSeasonResolver`, returning `Optional.empty()` when it is unknown;
  - (b) apply the **current season only** rule (research R8): a league counts as current when its season equals `sport_trending_fetch.league_season` for the sport; when that row or value is null, when it is the newest season in its own chain (`LeagueRepository.chainBySleeperId`); otherwise return `applies=false, reason="PAST_SEASON"`;
  - (c) choose the period kind **only** through `SportRules.playsMultipleGamesPerScoringPeriod()`: true → NIGHT, false → WEEK (FR-013);
  - (d) for WEEK, set `week = WeeklyReportService.defaultWeek(scoredWeeks.of(league.id()), 0)` and `weekFinal = scored.isFinal(week)`; with `week == 0`, set `period = null, periodUnavailable = "NO_WEEK_SCORED"`;
  - (e) leave the section bodies as empty placeholders, filled by later stories, each with `unavailable` null.

  Wrap each section's computation in its own try/catch that logs and yields `entries: [], unavailable: "SECTION_FAILED"`, so one section can never fail its siblings (research R11).
- [X] T007 Implement NIGHT period selection in `PlayerSpotlightService` (research R6). Add constant `static final int NIGHT_COMPLETE_HOUR_UTC = 10`, with Javadoc stating it is an **assumption** pending quickstart V6. Rules:
  - Night D is complete when `sport_week_stats.fetched_at` for the week containing D (the week of any `player_game` row dated D) is `>= D+1 at 10:00 UTC`.
  - `period` is the latest complete night, with `gamesCount = count(distinct game_id)` that date.
  - When a later date has rows but is not complete, set `laterNightInProgress` to that date.
  - With no current-season rows at all: `period = null, periodUnavailable = "NO_GAMES_YET"`.
  - With rows but no complete night: `period = null, periodUnavailable = "NO_COMPLETE_NIGHT_YET"`.

  Add read method `PlayerGameRepository.datesForSeason(sport, season)`, returning each distinct `game_date` with its `week` and game count, in `…/draftsim/store/PlayerGameRepository.java`. Add `SportWeekStatsRepository.forSeason` use if not already present. Take `Instant now` from an injectable `Clock`, so tests can pin it.
- [X] T008 Unit-test period selection in `backend/src/test/java/com/ballknowers/draftsim/engine/PlayerSpotlightPeriodTest.java`, with a fixed `Clock`. Cover:
  - a date whose week was fetched at D+1 09:59 UTC is **not** complete, and one fetched at 10:00 is;
  - a later in-progress date sets `laterNightInProgress` while `period` stays on the earlier complete night;
  - no rows gives `NO_GAMES_YET`;
  - rows with none complete gives `NO_COMPLETE_NIGHT_YET`;
  - a WEEK-kind league gets `period.week` equal to `WeeklyReportService.defaultWeek(...)` for the same snapshot.
- [X] T009 Create `…/draftsim/api/PlayerSpotlightController.java`: `GET /api/leagues/{sleeperId}/player-spotlight` with `@RequestHeader(value="X-Sleeper-User", required=false)`. Return 404 when `membership.visibleLeague(sleeperId, sleeperUserId).isEmpty()`, exactly like `WeeklyReportController`. Serialize with a package-private static `body(Result)` built on `LinkedHashMap`. Follow the contract's absent-vs-empty rule: `topOfNight` is **omitted** (not null, not `[]`) when `playersPlayMultiplePerPeriod` is false. In a `Performance`, `opponent`/`isAway` are emitted as null when unknown. A `TrendingEntry` carries `points`/`opponent`/`isAway` **only** when `outcome == "PLAYED"`.
- [X] T010 [P] Shape test in `backend/src/test/java/com/ballknowers/draftsim/api/PlayerSpotlightShapeTest.java`, mirroring `WeeklyReportShapeTest` (no database). Assert:
  - `applies=false` carries only `applies, reason, season, sport`;
  - football omits the `topOfNight` key;
  - basketball includes it;
  - a `DID_NOT_PLAY` trending entry has no `points` key;
  - a null `teamName` serializes without throwing.
- [X] T011 [P] Source-scan test in `backend/src/test/java/com/ballknowers/draftsim/engine/NoSportNameInPlayerSpotlightTest.java`, copying `NoSportNameInWeeklyReportTest`'s approach. Fail if `PlayerSpotlightService.java`, `PlayerSpotlightController.java` or `web/src/components/PlayerSpotlight.tsx` contains `Sport.NBA`, `Sport.NFL`, `"nba"`, `"nfl"`, `'nba'` or `'nfl'` (FR-013).
- [X] T012 Add types to `web/src/api.ts`, mirroring T006/T009 **field for field** (hard rule): `SpotlightOwnership`, `SpotlightPerformance`, `SpotlightTrendingEntry` (with `outcome: 'PLAYED' | 'DID_NOT_PLAY' | 'BYE' | 'NO_PERIOD'` and optional `points`/`opponent`/`isAway`), `SpotlightPeriod` (a discriminated union on `kind`), and `PlayerSpotlight`. Add `export const getPlayerSpotlight = (sleeperLeagueId: string) => apiFetch(\`/api/leagues/${sleeperLeagueId}/player-spotlight\`).then(json<PlayerSpotlight>)` next to `getWeeklyReport` (around line 1966). Run `cd web && npx tsc -b`. *(Amended after review: `BYE` is now `NO_GAME`, see research R3 "amended after review".)*
- [X] T013 Create `web/src/components/PlayerSpotlight.tsx`: `props { spotlight: PlayerSpotlight; weekly: Block<WeeklyReport> }`. It renders a `<section className="section lh-spotlight">` wrapper with a heading, and one sub-section per present section, each with its own `<h3>`. It renders **nothing** when `applies` is false. It never branches on `sport`: it uses `playersPlayMultiplePerPeriod` and the presence of `topOfNight` (FR-013). Period label helper:
  - NIGHT → the formatted calendar date ("Tue, Oct 21"), never "last night" alone (US1 sc.2);
  - WEEK → "Week N", with an "in progress" tag when `weekFinal` is false (US4 sc.2).

  Define the shared row component here: `PlayerFace` headshot, name, position/team, opponent ("vs CHA" / "@ CHA" / "opponent unknown"), the **exact points beside the name** (FR-015), and the ownership line ("Rostered by {teamName}" / "Free agent here"), with a tinted "Yours" pill when `isMe` (FR-009). Section bodies are filled by later stories.
- [X] T014 Wire the spotlight into `web/src/pages/LeagueHome.tsx`. Add `const spotlight = useBlock(id ? () => getPlayerSpotlight(id) : null, [id, version])` beside the existing blocks (around line 132). Render `<PlayerSpotlight>` only when `spotlight.status === 'ok' && spotlight.data.applies`. A 404 error (`isNotFound`) renders nothing; other errors render the page's existing compact block-error style **inside the spotlight area only**. Loading renders `Skeleton`. Update the block-to-endpoint table in the file's header comment. **No other block may depend on `spotlight`** (FR-012).
- [X] T015 [P] Foundation test in `web/src/components/PlayerSpotlight.test.tsx`: `applies:false` renders nothing (no headings); a NIGHT period renders the formatted date; a WEEK period with `weekFinal:false` shows "in progress". Run `cd web && npx vitest run src/components/PlayerSpotlight.test.tsx`.

**Checkpoint**: the endpoint returns 404 for a non-member and `applies:false` for NBA 2025, and its `period.week` matches `weekly-report/0` for an NFL 2026 league. The home renders no new content yet.

---

## Phase 3: User Story 1 — Top players of the night (P1) 🎯

**Goal**: a basketball league home lists the most recent complete night's best single-game scores among players rostered in the league.

**Independent test**: for a basketball league with a complete night, every entry is rostered, ordered by that night's points under the league's scoring, and shows opponent and owner. Today this is testable only with seeded rows; live verification is quickstart V6 (owed after 2026-10-21).

- [X] T016 [P] [US1] Preference-ordering unit test in `backend/src/test/java/com/ballknowers/draftsim/engine/PlayerSpotlightTopOfNightTest.java`. Seed in memory: five rostered players and one unrostered player on night D; one rostered player on night D-1; a scoring map where a 40-point line beats a 35-point one. Assert:
  - order is points descending, then `playerId` (an exact tie must order by id, stable across two calls);
  - the unrostered player and the D-1 game are excluded;
  - the limit is 10;
  - each entry's `points` equals `GameScoringService.score(scoring, stats)`.
- [X] T017 [US1] Implement `topOfNight` in `PlayerSpotlightService`, computed only when `period.kind == NIGHT`:
  - read `player_game` rows for `(sport, season, game_date = period.date)`, adding `PlayerGameRepository.forDate(sport, season, date)` to `…/draftsim/store/PlayerGameRepository.java`;
  - keep only players present in the T004 ownership map with `rostered == true`;
  - score with `GameScoringService.score(leagues.scoringOf(league.id()), JsonUtil.readMap(row.statsJson()))`;
  - resolve name, position and team from `PlayerRepository.findAll(sport)` by `sleeper_id`, skipping unresolvable ids;
  - sort points desc, then `playerId`; limit 10.

  When `period` is null, set `entries: [], unavailable: "NO_PERIOD"`.
- [X] T018 [US1] IT in `backend/src/test/java/com/ballknowers/draftsim/api/PlayerSpotlightTopOfNightIT.java` (skips without Postgres). Seed an NBA league in a throwaway season (e.g. 2099), with two rosters in `roster_week_points`, `player_game` rows across two dates, and `sport_week_stats.fetched_at` past the cutoff for both. Call the controller with a member header and assert:
  - `period.date` is the later date;
  - `topOfNight` order and points;
  - the owner names;
  - the caller's own player has `isMe: true`.

  Also assert SC-003: for a player in both, `topOfNight[i].points == weeklyReport(week).bestNights[j].points`. Clean up the seeded rows in `@AfterAll`.
- [X] T019 [US1] Render Top players of the night in `web/src/components/PlayerSpotlight.tsx`. Heading "Top players of the night", followed by the period date and "{gamesCount} games". The `unavailable` wording maps codes to sentences in the component, not the server (FR-010):
  - `NO_PERIOD` + `NO_GAMES_YET` → "No games yet this season.", plus " Tip-off is {seasonStartDate}." **only when `seasonStartDate` is non-null**;
  - `NO_COMPLETE_NIGHT_YET` → "Tonight's games are still being played.";
  - `SECTION_FAILED` → "Couldn't load this section."

  When `laterNightInProgress` is set, add a quiet line: "Games on {date} are still in progress."
- [X] T020 [P] [US1] Component tests in `web/src/components/PlayerSpotlight.test.tsx`:
  - pre-season with `seasonStartDate: null` shows no date text, and with `"2026-10-20"` shows it (SC-005);
  - entries render the exact points beside each name;
  - the `isMe` entry shows the "Yours" pill;
  - an empty `entries` list always has a reason sentence, never a bare empty list.

**Checkpoint**: US1 works against seeded data; the NBA 2026 home shows the pre-season sentence.

---

## Phase 4: User Story 4 — Football: the same sections, by week (P2)

**Goal**: a football league home shows Top players of the week, matching the Weekly Report. Trending and Rookie watch are scored against the same week (their bodies come in US2/US3).

**Independent test**: on "(Foot) Ball Knowers" 2026 (`1346366555759341568`), the home's top list matches the Weekly Report's Top performers for the same week, entry for entry, and both name the same week (quickstart V2.1–V2.3).

- [X] T021 [US4] In `web/src/components/PlayerSpotlight.tsx`, when `topOfNight` is **absent**, render "Top players of the week" from `weekly.data.topPerformers` (the payload `LeagueHome` already loads, research R9). Do not add a backend list. Label it with the spotlight's `period.week`, and add a dev-only `console.warn` if `weekly.data.week !== spotlight.period.week`. Handle `weekly` loading (Skeleton) and error ("Couldn't load this week's top players.") on their own, without affecting the Trending/Rookie sub-sections. Show each performer's points beside the name, and `teamName` as "Started for {teamName}" (the list is starters only, spec US4 amendment).
- [X] T022 [P] [US4] Component test in `web/src/components/PlayerSpotlight.test.tsx`: given a football spotlight (no `topOfNight`) and a weekly payload with three `topPerformers`, the section renders exactly those three, in order, with the same points; it shows "Week 3"; while `weekly` is loading, the Trending sub-section still renders.
- [X] T023 [US4] IT in `backend/src/test/java/com/ballknowers/draftsim/api/PlayerSpotlightWeekIT.java` (skips without Postgres). Against a seeded NFL league with weeks 1–2 final and week 3 not final, assert:
  - `spotlight.period.week == weeklyReportController.weeklyReport(id, 0, header).week` (contract invariant 2);
  - `topOfNight` is absent;
  - with no scored week, `periodUnavailable == "NO_WEEK_SCORED"`.

**Checkpoint**: an NFL 2026 league home shows Top players of the week, live, today.

---

## Phase 5: User Story 2 — Trending, and what they actually scored (P2)

**Goal**: Sleeper's most-added players, each with what he scored in the period, or "did not play" / "bye". Never 0 for a non-game.

**Independent test**: quickstart V1 (storage, freshness, failure isolation) and V2.5–V2.6 (scoring and the did-not-play wording) on an NFL 2026 league; V3.4 on NBA 2026 (every entry `NO_PERIOD`).

- [X] T024 [US2] Create `backend/src/main/resources/db/migration/V26__sport_trending.sql`, per data-model.md:
  - `create table sport_trending_fetch (sport text primary key, fetched_at timestamptz, lookback_hours int not null, league_season int, season_start_date date, last_failure_at timestamptz, last_failure text)`;
  - `create table sport_trending (sport text not null references sport_trending_fetch (sport) on delete cascade, rank int not null, sleeper_player_id text not null, add_count int not null, primary key (sport, rank))`.

  Header comment: why the fetch state is separate ("fetched, empty" ≠ "never fetched"), that counts are platform-wide, and Sleeper's attribution request. Append-only: never edit an applied migration.
- [X] T025 [P] [US2] Add `public List<Map<String, Object>> trendingAdds(String sport, int lookbackHours, int limit)` to `…/draftsim/ingest/SleeperClient.java`, calling `/players/{sport}/trending/add?lookback_hours={h}&limit={n}` in the same style as `rosters()`. Javadoc: returns `[{player_id, count}]`, `Content-Type: application/json` verified with `curl -D -` on 2026-10-01, CDN-cached ~10 min, counts across all Sleeper leagues.
- [X] T026 [US2] Create `…/draftsim/store/SportTrendingRepository.java`:
  - `replace(String sport, int lookbackHours, Integer leagueSeason, LocalDate seasonStartDate, List<Entry> entries, OffsetDateTime fetchedAt)`: in **one transaction**, upsert the `sport_trending_fetch` row (setting `fetched_at`, `lookback_hours`, `league_season`, `season_start_date`), `delete from sport_trending where sport=?`, then batch-insert the entries with `rank` 1..n;
  - `recordFailure(String sport, OffsetDateTime at, String reason)`: upserts the fetch row **without** touching `fetched_at` or the list. On first-ever failure, insert `lookback_hours` = 24;
  - `read(String sport)`: returns `Optional<Snapshot(fetchedAt, lookbackHours, leagueSeason, seasonStartDate, lastFailureAt, lastFailure, List<Entry>)>`.

  Bind `fetched_at`/`last_failure_at` with `setObject(i, OffsetDateTime)` and `season_start_date` with `setObject(i, LocalDate)`, **not** `java.sql.Timestamp` (lesson class 3).
- [X] T027 [P] [US2] IT in `backend/src/test/java/com/ballknowers/draftsim/store/SportTrendingRepositoryIT.java` (skips without Postgres), using a throwaway sport key such as `"it-trending"`. Cover: `replace` then `read` round-trips 25 entries in rank order with exact `fetchedAt`; a second `replace` with 3 entries leaves exactly 3; `recordFailure` after a success keeps the list and `fetchedAt` and sets `lastFailure`; `recordFailure` on a fresh sport creates a row with null `fetchedAt` and no entries. Clean up in `@AfterEach`.
- [X] T028 [US2] Create `…/draftsim/refresh/TrendingRefresh.java`, with `Outcome refreshIfStale(Sport sport, Instant now)`:
  - skip when `read(sport)` has `fetchedAt` within `RefreshProperties.STALE_AFTER` (1 h) of `now`;
  - otherwise call `sleeper.trendingAdds(sport.code(), 24, 25)` and `sleeper.state(sport.code())`; parse `league_season` (string → int) and `season_start_date` (→ `LocalDate`, null when absent or unparseable); then `replace(...)`;
  - on **any** exception, `recordFailure(...)`, log at warn, and return `FAILED`. **Never rethrow** (research R5: trending must not fail a league refresh).

  Use a per-sport `SingleFlight` so concurrent league refreshes in one sport share one fetch.
- [X] T029 [US2] Call `trendingRefresh.refreshIfStale(sport, startedAt)` in `…/draftsim/refresh/LeagueRefreshService.java`, right after the `playerGames.refreshSportSeason` loop (around line 202) and **before** the `weeksFailed > 0` throw, so it runs even when per-game fetching failed. Its outcome must not change `weeksFailed` or the refresh's success. Add a `trending(Sport)` step to `…/draftsim/refresh/DailyRefreshService.java#runAll`, with `static final String KIND_TRENDING = "TRENDING"`. It reports `DONE` on success and `SKIPPED_ALREADY_TODAY` when fresh. A trending failure is best-effort and must **not** make `DailyResult.failed()` true, which is "any step FAILED". So add a new `Outcome.DONE_TRENDING_FAILED_BEST_EFFORT`, alongside the existing ADP-specific `DONE_ADP_FAILED_BEST_EFFORT`, rather than reusing that name or returning `FAILED`. Grep `Outcome.` and `KIND_` across `backend/src` for any switch or serializer that enumerates them, and extend each one.
- [X] T030 [P] [US2] Unit test in `backend/src/test/java/com/ballknowers/draftsim/refresh/TrendingRefreshTest.java`, with mocked `SleeperClient` and repository. Cover: fresh within 1 h → no Sleeper call; stale → one `trendingAdds` and one `state` call, and `replace` with parsed season and start date; `trendingAdds` throws → `recordFailure` called, no exception escapes; `season_start_date` missing → null, not a guessed date.
- [X] T031 [US2] Extend an existing refresh IT, `backend/src/test/java/com/ballknowers/draftsim/refresh/RefreshControllerIT.java` or a new `TrendingRefreshIsolationIT.java` beside it, so a league refresh with a Sleeper double whose `trendingAdds` throws still ends with `league_refresh.last_success_at` set (quickstart V1.5). Note in the PR if `RefreshControllerIT` was already failing on base (T002 baseline).
- [X] T032 [P] [US2] Unit test of trending outcomes in `backend/src/test/java/com/ballknowers/draftsim/engine/PlayerSpotlightTrendingTest.java`. Cover:
  - (a) a player with a `player_game` row in the period → `PLAYED` with scored `points`;
  - (b) no row, and his current team appears as some row's `opponent` in the period → `DID_NOT_PLAY`;
  - (c) no row, and his team appears as no row's `opponent` → `BYE`;
  - (d) no row and a null team → `DID_NOT_PLAY`, never `BYE`;
  - (e) `period == null` → every entry `NO_PERIOD`;
  - (f) **no non-`PLAYED` entry has non-null `points`** (FR-005);
  - (g) an id missing from `player` is dropped and counted in `omittedUnknownPlayers`;
  - (h) order is Sleeper's `rank`, not points.
- [X] T033 [US2] Implement the `trending` section in `PlayerSpotlightService`:
  - read `SportTrendingRepository.read(sport)`; when empty or `fetchedAt == null`, return `entries: [], unavailable: "NEVER_FETCHED"`;
  - otherwise set `lookbackHours`, `fetchedAt`, and `stale = fetchedAt` older than `lookbackHours` hours before `now`; take `seasonStartDate` for the top-level field from the same snapshot (research R7);
  - for each entry up to 10, resolve the player, attach the T004 ownership (default `rostered:false`), and classify the outcome per T032 against the period's rows: NIGHT → `forDate`; WEEK → `PlayerGameRepository.forWeek(sport, season, week)`, which already exists;
  - build the opponent set once per request from those rows.
- [X] T034 [US2] Render Trending in `web/src/components/PlayerSpotlight.tsx`. Heading "Trending", with a subline "Most added across all Sleeper leagues, last {lookbackHours} hours" and a visible credit "Trending data from Sleeper" (FR-017). Each row shows rank, the shared row component, "+{addCount} adds", and the outcome:
  - `PLAYED` → points beside the name, with opponent;
  - `DID_NOT_PLAY` → "Did not play {period label}";
  - `BYE` → "Bye in week N";
  - `NO_PERIOD` → no figure at all.

  When `stale`, show "Updated {relative age} ago". For `NEVER_FETCHED`, show "Trending hasn't loaded yet; it updates when the league refreshes." When `omittedUnknownPlayers > 0`, show "{n} more players we don't have details for."
- [X] T035 [P] [US2] Component tests in `web/src/components/PlayerSpotlight.test.tsx`: a `DID_NOT_PLAY` entry shows the words and **no** "0" figure (SC-002); `BYE` shows the week; the Sleeper credit is present; `stale:true` shows the age line; `NEVER_FETCHED` shows the sentence, not an empty list.

**Checkpoint**: quickstart V1 passes against the real refresh, and NFL 2026 shows Trending with real week-3 outcomes.

---

## Phase 6: User Story 3 — Rookie watch (P3)

**Goal**: first-season players' best scores in the period, rostered or not, with owner or "free agent".

**Independent test**: for NFL 2026 week 3, every entry has `player.years_exp = 0`, there is no team defense, and order is points descending (quickstart V2.4).

- [X] T036 [P] [US3] Preference-ordering unit test in `backend/src/test/java/com/ballknowers/draftsim/engine/PlayerSpotlightRookieTest.java`. Cover:
  - `years_exp = 0` players are included whether rostered or not;
  - `years_exp = 1` and `years_exp = null` are excluded (FR-007, which keeps defenses out with no special case, research R4);
  - order is points desc, then `playerId`; limit 10;
  - a period with rows but no rookie → `entries: [], unavailable: "NO_ROOKIE_PLAYED"`;
  - `period == null` → `NO_PERIOD`.
- [X] T037 [US3] Implement `rookieWatch` in `PlayerSpotlightService`, reusing the period rows already loaded for T017/T033 (NIGHT: `forDate`; WEEK: `forWeek`). Filter to players whose `Player.yearsExp()` is non-null and `== 0`, score with `GameScoringService`, attach ownership (default unrostered), and sort and limit as in T036.
- [X] T038 [US3] Render Rookie watch in `web/src/components/PlayerSpotlight.tsx`. Heading "Rookie watch" with the period label; rows use the shared row component with "Free agent here" for unrostered players. Wording: `NO_ROOKIE_PLAYED` → "No rookies played {period label}."; `NO_PERIOD` → the same pre-season sentence as T019.
- [X] T039 [P] [US3] Component test in `web/src/components/PlayerSpotlight.test.tsx`: an unrostered rookie shows "Free agent here"; `NO_ROOKIE_PLAYED` shows the sentence.

**Checkpoint**: all three sections render in both sports.

---

## Phase 7: Polish and verification (the repo's bar)

- [X] T040 Full suites: run `cd backend && ./gradlew test` and read the skip count from `backend/build/test-results/test/*.xml`, comparing against the T002 baseline; then run `cd web && npx tsc -b && npx vitest run && npm run build`. Record the exact counts in this file under `## Verification`.
- [X] T041 Bug-hunting code review of the whole diff (not a style pass), as a separate pass on the session's own model per AGENTS.md. Look for: `Map.of` with nullable values; `points` leaking onto a non-`PLAYED` entry; the period week drifting from `weekly-report/0`; a sport-name comparison; the trending step able to fail a refresh; `api.ts` fields out of step with the Java records. Fix findings in a Sonnet pass, and re-run T040.
- [X] T042 Live verification per quickstart.md, against this worktree's backend on 8081 (`draft-sim-api-8081`). **Confirm the bootRun classpath is this worktree first** (memory: worktree preview can serve `main`). Run V1, V2, V3, V4 and V5 with real requests and `X-Sleeper-User` headers, then the browser pass (NFL 2026 and NBA 2026 homes, light and dark, 375 px), and screenshot each. Record the results in plain words in `## Verification`, keeping what was **run** apart from what was **not**.
- [X] T043 Record that quickstart **V6 (first NBA night, cutoff check) is owed** and cannot run before 2026-10-21. Write it into `HANDOFF.md` as an open item with the exact steps, and add a dated "owed" note under research.md R6. Do not mark the cutoff verified.
- [X] T044 Update docs: in `HANDOFF.md`, add a spec 014 status section that separates verified from assumed; in `README.md`, add the league home sections, if README lists home blocks; record any wrong guess found during verification as an "amended after verification" note in spec.md and research.md, never a silent rewrite.

---

## Dependencies & execution order

- **Phase 1 → Phase 2**: T001 must precede T024 (the migration number). T002 must precede T040 (the baseline).
- **Phase 2 blocks every story.** Inside it:
  - T003 → T006(d) → T008;
  - T004 → T006;
  - T006 → T007 → T008;
  - T006 → T009 → T010;
  - T009 → T012 → T013 → T014 → T015;
  - T011 can start as soon as T006/T009/T013's files exist.
- **US1** (T016–T020) depends only on Phase 2.
- **US4** (T021–T023) depends only on Phase 2. It is independent of US1, and the two can run in parallel.
- **US2**: T024 → T026 → T027; T025 → T028; T026 + T028 → T029 → T031; T033 depends on T026 + T032 (the test is written first) and uses the period rows from Phase 2. T034–T035 come after T033.
- **US3** (T036–T039) depends on Phase 2. It reuses the period-row loading from T017/T033 if those landed first; otherwise it loads the rows itself and the later task deduplicates.
- **Phase 7** comes after every story you intend to ship.

### Story completion order

```text
Phase 1 ─► Phase 2 ─┬─► US1 (P1) ─┐
                    ├─► US4 (P2) ─┤
                    ├─► US2 (P2) ─┼─► Phase 7
                    └─► US3 (P3) ─┘
```

## Parallel examples

- **Phase 2**: T004 (ownership) ∥ T003 (week extraction); after T006/T009: T010 ∥ T011 ∥ T012.
- **US1**: T016 (test) ∥ T020 (component test); then T017 → T018 → T019.
- **US2**: T024 ∥ T025 ∥ T030's test scaffolding; then T026 → T027 ∥ T028; T032 ∥ T035.
- **Across stories**: once Phase 2 is done, US1, US4 and US3 touch different methods of `PlayerSpotlightService` and different sub-sections of `PlayerSpotlight.tsx`, so they can run in parallel only with care. They share two files, so serialize the edits to those files even when the stories run side by side.

## Implementation strategy

1. **MVP that a reader can see today = Phase 2 + US4.** NFL 2026 is in season, so this is the first thing that is live-verifiable. US1 is the P1 story, but until 2026-10-20 its only visible output is the pre-season sentence.
2. Add **US1**, verified against seeded data plus the pre-season state. Its live check (V6) is owed.
3. Add **US2**, the only new data source and the only new migration. Verify V1 end to end, including the failure-isolation check.
4. Add **US3**, which is small once the period rows are loaded.
5. Phase 7: review → fix → live verification → docs. Ask before committing (hard rule); the concurrent-sessions memory says commit early, on this branch only.

## Baseline

Recorded 2026-10-01 (T002), worktree at `origin/main` 2dd47f2, before any spec 014 code:
`./gradlew test` → **965 tests, 1 failed, 0 skipped** (Postgres on 5433 reachable). The one failure is
`RefreshControllerIT.aChainRunShowsRunningAtTheTopEvenWhenTheShownSeasonIsLoadedComplete`, already known
to fail on base (memory: "RefreshControllerIT fails on base too"). It is not caused by this feature.

## Verification

Recorded 2026-10-01. **Run** and **not run** are kept apart.

**Run:**
- **T040, suites** (after the fix pass):
  - backend `./gradlew test`: **1,052 tests, 1 failure, 0 skipped**. The failure is the flaky
    `RefreshControllerIT.aChainRunShowsRunningAtTheTopEvenWhenTheShownSeasonIsLoadedComplete`. It failed on the
    untouched base (see Baseline), passed in another full run, and passed 3 out of 3 alone.
  - web: `tsc -b` clean, vitest **77 files / 950 tests passed**, `npm run build` OK.
- **T041, bug-hunting review** (cold read on the session's model): no blockers. Fixed:
  - (1) NBA off-nights labelled "Bye", and (2) an unfinished football week labelled "Bye in Week 1": `BYE` became
    `NO_GAME`, emitted only for a settled period;
  - (3) "still being played" copy stated a guess as fact;
  - (4) `omittedUnknownPlayers` overcounted;
  - (5) the football label was not tied to the data it sits over;
  - (6) a season-source failure 500'd the route;
  - (7) the trending read was not a consistent snapshot;
  - (8) a full player-table load on every request.
- **T042, live**, against this worktree's backend on 8081 (classpath confirmed as this worktree):
  - **V2** on "(Foot) Ball Knowers" 2026:
    - spotlight week == weekly-report/0 week (both 2);
    - `topOfNight` absent;
    - Rookie watch is all `years_exp = 0`, with no defense;
    - trending points equal stored Sleeper points (Kamara 7.2, Mumpfield 2.2);
    - a non-game has no `points` key.
  - **V1** (steps 1–4): a real refresh stored 25 rows per sport; order is identical to Sleeper's live list;
    back-dated 25 h it is flagged `stale: true` (then restored).
  - **V3** NBA 2026: `NO_GAMES_YET`, `seasonStartDate` 2026-10-20, every trending entry `NO_PERIOD` and unrostered.
  - **V4** NBA 2025: `PAST_SEASON`.
  - **V5**: 404 with no identity and with a foreign identity.
  - **Browser**: NFL home at desktop width and at 375 px, before and after the fix pass. No sideways scroll after
    either. The duplicate week label, the unseparated add counts and the crowded phone row were all found and fixed.
- **T043/T044**: V6 is recorded as owed (research R6, HANDOFF.md). HANDOFF.md has a spec 014 section, and
  lessons.md #29 is added. README is unchanged, because it does not list the home page's blocks.

**Not run:**
- **V6**: the first NBA night, which can't run before 2026-10-21.
- **V1.5** with a really dead upstream: covered by `TrendingRefreshIsolationIT` only.
- **V5.2**: forcing the spotlight endpoint to 500 in a live browser. Covered by `SpotlightArea`'s code path and
  component tests only.
- **"Yours" pill, live**: the browser identity isn't a member there. Covered by ITs and component tests.
- **Light theme**: not applicable; the app is dark-only.
- **Production / Railway**: not deployed.

### Design-review pass (2026-10-01, after the T042 verification)

A design-engineer review compared the block with FantasyPros, ffwrapped, DraftSharks, Sleeper and Yahoo. It
measured the block at 2,534 px tall under a 492 px dashboard. Allan's decisions:
- build the layout and row changes;
- score Trending and Rookie watch against the newest complete week;
- drop K and DEF from Rookie watch.

Also built in this pass: option A, the preseason wording ("No regular-season games yet … preseason games aren't
counted here"). See research R7 "amended". Contract changes are in "Amended after design review".

**Run, live on 8081** (classpath confirmed as this worktree):
- **Week:** the spotlight's WEEK period is week 3 (`weekFinal: true`) while `weekly-report/0` is week 2, which
  is the intended split.
- **Rookie watch:** positions are TE, RB and WR only. Sadiq's 23.5 equals Sleeper's stored 23.5 in "fantasy😍".
- **Fields:** `ownership.avatarId` and the weekly `topPerformers` `team`/`opponent`/`isAway`/`avatarId` are
  present (e.g. Smith-Njigba SEA @ ARI).
- **Browser:**
  - 1366 px: three columns aligned with the dashboard (341 px each); the block is **408 px tall** (was 2,534).
  - 375 px: a Top · Trending · Rookies tablist with Trending selected, **467 px tall**, no truncated text, no
    sideways scroll.

**Suites:**
- backend 1,060 tests, 0 skipped, 1 failure. The failure is the pre-existing `RefreshControllerIT` test: it
  failed in the agent's runs and passed when re-run here. It waits 5 s for a refresh thread to start (line 254),
  before any spec 014 code runs, and it also fails on base. Probably load-sensitive; **inferred, not proven**.
- web: 77 files / 957 tests, `tsc` clean, build OK.

**Not run:** the NBA league home in the browser (the profile's identity isn't a member there); the V6 first-night
check, still owed.
