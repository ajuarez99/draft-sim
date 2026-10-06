---
description: "Task list for spec 019: NBA minutes trends and streaming candidates"
---

# Tasks: NBA minutes trends and streaming candidates

**Input**: `specs/019-minutes-streaming/`: plan.md (including "Amended after review"), spec.md,
research.md (R1–R7), data-model.md (rewritten after review), contracts/api.md, quickstart.md
(V1–V8), plan-review.md (F1–F13, N1–N17). Generated after the review dispositions.

**Tests**: requested. Failing tests first, then live verification (repo bar).

**Deadline**: production before **2026-10-20**.

**Rules** (AGENTS.md):
- Coding runs on Sonnet subagents, and the parent reads each diff.
- Migrations are append-only.
- `api.ts` mirrors records in the same change.
- No `Map.of` with nulls.
- Ask before committing.
- Work only in `.claude/worktrees/019-minutes-streaming`.

Paths: `B/` = `backend/src/main/java/com/ballknowers/draftsim/`, `T/` = `backend/src/test/java/com/ballknowers/draftsim/`.

## Phase 1: Setup

- [X] T001 Confirm the highest migration is V27 (`ls backend/src/main/resources/db/migration | sort -V | tail -1`) and that `origin/main` hasn't moved past `787abd9`. If V28 is taken, renumber everywhere and note it in plan.md

## Phase 2: Foundational (blocking)

- [X] T002 [P] F11: extract `B/engine/RosterOwners.java` with `static Map<Integer, RosterOwner> ownerNames(List<RosterSeasonRepository.StandingRow>, List<LeagueMemberRepository.MemberRow>, Long callerManagerId)` returning `RosterOwner(String name, String avatarId, boolean isMe)`. Use the exact rule in `SpotlightOwnership.build` (`:60-97`): team name, else manager name, else "Roster N". Switch `SpotlightOwnership.build` to call it, with existing `SpotlightOwnership*` tests unchanged and green. Add `T/engine/RosterOwnersTest.java`, including one case asserting Spotlight and `ownerNames` give the same name for the same roster
- [X] T003 Write `backend/src/main/resources/db/migration/V28__roster_players.sql`: `alter table roster_season add column players text[]; alter table roster_season add column players_fetched_at timestamptz;`
- [X] T004 `B/store/RosterSeasonRepository.java`:
  - `Upsert` gains `List<String> players` (nullable) and `OffsetDateTime playersFetchedAt` (nullable).
  - The upsert writes both. Use a `JdbcTemplate` with `connection.createArrayOf("text", …)` as `LeagueRepository:54` does (N4). Null stays null.
  - Add `Optional<Rostered> rosteredPlayers(long leagueId)` with `record Rostered(Map<String,Integer> byPlayer, OffsetDateTime fetchedAt)`. It's empty if any row's `players` is null, `fetchedAt` is the **min** (N9), and a player on two rosters → first by roster id.
- [X] T005 `B/ingest/LeagueHistoryIngestService.java` `ingestStandings` (`:178`): build `players` as the de-duplicated, sorted union of the roster map's `players`, `reserve` and `taxi` lists (any may be null or absent; values are strings). Pass it plus the refresh's `Instant`/`OffsetDateTime`. It's the single `Upsert` producer, so update its constructor call
- [X] T006 [P] `T/store/RosterSeasonRepositoryPlayersIT.java` (DB IT, the repo's skip convention): upsert with `["1","2"]`, with `[]` and with null, read back via `rosteredPlayers` and raw SQL (`cardinality`). A null row gives an empty Optional. Re-upsert overwrites
- [X] T007 [P] `B/store/PlayerGameRepository.java`: `public static final String TEAM_ID_PREFIX = "TEAM_"`. Add `seasonPlayerGames(Sport, int season)`, returning only `sleeper_player_id, game_id, game_date, opponent, stats`, with `sport = ?` and `left(sleeper_player_id, 5) <> 'TEAM_'` (N2, N3). Add `seasonTeamGames(Sport, int season)`, the team rows (`left(...) = 'TEAM_'`) as `TeamGame(String code, String gameId, LocalDate date, String opponent, Map stats)`, where code is the suffix (may be empty: the All-Star bare `TEAM_`, F9)
- [X] T008 `B/config/PlayerTrendsProperties.java`, a record bound to `draftsim.player-trends` with Integer `roleThresholdMinutes`, `minSeasonGames`, `formGames`, `formMinGames`, `recencyDays`, `listSize`, `streamingSize` and Double `oneGameShare`. `loaded()` = all non-null. Validate ranges when present (positive; `oneGameShare` in (0,1]). A missing block means null fields. Register in `B/DraftSimApplication.java`. Add the block to `config/weights.yml` (values: 6, 5, 5, 3, 14, 10, 20, 0.9) with an ARBITRARY comment citing research R5 and review F7. Add `"playerTrendsLoaded"` to `B/api/HealthController.java` and its test. Write `T/config/PlayerTrendsPropertiesTest.java`

**Checkpoint**: migration applies on bootRun, and the IT passes with 0 skipped.

## Phase 3: US1 + US2, the trends and streaming endpoint (P1) 🎯 MVP

### Tests first (must fail before T011)

- [X] T009 [P] [US1] Write `T/engine/PlayerTrendsServiceTest.java` against a pure `PlayerTrendsService.compute(Input)`. Cases, one test each:
  - 10 games at 18 min then 3 at 32 → RISER +14;
  - 10 at 30 with the last three 30, 30, 8 → STEADY (median);
  - `TEAM_*` and All-Star (opponent not in team codes) games never counted, and no `TEAM_` in output (F9);
  - pooled usage equals Σnum/Σden on a hand example, including an overtime team row (TmMIN 265) and a game with no team row skipped (F12);
  - a missing `to`/`fga` key counts as 0;
  - a stale player (last game 60 days before the season's last game) is excluded and counted in `excludedStale` (F2);
  - a player with null team is excluded and counted in `excludedNoTeam` (F2, F10);
  - the per-list cutover: the new season has teams with 1–2 games → both lists read the previous season, with `*Fallback` true; half the teams at ≥ 3 but < 5 → streaming new and roles previous (F1);
  - streaming sorted by seasonPts, not formPts: a player with a higher form but lower season mean ranks lower (F7, preference ordering);
  - streaming excludes rostered players, and every streaming row has `rostered == false`;
  - streamingReason precedence: complete → `SEASON_COMPLETE`; `pre_draft` with a NON-empty rostered map → `NOT_DRAFTED` (F5); in season with `rosteredPlayers` empty → `ROSTERS_NOT_LOADED`;
  - with any streamingReason, every row's `rostered` is null (F4);
  - list sizes, totals, and ties (→ higher recentMin, then id);
  - `missedTeamGames` counts the most recent consecutive `TEAM_PLAYED_NO_ENTRY` absences;
  - `gamesThisWeek` is looked up by week number (N8), and null for a complete season (N7);
  - `oneGameCredit`: share ≥ 0.9 → present, else null.
- [X] T010 [P] [US1] Write `T/api/PlayerTrendsControllerTest.java`: `NOT_CONFIGURED`, `NOT_BASKETBALL` (an NFL league), `NO_GAMES`, and 404 for an invisible league. Add `/api/leagues/<it league>/player-trends` to `T/api/AccessControlMvcIT.java` for no header, blank header and stranger → 404, and member → 200

### Implementation

- [X] T011 [US1] `B/engine/PlayerTrendsService.java`:
  - **Pure core `compute(Input)`**, following data-model "Which season each list reads", "Derived values", "Lists", "Streaming availability" and "One-game note". Records `TrendRow`, `PlayerTrends`, `OneGameCredit`, and `Windows` exactly as in contracts/api.md (field names, nullability; `rostered` is a `Boolean`).
  - **`read(LeagueRow, String sleeperUserId)`** wires the inputs:
    - seasons N and N−1 via `seasonPlayerGames`/`seasonTeamGames`;
    - absences via the existing `PlayerAbsenceRepository` (find its read; add a season read only if none exists);
    - players via `PlayerRepository.byIds` for team and positions;
    - scoring via `LeagueRepository.scoringOf`;
    - the grid via `ScheduleGridService.forLeague`;
    - rosters via `RosterSeasonRepository.rosteredPlayers`, with owner names via `RosterOwners.ownerNames`;
    - league status via `LeagueRow`.
  - **`oneGameCredit`**: computed from this league's `RosterWeekPointsRepository.breakdownsFor` + `player_game` (or the previous season in the chain when it has no weeks). It's the share of multi-game starter-weeks whose credited points equal one game's score, rounded to 2 dp.
  - The basketball gate is `playsMultipleGamesPerScoringPeriod()`.
  - Make T009 pass.
- [X] T012 [US1] `B/api/PlayerTrendsController.java`: `GET /api/leagues/{sleeperLeagueId}/player-trends`, with `LeagueMembership.visibleLeague` → 404. Make T010 pass
- [X] T013 [US1] `T/engine/PlayerTrendsReadIT.java` (DB IT): NBA 2025 league `1229352720222134272` → `rolesFallback` false, `streamingReason` SEASON_COMPLETE, contract invariants 1, 3, 4, 5, 7 and 8. NBA 2026 `1339351318115946496` → both fallbacks true with season 2025, `streamingReason` NOT_DRAFTED (it's `pre_draft` until 10-10), and `oneGameCredit` non-null. Report Jokić's (1658) rows/values if present and the request time
- [X] T014 [US2] Web: in `web/src/api.ts`, add types + `getPlayerTrends` exactly as the contract's TS mirror. In `web/src/destinations.ts`, add the `trends` destination (`sports: ['nba']`, label "Trends") the way spec 017 added `schedule`, updating `DestinationKey`. In `web/src/App.tsx`, add the route `/leagues/:sleeperLeagueId/trends`
- [X] T015 [US2] `web/src/playerTrends.ts` + test: `streamingReasonSentence` (no "ingest" or "/api/"; SEASON_COMPLETE promises no refresh); `oneGameNote(credit)` (the sentence with the measured +4% and its provenance from data-model "One-game note"; null when credit is null); fallback labels per list
- [X] T016 [US2] `web/src/pages/PlayerTrends.tsx` + `.test.tsx`, per the contract's UI contract:
  - streaming first;
  - risers/fallers split only when `rostered` is non-null;
  - excluded counts stated;
  - every number labelled with its window;
  - keyed on `useLeagueDataVersion`;
  - tables scroll inside their card at 375px.

  Vitest: the fallback label, no streaming table with a reason, the unsplit lists when `rostered` is null, and the note only with `oneGameCredit`.

## Phase 4: US3, rostered between draft and week 1 (P2)

- [X] T017 [US3] Live: bootRun from this worktree (verify the PID/classpath), let V28 apply, and trigger a refresh of NBA 2026 (visit, or the refresh route with the local secret). Then query `roster_season` for league 210: 12 rows with `players = '{}'` and `players_fetched_at` set (pre-draft). Record in verification.md. After 10-10: compare with Sleeper `/rosters` (dated check, quickstart V1/V4)

## Phase 5: Polish & verification

- [X] T018 Amend `HANDOFF.md` (019 section) and the roadmap status line
- [X] T019 Full regression: backend `./gradlew test` (report total and **skipped**), web tsc/vitest/build
- [X] T020 Live verification, quickstart V2–V7, recorded run/not-run in `specs/019-minutes-streaming/verification.md`, with screenshots
- [X] T021 Bug-hunting code review as a separate pass → `code-review.md`. Fix confirmed findings via Sonnet
### Fixes from code review (2026-10-06)

- [X] T021a Backend + web fixes for code-review B1–B7 and the label note, per data-model "Amended after code review". Tests for each. Then the full backend suite and web tsc/vitest/build

- [ ] T022 Ask Allan → commit, PR, merge, deploy both services, production V4 before 10-20. The dated post-draft check (10-11…10-19) and cutover dates (10-20…10-29) are recorded later

## Dependencies

- Phase 2 before 3.
- T002 is independent.
- T003 → T004 → T005 → T006.
- T007 and T008 are independent.
- T009/T010 before T011/T012.
- T014–T016 can start once the contract is fixed (parallel with the backend).
- T017 needs T003–T005.
- Then polish.
