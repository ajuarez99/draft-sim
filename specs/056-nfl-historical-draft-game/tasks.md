# Tasks: Football Time Machine (spec 056)

> **Renumbered 2026-11-06:** this feature's migration is now `V46__historical_nfl.sql` (057 keeps V45, Allan's call; 057 merges first). **Amended 2026-11-08:** 056 merged first after all, since 057 was still uncommitted; 057 renumbers to V47. See plan.md. "V45" below means this migration under its old number.

**Input**: `specs/056-nfl-historical-draft-game/` (spec, plan, research, data-model, contracts, quickstart, plan-review).
Every "Amended after review" section wins over the text above it.

**Tests**: these are required here. AGENTS.md treats a green suite as necessary but not sufficient, and
scoring/ranking changes need preference-ordering tests (bug class 1).

**Paths**: backend = `backend/src/main/java/com/ballknowers/draftsim/`, tests =
`backend/src/test/java/com/ballknowers/draftsim/`, web = `web/src/`.

**Base**: branch `056-nfl-historical-draft-game` = `origin/main` @ 5a47471 (053 merged). Nothing here edits V43/V44.

## Phase 1: Setup

- [x] T001 Re-check that V45 is free on `origin/main` and every branch (`git log --all --diff-filter=A -- 'backend/src/main/resources/db/migration/V45*'` shows only the reverted cf84da0) and on the shared 5433 `flyway_schema_history` (highest = 44). Record the result in `specs/056-nfl-historical-draft-game/research-notes.md`.
- [x] T002 Start a scratch Postgres `draftsim-pg-056` on port **5439** (docker, trust auth, db/user `draftsim`). Add launch entries `056-api` (bootRun from THIS worktree, port 8156, `ADMIN_TOKEN=local-056`, datasource → 5439) and `056-web` (vite 5256, proxy → 8156) to the **main checkout's** `.claude/launch.json`, without touching other entries. Before using a port, check it isn't already in another entry.
- [x] T003 [P] Measure and record in `research-notes.md` (T-measure): REG game counts and max week for all 27 nflverse files 1999–2025, blank-id rows split into "team rows" and "rows with a skill position or a scored stat" (F7), and the PPR agreement of FR-003 with INT −2 against `fantasy_points_ppr` for 2000/2012/2023. Use a scratchpad Python script, never in the repo.

## Phase 2: Foundational (blocks every story)

- [x] T004 Write `backend/src/main/resources/db/migration/V45__historical_nfl.sql` in **exactly** the data-model "Amended after review" F2 order:
  1. add `historical_season.sport text not null default 'NBA' check (sport in ('NBA','NFL'))`;
  2. add `historical_game.sport text not null default 'NBA'`;
  3. drop `historical_game_season_start_fkey`;
  4. drop `historical_season_pkey`;
  5. add PK `(sport, season_start)`;
  6. add FK `historical_game (sport, season_start) → historical_season (sport, season_start)`;
  7. replace `historical_season_source_check` and `historical_player_source_check` to allow `'KAGGLE','SLEEPER','NFLVERSE'`;
  8. `opening_night`/`last_counted_date` drop not null; add `expected_game_count int`, `dropped_rows int not null default 0`, `source_sha256 text`;
  9. `alter column sport drop default` on both tables (F8);
  10. create `historical_nfl_player_week` (PK `(player_id, season, week)`, the 13 stat columns `numeric not null default 0`, `src_ppr numeric`, `position text not null`, `game_id`, `team`, `opponent`, index `(season, week)`);
  11. create `historical_nfl_player_season` (PK `(player_id, season)`, `team`, `games int not null`, `positions text[] not null`, the 13 stat columns);
  12. create `historical_nfl_draft_pick` (PK `player_id`, `season`, `round`, `pick`, `position`).

  No CASCADE. Header comment as in V43. **Apply it to 5439 on top of a V43+V44 DB** and confirm every statement ran (bug class 2).
- [x] T005 [P] `historical/StatLine.java` + `historical/WeekStatLine.java` (data-model "Java shapes"). In `historical/HistoricalDataRepository.java`, `SeasonLine implements StatLine` with `stat(k)` = the body of `HistoricalBoardBuilder.total` moved verbatim (returns null for an unrecorded stat, so callers keep the 0 rule), and `GameLine implements WeekStatLine` with `stat(k)` = the `SeasonReplay.statMap` switch moved verbatim. **NBA arithmetic must not change** (SC-008).
- [x] T006 Make the four season statements sport-scoped (F3): `HistoricalDataRepository.upsertSeason` (`on conflict (sport, season_start)`, a `sport` column in insert/update, the new nullable columns), `findSeason(sport, s)`, `offeredSeasons(sport, from, to)` (its prior-lines `exists` reads `historical_player_season` for NBA and `historical_nfl_player_season` for NFL; `FIRST/LAST_OFFERED` per sport: NBA 1970..2025, NFL 2000..2025), and `HistoricalGameRepository.summaries` (join on `s.sport = g.sport and s.season_start = g.season_start`, return `sport`). `HistoricalGameRepository.create` takes and inserts `sport` explicitly. `GameRow` gains `sport`. `SeasonRow` gains `sport, expectedGameCount, droppedRows, sourceSha256`. Update every NBA caller to pass `"NBA"`, including the import services and controller `seasons`.
- [x] T007 `config/NflHistoricalProperties.java`: a **separate** `@ConfigurationProperties(prefix = "historical-nfl")` record, so 053's `HistoricalProperties` constructor and its IT callers are unchanged (F10). Fields: `statsUrlTemplate` (`…/stats_player/stats_player_week_{season}.csv.gz`), `draftPicksUrl`, `teams 12`, `rounds 15`, `roster` (`QB,RB,RB,WR,WR,TE,FLEX` + 8 `BN`), `reversalRound 0`, `scoring` (FR-003 as amended: pass_yd 0.04, pass_td 4, pass_int −1, rush_yd 0.1, rush_td 6, rec 1, rec_yd 0.1, rec_td 6, fum_lost −2, pass_2pt 2, rush_2pt 2, rec_2pt 2, st_td 6), `rankStandardGames 17`, `rankMinGames 6`, `rankLookbackSeasons 3`, `rookiePositionalOffset 12`, `rookieMaxRound 2`, `expectedGames` (a map: 2000:248, 2001:248, 2002–2020:256, 2021:272, 2022:271, 2023–2025:272, written out per year), `firstOffered 2000`, `lastOffered 2025`. The same validation style as `HistoricalProperties`. Add it to `application.yml` under `historical-nfl:` with each hand-set value commented "hand-set (spec 056 …)", and the game counts commented "schedule fact". Register it wherever `HistoricalProperties` is enabled. Add `HistoricalPropertiesTest`-style coverage in `config/NflHistoricalPropertiesTest.java`.
- [x] T008 [P] `historical/StatOrder.java`: append the NFL keys after the NBA keys, in the order `pass_yd, pass_td, pass_int, rush_yd, rush_td, rec, rec_yd, rec_td, fum_lost, pass_2pt, rush_2pt, rec_2pt, st_td` (NBA order unchanged).
- [x] T009 `historical/HistoricalSport.java` (interface) + `NbaHistoricalSport` (wraps today's exact behaviour: `HistoricalProperties`, `LeagueShape.NBA_ROSTER`, `HistoricalBoardBuilder.label`, the NBA COUNTING keys, `HistoricalGameState.SETTINGS_SOURCE`, `HistoricalDataRepository` access) + `HistoricalSports` (lookup by `"NBA"/"NFL"`, throws `IllegalArgumentException` on an unknown sport → 400). `NflHistoricalSport` lands in T020.
- [x] T010 Generalize `historical/HistoricalBoardBuilder.java`: `build(...)` takes the `Sport`, the counting keys, a label function, a ranking mode and the rookie inputs. It reads lines through `StatLine.stat(k)`. Remove the `Sport.NBA` literals at the position check and `toEntry` (`toEntry(card, sport)`). **The NBA mode must produce byte-identical cards**: run `HistoricalBoardBuilderTest` unchanged.
- [x] T011 Generalize `historical/HistoricalGameService.java`: `create(user, sport, slot, seasonOverride)` resolves `HistoricalSport`. Every `props.*`, `Sport.NBA`, `NBA_ROSTER`, `PositionalPriors.uniform(...)`, `LeagueSettings(...)` and `rulesRegistry.get(...)` reads the game row's `sport`. `readSnapshot` reads optional `dataNotes`/`attribution` with `has()` (F9), and writes them for new games. `state()` fills `sport`, `dataNotes`, `attribution` and the per-sport `settingsSource`/`rankingLabel`.
- [x] T012 `historical/HistoricalGameState.java` (+ `GameSummary.sport`, `SeasonView.dataNotes` (never null), `SeasonView.attribution` (nullable)) and `HistoricalPlayerCard` (+ `rookiePick Integer`, nullable; `Prior.totals Map<String, Double>`, never null, nonzero keys only). **In the same task**, mirror every field in `web/src/api.ts` (hard rule).
- [x] T013 `historical/SeasonReplay.java`: `statMap` reads `WeekStatLine.stat(k)` (null → 0). Null-safe `l.date()` at the starters loop (F4). `Input.games` becomes `List<? extends WeekStatLine>`. Keep `weekStarts` empty for NFL.
- [ ] T014 Regression checkpoint: `./gradlew test` on 5439 with **0 skipped ITs** (memory: the suite skips ITs silently when Postgres is down; check the skip count). All 053 tests are green, unchanged. *(2026-11-05 build: NOT ticked. 2688 run, 13 failed, 54 skipped on a fresh scratch DB; the 13 failures are byte-identical on base 5a47471, the 54 skips are real-data absence, 2 classes excluded. Details and the exact 053 test edits are in research-notes.md.)*

**Checkpoint**: basketball works exactly as before on a V45 database.

## Phase 3: User Story 1, play a football season (P1) 🎯 MVP

**Goal**: start an NFL game, draft QB/RB/WR/TE against bots, and get a 17/18-week best-ball replay.
**Independent test**: quickstart §2 + SC-003.

- [x] T015 [P] [US1] `historical/NflverseSource.java`: download `.csv.gz` per season with 053's redirect-following `HttpClient` construction (`KaggleHistoricalSource.java:176`, F11), stream through `GZIPInputStream` + 053's `KaggleCsv` reader, and keep REG rows only.
  - **Header check**: fail loudly if any expected column is missing.
  - Map `position` FB/HB → RB, keep QB/RB/WR/TE, drop the rest.
  - `fum_lost` = the sum of the three `*_fumbles_lost`. The 2pt keys are the three `*_2pt_conversions`. `st_td` = `special_teams_tds`. NA → 0.
  - Blank-id rows: count them all in the report; count as `dropped_rows` only the ones with a skill position or a nonzero scored stat (F7).
  - Fail the season loudly on a duplicate `(player, week)`.
  - Aggregate the season lines (R5): games = REG weeks with a row, team = last row's.
  - Record the file SHA-256 and byte count.
  - Unit test `historical/NflverseSourceTest.java` over a small hand-written fixture CSV: the column map, FB→RB, the drop rule, the duplicate failure, a missing-header failure and aggregation.
- [x] T016 [P] [US1] `historical/NflHistoricalRepository.java`: upsert `historical_player` (source `NFLVERSE`, key = GSIS id), replace one season's week and season rows in one transaction, `upsertSeason` (sport NFL, label `"2007"`, counted_stats = all 13 scoring keys, `excluded_stats`/`stat_coverage` `{}`, ranking_source `PRIOR_SEASON_POINTS`, week_count = max REG week, `expected_game_count`, `dropped_rows`, `source_sha256`), `candidates(season)`, `lines(season)` → `NflSeasonLine implements StatLine` (age null), `playedWeeks(season, ids)` → `NflWeekLine implements WeekStatLine` (gameDate null), and draft picks (replace/load). Use `batchUpdate` or COPY for the week rows. IT: `historical/NflHistoricalRepositoryIT.java`.
- [x] T017 [US1] The draft picks import in `NflverseSource`/`HistoricalImportService`: fetch `draft_picks.csv`, keep skill positions, and keep only `gsis_id`s that are existing `historical_player` NFLVERSE rows, one row per player (his first draft). It runs after the seasons in the same NFLVERSE job.
- [x] T018 [US1] `historical/HistoricalImportService.java`: add part `NFLVERSE` (`ALL` stays NBA-only, per contract). Seasons 1999..2025, one transaction each, report rows per contract (`games, expectedGames, playerWeeks, playerSeasons, droppedRows, rawBlankIdRows, fileSha256, fileBytes`). Update the 400 message and `HistoricalImportControllerIT` for the new part.
- [x] T019 [US1] Ranking in `HistoricalBoardBuilder` for NFL (F6, F14). **Preference-ordering tests first**, in `historical/HistoricalBoardBuilderNflTest.java`:
  - (a) A veteran's score = PPR/game × `rankStandardGames` − the replacement score, where replacement = the score of the Nth qualifying veteran at the same position on this board, N = teams × that position's **dedicated** starter slots from the roster (QB 12, RB 24, WR 24, TE 12; fewer veterans → the last one's score).
  - (b) Test: with a QB at 20 ppg and a WR at 16 ppg, where the 12th QB is 17 and the 24th WR is 10, **the WR ranks above the QB**.
  - (c) A rookie with an NFL pick in rounds ≤ `rookieMaxRound` is valued as the veteran at positional rank `rookiePositionalOffset` + his order (by overall pick) among same-position early rookies. Test: an early rookie RB ranks above the 30th veteran RB and below the 5th, and a round-3 rookie stays in the seeded tail.
  - (d) The newest qualifying season within the lookback, ≥ `rankMinGames`.
  - (e) The returned list is rank-ascending and ties break by id.

  Card fields: `prior.perGame` (1 decimal) for the yardage/TD/rec/int keys, `prior.totals` for `st_td, fum_lost, pass_2pt, rush_2pt, rec_2pt` when nonzero (F16), `prior.fantasyPoints` = the season's PPR total, `rookiePick` for rookies, and `label` = `String.valueOf(year)`.
- [x] T020 [US1] `historical/NflHistoricalSport.java`: `Sport.NFL`, roster/teams/rounds/scoring/rank from `NflHistoricalProperties`, label `"2007"`, the ranking mode from T019, settingsSource = `"Hand-set 12-team, 15-round roster: QB, 2 RB, 2 WR, TE, FLEX + 8 bench · full PPR"`, rankingLabel = `"ranked by last season's points over replacement"`, attribution = `"Stats: nflverse (nflverse-data), CC BY 4.0"`.
  - dataNotes: `"{loaded} of {expected} regular-season games are in the data"` when they differ, `"{n} player rows had no player id and were left out"` when `dropped_rows > 0`, and always `"Positions are each player's current listing, not necessarily his position that season"` (O2).
- [x] T021 [US1] `api/HistoricalGameController.java`: `sport` is **required** in `CreateRequest` and in `GET /seasons?sport=` (400 when missing or unknown, no default; contracts). `SeasonSummary` gains `sport`, `dataNotes`. Remove the controller's own `SETTINGS_SOURCE` copy (F10) and read it from `HistoricalSport`. Update `HistoricalGameMvcIT`/`HistoricalSeasonsMvcIT` to send `"sport":"NBA"`, and add 400 tests.
- [x] T022 [US1] IT `historical/NflGameFlowIT.java` on a **synthetic** two-season NFL fixture (prior + drafted, ≥ 200 skill players, two of them sharing a display name with different ids):
  - create(NFL) → the board has only QB/RB/WR/TE and no drafted-season numbers in any card (FR-004);
  - auto FINISH → COMPLETE, `weekCount` weeks, the standings sum to 12 teams;
  - one team-week checked by hand against the fixture (SC-003 shape) — compare **totals and lineup sets**, not JSON order (F12);
  - a bye week scores 0 and the bench fills in;
  - FLEX takes the best remaining RB/WR/TE while TE stays a TE (US1 scenarios 4–5);
  - both same-name players are separate cards (SC-007);
  - no "ADP" in the serialized state (SC-006);
  - `starters[].gameDate` is null and nothing throws (F4).
- [x] T023 [US1] IT `historical/SportCoexistenceIT.java` (F3, F9, SC-008):
  - insert NBA season 2005 and NFL season 2005 → `GET /api/history/games` returns exactly one row per game, with the right sport and label;
  - an NBA `upsertSeason` re-run succeeds;
  - an NBA game row whose `board` is a **053-shape fixture string** (no `dataNotes` key) reopens without error and with an identical `result`;
  - `offeredSeasons("NBA")` never returns an NFL row, and vice versa.
- [x] T024 [P] [US1] Web:
  - `pages/HistoricalDraft.tsx`: a sport choice (Basketball / Football) before Start, which sends `sport`, and `GET /seasons?sport=`. Per-sport copy, so no "real NBA season / 1970-71" on football.
  - `api.ts`: the client functions take `sport`.
  - `pages/HistoricalRoom.tsx`: `sport` from state for `DraftBoard`/`posPill`. Show `SeasonDisclosure` per sport: for NFL, the scoring list, `dataNotes` and `attribution`, not the coverage/bonus copy.
  - `components/HistoricalPlayerList.tsx`: filters `ALL QB RB WR TE` for NFL.
  - `historicalCopy.ts`: NFL `STAT_ORDER`/`SHORT` labels (pass yd, pass TD, INT, rush yd, rush TD, rec, rec yd, rec TD, fum lost, 2pt, ret TD); hide zero per-game keys; show `totals` as "2 ret TD"; the rookie line `"rookie · NFL pick #5"` or `"no NFL stats last season"`; the positions default for NFL is not `'SF'`.
  - `components/HistoricalResults.tsx`: when `startDate`/`gameDate` is null, show "Week N" and never "did not play" for a starter (F4). Per-sport copy at the locked-game note.
- [x] T025 [P] [US1] Web tests: extend `pages/HistoricalDraft.test.tsx` and `pages/HistoricalRoom.test.tsx` with an NFL state fixture. Check that the sport is sent, the QB/RB/WR/TE filters, the attribution and data note are visible, no "ADP" text, "Week N" in results, and that the NBA fixtures still pass unchanged. Run `npx tsc -b && npm run build && npx vitest run`.

**Checkpoint**: a football game plays end to end on synthetic data.

## Phase 4: User Story 2, comparisons (P2)

- [x] T026 [US2] Confirm that `Comparisons` (hindsight, bust, steal) works unchanged for NFL. Add an NFL case to `historical/ComparisonsTest.java` with a pick-3-finishes-150th bust and a late-pick star who is never the bust (preference ordering, bug class 1). Fix only if the test fails.

## Phase 5: User Story 3, provenance (P2)

- [x] T027 [US3] Verify, in `NflGameFlowIT`, that a season with expected ≠ loaded shows the games note, the attribution is on every NFL state, the ranking label has no "ADP", and an NBA state has `attribution` null and its 053 disclosure unchanged.

## Phase 6: User Story 4, past games list (P3)

- [x] T028 [US4] `pages/HistoricalDraft.tsx` past-games list: a sport tag per row, from `GameSummary.sport`. Add a test with one NBA and one NFL row (FR-015).

## Phase 7: Real data and polish

- [x] T029 Run the real NFLVERSE import on 5439 (quickstart §1). Record the report in `research-notes.md`: 27 seasons, 2000 = 246/248, 2022 = 271/271, the dropped and raw blank-id counts, and the import wall-clock.
- [x] T030 (SC-007 real-data SQL pass not run; see verification.md) SC-002/SC-004 on the real data. SQL in `research-notes.md`: the loaded vs expected game counts for all 26 offered seasons, and the computed PPR (recomputed with INT −2, F13) vs `src_ppr` within 0.01 for 2003, 2014 and 2024. Report the measured shares as measured. *(Added by analyze C1/C2:)* SC-003 on **real** data: hand-recompute one team-week from `historical_nfl_player_week` in one season ≤2009, one in 2010–2020 and one ≥2021, and compare it with the stored result. SC-007 on real data: SQL listing every (season, display name) with more than one `player_id`, and confirming each id is a separate board card.
- [x] T031 SC-005: for 2021, compare the nflverse season sums of the 8 main counting stats with Sleeper's `/v1/stats/nfl/regular/2021`, joined on the Sleeper player dump's `gsis_id`, for players with 50+ PPR. Report the exact-match share and list the misses (F5). Don't pre-commit to a threshold.
- [x] T032 F6 check: a seeded auto-finish of 3 real seasons. Record the QB count per bot roster and how many rookies the bots drafted, in `research-notes.md`. *(Added by analyze C3:)* Also record the server-side ms for create, auto FINISH and the replay (from the existing log lines) against SC-001.
- [x] T033 [P] Docs: `README.md` (football Time Machine and the import part), `HANDOFF.md` (status, deploy order **web before backend**, the post-deploy `NFLVERSE` import, the prod `historical_game` count to take before V45), and `claude/lessons.md` if a new class turns up.

## Dependencies

- Phase 1 → Phase 2 (T004 before every DB-touching task; T005–T013 are mostly sequential in the same files; T014 gates the stories).
- US1 (T015–T025) depends on Phase 2. T015/T016 [P], then T017 → T018. T019 → T020 → T021 → T022/T023. T024/T025 can start once T012 has landed.
- US2/US3/US4 depend on US1. Phase 7 depends on US1–US4.

## Parallel examples

- After T004: T005, T007 and T008 at once (different files).
- In US1: T015 ‖ T016 ‖ T024 (backend source, repo, web).

## Implementation strategy

MVP = Phases 1–3: a football game on synthetic data, with basketball proven unchanged. Then the comparison check, provenance checks and list tag, and only then the real import and the measured SCs.
