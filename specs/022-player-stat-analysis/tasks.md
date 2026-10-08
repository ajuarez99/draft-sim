---
description: "Task list for spec 022: player stat analysis"
---

# Tasks: Player stat analysis, nightly and season-long

**Input**: Design documents in `specs/022-player-stat-analysis/`: plan.md, spec.md, research.md,
data-model.md, contracts/api.md and quickstart.md.

**Tests**: included. plan.md's Testing section and data-model.md's invariants I1–I8 each require
one, and AGENTS.md's bug class #1 requires preference-ordering tests for any ranking.

**Organization**: one phase per user story, in the plan's ship order:

- US1 (P1)
- US2 (P1)
- US4 (P2)
- US3 (P2), last because FR-036 needs opening-night data (quickstart V12)

**Pipeline**: the adversarial plan review (T001) runs **before** any code, as AGENTS.md's feature
convention requires. Every coding task is done by a Sonnet subagent (AGENTS.md "Subagents"). The
parent session reads each diff.

## Path conventions

- `BE` = `backend/src/main/java/com/ballknowers/draftsim`
- `BT` = `backend/src/test/java/com/ballknowers/draftsim`
- `WEB` = `web/src`

Every path below is written out in full.

---

## Phase 1: Setup

**Purpose**: review the plan cold, then add the config the later phases bind to.

- [ ] T001 Run the adversarial plan review, a separate pass that reads plan.md, research.md, data-model.md and contracts/api.md cold and re-measures against the DB and code. Write findings to specs/022-player-stat-analysis/plan-review.md, and amend the docs in place with dated notes (as spec 019's plan did). **Do not start T002 until the findings are dispositioned.**
- [ ] T002 [P] Add a `draftsim.player-stats` block to config/weights.yml, with keys `rank-min-games-share: 0.5`, `rank-min-minutes-per-game: 15`, `small-sample-minutes: 100`, `standout-min-prior-games: 5`, `standout-ts-delta: 15`, `standout-ts-min-attempts: 10`, `standout-minutes-jump: 10` and `leaders-size: 5`. Each key gets a comment saying it is ARBITRARY and hand-set (research R12).
- [ ] T003 [P] Create backend/src/main/java/com/ballknowers/draftsim/config/PlayerStatsProperties.java. It is a `@ConfigurationProperties(prefix = "draftsim.player-stats")` record with nullable boxed fields, built on PlayerTrendsProperties' pattern:
  - A missing block binds to nulls and startup still succeeds.
  - A present value must be positive, and `rank-min-games-share` must be in (0, 1]. A bad value fails startup.
  - Register it in backend/src/main/java/com/ballknowers/draftsim/DraftSimApplication.java's `@EnableConfigurationProperties`.
- [ ] T004 Add `playerStatsLoaded` (true when the block is present) to backend/src/main/java/com/ballknowers/draftsim/api/HealthController.java, with a case in backend/src/test/java/com/ballknowers/draftsim/api/HealthControllerTest.java.

---

## Phase 2: Foundational (extractions, cache, resolver)

**Purpose**: the shared building blocks from data-model.md. These are refactors of existing rules
(research R2, R3, R4, R6, R7), and **Player Trends' behaviour must not change**.

**⚠️ CRITICAL**: no user-story work starts until T016 passes.

- [ ] T005 Capture a baseline before any refactor: save the JSON of `GET /api/leagues/{nba2026}/player-trends` (with a member `X-Sleeper-User`) to specs/022-player-stat-analysis/baseline/player-trends-before.json, sorted with `jq -S`. Record the backend suite's pass and skip counts in specs/022-player-stat-analysis/verification.md. The skip count must be 0 (memory: "Backend suite skips ITs silently").
- [ ] T006 [P] Write the I1 test in backend/src/test/java/com/ballknowers/draftsim/engine/GameScoringServiceTest.java:
  - `contributions(scoring, stats)` returns one entry per **scoring** key the game has as a Number, each `weight × stat`.
  - `score` equals `round2(sum(contributions))`.
  - The existing cases still pass.
  - Add an IT, backend/src/test/java/com/ballknowers/draftsim/engine/GameScoringParityIT.java: on every stored NBA 2025 game, `score` after the change equals a copy of the pre-change algorithm kept in the test.
- [ ] T007 Add `public Map<String, Double> contributions(Map<String, ? extends Number> scoring, Map<String, ?> stats)` to backend/src/main/java/com/ballknowers/draftsim/engine/GameScoringService.java. It iterates the scoring keys and skips non-Number stats, exactly as `score` does. Rewrite `score` as the rounded sum of `contributions`, and keep the class javadoc's one-implementation note (research R4).
- [ ] T008 [P] Write backend/src/test/java/com/ballknowers/draftsim/engine/NbaGameLinesTest.java, covering data-model "NbaGameLines" over synthetic rows:
  - `TEAM_` rows are never lines (I5).
  - The bare `TEAM_` row, and a game whose opponent is not a season team code (All-Star), are dropped.
  - `sp <= 0` is not a game.
  - The team row is the same-game row whose code ≠ the opponent; the opponent row is the one whose code = the opponent.
  - Ordering is by `(date, gameId)`.
  - A traded player's lines carry each game's own team.
- [ ] T009 Create backend/src/main/java/com/ballknowers/draftsim/engine/NbaGameLines.java, a pure class. Move the join from `PlayerTrendsService.prepare` (backend/src/main/java/com/ballknowers/draftsim/engine/PlayerTrendsService.java:414-470) **verbatim** in behaviour. It exposes:
  - `record Line(String gameId, LocalDate date, int week, String team, String opponent, Boolean isHome, double minutes, Map<String,Object> stats, TeamGame teamRow, TeamGame oppRow)`, where `isHome` is nullable;
  - `Map<String, List<Line>> byPlayer` (oldest first), `Map<String, List<TeamGame>> teamGames` and `Set<String> teamCodes`.

  Make `PlayerTrendsService.prepare` call it.
- [ ] T010 [P] Create backend/src/test/java/com/ballknowers/draftsim/engine/AdvancedStatsUsageTest.java by moving Trends' existing usage assertions there, unchanged.
- [ ] T011 Create backend/src/main/java/com/ballknowers/draftsim/engine/AdvancedStats.java, a pure class. Move `PlayerTrendsService.usage` (`:515-526`) into it as `static Rate usage(List<Line>)`. `Rate` is a record `{Double value, String reason}`, with reason ∈ {`NO_ATTEMPTS`, `NO_MINUTES`, `NO_TEAM_ROW`}, and exactly one of the two is non-null (I2). Trends unwraps `.value()` so its wire shape is unchanged.
- [ ] T012 Add `public record SeasonToken(long count, OffsetDateTime maxFetchedAt)` and `seasonToken(Sport, int)` to backend/src/main/java/com/ballknowers/draftsim/store/PlayerGameRepository.java, as `select count(*), max(fetched_at) from player_game where sport = ? and season = ?`. Use `.single()`, not `.stream()` (the connection-leak note at `:261`). Add a round-trip case to backend/src/test/java/com/ballknowers/draftsim/store/PlayerGameRepositoryIT.java.
- [ ] T013 [P] Write backend/src/test/java/com/ballknowers/draftsim/engine/SeasonBoxCacheTest.java against a fake loader:
  - The same token gives no reload; a changed token reloads.
  - Two concurrent `get`s on a cold key load once (single flight).
  - The compact `Map` view returns the same values as the source JSON map for every key, and null for absent keys. A missing key is never 0, because the scorer treats absent and non-Number keys as contributing nothing.
- [ ] T014 Create backend/src/main/java/com/ballknowers/draftsim/engine/SeasonBoxCache.java, a Spring `@Service`. Its `get(Sport, int season)` returns the `NbaGameLines` result for the season. It:
  - stores each game's stats as interned key indexes plus `float[]`;
  - exposes a read-only `AbstractMap` view;
  - checks `seasonToken` on every `get`;
  - reloads under a per-key lock.

  Switch `PlayerTrendsService.read` to it (research R6).
- [ ] T015 Extend backend/src/main/java/com/ballknowers/draftsim/engine/LeagueSeasonResolver.java with `enum Rule { PLAYED_WEEKS, STORED_GAMES }` and `resolve(String sleeperLeagueId, Rule rule)`:
  - `STORED_GAMES` walks back to the newest chain season whose `seasonToken.count > 0`.
  - The existing `resolve(String)` delegates to `PLAYED_WEEKS`.
  - No defaulted rule parameter: the memory "Optional params that encode rules" applies.
  - Add tests for both rules in backend/src/test/java/com/ballknowers/draftsim/engine/LeagueSeasonResolverTest.java.
- [ ] T016 Run the foundation gate (quickstart V1, V2):
  - `cd backend && ./gradlew test` passes with 0 skipped.
  - Trends JSON equals specs/022-player-stat-analysis/baseline/player-trends-before.json under `jq -S`.
  - The GameScoringParityIT has 0 mismatches.

  Record each result in specs/022-player-stat-analysis/verification.md as measured.

**Checkpoint**: Trends is unchanged, and the shared rules each exist once.

---

## Phase 3: User Story 1, the player page (Priority: P1) 🎯 MVP

**Goal**: one league-scoped page per NBA player, reached from every player name on the league
pages. It shows the season line, the game log, fantasy points under this league's scoring, league
and position rank against points rank, the fantasy-points breakdown, and the season picker.

**Independent Test**: quickstart V3–V6. SC-002 (zero mismatches across 10 players, one of them
traded) and SC-008 (fantasy figures equal `GameScoringService`), plus a browser click-through from
5 pages.

### Tests for User Story 1

- [ ] T017 [P] [US1] Write backend/src/test/java/com/ballknowers/draftsim/engine/AdvancedStatsWindowTest.java for the traditional parts of `AdvancedStats.window`:
  - Per-game, totals and per-36 figures are pooled.
  - `fgPct`, `tpPct` and `ftPct` are `Rate`s, with `NO_ATTEMPTS` when attempts are 0 (FR-006).
  - A last-N window covers `min(N, games)` games and reports that count.
  - `smallSample` is set when minutes < `small-sample-minutes`.
- [ ] T018 [P] [US1] Write backend/src/test/java/com/ballknowers/draftsim/engine/PlayerStatsServiceTest.java for the pure core, using synthetic lines and two leagues with different scoring:
  - **Qualification**: `games ≥ ceil(share × maxTeamGamesSoFar)` and MPG ≥ min.
  - **Ranks**: by `fpPerGame` descending. Ties break on value desc, then games desc, then name asc, then sleeper id; tied players share the lower rank (I4).
  - **Ordering** (I7): more FP/G gives a better `leagueRank`.
  - **Moves**: `pointsRank` and `rankMove = pointsRank − leagueRank`.
  - **Position rank** uses the first listed position.
  - **Breakdown** sums to the season total within ±0.01 × games (I3), with negatives kept.
  - **Unqualified players** get null ranks and `NOT_QUALIFIED`.
  - **Across leagues** (I6): identical real stats, different fantasy figures.
- [ ] T019 [P] [US1] Write backend/src/test/java/com/ballknowers/draftsim/engine/PlayerOwnershipTest.java for research R8:
  - **Current season**: reads V28 `rosteredPlayers`, giving `ROSTERED` or `FREE_AGENT` with `asOf {kind: CURRENT, fetchedAt}`.
  - **`pre_draft`/`drafting`**: `NOT_DRAFTED`.
  - **Completed season**: the last stored week's `players_points` keys, with `asOf {kind: WEEK, week}`.
  - **Nothing stored**: `UNAVAILABLE`, and never the current rosters (I8).
  - **Names** come from `RosterOwners.ownerNames`.
- [ ] T020 [P] [US1] Add `GET /api/leagues/{id}/players/{sleeperPlayerId}` to the hand-listed league routes in backend/src/test/java/com/ballknowers/draftsim/api/AccessControlMvcIT.java. A stranger or no identity gets 404.

### Implementation for User Story 1

- [ ] T021 [US1] Extend backend/src/main/java/com/ballknowers/draftsim/engine/AdvancedStats.java with:
  - `record Window`, holding games, minutes, minutesPerGame, perGame/totals/per36 for pts, reb, oreb, dreb, ast, stl, blk, tov, pf, fgm, fga, tpm, tpa, ftm and fta; `fgPct`, `tpPct` and `ftPct` as `Rate`; `plusMinusPerGame`; `gameScorePerGame`; and `smallSample`;
  - `static Window window(List<Line>, int smallSampleMinutes)`;
  - `static double gameScore(Line)`, using research R5's game-score formula;
  - an `enum WindowKind { SEASON, LAST_10, LAST_5 }`.

  A missing stat key counts as 0 inside a line that exists (spec edge case "Omitted zero stats").
- [ ] T022 [US1] Create backend/src/main/java/com/ballknowers/draftsim/engine/PlayerOwnership.java, a pure resolver for research R8, returning a record `Ownership(String state, Integer rosterId, String ownerName, String avatarId, boolean isMe, AsOf asOf)`.
  - `state` ∈ {`ROSTERED`, `FREE_AGENT`, `NOT_DRAFTED`, `UNAVAILABLE`}.
  - `AsOf(String kind, OffsetDateTime fetchedAt, Integer week)`, with kind ∈ {`CURRENT`, `WEEK`}.
  - Inputs: `league.status`, `RosterSeasonRepository.rosteredPlayers`, `RosterWeekPointsRepository.breakdownsFor` and `RosterOwners.ownerNames`.
- [ ] T023 [US1] Create backend/src/main/java/com/ballknowers/draftsim/engine/PlayerStatsService.java:
  - **Pure core** `compute(Input)`: qualification, `fpPerGame` per window via `GameScoringService.score`, ranks with `pointsRank` and `rankMove`, and the season breakdown via `contributions` sorted by points desc (data-model "League layer").
  - **`readPlayer(LeagueRow, String sleeperPlayerId, String requester)`**: resolves the season with `LeagueSeasonResolver.resolve(id, STORED_GAMES)`, reads `SeasonBoxCache`, `PlayerRepository.byIds` and ownership, and builds contract C1 (US1 fields).
  - **Gates**: `NOT_BASKETBALL` via the sport rules, `NOT_CONFIGURED` when the properties block is missing, and `NO_GAMES`.
  - **`seasons`**: every chain season with stored games, newest first.
  - **`dataAsOf`**: `seasonToken.maxFetchedAt`.
  - **Wire shape**: records only, no `Map.of` (constitution rule 4).
- [ ] T024 [US1] Create backend/src/main/java/com/ballknowers/draftsim/api/PlayerStatsController.java with `GET /api/leagues/{sleeperLeagueId}/players/{sleeperPlayerId}`:
  - It uses `LeagueMembership.visibleLeague` and returns 404 when the league isn't visible.
  - It also returns 404 for a player id with no stored games and no player row.
  - Its shape follows backend/src/main/java/com/ballknowers/draftsim/api/PlayerTrendsController.java.
- [ ] T025 [US1] Add a C1 read IT, backend/src/test/java/com/ballknowers/draftsim/engine/PlayerStatsReadIT.java, over the real 2025 rows (SC-002). For 10 players, including one traded mid-season, every per-game average and shooting percentage equals a direct SQL recomputation that excludes `TEAM_` rows and the All-Star game. Assert zero mismatches.
- [ ] T026 [P] [US1] Add C1's types to web/src/api.ts: `Rate`, `Pct`, `Ownership`, `AsOf`, `WindowStats`, `GameLogRow`, `PlayerStatsPage` and `getPlayerStats(leagueId, playerId)`. Mirror the Java records field for field in the same change, with nullability as contracts/api.md states (constitution rule 2).
- [ ] T027 [P] [US1] Create web/src/statCopy.ts, holding the reason sentences for `NO_ATTEMPTS`, `NO_MINUTES`, `NO_TEAM_ROW`, `NOT_QUALIFIED`, `NO_GAMES`, `NOT_BASKETBALL`, `NOT_CONFIGURED`, `NOT_DRAFTED` and `UNAVAILABLE`, plus labels marking each figure "Real stat" or "This league's fantasy". Test it in web/src/statCopy.test.ts: every code has a sentence, and none is empty.
- [ ] T028 [P] [US1] Create web/src/components/PlayerLink.tsx, a link to `/leagues/{seasonLeagueId}/players/{sleeperPlayerId}`, with web/src/components/PlayerLink.test.tsx. It takes a required `sleeperLeagueId` (never defaulted) and renders its children inside the link.
- [ ] T029 [US1] Create web/src/pages/PlayerPage.tsx with web/src/pages/PlayerPage.test.tsx. It shows:
  - a header with `PlayerFace`, team, positions and ownership with its as-of;
  - the season line, with every figure labelled real or fantasy (FR-005);
  - league rank and position rank, with the move from points rank and the group sizes;
  - the breakdown, with negatives shown;
  - the game log, newest first, with the team played for that night;
  - missed team games;
  - a season picker from `seasons`, which navigates by league id and says when it fell back (`requestedSeason`);
  - empty and reason states, so there are never empty tables or zeros.

  Tests cover a traded player, `NO_GAMES`, a fallback season, `NOT_QUALIFIED` and a football league (`NOT_BASKETBALL`).
- [ ] T030 [US1] Add the route `/leagues/:sleeperLeagueId/players/:sleeperPlayerId` (via `KeyedByLeague`) to web/src/App.tsx. Make the rail stay inside the league on that route by adding a `match` to the relevant entry in web/src/destinations.ts, without adding a rail item. Cover it in web/src/destinations.test.ts.
- [ ] T031 [US1] Wrap NBA player names in `PlayerLink` in web/src/components/PlayerSpotlight.tsx, web/src/pages/WeeklyReport.tsx, web/src/pages/PlayerTrends.tsx, web/src/pages/RosterManagement.tsx and web/src/pages/LeagueAnalysis.tsx (FR-014). Branch on the league's sport rule the pages already receive, not on a string compare. Update each page's existing test to assert the link's href.
- [ ] T032 [US1] Run quickstart V3–V6 live: the API by curl with a member identity, and V5's five-page click-through in a real browser via `preview_start`. Check the bootRun classpath is this worktree's (memory "Worktree preview serves main"). Record the measured results, including Gobert's and Clingan's actual league rank against points rank, in specs/022-player-stat-analysis/verification.md.

**Checkpoint**: US1 is shippable on its own: a bug-hunting code review, then ask, merge and deploy.

---

## Phase 4: User Story 2, advanced stats (Priority: P1)

**Goal**: an Advanced view on the player page with efficiency, team-context rates, per-36, game
score and plus-minus, across three windows. It gives percentiles against NBA position peers and
against players rostered in this league, with plain-language definitions.

**Independent Test**: quickstart V7, plus V11's manual cross-check with Basketball Reference
(SC-003).

### Tests for User Story 2

- [ ] T033 [P] [US2] Extend backend/src/test/java/com/ballknowers/draftsim/engine/AdvancedStatsWindowTest.java with every research R5 rate (`ts`, `efg`, `ftr`, `tpar`, `minutesShare`, `astPct`, `orbPct`, `drbPct`, `trbPct`, `stlPct`, `blkPct`, `tovPct`):
  - hand-computed values over 2–3 synthetic games with team and opponent rows;
  - pooling, not averaging per-game rates;
  - an overtime game's TmMP of 265;
  - zero denominators giving the right `reason`;
  - a game with no team row giving `NO_TEAM_ROW` for the team-context rates.
- [ ] T034 [P] [US2] Write backend/src/test/java/com/ballknowers/draftsim/engine/PercentilesTest.java for data-model "Percentiles":
  - the formula `100 × (below + 0.5 × tiesExcludingSelf) / (n − 1)`;
  - TOV% inverted, so a higher TOV% gets a lower percentile (I7);
  - n < 2 giving `GROUP_TOO_SMALL`;
  - an unqualified player giving `NOT_QUALIFIED`;
  - `LEAGUE_ROSTERED` under `UNAVAILABLE` or `NOT_DRAFTED` ownership giving `OWNERSHIP_UNAVAILABLE`;
  - each `Pct` carrying `{group, n}`.

### Implementation for User Story 2

- [ ] T035 [US2] Add the rates in research R5's table, including the team possessions estimate, to `Window` in backend/src/main/java/com/ballknowers/draftsim/engine/AdvancedStats.java. Each is pooled over the window's lines, using each line's own `teamRow` and `oppRow`; `TmMP` is the team row's `sp / 60`. `usage` stays the moved implementation from T011, not a copy.
- [ ] T036 [US2] Add `percentiles` (both groups, per rate, per window) and `windows.LAST_10`/`LAST_5` advanced fields to backend/src/main/java/com/ballknowers/draftsim/engine/PlayerStatsService.java and contract C1. Mirror them in web/src/api.ts in the same change.
- [ ] T037 [P] [US2] Add the advanced stats' plain-language definitions to web/src/statCopy.ts (FR-017): one or two sentences each, plus the R5 pooling deviation where it applies ("uses only the games he played"). Extend web/src/statCopy.test.ts to require a definition for every advanced key.
- [ ] T038 [US2] Add the Advanced view to web/src/pages/PlayerPage.tsx:
  - season, last-10 and last-5 side by side, with each window's actual game count;
  - small-sample labels;
  - a toggle between the NBA-position and rostered-in-league groups, showing each group's name, n and the exact value beside the percentile;
  - definitions beside each stat;
  - plus-minus labelled noisy.

  Extend web/src/pages/PlayerPage.test.tsx for the zero-attempt, small-sample and `OWNERSHIP_UNAVAILABLE` states.
- [ ] T039 [US2] Run quickstart V7 live, then V11: read Basketball Reference's 2025-26 (`NBA_2026`) TS%, eFG%, USG% and TRB% for 5 players in a browser and record each actual difference against the tolerances. A stat outside tolerance is reported with research R5's explanation and **not tuned**. Record the results in specs/022-player-stat-analysis/verification.md.

**Checkpoint**: US1 + US2 are shippable. Run the code review, then ask, merge and deploy.

---

## Phase 5: User Story 4, leaderboard, stat leaders and draft value (Priority: P2)

**Goal**: a sortable, filterable table of every player with column groups and a pinned name. It
has league rank, value over replacement, the draft pick and manager, Board ADP and Draft Grades'
value over slot, plus a stat-leaders view.

**Independent Test**: quickstart V8–V9: SC-009 (all 168 picks match), 2025 ADP reads
`NO_ADP_STORED`, values equal the player page (I6), the 375 px layout (SC-010), and timings
(SC-006).

### Tests for User Story 4

- [ ] T040 [P] [US4] Write backend/src/test/java/com/ballknowers/draftsim/engine/ReplacementLevelTest.java for data-model "Replacement":
  - slots are `roster_positions` without BN, IR and TAXI, ordered single positions, then G/F, then UTIL;
  - eligibility comes from `BasketballRules.isEligible`;
  - the `teams × slots` fill takes the best remaining qualified player;
  - each position's level is the best unplaced eligible player;
  - VOR is the max across eligible positions, and reports the position used;
  - ordering (I7): a lower replacement level gives a higher VOR;
  - a C-scarce league gives a lower C replacement than PG.
- [ ] T041 [P] [US4] Write backend/src/test/java/com/ballknowers/draftsim/engine/DraftAndAdpJoinTest.java:
  - a drafted player gets `{pickNo, round, managerName}`; an undrafted one gets null and no `draftValue` (I8);
  - a league that isn't `complete` gives `draft.state NOT_HAPPENED`;
  - ADP is `BoardRepository.asOf(NBA, "blend", draftDate)`, and no capture on or before that date gives `NO_ADP_STORED`;
  - `sleeper_search_rank` is never read as ADP;
  - `draftValue` equals `DraftGradesService` `valueOverSlot` for the same pick.
- [ ] T042 [P] [US4] Add `GET /api/leagues/{id}/stats?window=…` to backend/src/test/java/com/ballknowers/draftsim/api/AccessControlMvcIT.java. A controller test asserts that a missing or unknown `window` gives 400 (the window is never defaulted).
- [ ] T043 [P] [US4] Write web/src/statLeaderboard.test.ts:
  - the single comparator orders by value, then games, then name, then id, deterministically (I4);
  - the five column groups hold the right columns;
  - switching group keeps the sort, window and filters, and a sort column outside the group is not offered (FR-038);
  - stat leaders take the top `leaders-size` per category using the same comparator and qualification;
  - the free-agent, rostered and manager filters work.

### Implementation for User Story 4

- [ ] T044 [US4] Create backend/src/main/java/com/ballknowers/draftsim/engine/ReplacementLevel.java, a pure class for research R11. It takes the qualified players with `fpPerGame`, the league's `roster_positions`, `total_rosters` and the `SportRules`. It returns a record `Replacement(Map<String, Double> byPosition, String rule, int teams, List<String> slots)`, plus a per-player `(valueOverReplacement, vorPosition)`.
- [ ] T045 [US4] Add the draft, ADP and Draft Grades join to backend/src/main/java/com/ballknowers/draftsim/engine/PlayerStatsService.java:
  - the draft from `DraftRepository.forLeague` and `picks`;
  - managers via `RosterOwners.ownerNames`;
  - ADP via `BoardRepository.asOf(NBA, BoardRepository.SOURCE_BLEND, draft start date)` (research R10);
  - `draftValue` from `DraftGradesService.read(draft)` picks' `valueOverSlot`, matched by `pickNo` (research R9).
- [ ] T046 [US4] Add `readLeaderboard(LeagueRow, WindowKind window, String requester)` to backend/src/main/java/com/ballknowers/draftsim/engine/PlayerStatsService.java, building contract C2. `rows` covers every player with ≥1 game in the window. Ranks are over the qualified group only. A value for player P in window W **is** the value `readPlayer` returns for P and W, from the same computation (I6, FR-025). Add `GET /api/leagues/{sleeperLeagueId}/stats` to backend/src/main/java/com/ballknowers/draftsim/api/PlayerStatsController.java, with a required `window` param.
- [ ] T047 [US4] Add a C2 read IT, backend/src/test/java/com/ballknowers/draftsim/engine/PlayerStatsLeaderboardReadIT.java, over the real 2025 data:
  - all 168 picks match `draft_pick` (SC-009);
  - ADP is `NO_ADP_STORED`;
  - TS% and FP/G for 5 rows equal C1's (I6);
  - no `TEAM_` ids appear (I5).
- [ ] T048 [P] [US4] Add C2's types (`StatLeaderboard`, `LeaderboardRow`, `Replacement`, `getStatLeaderboard(leagueId, window)`) to web/src/api.ts, mirroring the Java records.
- [ ] T049 [P] [US4] Create web/src/statLeaderboard.ts, holding the single comparator, the column-group definitions (Basic, Shooting, Advanced, Fantasy, Draft value), the filters and the stat-leaders selection (research R14).
- [ ] T050 [US4] Create web/src/pages/StatLeaderboard.tsx with web/src/pages/StatLeaderboard.test.tsx. It has:
  - window and mode controls (per game, totals, per 36);
  - a group switcher, with a pinned name column and sideways scrolling inside the table;
  - filters for position, team and availability, showing the ownership as-of;
  - a qualification note with an off switch;
  - a replacement-rule note listing the level per position;
  - draft columns reading "undrafted" or "draft hasn't happened";
  - an ADP column with source and capture date, or "no ADP stored for this season";
  - Draft value labelled "per counted week, from Draft Grades" and linked to that page;
  - the stat-leaders view;
  - `PlayerLink` on every name.
- [ ] T051 [US4] Add the `stats` destination (sports `['nba']`, group `season`, one-season `ctx.season` href `/leagues/{id}/stats`) to web/src/destinations.ts, with a case in web/src/destinations.test.ts. Add the route to web/src/App.tsx.
- [ ] T052 [US4] Run quickstart V8 and V9 live:
  - V8: the 2025 draft and ADP checks, Draft Grades equality for 5 picks, and a hand check of one position's replacement fill.
  - The 375 px check of all five groups with `resize_window` and a screenshot (SC-010), then reset to desktop.
  - V9: the cache's cold-load time and heap, the token-query time, and C1/C2 warm times and gzipped sizes, locally and in production, plus Railway's container memory.

  Record every number in specs/022-player-stat-analysis/verification.md. Add compression only if V9's numbers call for it.

**Checkpoint**: US4 is shippable. Run the code review, then ask, merge and deploy.

---

## Phase 6: User Story 3, the nightly report (Priority: P2)

**Goal**: one page per night with every game and score, separate rankings by game score and by
this league's fantasy points, the member's roster lines (played and did-not-play), and standout
lines flagged by named rules.

**Blocked by**: T053 (FR-036). Build T054 onward only once T053 has a measured delay.

**Independent Test**: quickstart V10, three past 2025 nights including an overtime game (SC-004).

- [ ] T053 [US3] Run quickstart V12 on the first 2026 regular-season nights (from 2026-10-21): for 5 games, record the final time, the first refresh that stored both team rows and every player row, and when Sleeper's per-game endpoint first had the full box. Write the delay to specs/022-player-stat-analysis/verification.md, and amend research R13 with a dated note. If the delay is long, raise the ESPN fallback with Allan as a decision; it is **not** adopted here.

### Tests for User Story 3

- [ ] T054 [P] [US3] Write backend/src/test/java/com/ballknowers/draftsim/engine/NightlyReportServiceTest.java:
  - **Completeness**: complete only when every scheduled game on the date has `status = complete` and both team rows; otherwise `missingGames` counts what's missing.
  - **Default date**: the latest date with at least one complete game.
  - **Rankings**: game score and fantasy are separate, and ties are deterministic.
  - **"mine"**: played vs. didNotPlay. A player is didNotPlay when his team played and he has no line. Ownership is that night's week roster.
  - **Each standout rule** (data-model "Night"), at, just under and just over its threshold:
    - a season high requires `standout-min-prior-games` earlier games;
    - TS% compares against the season **before tonight**;
    - `MINUTES_JUMP_FREE_AGENT` is never evaluated under `UNAVAILABLE` ownership.
  - **No games**: a date with no games gives `NO_GAMES_ON_DATE`.
- [ ] T055 [P] [US3] Add `GET /api/leagues/{id}/nightly` to backend/src/test/java/com/ballknowers/draftsim/api/AccessControlMvcIT.java. A date outside the season gives 400.

### Implementation for User Story 3

- [ ] T056 [US3] Create backend/src/main/java/com/ballknowers/draftsim/engine/NightlyReportService.java, built on `SeasonBoxCache`, `SportScheduleRepository`, `AdvancedStats.gameScore`, `GameScoringService` and `PlayerOwnership` (week-scoped), returning contract C3. Standout thresholds come from `PlayerStatsProperties`, and `standoutRules` echoes them.
- [ ] T057 [US3] Create backend/src/main/java/com/ballknowers/draftsim/api/NightlyReportController.java with `GET /api/leagues/{sleeperLeagueId}/nightly?date=` (optional; the default is the stated rule, and the response names the date it answers about).
- [ ] T058 [P] [US3] Add C3's types (`NightlyReport`, `NightLine`, `Standout`, `getNightlyReport(leagueId, date?)`) to web/src/api.ts, and the standout rule sentences to web/src/statCopy.ts. Each rule's sentence shows its comparison values, such as "season high in points: 41, previous high 33".
- [ ] T059 [US3] Create web/src/pages/NightlyReport.tsx with web/src/pages/NightlyReport.test.tsx. It has a date picker from `dates`, the game list with scores, two labelled rankings, "your players" (played and didn't), standouts with the rule stated, an incomplete-night banner with `dataAsOf`, and a no-games state. Every name uses `PlayerLink`.
- [ ] T060 [US3] Add the `nightly` destination (sports `['nba']`, group `thisWeek`, one-season href) to web/src/destinations.ts, and the route to web/src/App.tsx. Link the spotlight's "Top of the night" header to `/leagues/{id}/nightly?date={that night}` in web/src/components/PlayerSpotlight.tsx, with tests in web/src/destinations.test.ts and web/src/components/PlayerSpotlight.test.tsx.
- [ ] T061 [US3] Run quickstart V10 live on three past 2025 nights, including one with an overtime game, and record the results in specs/022-player-stat-analysis/verification.md.

**Checkpoint**: US3 is shippable. Run the code review, then ask, merge and deploy.

---

## Phase 7: Polish and cross-cutting

- [ ] T062 [P] Add a basketball-only source-scan test in backend/src/test/java/com/ballknowers/draftsim/engine/PlayerStatsSportRuleScanTest.java, on spec 014's pattern. `PlayerStatsService`, `NightlyReportService` and `AdvancedStats` must never compare a sport by string or enum; the gate goes through `SportRules`.
- [ ] T063 [P] Update HANDOFF.md and README.md with what shipped, what's verified, and what's still owed (the V12 delay, the V9 production numbers, V11's differences). Add a memory note for any durable lesson to claude/lessons.md.
- [ ] T064 Write the bug-hunting code review for each chunk to specs/022-player-stat-analysis/code-review.md (a separate pass, not a style pass), then address its findings, with dated amendment notes where a doc was wrong.

---

## Dependencies and execution order

### Phase dependencies

- **Setup (T001–T004)**: T001, the plan review, blocks everything. T002 and T003 can then run in parallel, and T004 follows T003.
- **Foundational (T005–T016)**: blocks every story. Within it:
  - T005 comes first, as the baseline.
  - The test/implementation pairs come next: T006→T007, T008→T009, T010→T011 and T013→T014.
  - T012 comes before T014.
  - T015 is independent.
  - T016 is the gate.
- **US1 (T017–T032)**: needs the foundation. It is the MVP.
- **US2 (T033–T039)**: needs US1 (it extends `AdvancedStats.Window`, `PlayerStatsService` and the page).
- **US4 (T040–T052)**: needs US1 and US2, because it reuses every rate, rank and percentile qualification.
- **US3 (T053–T061)**: needs the foundation, plus `PlayerOwnership` (T022) and `AdvancedStats.gameScore` (T021). **T053 is gated on the calendar (2026-10-21 or later).** US3 can be built in parallel with US4 once T053 is done.
- **Polish (T062–T064)**: T062 can start after US1. T063 and T064 run per chunk.

### Within each story

The tests are written first and fail before the implementation. The order is pure core, then the
read path, then the controller, then `api.ts` (in the same change as the records), then the page,
then live verification.

### Parallel opportunities

- **Setup**: T002 ∥ T003.
- **Foundation tests**: T006 ∥ T008 ∥ T010 ∥ T013, each in its own file.
- **US1**: tests T017 ∥ T018 ∥ T019 ∥ T020; then web T026 ∥ T027 ∥ T028 while the backend's T021–T025 proceed.
- **US2**: T033 ∥ T034, then T037 alongside T035.
- **US4**: T040 ∥ T041 ∥ T042 ∥ T043; then T048 ∥ T049 alongside T044–T047.
- **US3**: T054 ∥ T055; T058 alongside T056 and T057.

## Parallel example: User Story 1

```text
# Tests first, in parallel (separate files):
T017 AdvancedStatsWindowTest.java   T018 PlayerStatsServiceTest.java
T019 PlayerOwnershipTest.java       T020 AccessControlMvcIT.java (route entry)

# Web scaffolding in parallel with backend T021–T025:
T026 web/src/api.ts   T027 web/src/statCopy.ts   T028 web/src/components/PlayerLink.tsx
```

## Implementation strategy

### MVP first (US1)

1. Setup and the plan review (T001–T004).
2. The foundation, with its Trends-unchanged gate (T005–T016).
3. US1 (T017–T032). Stop, review, verify live, then ask, merge and deploy. This is the hub every
   later chunk links to.

### Incremental delivery

- US2: the Advanced view on the same page. Ship.
- US4: the leaderboard, stat leaders and draft value. Ship.
- US3: the nightly report, once V12 exists, from 2026-10-21. Ship.

Each chunk is a separate PR from this branch's worktree. Ask before every commit, because
concurrent sessions share the tree.

## Notes

- **[P]** marks tasks in different files with no dependency on an incomplete task.
- Every hand-set number lives in `weights.yml` and is labelled ARBITRARY. None is ever tuned to
  match a Basketball Reference figure or an earlier estimate (constitution rule 3).
- `web/src/api.ts` changes in the **same** task or PR as the Java records it mirrors.
- If a task needs a migration, that is a plan failure. Stop and raise it, because none is planned.
