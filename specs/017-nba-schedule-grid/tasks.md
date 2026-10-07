---
description: "Task list for spec 017: NBA schedule grid and next opponent for basketball"
---

# Tasks: NBA schedule grid and next opponent for basketball

**Input**: `specs/017-nba-schedule-grid/`: plan.md (incl. "Amended after review"),
spec.md, research.md (R1–R9), data-model.md, contracts/api.md, quickstart.md (V1–V8),
plan-review.md (F1–F11, N1–N12).

**Tests**: requested. The plan's build order is fixtures and failing tests first
(plan.md "Build order" step 1), and this repo's bar adds live verification on top.

**Deadline**: US1 and US2 deployed to production before **2026-10-20** (NBA tip-off).

**Repo rules that apply to every task** (AGENTS.md):
- Code tasks run on Sonnet subagents. The parent session reads each diff before
  ticking it off.
- Migrations are append-only.
- `web/src/api.ts` mirrors the Java records in the same change.
- No `Map.of` with nullable values.
- Never commit without asking. Concurrent sessions share this tree, so diff before
  committing.

Paths: backend `backend/src/main/java/com/ballknowers/draftsim/` (abbreviated
`B/`), backend tests `backend/src/test/java/com/ballknowers/draftsim/` (`T/`),
web `web/src/`.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel (different files, no dependency on an unfinished task)
- **[Story]**: US1 grid, US2 playoff weeks, US3 next opponent

---

## Phase 1: Setup

**Purpose**: real-data fixtures, pre-build checks, and the one measurement that gates
US3's football half.

- [X] T001 Re-check the highest migration on the branch (`ls backend/src/main/resources/db/migration | sort -V | tail -1`) and in other worktrees/branches (`git branch -a`, `git worktree list`). It was V26 on 2026-10-05. If V27 is taken, use the next free number everywhere below and note it in plan.md
- [X] T002 [P] Save the trimmed real 2025 schedule as `backend/src/test/resources/sleeper/nba-schedule-2025.json`. Fetch `https://api.sleeper.app/schedule/nba/regular/2025` and keep only `game_id, week, date, status, home.team, away.team`, with **`home`/`away` kept as nested `{"team": ...}` objects** (N1). Expect 1,235 rows, ~158 KB
- [X] T003 [P] Save the trimmed real 2026 schedule as `backend/src/test/resources/sleeper/nba-schedule-2026.json` the same way from `/schedule/nba/regular/2026`. Expect 1,200 rows, ~153 KB. If Sleeper's numbers have changed since 2026-10-05, record the new counts in research.md R1 as an amended note rather than editing the measured ones
- [ ] T004 F7 boundary measurement (gates T042–T044 only). On **2026-10-06 and 2026-10-07**, several times each, run `curl -s https://api.sleeper.app/v1/league/1346366555759341568`. Record `settings.leg` and `settings.last_scored_leg` with timestamps in `specs/017-nba-schedule-grid/research.md` R6, under the "Open after review (F7)" note. Then write the rule for whichever order was seen. If `last_scored_leg` can equal `leg` before `leg` advances, the rule is "next week = `leg + 1` when week `leg` is scored". Add that row to the state table in `data-model.md`
  - *(2026-10-07)* Not done: the 10-06 readings weren't taken, and the two on 10-07 came after the boundary (`leg 5`, `lsl 4`, research R6). Redo Tue 2026-10-13.

---

## Phase 2: Foundational (blocking)

**Purpose**: one parser, the stored schedule, and the league-settings reads that every
story uses.

**⚠️ No story work starts until this phase is done.**

### Tests first (must fail before T009–T016)

- [X] T005 [P] Write `T/ingest/SportScheduleTest.java`, covering:
  - `parse` with both home/away shapes: nested `{team}` from the 2025 fixture, and a bare string
  - `games()` de-duplicates a repeated `game_id` (last wins, F3)
  - `counts(status)` is false for `"postponed"` and `"canceled"`, and true for `"complete"`, `"pre_game"`, `"in_game"` and `null`. A null status counts (data-model)
- [X] T006 [P] Write `T/store/PlayoffWindowTest.java` for `LeagueRepository.PlayoffFormat.lastPlayoffWeek()` / `playoffWindowRefusal()`:
  - (start 20, teams 6, type 0) → 22
  - (19, 6, 0) → 21
  - (22, 6, 0) → 24
  - (15, 6, 0) → 17
  - (15, 4, 0) → 16
  - (15, 2, 0) → 15
  - (15, 6, **null**) → empty, `ROUND_TYPE_UNKNOWN`
  - (15, 6, 1) → empty, `ROUND_TYPE_UNSUPPORTED` (F5, West Coast FF's real settings)
  - (15, 6, 2) → `ROUND_TYPE_UNSUPPORTED`
  - (0, 6, 0) → `NO_START`
  - (15, 1, 0) → `TOO_FEW_TEAMS`
- [X] T007 [P] Write `T/store/SportScheduleRepositoryIT.java` (needs Postgres on 5433; it **skips silently** without it, so check the skip count):
  - `replaceSeason` stores N rows and reads back one row's `game_date` (as `LocalDate`) and `fetched_at` (as `OffsetDateTime`) by value (N2, bug class #3)
  - a second `replaceSeason` with a smaller list leaves only the new rows
  - an empty list is a no-op and keeps the old rows (FR-002)
  - a duplicate `game_id` in the input doesn't throw (`on conflict do update`)
  - a failure mid-insert rolls back to the old rows

### Implementation

- [X] T008 Lift `PlayerGameIngestService.Schedule` to `B/ingest/SportSchedule.java`, a `public final class` with `public record Game(String gameId, int week, LocalDate date, String status, String home, String away)` and `public static SportSchedule parse(List<Map<String,Object>>)`. The logic is unchanged.
  - Add `public List<Game> games()` built from the de-duplicated `byId` map, **not** raw rows (F3).
  - Add `public static boolean counts(String status)`, which returns `!"postponed".equals(status) && !"canceled".equals(status)`.
  - Keep `SETTLED`, `lastStartedWeek`, `isAway`, `teamOf`, `hasCompleteGame`, `teamPlayed` and `isFinal` with identical behavior.
  - Share the `"postponed"`/`"canceled"` string constants between `SETTLED` and `counts`.
  - Update `B/ingest/PlayerGameIngestService.java` to use it.
- [X] T009 Update `T/ingest/PlayerGameWeekIngestTest.java` **mechanically only** (F4): `PlayerGameIngestService.Schedule` → `SportSchedule` at about :438, :450 and :510–513. The diff must show **no changed assertion**. Run `cd backend && ./gradlew test --tests '*PlayerGameWeekIngestTest' --tests '*SportScheduleTest'` and confirm both are green
- [X] T010 Create `backend/src/main/resources/db/migration/V27__sport_schedule.sql` (number from T001). Use a header comment in the style of V26 (spec reference, "append-only, re-checked V26 was highest", why no CHECK on status).
  - Table `sport_schedule`: `sport text not null`, `season int not null`, `game_id text not null`, `week int not null`, `game_date date null`, `home text null`, `away text null`, `status text null`, `fetched_at timestamptz not null`.
  - `primary key (sport, season, game_id)`, plus an index on `(sport, season, week)`.
  - **No CHECK on `status`.** Sleeper's vocabulary isn't documented, and an unknown value must be stored, not rejected.
  - No FK to `league`.
- [X] T011 Create `B/store/SportScheduleRepository.java` (`JdbcClient`, the style of `B/store/SportTrendingRepository.java`):
  - `@Transactional void replaceSeason(String sport, int season, List<SportSchedule.Game> games, OffsetDateTime fetchedAt)`: delete the season's rows, then insert each game `on conflict (sport, season, game_id) do update`. Return early with a `log.warn` when `games` is empty (FR-002). Bind `game_date` as `LocalDate` and `fetched_at` as `OffsetDateTime` via `setObject`/params, as `SportTrendingRepository` does (N2).
  - `List<SportSchedule.Game> forSeason(String sport, int season)` ordered by week, game_date, game_id.
  - `Optional<OffsetDateTime> fetchedAt(String sport, int season)` (max).

  Make T007 pass.
- [X] T012 Wire the store into `B/ingest/PlayerGameIngestService.java` `doRefresh` (F3):
  - Call `scheduleRepository.replaceSeason(code, season, schedule.games(), OffsetDateTime.ofInstant(now, ZoneOffset.UTC))` **after the week loop and the no-entry pass**, not at step 1.
  - Wrap it in try/catch: log a warning with the exception and set a flag. Never rethrow.
  - Add `boolean scheduleStoreFailed` as the last component of `Result`, and update every `new Result(...)` call, e.g. `ingest()`'s `new Result(0, 0, 0, 0, 0)` and any in tests.
  - Inject `SportScheduleRepository` through the constructor, and add the mock argument to `PlayerGameWeekIngestTest`'s constructor call at about :119. That's mechanical (F4).
- [X] T013 Update `B/refresh/LeagueRefreshService.java` `refreshChain`: sum `scheduleStoreFailed` across the season loop. After the loop **and after** `trendingRefresh.refreshIfStale(...)`, throw `IllegalStateException("schedule store failed")` when any failed, just like the existing `weeksFailed > 0` throw. The season is recorded FAILED, but per-game data and trending have already run (FR-014)
- [X] T014 Add tests in `T/ingest/PlayerGameWeekIngestTest.java` (new methods only):
  - (a) a refresh stores the parsed schedule after the weeks (verify the call order with Mockito `InOrder`: week fetches before `replaceSeason`)
  - (b) a stats client returning `[]` for the schedule doesn't call `replaceSeason` with games, or calls it with an empty list that's a no-op
  - (c) a `replaceSeason` that throws still stores the per-game rows and returns `scheduleStoreFailed = true`
- [X] T015 Extend `B/store/LeagueRepository.java`:
  - Add `Integer playoffRoundType` (**nullable, read uncoalesced** from `settings_json->>'playoff_round_type'`; research R5, N11) to `PlayoffFormat` and to the `playoffFormat(leagueId)` query.
  - Add `OptionalInt lastPlayoffWeek()` = `start + ceil(log2(teams)) - 1`, present iff `playoffRoundType == 0 && playoffWeekStart >= 2 && playoffTeams >= 2`.
  - Add `Optional<String> playoffWindowRefusal()`, returning codes `NO_START`/`TOO_FEW_TEAMS`/`ROUND_TYPE_UNKNOWN`/`ROUND_TYPE_UNSUPPORTED`. **Codes, not sentences:** `store/` isn't scanned by `NoIngestHintsInMessagesTest` (N3).
  - Add `OptionalInt currentLeg(long leagueId)` reading `settings_json->>'leg'`, empty when absent. **Never default to 1** (research R6).

  Make T006 pass.
- [X] T016 Update `T/engine/PlayoffOddsServiceTest.java` mechanically (F4): add the new `playoffRoundType` argument to the `PlayoffFormat` constructors at about :51, :116 and :128, using `0` to match those leagues' real format. No assertion changes. Run `./gradlew test` and confirm the full backend suite is green, reading the **skip count**

**Checkpoint**: the schedule is stored on every refresh, the league reads exist, and the
existing suite is unchanged in behavior.

---

## Phase 3: User Story 1: games per team per week (P1) 🎯 MVP

**Goal**: a basketball league has a Schedule grid page showing each NBA team's counted
games per week from the league's current week to its last week, sortable by next N
weeks.

**Independent test**: `/leagues/1339351318115946496/schedule`. The week-1 column shows
5 teams at 2, 24 at 3 and 1 at 4 (PHI), and "Next 1 week" puts PHI first.

### Tests first (must fail before T020)

- [X] T017 [P] [US1] Write `T/engine/ScheduleGridServiceTest.java` for the pure core (no DB), loading both fixtures from `src/test/resources/sleeper/`:
  - **SC-002**: 2026 week 1 has 5 teams at 2, 24 at 3, 1 at 4, and the 4 is PHI. Every team's `seasonTotal` is 80, and there are 30 teams.
  - **SC-003**: 2025 has 30 teams, `seasonTotal` 82 for all but NYK and SAS (83), and no STP/STR row. `excluded` = postponed 3, canceled 1.
  - `teams[i].games.length == weeks.length`, and `weeks` covers 1–25 in ascending order.
  - Teams are ordered by code ascending, so the server encodes no ranking.
  - **F2**: a 2025 league with `leg` 21, status complete and playoff (19, 6, 0) gives `lastLeagueWeek` 21 and `seasonOver` true. A 2026 league with `leg` 1 and (20, 6, 0) gives `lastLeagueWeek` 22 and `seasonOver` false. A league with no `leg` gives `currentWeek` null.
  - The football league case is unavailable, with the C1 reason. An empty stored schedule is unavailable with the "hasn't been loaded yet" reason, and neither reason contains `/api/`.
- [X] T018 [P] [US1] Write a test in `T/engine/ScheduleGridServiceTest.java` for **F1**: a two-season chain (2026 newest, no scored weeks; 2025 with scored weeks) where the service is called with the 2026 sleeper id. The result has `season` 2026 and uses 2026's `leg` and schedule. Mock `LeagueRepository`, and make sure `LeagueSeasonResolver` isn't a dependency at all
- [X] T019 [P] [US1] Write `web/src/pages/ScheduleGrid.test.tsx` (Vitest + Testing Library, in the style of `web/src/pages/ExpectedWins.test.tsx`), with a mocked `getLeagueSchedule` response built from three teams and four weeks:
  - (a) cells print the count, and the tint class tracks it
  - (b) **ordering (bug class 1):** "Next 1 week" ranks a 4-game team above a 3-game team above a 2-game team, and ties go by team code; "Next 2 weeks" ranks by the 2-week sum
  - (c) columns run from `currentWeek` to `lastLeagueWeek` and are looked up **by week number** (N5), tested with a response whose `weeks` don't start at 1
  - (d) `seasonOver` shows the season-over line and no upcoming columns
  - (e) `available: false` shows `reason` and no table
  - (f) **F8:** bumping the league data version refetches
  - (g) the current-week column header says it includes games already played (N8)

### Implementation

- [X] T020 [US1] Create `B/engine/ScheduleGridService.java`.
  - Public `Optional<Result> forLeague(String sleeperLeagueId)` reads the league row with `leagues.bySleeperId` (**never `LeagueSeasonResolver`**, F1), then `currentLeg`, `playoffFormat` and `sportSchedule.forSeason(sport.code(), season)`.
  - A package-private static pure core takes those inputs and returns the result.
  - Records mirror contracts/api.md C1 exactly: `Result(String sport, int season, boolean available, String reason, OffsetDateTime fetchedAt, Integer currentWeek, Integer lastLeagueWeek, boolean seasonOver, List<Week> weeks, Playoff playoff, List<Team> teams, Excluded excluded)`, plus `Week(int week, LocalDate firstDate, LocalDate lastDate)`, `Team(String team, int[] games, int seasonTotal)`, `Playoff(Integer startWeek, Integer endWeek, String reason)` and `Excluded(int postponed, int canceled)`.
  - Count with `SportSchedule.counts` only (FR-004). A team row exists iff it has ≥ 1 counted game (R4). Week dates come from counted games only.
  - `lastLeagueWeek` = `lastPlayoffWeek()`, else `playoff_week_start − 1` when start ≥ 2, else the last stored week. `seasonOver` = `LeagueRow.isComplete(status)` or `currentWeek > lastLeagueWeek` (data-model "League span").
  - Unavailable reasons are copied verbatim from C1: football league → "The schedule grid is for basketball leagues: an NFL team plays once a week.". Nothing stored → "The {season} NBA schedule hasn't been loaded yet. It loads with the league's next refresh.".
  - Fill `playoff` here with `reason` null when `endWeek` is present. Refusal sentences come in T029 (US2), so leave `reason` as the raw code until then.

  Make T017 and T018 pass.
- [X] T021 [US1] Create `B/api/ScheduleController.java`: `@GetMapping("/api/leagues/{sleeperId}/schedule")` with `@RequestHeader(value = "X-Sleeper-User", required = false)`. If `membership.visibleLeague(sleeperId, sleeperUserId).isEmpty()`, return 404, as in `B/api/ExpectedWinsController.java`. Otherwise return `ResponseEntity.ok(result)`, letting Jackson serialize the records (no `Map.of`; several fields are nullable). Confirm `OffsetDateTime`/`LocalDate` serialize as ISO strings, matching other endpoints. Add a MockMvc test in `T/api/` (or extend `AccessControlMvcIT`) asserting 404 with no header
- [X] T022 [P] [US1] Add the C1 TypeScript mirror to `web/src/api.ts` **field for field**: `ScheduleWeek`, `ScheduleTeam`, `PlayoffWindow`, `LeagueSchedule` (including `lastLeagueWeek: number | null` and `seasonOver: boolean`). Also add `export const getLeagueSchedule = (id: string) => apiFetch(`/api/leagues/${id}/schedule`).then(json<LeagueSchedule>)`
- [X] T023 [P] [US1] Add the destination in `web/src/destinations.ts`:
  - Add `'schedule'` to the `DestinationKey` union (N4).
  - Add a `LEAGUE_DESTINATIONS` row: `key: 'schedule'`, `group: 'thisWeek'`, `glyph` (pick one not in use), `sports: ['nba']` with a comment saying why ("An NFL team plays once a week; the grid is a basketball tool. See specs/017."), `label: 'Schedule grid'`, `href: (ctx) => `/leagues/${ctx.season.sleeperLeagueId}/schedule`` (one season, spec 011's rule; N10), `match: /^\/leagues\/([^/]+)\/schedule\/?$/`, `idKind: 'league'`, `requiresStatus: null`, `isAction: false`.
  - Extend `web/src/destinations.test.ts`: NBA `destinationsFor` includes `schedule`, NFL doesn't, and `destinationFromPath('/leagues/x/schedule') === 'schedule'`.
- [X] T024 [US1] Add `<Route path="/leagues/:sleeperLeagueId/schedule" element={<KeyedByLeague page={ScheduleGrid} />} />` to `web/src/App.tsx` beside the other league routes (around :121)
- [X] T025 [US1] Create `web/src/pages/ScheduleGrid.tsx`.
  - Read `sleeperLeagueId` from the route and `version = useLeagueDataVersion(id)`. Fetch with `useBlock(() => getLeagueSchedule(id), [id, version])` (F8).
  - Render a page header in the existing league-page shell style, and the freshness line "Schedule from Sleeper, fetched {relative time}. The NBA adds and moves games during the season." (no Cup explanation, R1 is inferred). Show `excluded` counts when non-zero ("3 postponed and 1 canceled games aren't counted").
  - Sort control: "Next 1 / 2 / 3 / 4 weeks" (sum over `currentWeek` … `min(currentWeek+N−1, lastLeagueWeek)`, most first, ties by team code). The 1–4 range is a UI choice; comment that.
  - Table: rows are teams with a sticky first column; columns are weeks `currentWeek` … `lastLeagueWeek`, **looked up by `week` number** (N5). Each cell prints the count and gets a tint class by count, one encoding plus the number (memory: label the axis, spell out the number). Header cells show the week number and date range, and the current week is labelled "this week (incl. played)" (N8). Weeks inside `playoff.startWeek…endWeek` get a header marker.
  - `seasonOver` → the line "This league's season is over." and no upcoming columns. `available: false` → `reason` only.
  - The table scrolls horizontally **inside its container**, and the page itself never does (375px).
  - Card/dark styling per memory (tinted pills, hover depth). It's a grid, so it's a table, not cards.

  Make T019 pass, then run `cd web && npx tsc -b && npm test`.
- [X] T026 [US1] Backend live check, quickstart V2 (with the F11 header/poll steps), V3 and V5's week-1 part, plus V6 (league weeks vs schedule weeks, F6). Record outputs, run or not run, in `specs/017-nba-schedule-grid/verification.md`
- [X] T027 [US1] Browser check, quickstart V8.1–V8.4 (rail link NBA-only, 30 rows, PHI first, first-visit fill without reload, 375px with no page scroll, both themes). Screenshots and notes go in `verification.md`

**Checkpoint**: US1 is shippable alone. If time runs short, deploy here.

---

## Phase 4: User Story 2: games during the league's playoff weeks (P2)

**Goal**: a "Playoff weeks" view of the same grid, limited to the league's playoff window,
with a per-team total. Unsupported formats are refused, never guessed.

**Independent test**: NBA 2026 shows weeks 20–22 with totals, and NBA 2025 shows 19–21.

### Tests first

- [X] T028 [P] [US2] Extend `T/engine/ScheduleGridServiceTest.java`:
  - `playoff` = {20, 22, null} for 2026 (20, 6, 0), and {19, 21, null} for 2025
  - for (15, 6, 1), `endWeek` null and `reason` is the ROUND_TYPE_UNSUPPORTED sentence
  - for a null round type, the ROUND_TYPE_UNKNOWN sentence
  - the invariant `reason == null ⇔ endWeek != null` holds
  - no reason contains `/api/`
- [X] T029 [P] [US2] Extend `web/src/pages/ScheduleGrid.test.tsx`:
  - the "Playoff weeks" view shows only `playoff.startWeek…endWeek` columns and a per-team total, sorted by that total (ties by team code)
  - `playoff.reason` set → the reason and no table
  - a playoff week beyond the last entry of `weeks` renders "not in Sleeper's schedule", not 0 (N6)

### Implementation

- [X] T030 [US2] In `B/engine/ScheduleGridService.java`, turn `playoffWindowRefusal()` codes into sentences (in `engine/`, so `NoIngestHintsInMessagesTest` scans them; N3):
  - `NO_START` → "This league has no playoff start week in its settings."
  - `TOO_FEW_TEAMS` → "This league's settings don't have enough playoff teams to make a bracket."
  - `ROUND_TYPE_UNKNOWN` → "This league's settings don't say how long each playoff round is."
  - `ROUND_TYPE_UNSUPPORTED` → "This league's playoff rounds aren't one week each, and the grid only works out one-week rounds so far."

  Make T028 pass.
- [X] T031 [US2] Add the "Playoff weeks" sort/view option to `web/src/pages/ScheduleGrid.tsx`: columns `playoff.startWeek…playoff.endWeek` looked up by week, a "Playoff total" column, and rows sorted by it. Show `playoff.reason` when `endWeek` is null. Mark missing weeks "not in Sleeper's schedule" (N6). Make T029 pass
- [X] T032 [US2] Live check, quickstart V4 (`ADMIN_TOKEN` set at bootRun, F11; ingest 2025 via the admin route; expect SC-003 totals, `seasonOver: true`, `lastLeagueWeek: 21`) and V5's playoff part (20–22 / 19–21). Then browser V8.2's playoff view. Record in `verification.md`

**Checkpoint**: US1 + US2 are shippable together. Deploy both Railway services and run
V8.1–2 in production before 2026-10-20 (T049).

---

## Phase 5: User Story 3: basketball league home shows the next opponent (P3)

**Goal**: league home names your opponent for the league's current week (`leg`) from
stored pairings, for basketball now and for football once T004 is recorded.

**Independent test**: for NBA 2026 today, the home block shows "Pairings for week 1
aren't out yet…". After the 2026-10-10 draft, it names the same partner as Sleeper's
`/v1/league/1339351318115946496/matchups/1`.

### Tests first

- [X] T033 [P] [US3] Write `T/engine/NextMatchupServiceTest.java` (Mockito, no DB) with one test per data-model state-table row:
  - no `leg` → unavailable, `week` null, "Sleeper hasn't said which week this league is in yet."
  - `leg ≥ playoff_week_start`, or league complete → "The regular season is over."
  - no fixtures for `leg` → "Pairings for week {leg} aren't out yet. Sleeper publishes them shortly before the week starts."
  - caller has no roster → available, `me` null
  - **bye** (F10): caller's roster absent from the fixtures, or alone on its `matchup_id` → available, `me` set, `opponent` null
  - paired → both sides set
  - two rosters for the caller → the **lowest roster id** is used
  - names come from `LeagueMemberRepository.MemberRow.teamName` and `RosterSeasonRepository.StandingRow.managerName`/`avatarId`, sent raw and nullable
  - the invariant: `opponent` is never set without `me`
- [X] T034 [P] [US3] Add an **F1** test in `T/engine/NextMatchupServiceTest.java`: a two-season chain where 2026 has no scored weeks. Called with the 2026 id, it returns `season` 2026, `week` 1 and the "not out yet" reason, **not** 2025's "regular season is over"
- [X] T035 [P] [US3] Extend `web/src/pages/LeagueHome.test.tsx`:
  - (a) an NBA league renders NextOpponentBlock from a mocked `getNextMatchup`: opponent name, bye text, the not-available reason, and nothing when `me` is null
  - (b) the NBA block links to the schedule destination, **not** `/analysis` (F9)
  - (c) bumping the data version refetches next-matchup (F8)
  - (d) an NFL league still renders today's analysis-backed block unchanged, including the "Projected X to Y" line (the guard until T043)

### Implementation

- [X] T036 [US3] Create `B/engine/NextMatchupService.java`.
  - `Optional<Result> forLeague(String sleeperLeagueId, String sleeperUserId)` reads `leagues.bySleeperId` (**never the resolver**, F1).
  - It uses `currentLeg`, `playoffFormat`, `LeagueMatchupRepository.between(leagueId, season, leg, leg)`, `RosterSeasonRepository.forLeague(leagueId)`, `LeagueMemberRepository.forLeague(leagueId)` (team names, F10), and `ManagerRepository.idsBySleeperUserId().get(sleeperUserId)` for "me", the same route as `WeeklyReportService:213`.
  - Records mirror C2: `Result(String sport, int season, Integer week, boolean available, String reason, Side me, Side opponent)` and `Side(int rosterId, String teamName, String username, String avatarId)`.
  - Reasons are copied verbatim from data-model's state table.

  Make T033 and T034 pass.
- [X] T037 [US3] Create `B/api/NextMatchupController.java`: `@GetMapping("/api/leagues/{sleeperId}/next-matchup")` with the `X-Sleeper-User` header, a `visibleLeague` 404 gate, and records returned as-is. Add a 404-without-header MockMvc assertion next to T021's
- [X] T038 [P] [US3] Add the C2 TypeScript mirror to `web/src/api.ts` field for field: `MatchupSide`, `NextMatchup`, and `export const getNextMatchup = (id: string) => apiFetch(`/api/leagues/${id}/next-matchup`).then(json<NextMatchup>)`
- [X] T039 [US3] Update `web/src/pages/LeagueHome.tsx`.
  - For **basketball**, add `const nextMatchup = useBlock(id && sport === 'nba' ? () => getNextMatchup(id) : null, [id, sport, version])` (F8).
  - Render a NextOpponentBlock variant from it: "Week {week} against {name}" using the existing client-side fallback (team name → username → "roster N", `LeagueHome.tsx:409-410`), "You have a bye in week {week}." when `opponent` is null and `me` is set, the `reason` when unavailable, and nothing when `me` is null. There's no projected line for basketball.
  - **Link (F9):** show "Team strength" → `/analysis` only when `analysisOffered(sport)`. Otherwise link to the `schedule` destination found through `destinationsFor`/`LEAGUE_DESTINATIONS`, without restating the sport rule.
  - **Football is unchanged in this task** (F7 gate).
  - Update the file's header comment block (around :40–56) to say where each sport's next opponent comes from.

  Make T035 pass.
- [X] T040 [US3] Live check, quickstart V7: NBA 2026 → `season` 2026, week 1, "not out yet"; NFL endpoint answer vs Sleeper's matchups for `leg`; 404 with no header. Then browser V8.5 for NBA (block + Schedule link). Record in `verification.md`
- [ ] T041 [US3] **Dated post-draft check (between 2026-10-11 and 10-19)**, quickstart V7 (F11): after a visit refresh of NBA 2026, `select week, count(*) from league_matchup where league_id = 210 and week = 1` is non-empty (check the real column names), and next-matchup names the same partner as `https://api.sleeper.app/v1/league/1339351318115946496/matchups/1` for the reader's roster. If they disagree, check N12 (stale pre-paired weeks) first. Record in `verification.md`

### Football switch (gated on T004)

- [ ] T042 [US3] **Only after T004 is recorded:** if T004 found that `last_scored_leg` can reach `leg` before `leg` advances, add that rule to `B/engine/NextMatchupService.java` (e.g. "if week `leg` is already scored, next = `leg + 1`") with a new state-table row in data-model.md and a test in `T/engine/NextMatchupServiceTest.java`. If T004 found `leg` always moves first, record "no extra rule needed" in research R6 instead
- [ ] T043 [US3] **After T042:** switch football in `web/src/pages/LeagueHome.tsx` to `getNextMatchup` too (drop the `sport === 'nba'` condition from the T039 block). Keep `getLeagueAnalysis` for football only to show "Projected {mine} to {theirs}. A projection, not a result." **only when `analysis.matchups.week === nextMatchup.week`** (FR-010). Replace T035(d) with a test for agreeing weeks (line shown) and differing weeks (line hidden, opponent still shown)
- [ ] T044 [US3] SC-005 check after T043: NFL league home's opponent before vs after the change on a mid-week day **and on a Tuesday** (F7). Take a screenshot before, from today's block. Record in `verification.md`

**Checkpoint**: all three stories work. Football's switch is done or explicitly deferred
with T004's numbers.

---

## Phase 6: Polish & cross-cutting

- [X] T045 [P] Run `T/api/NoIngestHintsInMessagesTest.java` and confirm it covers `ScheduleGridService`, `NextMatchupService` and the two controllers (they're in `engine/` and `api/`). Grep `B/store/` for any sentence that ended up there, which would be N3
- [X] T046 [P] Add a "Status update" for spec 017 to `HANDOFF.md`, and the Phase 1 status to `claude/competitor-gap-roadmap.md`, each saying what is verified vs not, in those words. The design-doc amendment is already in `78d86ad`
- [X] T047 Bug-hunting code review of the full diff (`/code-review`, a separate pass, not by the agent that built it). Watch for bug classes #1 (sort direction), #2/#3 (run the SQL and binds for real), #6 (browser path) and two implementations of one rule (`counts` vs `SETTLED`, `leg` vs the analysis week). Record findings and fixes in `specs/017-nba-schedule-grid/code-review.md`
- [X] T048 Full live verification, quickstart V1–V8, on a restarted `bootRun` (no hot reload) and a hard-refreshed vite tab. Each step is recorded **run (with output) or not run (and why)** in `verification.md`. Read the backend suite's skip count
- [ ] T049 *(amended after code review R1)* After deploy, backfill finished NBA seasons once with the admin route: `POST /api/ingest/player-games/1229352720222134272?season=2025` and `…/1141438340626231296?season=2024` (`X-Admin-Token`). Completed seasons are never refreshed, so without this their grid says "wasn't saved". Deploy **both** Railway services (backend and web deploy independently and have drifted before), after asking Allan. Then repeat V8.1–2 on production (ballknowers.co) **before 2026-10-20**. Record in `verification.md`
  - *(2026-10-07)* Both services confirmed on the 017 build. Backfill ran: 2025 correct; 2024 showed CHK/SHQ (the All-Star final) as two extra teams, fixed by the exhibition rule (verification V9). Prod recheck after that fix deploys, and the V8.1–2 browser pass, are still owed.

---

## Dependencies & execution order

### Phases

- **Phase 1**: T001 first. T002/T003 run in parallel. T004 runs on its own calendar (10-06/10-07) and blocks only T042–T044.
- **Phase 2**: T005–T007 (tests) can run in parallel, then T008 → T009, T010 → T011, then T012 → T013 → T014. T015 → T016 can run alongside T008–T014 (a different file).
- **US1 (Phase 3)**: needs Phase 2. Tests T017–T019, then T020 → T021. T022/T023 can run in parallel with the backend. Then T024 → T025, then T026/T027.
- **US2 (Phase 4)**: needs T020/T025 (it extends the same service and page). It can't run in parallel with US1 tasks that touch the same two files.
- **US3 (Phase 5)**: needs only Phase 2 (`currentLeg`, `playoffFormat`), so it can run **in parallel with US1/US2**. T042–T044 also need T004.
- **Polish**: after the stories being shipped. T049 is the deadline-bound step.

### Story independence

- US1 is standalone (the MVP).
- US2 depends on US1's service and page. That's by design: it's a view of the same grid.
- US3 is independent of US1/US2, apart from T039's Schedule link needing US1's destination (T023). If US3 is built first, link to Matchups & awards until T023 lands.

### Parallel examples

```text
# Phase 2 tests together:
T005 SportScheduleTest  |  T006 PlayoffWindowTest  |  T007 SportScheduleRepositoryIT

# US1, once T020 is in:
T022 api.ts types  |  T023 destinations.ts + test     (then T024, T025)

# US3 alongside US1 (different files):
T033/T034 NextMatchupServiceTest → T036 → T037  while  T020 → T021 run
```

## Implementation strategy

1. **MVP = Phase 1–2 + US1** (T001–T027): the grid is live for week 1, which is
   where it's worth most. Deploy here if 10-20 is close.
2. **+ US2** (T028–T032): a small increment on the same page. Ship it with US1 if it's ready.
3. **+ US3 basketball** (T033–T041), useful once the 10-10 draft publishes pairings.
4. **+ US3 football switch** (T042–T044), only after T004's measurement.
5. Polish, review, verify and deploy (T045–T049). Pipeline order: build → bug-hunt
   review → live verification. A green suite alone isn't "verified".
