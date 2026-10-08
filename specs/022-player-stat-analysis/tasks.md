---
description: "Task list for spec 022: player stat analysis"
---

# Tasks: Player stat analysis, nightly and season-long

**Input**: Design documents in `specs/022-player-stat-analysis/`: plan.md, spec.md, research.md,
data-model.md, contracts/api.md and quickstart.md.

> **Regenerated after review, 2026-10-07.** T001, the adversarial plan review, is done; see
> [plan-review.md](plan-review.md). Its 13 findings and 17 notes are dispositioned in plan.md
> "Amended after review", and the tasks below implement those dispositions. Each changed task
> names the finding it carries (F1–F13, N1–N17). Four tasks are new: T016 (the forward chain
> walk, F4), T027 (the `players` destination row, F11/N5), T048 (ADP read and draft start time,
> F12) and T061 (the shared night rule, F3). The original 64-task version is in git history at
> `8c848a7`.

**Tests**: included. plan.md's Testing section and data-model.md's invariants I1–I9 each require
one, and AGENTS.md's bug class #1 requires preference-ordering tests for any ranking.

**Organization**: one phase per user story, in the plan's ship order:

- US1 (P1)
- US2 (P1)
- US4 (P2)
- US3 (P2), last because FR-036 needs opening-night data from 2026-10-20 (quickstart V12, N10)

**Pipeline**: every coding task is done by a Sonnet subagent (AGENTS.md "Subagents"). The parent
session reads each diff before marking a task done. A bug-hunting code review and live
verification close each chunk.

## Paths

Every path is written out in full. The backend main tree is
`backend/src/main/java/com/ballknowers/draftsim`, the backend tests are
`backend/src/test/java/com/ballknowers/draftsim`, and the web code is in `web/src`.

---

## Phase 1: Setup

- [x] T001 Run the adversarial plan review, a cold read with re-measurement. Output in specs/022-player-stat-analysis/plan-review.md. **Done 2026-10-07**: 13 findings and 17 notes, all accepted; the dispositions are in specs/022-player-stat-analysis/plan.md.
- [x] T002 [P] Add a `draftsim.player-stats` block to config/weights.yml with these keys, each commented as ARBITRARY and hand-set (research R12):
  - `rank-min-games-share: 0.5`
  - `rank-min-minutes-per-game: 15`
  - `small-sample-minutes: 100`
  - `recency-days: 14` (F9)
  - `standout-min-prior-games: 5`
  - `standout-ts-delta: 15`
  - `standout-ts-min-attempts: 10`
  - `standout-minutes-jump: 10`
  - `leaders-size: 5`
- [x] T003 [P] Create backend/src/main/java/com/ballknowers/draftsim/config/PlayerStatsProperties.java on PlayerTrendsProperties' pattern:
  - It is a `@ConfigurationProperties(prefix = "draftsim.player-stats")` record with nullable boxed fields.
  - A missing block binds to nulls, and startup still succeeds.
  - A value that is present must be positive. `rank-min-games-share` must be in (0, 1].
  - A bad value fails startup.
  - Register it in backend/src/main/java/com/ballknowers/draftsim/DraftSimApplication.java's `@EnableConfigurationProperties`.
- [x] T004 Add `playerStatsLoaded` to backend/src/main/java/com/ballknowers/draftsim/api/HealthController.java. It is true when the block is present. Add a case for it in backend/src/test/java/com/ballknowers/draftsim/api/HealthControllerTest.java.

---

## Phase 2: Foundational (extractions, cache, resolver)

**Purpose**: the shared building blocks in data-model.md. Player Trends' **output** must not
change: V1 compares JSON byte for byte. Its test helpers may change only for `SeasonGame.isAway`
(F7, N12).

**⚠️ CRITICAL**: no user-story work starts until T017 passes.

- [x] T005 Capture the baselines before any refactor (N13):
  - Save the JSON of `GET /api/leagues/{nba2026}/player-trends` and `GET /api/leagues/{nba2025}/player-trends`, each with a member `X-Sleeper-User`.
  - Delete `rostersFetchedAt`, `currentWeek` and `staleReferenceDate`, sort with `jq -S`, and save the results to specs/022-player-stat-analysis/baseline/player-trends-2026.json and specs/022-player-stat-analysis/baseline/player-trends-2025.json.
  - Record the backend suite's pass and skip counts in specs/022-player-stat-analysis/verification.md. The skip count must be 0 (memory "Backend suite skips ITs silently").
- [x] T006 [P] Write the I1 tests in backend/src/test/java/com/ballknowers/draftsim/engine/GameScoringServiceTest.java:
  - `contributions(scoring, stats)` returns one entry per scoring key whose stat is a Number, valued `weight × stat`.
  - `score` equals the rounded **naive left-to-right sum in scoring-key order** (N3).
  - The existing cases still pass.

  Also add backend/src/test/java/com/ballknowers/draftsim/engine/GameScoringParityIT.java. On every stored NBA 2025 **and NFL 2025** game (N3), the new `score` must equal a copy of the pre-change algorithm kept inside the test.
- [x] T007 Add `public Map<String, Double> contributions(Map<String, ? extends Number> scoring, Map<String, ?> stats)` to backend/src/main/java/com/ballknowers/draftsim/engine/GameScoringService.java.
  - It iterates the scoring keys and skips non-Number stats, exactly as `score` does.
  - It returns a `LinkedHashMap` in scoring-key order.
  - Rewrite `score` as `round2` of a plain `for` loop sum over that map. Don't use `DoubleStream.sum()` (N3).
  - Keep the class javadoc's one-implementation note (research R4).
- [x] T008 [P] Write backend/src/test/java/com/ballknowers/draftsim/engine/NbaGameLinesTest.java over synthetic rows, covering data-model "NbaGameLines":
  - `TEAM_` rows are never lines (I5).
  - The bare `TEAM_` row, and any game whose opponent isn't a season team code (All-Star), are dropped.
  - A game with `sp <= 0` is not a game.
  - The player's team row is the same-game row whose code is not the opponent; the opponent row is the one whose code is the opponent.
  - Lines are ordered by `(date, gameId)`.
  - A traded player's lines carry each game's own team.
  - `isHome` is `!isAway`, or null when `isAway` is null (F7).
  - The returned lists and maps throw on mutation (F8).
- [x] T009 Add `Boolean isAway` (from `player_game.is_away`) to `PlayerGameRepository.SeasonGame` and its `seasonPlayerGames` query in backend/src/main/java/com/ballknowers/draftsim/store/PlayerGameRepository.java (F7). Then create backend/src/main/java/com/ballknowers/draftsim/engine/NbaGameLines.java, a pure class:
  - Move the join out of `PlayerTrendsService.prepare` (backend/src/main/java/com/ballknowers/draftsim/engine/PlayerTrendsService.java:414-470) with its behaviour unchanged.
  - `record Line(String gameId, LocalDate date, int week, String team, String opponent, Boolean isHome, double minutes, Map<String,Object> stats, TeamGame teamRow, TeamGame oppRow)`. `isHome` is nullable, and so are `teamRow` and `oppRow`.
  - It exposes unmodifiable `Map<String, List<Line>> byPlayer` (oldest first), `Map<String, List<TeamGame>> teamGames` and `Set<String> teamCodes`.
  - `PlayerTrendsService.prepare` calls it, sorting a copy if it needs another order.
  - Update the `SeasonGame` construction in backend/src/test/java/com/ballknowers/draftsim/engine/PlayerTrendsServiceTest.java's helpers for the new field. Change nothing else in that file.
- [x] T010 [P] Write backend/src/test/java/com/ballknowers/draftsim/engine/AdvancedStatsUsageTest.java: direct `AdvancedStats.usage` tests on hand-computed pooled values. Trends' own usage assertions stay where they are, in PlayerTrendsServiceTest, as its guard (N12).
- [x] T011 Create backend/src/main/java/com/ballknowers/draftsim/engine/AdvancedStats.java, a pure class.
  - Move `PlayerTrendsService.usage` (`:515-526`) into it as `static Rate usage(List<Line>)`.
  - `Rate` is a record `{Double value, String reason}`. `reason` is one of `NO_ATTEMPTS`, `NO_MINUTES` or `NO_TEAM_ROW`, and exactly one of the two fields is non-null (I2).
  - Trends unwraps `.value()`, so its wire shape is unchanged.
- [x] T012 Add `public record SeasonToken(long count, OffsetDateTime maxFetchedAt)` and `seasonToken(Sport, int)` to backend/src/main/java/com/ballknowers/draftsim/store/PlayerGameRepository.java.
  - The query is `select count(*), max(fetched_at) from player_game where sport = ? and season = ?`.
  - Use `.single()`, never `.stream()` (the connection-leak note at `:261`).
  - Add a round-trip case to backend/src/test/java/com/ballknowers/draftsim/store/PlayerGameRepositoryIT.java.
- [x] T013 [P] Write backend/src/test/java/com/ballknowers/draftsim/engine/SeasonBoxCacheTest.java against a fake loader:
  - The same token causes no reload, and a changed token reloads.
  - The token is read before the rows: a write between the token read and the row read still reloads on the next `get` (F8).
  - `invalidate(sport, season)` forces a reload.
  - While a refresh of that season is marked in flight, a changed token serves the current entry and does not reload (F8).
  - Two concurrent `get`s on a cold key load once, verified with virtual threads (F8).
  - Every list and map in an entry throws on mutation (F8).
  - The compact `Map` view returns the source value as a `double` for every key, and null for absent keys (N2). A missing key is never 0.
  - Raw `SeasonGame` and `TeamGame` lists are available alongside the lines (F7).
- [x] T014 Create backend/src/main/java/com/ballknowers/draftsim/engine/SeasonBoxCache.java, a Spring `@Service` (research R6 as amended).
  - **What it stores**: the raw rows (`SeasonGame` with `isAway`, plus `TeamGame`), with stats as interned key indexes and `double[]` behind a read-only `AbstractMap` view. It also stores the `NbaGameLines` result over them.
  - **`get(Sport, int season)`**: reads `seasonToken` **before** the rows.
  - **Concurrency**: reloads go through a lock-free single flight on backend/src/main/java/com/ballknowers/draftsim/refresh/SingleFlight.java's pattern, as its own instance. Never use `synchronized`.
  - **Invalidation**: add `invalidate(sport, season)` and `markRefreshing(sport, season, boolean)`. Call both from `refreshSportSeason` in backend/src/main/java/com/ballknowers/draftsim/ingest/PlayerGameIngestService.java, at its start and end, with `finally`.
  - **Trends**: switch `PlayerTrendsService.read` to the cache. `oneGameShare` keeps receiving the raw lists, **including the All-Star row**, unchanged (F7). Excluding that row is a follow-up, and it goes in T068's handoff notes.
- [x] T015 Extend backend/src/main/java/com/ballknowers/draftsim/engine/LeagueSeasonResolver.java with `enum Rule { PLAYED_WEEKS, STORED_GAMES }` and `resolve(String sleeperLeagueId, Rule rule)`.
  - `STORED_GAMES` walks back to the newest chain season whose `SeasonBoxCache` token count is above 0 (shared token, N17).
  - The existing `resolve(String)` delegates to `PLAYED_WEEKS`.
  - The rule is a required argument and is never defaulted (memory "Optional params that encode rules").
  - Add tests for both rules in a **new** test file, backend/src/test/java/com/ballknowers/draftsim/engine/LeagueSeasonResolverTest.java (none exists today).
- [x] T016 Add `Optional<LeagueRow> successorOf(String sleeperId)` (`where previous_league_id = ?`) to backend/src/main/java/com/ballknowers/draftsim/store/LeagueRepository.java, with a **new** IT, backend/src/test/java/com/ballknowers/draftsim/store/LeagueRepositorySuccessorIT.java, because none exists for that repository today (F4). Then add `LeagueSeasonResolver.seasons(String sleeperLeagueId)`:
  - It walks forward to the chain head with `successorOf`, then back with `chainBySleeperId`.
  - It returns `[{season, sleeperLeagueId, hasGames}]`, newest first.

  Test: from the 2024 id, it returns 2026, 2025 and 2024.
- [x] T017 Run the foundation gate (quickstart V1, V2):
  - `cd backend && ./gradlew test` with 0 skipped.
  - Both Trends baselines are byte-identical after stripping the time-varying fields.
  - GameScoringParityIT shows 0 mismatches on NBA and NFL 2025.

  Record every result as measured in specs/022-player-stat-analysis/verification.md.

**Checkpoint**: Trends' output is unchanged, and each shared rule exists exactly once.

---

## Phase 3: User Story 1, the player page (Priority: P1) 🎯 MVP

**Goal**: one league-scoped page per NBA player, linked from every player name on the basketball
league pages. It shows:

- the season line and game log;
- this league's fantasy points;
- league and position rank against the player's points rank;
- the fantasy-points breakdown;
- a season picker that reaches every season in the league's chain.

**Independent Test**: quickstart V3–V6. That covers:

- SC-002: zero mismatches across 10 players, one of them traded;
- SC-008: fantasy figures equal `GameScoringService`;
- a browser click-through from 5 pages;
- forward and backward season picks.

### Tests for User Story 1

- [x] T018 [P] [US1] Write backend/src/test/java/com/ballknowers/draftsim/engine/AdvancedStatsWindowTest.java for the traditional parts of `AdvancedStats.window`:
  - per-game, totals and per-36 figures are pooled;
  - `fgPct`, `tpPct` and `ftPct` are `Rate`s, with `NO_ATTEMPTS` when there are 0 attempts (FR-006);
  - a last-N window covers `min(N, games)` games and states that count;
  - `firstGameDate` and `lastGameDate` are set (F9);
  - `smallSample` is true below `small-sample-minutes`;
  - `teamGamesMissed` follows data-model's rule, tested with a traded player and a mid-season signing (F10).
- [x] T019 [P] [US1] Write backend/src/test/java/com/ballknowers/draftsim/engine/PlayerStatsServiceTest.java for the pure core, using synthetic lines and two leagues with different scoring.
  - **Qualification**:
    - SEASON: `games ≥ ceil(share × maxTeamGames)` and minutes per game at or above the minimum.
    - LAST_N: all N games played, the minutes rule, and a last game within `recency-days`. A player whose last game is 60 days old gets `NOT_QUALIFIED_STALE` (F9).
  - **Ranks** (F13): competition ranks on the **2-decimal** `fpPerGame`. Two players at 41.234 and 41.231 share a rank. Games, then name, then id set display order only (I4).
  - **Preference ordering** (I7): more FP/G gives a better `leagueRank`.
  - **Position and points rank**: `pointsRank` and `rankMove`, and `positionRank` by first listed position.
  - **Breakdown**:
    - it sums to the total within 0.01 × games (I3);
    - negatives are kept;
    - `dd`, `td` and `bonus_*` keys appear (N8);
    - `share` is null when the total is ≤ 0 (N8).
  - **Leagues and empty seasons**:
    - identical real stats across the two leagues, with different fantasy figures (I6);
    - `NO_PLAYER_GAMES`, distinct from `NO_GAMES` (N9).
- [x] T020 [P] [US1] Write backend/src/test/java/com/ballknowers/draftsim/engine/PlayerOwnershipTest.java against data-model's "Ownership" table (F2, F5, F6, N6). Cases:
  - current season: V28 rosters, `CURRENT`;
  - `pre_draft` or `drafting`: `NOT_DRAFTED`;
  - completed season: week `playoff_week_start − 1` (18 for 2025, 21 for 2024), labelled end of regular season;
  - a scored week: that week's roster;
  - the current, unscored week: V28 `CURRENT`;
  - after the league's last week: `UNAVAILABLE`;
  - any `{}` roster in a week: the whole week is `UNAVAILABLE`;
  - `currentOwnership` present only on a fallback;
  - a past season's `ownership` never uses current rosters (I8);
  - owner names come from `RosterOwners.ownerNames`, and `rosterId` is carried.
- [x] T021 [P] [US1] Add `GET /api/leagues/{id}/players/{sleeperPlayerId}` to the hand-listed league routes in backend/src/test/java/com/ballknowers/draftsim/api/AccessControlMvcIT.java. A stranger, or a request with no identity, gets 404.

### Implementation for User Story 1

- [x] T022 [US1] Extend backend/src/main/java/com/ballknowers/draftsim/engine/AdvancedStats.java.
  - Add `record Window` with:
    - games, `firstGameDate`, `lastGameDate`, minutes and minutesPerGame;
    - perGame, totals and per36 for pts, reb, oreb, dreb, ast, stl, blk, tov, pf, fgm, fga, tpm, tpa, ftm and fta;
    - `fgPct`, `tpPct` and `ftPct` as `Rate`;
    - `plusMinusPerGame`, `gameScorePerGame` and `smallSample`.
  - Add `static Window window(List<Line>, int smallSampleMinutes)`.
  - Add `static double gameScore(Line)`, using research R5's formula.
  - Add `enum WindowKind { SEASON, LAST_10, LAST_5 }`.
  - Add `static int teamGamesMissed(List<Line>, Map<String, List<TeamGame>> teamGames)`, per data-model (F10).
  - A missing stat key in an existing line counts as 0.
- [x] T023 [US1] Create backend/src/main/java/com/ballknowers/draftsim/engine/PlayerOwnership.java, a pure resolver for data-model's "Ownership" table.
  - It returns `record Ownership(String state, Integer rosterId, String ownerName, String avatarId, boolean isMe, AsOf asOf)`. `state` is one of `ROSTERED`, `FREE_AGENT`, `NOT_DRAFTED` or `UNAVAILABLE`.
  - `AsOf` is `(String kind, OffsetDateTime fetchedAt, Integer week)`, where `kind` is `CURRENT` or `WEEK`.
  - Inputs:
    - `league.status`;
    - `playoff_week_start` from `settings_json`;
    - `league.currentLeg` / last scored leg;
    - `RosterSeasonRepository.rosteredPlayers`;
    - `RosterWeekPointsRepository.breakdownsFor`;
    - `RosterOwners.ownerNames`.
  - Expose two entry points: `forSeasonView(...)`, and `forNight(..., int week)` for US3.
- [x] T024 [US1] Create backend/src/main/java/com/ballknowers/draftsim/engine/PlayerStatsService.java.
  - **Pure core** `compute(Input)`:
    - qualification per window (F9);
    - `fpPerGame` per window via `GameScoringService.score`;
    - competition ranks on rounded values (F13), plus `pointsRank` and `rankMove`;
    - the season breakdown via `contributions`, sorted by points descending (N8).
  - **`readPlayer(LeagueRow, String sleeperPlayerId, String requester)`** builds contract C1's US1 fields:
    - season from `LeagueSeasonResolver.resolve(id, STORED_GAMES)`;
    - `seasons` from `LeagueSeasonResolver.seasons` (F4);
    - game data from `SeasonBoxCache`, names from `PlayerRepository.byIds`;
    - ownership, and `currentOwnership` when the season fell back (F6);
    - `teamGamesMissed` (F10);
    - `dataAsOf` from the token.
  - **Gates**: `NOT_BASKETBALL` (through the sport rules), `NOT_CONFIGURED`, `NO_GAMES` and `NO_PLAYER_GAMES`.
  - Use records only, never `Map.of` (constitution rule 4).
- [x] T025 [US1] Create backend/src/main/java/com/ballknowers/draftsim/api/PlayerStatsController.java with `GET /api/leagues/{sleeperLeagueId}/players/{sleeperPlayerId}`, shaped like backend/src/main/java/com/ballknowers/draftsim/api/PlayerTrendsController.java.
  - It uses `LeagueMembership.visibleLeague`, and returns 404 when the league isn't visible.
  - It also returns 404 for a player id with no stored games and no player row.
- [x] T026 [US1] Add backend/src/test/java/com/ballknowers/draftsim/engine/PlayerStatsReadIT.java over the real 2025 rows (SC-002).
  - For 10 players, one traded mid-season, every per-game average and shooting percentage must equal a direct SQL recomputation that excludes `TEAM_` rows and the All-Star game. Zero mismatches.
  - From the 2024 league id, `seasons` lists 2026, 2025 and 2024 (F4, I9).
- [x] T027 [US1] Add a `players` row to web/src/destinations.ts (F11, N5).
  - It has a new `inRail: false` field, which the rail rendering honours. Its sports are `['nba']`.
  - Its `match` is `/^\/leagues\/([^/]+)\/players\/[^/]+\/?$/`.
  - Its href is one-season: `/leagues/{ctx.season.sleeperLeagueId}/players/...`, so the rail's year links stay on the player page.
  - Export `playerPagesFor(sport)`, derived from that row's `sports`. It is the only gate deciding whether a name links, and the client never compares a sport string.
  - Test it in web/src/destinations.test.ts: hidden from the rail, matched on the route, and `playerPagesFor('nfl')` is false.
- [x] T028 [P] [US1] Add C1's types to web/src/api.ts, mirroring the Java records field for field in the same change, with contracts/api.md's nullability (constitution rule 2):
  - `Rate`, `Pct`, `Ownership` (with `rosterId`), `AsOf`;
  - `WindowStats` (with `firstGameDate` and `lastGameDate`), `GameLogRow`;
  - `SeasonOption` (with `hasGames`), and `PlayerStatsPage` (with `teamGamesMissed` and `currentOwnership`);
  - `getPlayerStats(leagueId, playerId)`.
- [x] T029 [P] [US1] Create web/src/statCopy.ts, with a test in web/src/statCopy.test.ts asserting every code has a non-empty sentence. It holds:
  - **Reason sentences**: `NO_ATTEMPTS`, `NO_MINUTES`, `NO_TEAM_ROW`, `NOT_QUALIFIED`, `NOT_QUALIFIED_STALE` ("hasn't played since {date}"), `NO_GAMES`, `NO_PLAYER_GAMES`, `NOT_BASKETBALL`, `NOT_CONFIGURED`, `NOT_DRAFTED` and `UNAVAILABLE`.
  - **Figure labels**: "Real stat" and "This league's fantasy".
  - **Scoring-key labels** (N8): every NBA scoring key, including `dd`, `td`, `ff`, `tf`, `bonus_pt_40p`, `bonus_pt_50p`, `bonus_reb_20p` and `bonus_ast_15p`.
  - **Fallback note** (N15, N16): "{requested} has no games yet; showing {shown}. Trends may show a different season."
- [x] T030 [P] [US1] Create web/src/components/PlayerLink.tsx, with a test in web/src/components/PlayerLink.test.tsx.
  - It links to `/leagues/{sleeperLeagueId}/players/{sleeperPlayerId}`.
  - It takes a required `sleeperLeagueId` and a required `sport`, neither defaulted.
  - It renders plain children, with no link, when `playerPagesFor(sport)` is false.
- [x] T031 [US1] Create web/src/pages/PlayerPage.tsx, with tests in web/src/pages/PlayerPage.test.tsx. The page shows:
  - **Header**: `PlayerFace`, team and positions, plus ownership with its as-of.
  - **`currentOwnership`**: labelled separately when present (F6).
  - **Season line**: every figure labelled real or fantasy (FR-005).
  - **Ranks**: league and position rank, the move from points rank, and the group sizes.
  - **Breakdown**: negatives shown, and a null share shown as "—" (N8).
  - **Game log**: newest first, with the team he played for that night.
  - **`teamGamesMissed`**.
  - **Season picker**: built from `seasons`, including "no games yet" entries (F4). It navigates by league id and shows the fallback note.
  - **Empty and reason states**: never an empty table or zeros.

  Tests cover a traded player, `NO_PLAYER_GAMES`, a fallback season with `currentOwnership`, `NOT_QUALIFIED_STALE`, and a football league.
- [x] T032 [US1] Add the route `/leagues/:sleeperLeagueId/players/:sleeperPlayerId` to web/src/App.tsx. Key its element on **both** ids, so moving from one player to another remounts it (N4). Add a case to web/src/App.test.tsx.
- [x] T033 [US1] Wrap NBA player names in `PlayerLink` in web/src/components/PlayerSpotlight.tsx, web/src/pages/WeeklyReport.tsx, web/src/pages/PlayerTrends.tsx, web/src/pages/RosterManagement.tsx and web/src/pages/Superlatives.tsx (F11 replaces LeagueAnalysis.tsx; FR-014).
  - Pass `sleeperLeagueId` and the payload's `sport` into `PlayerSpotlight` from web/src/components/HomeSpotlight.tsx (F11).
  - The gate is `PlayerLink`'s `playerPagesFor`, so NFL names in WeeklyReport and RosterManagement stay unlinked.
  - Update each page's existing test to assert the href on NBA and its absence on NFL.
- [x] T034 [US1] Run quickstart V3–V6 live.
  - The API by curl with a member identity.
  - V5's five-page click-through in a real browser via `preview_start`, after checking the bootRun classpath is this worktree's (memory "Worktree preview serves main").
  - V6's forward and backward picks, and the fallback with `currentOwnership`.

  Record measured results in specs/022-player-stat-analysis/verification.md, including Gobert's and Clingan's actual league rank against their points rank.

**Checkpoint**: US1 can ship on its own: a bug-hunting code review, then ask, merge and deploy.

---

## Phase 4: User Story 2, advanced stats (Priority: P1)

**Goal**: an Advanced view on the player page, across three windows. It covers efficiency,
team-context rates, per-36 figures, game score and plus-minus. Every rate has a percentile against
NBA position peers and against the players rostered in this league, and a plain-language
definition.

**Independent Test**: quickstart V7, and V11's manual check against Basketball Reference (SC-003).

### Tests for User Story 2

- [x] T035 [P] [US2] Extend backend/src/test/java/com/ballknowers/draftsim/engine/AdvancedStatsWindowTest.java with every research R5 rate: `ts`, `efg`, `ftr`, `tpar`, `minutesShare`, `astPct`, `orbPct`, `drbPct`, `trbPct`, `stlPct`, `blkPct` and `tovPct`. Cover:
  - hand-computed values over 2–3 synthetic games with team and opponent rows;
  - pooling, never averaging per-game rates;
  - an overtime game with team minutes (TmMP) of 265;
  - each zero-denominator `reason`;
  - a game with no team row, which gives `NO_TEAM_ROW`.
- [x] T036 [P] [US2] Write backend/src/test/java/com/ballknowers/draftsim/engine/PercentilesTest.java, covering data-model's "Percentiles" (F13):
  - **Formula**: `100 × (below + 0.5 × ties) / n`, with n the other group members (amended at build, 2026-10-08: dividing by `n − 1` exceeded 100; see data-model "Percentiles").
  - **Inversion**: TOV% is inverted, so a higher TOV% gets a lower percentile (I7).
  - **Reasons**: n < 2 gives `GROUP_TOO_SMALL`; an unqualified or stale player gives `NOT_QUALIFIED` or `NOT_QUALIFIED_STALE`.
  - **Free agents**: their value is ranked against the rostered group, and gets a value, not a reason.
  - **No ownership**: `UNAVAILABLE` or `NOT_DRAFTED` ownership gives `OWNERSHIP_UNAVAILABLE` for the rostered group.
  - **Windows**: a percentile is computed per window, using that window's qualification.
  - **Fields**: each `Pct` carries `{group, n}`.

### Implementation for User Story 2

- [x] T037 [US2] Add research R5's rates and the team possessions estimate to `Window` in backend/src/main/java/com/ballknowers/draftsim/engine/AdvancedStats.java.
  - Pool them over each line's own `teamRow` and `oppRow`, with TmMP equal to the team row's `sp / 60`.
  - `usage` stays the implementation moved in T011.
- [x] T038 [US2] Add `percentiles` to backend/src/main/java/com/ballknowers/draftsim/engine/PlayerStatsService.java and contract C1, keyed **by window, then rate**, with both groups (F13), plus the advanced fields for `LAST_10` and `LAST_5`. Mirror them in web/src/api.ts in the same change.
- [x] T039 [P] [US2] Add plain-language definitions of the advanced stats to web/src/statCopy.ts (FR-017):
  - one or two sentences each;
  - R5's pooling deviation, where it applies: "uses only the games he played".

  Extend web/src/statCopy.test.ts to require a definition for every advanced key.
- [x] T040 [US2] Add the Advanced view to web/src/pages/PlayerPage.tsx. It shows:
  - season, last-10 and last-5 side by side, each with its real game count and date span;
  - small-sample and "hasn't played since" labels;
  - a toggle between the NBA-position and rostered-in-league groups, showing group, n, and the exact value beside each percentile;
  - definitions;
  - plus-minus labelled noisy.

  Extend web/src/pages/PlayerPage.test.tsx with the zero-attempt, small-sample, stale, free-agent-against-rostered and `OWNERSHIP_UNAVAILABLE` states.
- [x] T041 [US2] Run quickstart V7 live. Then run V11: in a browser, read Basketball Reference's 2025-26 season (`NBA_2026`) TS%, eFG%, USG% and TRB% for 5 players, and record each actual difference against its tolerance.
  - A stat outside tolerance is reported with R5's explanation, and **not tuned**.
  - Record the results in specs/022-player-stat-analysis/verification.md.

**Checkpoint**: US1 and US2 can ship: code review, then ask, merge and deploy.

---

## Phase 5: User Story 4, leaderboard, stat leaders and draft value (Priority: P2)

**Goal**: a sortable, filterable table with column groups and a pinned name column. It shows:

- league rank;
- value over replacement;
- draft pick and drafting manager;
- Board ADP;
- Draft Grades' value over slot, with its state;
- a stat-leaders view.

**Independent Test**: quickstart V8–V9:

- SC-009: all 168 picks match;
- an in-season league with a complete draft shows the draft (F1);
- 2025 ADP reads `NO_ADP_STORED`, and 2026 reads the 09-28 capture;
- values equal the player page's (I6);
- the 375 px layout works (SC-010);
- the timings are recorded (SC-006).

### Tests for User Story 4

- [ ] T042 [P] [US4] Write backend/src/test/java/com/ballknowers/draftsim/engine/ReplacementLevelTest.java, covering data-model "Replacement":
  - starting slots are `roster_positions` minus BN, IR and TAXI, ordered single positions first, then G and F, then UTIL;
  - eligibility comes from `BasketballRules.isEligible`;
  - the `teams × slots` fill takes the best remaining qualified player each time;
  - a position's level is its best unplaced eligible player;
  - VOR is the maximum across a player's eligible positions, reported with the position used;
  - preference ordering (I7): a lower replacement level gives a higher VOR;
  - a C-scarce league gets a lower C replacement level than PG.
- [ ] T043 [P] [US4] Write backend/src/test/java/com/ballknowers/draftsim/engine/DraftAndAdpJoinTest.java (F1, F12):
  - **Draft state** reads `draft.status`:
    - an **`in_season` league with a `complete` draft** gives `COMPLETE`;
    - a `pre_draft` draft row gives `NOT_HAPPENED`;
    - no draft row gives `NONE`.
  - **Drafted and undrafted players**: a drafted player gets `{pickNo, round, managerName}`, with `managerName` from Draft Grades' rule. An undrafted player gets null and no `draftValue` (I8).
  - **ADP**:
    - it comes from `latestBefore(NBA, "blend", startTime)`;
    - a null `startTime` gives `NO_DRAFT_DATE`;
    - no capture gives `NO_ADP_STORED`;
    - `sleeper_search_rank` is never read.
  - **Draft Grades**: `draftGrades` copies `{available, reason, gradesEarly, weeksCounted}`. `draftValue` equals `valueOverSlot`, and is null when grades aren't available.
- [ ] T044 [P] [US4] Add `GET /api/leagues/{id}/stats?window=…` to backend/src/test/java/com/ballknowers/draftsim/api/AccessControlMvcIT.java. A controller test asserts that a missing or unknown `window` gives 400.
- [ ] T045 [P] [US4] Write web/src/statLeaderboard.test.ts:
  - **Comparator**: one comparator orders by value, then games, then name, then id, deterministically (I4).
  - **Column groups**: the five groups hold the right columns. Switching group keeps the sort, window and filters, and a sort outside the current group isn't offered (FR-038).
  - **Stat leaders**: the top `leaders-size` per category, using the same comparator, qualification and staleness rule (F9).
  - **Filters**: free agent, rostered, and by manager (`rosterId`, F13).

### Implementation for User Story 4

- [ ] T046 [US4] Create backend/src/main/java/com/ballknowers/draftsim/engine/ReplacementLevel.java, a pure class for research R11.
  - It takes the qualified players with their `fpPerGame`, the league's `roster_positions` and `total_rosters`, and the `SportRules`.
  - It returns `record Replacement(Map<String, Double> byPosition, String rule, int teams, List<String> slots)`, plus `(valueOverReplacement, vorPosition)` for each player.
- [ ] T047 [US4] Add `Instant startTime` (nullable) to `DraftRepository.DraftRow` and to its `forLeague`/`bySleeperId` queries in backend/src/main/java/com/ballknowers/draftsim/store/DraftRepository.java (F12).
- [ ] T048 [US4] Add `Optional<Capture> latestBefore(Sport, String source, LocalDate onOrBefore)` to backend/src/main/java/com/ballknowers/draftsim/store/BoardRepository.java, where `record Capture(LocalDate capturedOn, List<Row> rows)` (F12).
  - Add an IT in backend/src/test/java/com/ballknowers/draftsim/store/BoardRepositoryLatestBeforeIT.java that runs it against the real DB.
  - The IT checks that NBA blend on or before the 2025 draft is empty, and that on or before 2026-10-10 it returns 2026-09-28.
  - The existing, never-called `asOf` is left alone.
- [ ] T049 [US4] Add the draft, ADP and Draft Grades join to backend/src/main/java/com/ballknowers/draftsim/engine/PlayerStatsService.java:
  - `draft.state` from `draft.status` (F1);
  - picks from `DraftRepository.picks`, with managers named by Draft Grades' rule (`DraftGradesService.java:310-329`; F12);
  - ADP from `latestBefore(NBA, BoardRepository.SOURCE_BLEND, startTime)`;
  - `draftGrades` and `draftValue` from `DraftGradesService.read(draft)`, matched by `pickNo`, and memoised on the season token (F12).
- [ ] T050 [US4] Add `readLeaderboard(LeagueRow, WindowKind window, String requester)` to backend/src/main/java/com/ballknowers/draftsim/engine/PlayerStatsService.java, building contract C2.
  - `rows` holds every player with at least one game in the window.
  - Ranks, replacement and leaders are computed over the qualified, non-stale group only.
  - Each value **is** the value `readPlayer` gives for the same player and window (I6, FR-025).
  - Include `currentOwnership` when the season fell back (F6).
  - Add `GET /api/leagues/{sleeperLeagueId}/stats` with a required `window` param to backend/src/main/java/com/ballknowers/draftsim/api/PlayerStatsController.java.
- [ ] T051 [US4] Add backend/src/test/java/com/ballknowers/draftsim/engine/PlayerStatsLeaderboardReadIT.java over the real 2025 data:
  - all 168 picks match `draft_pick` (SC-009);
  - ADP reads `NO_ADP_STORED`;
  - TS% and FP/G for 5 rows equal C1's (I6);
  - there are no `TEAM_` ids (I5);
  - 582 rows for SEASON (N14).
- [ ] T052 [P] [US4] Add C2's types to web/src/api.ts, mirroring the Java records: `StatLeaderboard`, `LeaderboardRow`, `Replacement`, `DraftGradesState` and `getStatLeaderboard(leagueId, window)`.
- [ ] T053 [P] [US4] Create web/src/statLeaderboard.ts, holding (research R14):
  - the single comparator;
  - the column-group definitions (Basic, Shooting, Advanced, Fantasy, Draft value);
  - the filters;
  - the stat-leaders selection.
- [ ] T054 [US4] Create web/src/pages/StatLeaderboard.tsx, with tests in web/src/pages/StatLeaderboard.test.tsx. The page has:
  - **Controls**: window and mode (per game, totals, per 36), and the group switcher. The name column is pinned, and the table scrolls sideways inside itself.
  - **Filters**: position, team, and availability with its as-of.
  - **Notes**: the qualification note, with an off switch, and the "hasn't played since" exclusion. A replacement-rule note gives the level for each position.
  - **Draft and ADP columns**: draft columns reading "undrafted" or "draft hasn't happened", and an ADP column with its source and capture date, or "no ADP stored" / "no draft date".
  - **Draft value**: labelled "per counted week, from Draft Grades", linked to that page, and showing the `gradesEarly` caveat and the unavailable state.
  - **Stat leaders**, plus `PlayerLink` on every name.
- [ ] T055 [US4] Add the `stats` destination to web/src/destinations.ts: sports `['nba']`, group `season`, and the one-season href `/leagues/{id}/stats`. Add a case in web/src/destinations.test.ts, and the route in web/src/App.tsx.
- [ ] T056 [US4] Run quickstart V8 and V9 live:
  - **V8**:
    - the 2025 draft;
    - an in-season league with a complete draft (F1);
    - ADP for 2025 and 2026;
    - Draft Grades equality and state for 5 picks;
    - a hand check of one position's replacement fill.
  - **375 px**: check all five groups with `resize_window`, take a screenshot (SC-010), then reset to desktop.
  - **V9**, locally and in production:
    - the cache's cold-load time and heap use;
    - token-query time;
    - a request timed during a running refresh (F8);
    - Draft Grades read time;
    - C1 and C2 warm times, and uncompressed and gzipped sizes;
    - Railway's container memory.

  Record every number in specs/022-player-stat-analysis/verification.md. Add compression only if V9 calls for it (N14 expects about 0.75 MB per window uncompressed).

**Checkpoint**: US4 can ship: code review, then ask, merge and deploy.

---

## Phase 6: User Story 3, the nightly report (Priority: P2)

**Goal**: one page per night. It shows:

- every stored game with its score;
- separate rankings by game score and by this league's fantasy points;
- the member's roster lines, played and did-not-play;
- standout lines flagged by named rules.

The night's completeness and the default night use the rule Spotlight already uses.

**Blocked by**: T057 (FR-036). Build T058 onward only once T057 has a measured delay.

**Independent Test**: quickstart V10:

- three past 2025 nights, including an overtime game (SC-004);
- the postponed-game nights and the All-Star night;
- a 2024 night;
- a current-week night.

- [ ] T057 [US3] Run quickstart V12 on the first 2026 regular-season nights, from **2026-10-20** (N10).
  - For 5 games, record the final time, the first refresh that stored both team rows and every player row, and when Sleeper's per-game endpoint first had the full box score.
  - Write the delay to specs/022-player-stat-analysis/verification.md, and amend research R13 with a dated note.
  - If the delay is long, raise the ESPN fallback with Allan as a decision. It is **not** adopted here.

### Tests for User Story 3

- [ ] T058 [P] [US3] Write backend/src/test/java/com/ballknowers/draftsim/engine/NightlyReportServiceTest.java (F2, F3, N7):
  - **Game list**: built from stored team rows. A 2024 night with no schedule lists its games, with `missingGames` null and `NO_SCHEDULE`. The All-Star night lists no STP/STR game. A night with a postponed game doesn't count the postponed game as missing.
  - **Completeness and default night**: `complete` and the default date come from the shared night rule (T061), and agree with Spotlight on the same fixture.
  - **Rankings**: game-score and fantasy lists are separate, and ties are ordered deterministically.
  - **`mine`**:
    - a scored week uses that week's roster;
    - the current, unscored week uses `CURRENT` rosters;
    - a night after the league's last week gives `UNAVAILABLE`;
    - `didNotPlay` comes from game-level `player_absence` `ENTRY_WITHOUT_PLAY` rows, not `player.team`.
  - **Standouts**: each rule at, just under and just over its threshold.
    - A season high needs `standout-min-prior-games` earlier games.
    - TS% is compared against the season figure from **before tonight**.
    - `MINUTES_JUMP_FREE_AGENT` is never evaluated under `UNAVAILABLE`.
  - **No games**: a date with no games gives `NO_GAMES_ON_DATE`.
- [ ] T059 [P] [US3] Add `GET /api/leagues/{id}/nightly` to backend/src/test/java/com/ballknowers/draftsim/api/AccessControlMvcIT.java. A date outside the season gives 400.
- [ ] T060 [P] [US3] Write backend/src/test/java/com/ballknowers/draftsim/engine/NightRuleTest.java for the extracted rule, reusing the fixtures in backend/src/test/java/com/ballknowers/draftsim/engine/PlayerSpotlightPeriodTest.java and PlayerSpotlightTopOfNightTest.java. `PlayerSpotlightService`'s tests must pass unchanged after T061 (F3).

### Implementation for User Story 3

- [ ] T061 [US3] Extract `PlayerSpotlightService.isComplete` and `choosePeriod` (backend/src/main/java/com/ballknowers/draftsim/engine/PlayerSpotlightService.java:474-525, with `NIGHT_COMPLETE_HOUR_UTC` at `:58`) into backend/src/main/java/com/ballknowers/draftsim/engine/NightRule.java, a pure class (F3).
  - Spotlight calls `NightRule`, and its behaviour is unchanged.
  - Make `ScheduleGridService`'s real-game rule (backend/src/main/java/com/ballknowers/draftsim/engine/ScheduleGridService.java:54-65,127-137: postponed, canceled and exhibition excluded) callable as one static method, so the nightly report can call it rather than copy it.
- [ ] T062 [US3] Create backend/src/main/java/com/ballknowers/draftsim/engine/NightlyReportService.java, returning contract C3. It uses:
  - `SeasonBoxCache`;
  - `NightRule`, for the default date and `complete`;
  - the stored team rows, for the game list;
  - `SportScheduleRepository` with `ScheduleGridService`'s real-game rule, for `missingGames`;
  - `AdvancedStats.gameScore` and `GameScoringService`;
  - `PlayerOwnership.forNight`;
  - `PlayerAbsenceRepository`, for `didNotPlay`.

  The standout thresholds come from `PlayerStatsProperties`, and `standoutRules` echoes them.
- [ ] T063 [US3] Create backend/src/main/java/com/ballknowers/draftsim/api/NightlyReportController.java with `GET /api/leagues/{sleeperLeagueId}/nightly?date=`. `date` is optional; when absent, the rule-based default applies, and the response names the date it used.
- [ ] T064 [P] [US3] Add C3's types to web/src/api.ts: `NightlyReport`, `NightLine`, `Standout` and `getNightlyReport(leagueId, date?)`. Add the standout and reason sentences (`NO_SCHEDULE`, `NO_GAMES_ON_DATE`) to web/src/statCopy.ts. Each standout sentence shows its comparison values, for example "season high in points: 41, previous high 33".
- [ ] T065 [US3] Create web/src/pages/NightlyReport.tsx, with tests in web/src/pages/NightlyReport.test.tsx. It shows:
  - a date picker built from `dates`;
  - the game list with scores;
  - two labelled rankings;
  - "your players", split into played and didn't, with the ownership as-of;
  - standouts, each with its rule stated;
  - an incomplete-night banner with `dataAsOf`;
  - a "games scheduled but not stored" count, or "no schedule for this season";
  - a no-games state;
  - `PlayerLink` on every name.
- [ ] T066 [US3] Add the `nightly` destination to web/src/destinations.ts: sports `['nba']`, group `thisWeek`, one-season href. Add the route in web/src/App.tsx.
  - Link the spotlight's "Top of the night" header to `/leagues/{id}/nightly?date={that night}` in web/src/components/PlayerSpotlight.tsx.
  - That night is chosen by the same `NightRule`, so the two agree (F3).
  - Test it in web/src/destinations.test.ts and web/src/components/PlayerSpotlight.test.tsx.
- [ ] T067 [US3] Run quickstart V10 live, covering:
  - three past 2025 nights, including one with an overtime game;
  - 2026-01-08 and 2026-01-25 (postponed games);
  - 2026-02-15 (the All-Star game);
  - one 2024 night;
  - once in season, last night (`mine` reads `CURRENT`).

  Record the results in specs/022-player-stat-analysis/verification.md.

**Checkpoint**: US3 can ship: code review, then ask, merge and deploy.

---

## Phase 7: Polish and cross-cutting

- [ ] T068 [P] Update HANDOFF.md and README.md with what shipped, what is verified, and what is still owed:
  - the V12 delay;
  - V9's production numbers;
  - V11's differences;
  - the `oneGameShare` All-Star follow-up (F7).

  Add any durable lesson to claude/lessons.md. The review's F3 qualifies: "a plan's survey of existing rules missed three, all in the area it covered least".
- [ ] T069 [P] Add a basketball-only source-scan test, on spec 014's pattern, in backend/src/test/java/com/ballknowers/draftsim/engine/PlayerStatsSportRuleScanTest.java. `PlayerStatsService`, `NightlyReportService` and `AdvancedStats` must never compare a sport by string or enum; the gate goes through `SportRules`.
- [ ] T070 Write the bug-hunting code review for each chunk, as a separate pass rather than a style pass, to specs/022-player-stat-analysis/code-review.md. Then fix what it finds, adding dated amendment notes wherever a doc was wrong.

---

## Dependencies and execution order

### Phase dependencies

- **Setup**: T001 is done. T002 and T003 can run in parallel. T004 follows T003.
- **Foundational (T005–T017)**: blocks every story.
  - T005 comes first, because it records the baselines.
  - Each test precedes its implementation: T006 → T007, T008 → T009, T010 → T011 and T013 → T014.
  - T009 and T012 precede T014. T014 precedes T015, and T015 precedes T016.
  - T017 is the gate.
- **US1 (T018–T034)**: needs the foundation. This is the MVP. T027 precedes T030 and T033.
- **US2 (T035–T041)**: needs US1, because it extends `Window`, `PlayerStatsService` and the page.
- **US4 (T042–T056)**: needs US1 and US2, because it reuses every rate, rank and qualification rule. T047 and T048 precede T049.
- **US3 (T057–T067)**: needs the foundation, plus T023 (`PlayerOwnership.forNight`) and T022 (`gameScore`).
  - **T057 is gated on the calendar**: it can't run before 2026-10-20.
  - T060 → T061 → T062.
  - Once T057 is done, US3 can be built in parallel with US4.
- **Polish**: T069 can start after US1. T068 and T070 run once per chunk.

### Within each story

1. Tests are written first, and must fail before the code exists.
2. The pure core comes next, then the read path, then the controller.
3. `api.ts` changes in the same change as the records it mirrors.
4. The page comes after that.
5. Live verification is last.

### Parallel opportunities

- **Setup**: T002 alongside T003.
- **Foundation tests**: T006, T008, T010 and T013, each in its own file.
- **US1**:
  - the tests T018, T019, T020 and T021, in parallel;
  - then T028, T029 and T030 on the web side, while the backend's T022–T026 proceed.
- **US2**: T035 alongside T036, and T039 alongside T037.
- **US4**:
  - the tests T042, T043, T044 and T045, in parallel;
  - then T052 and T053 alongside T046–T051.
- **US3**: T058, T059 and T060 in parallel, then T064 alongside T061–T063.

## Parallel example: User Story 1

```text
# Tests first, in parallel (separate files):
T018 AdvancedStatsWindowTest.java   T019 PlayerStatsServiceTest.java
T020 PlayerOwnershipTest.java       T021 AccessControlMvcIT.java (route entry)

# Web scaffolding in parallel with backend T022–T026 (after T027):
T028 web/src/api.ts   T029 web/src/statCopy.ts   T030 web/src/components/PlayerLink.tsx
```

## Implementation strategy

### MVP first (US1)

1. Setup (T002–T004).
2. The foundation, with its Trends-unchanged gate (T005–T017).
3. US1 (T018–T034). Then stop, review, verify live, and ask before merging and deploying.

### Incremental delivery

- **US2**: the Advanced view. Ship.
- **US4**: the leaderboard, stat leaders and draft value. Ship.
- **US3**: the nightly report, once V12 has data from 2026-10-20 onward. Ship.

Each chunk goes out as a separate PR from this branch's worktree. Ask before every commit:
concurrent sessions share the tree.

## Notes

- **[P]** marks a task in its own file that depends on no unfinished task.
- Every hand-set number lives in `weights.yml` and is labelled ARBITRARY. None is ever tuned to
  match a Basketball Reference figure or an earlier estimate (constitution rule 3).
- `web/src/api.ts` changes in the **same** task or PR as the Java records it mirrors.
- If a task needs a migration, that is a plan failure. Stop and raise it, because none is planned.
