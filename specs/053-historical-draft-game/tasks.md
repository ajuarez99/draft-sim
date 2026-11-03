# Tasks: Historical Draft Game (spec 053)

**Input**: `specs/053-historical-draft-game/`: spec.md, plan.md (read its "Amended
after review" table; it overrides older text), research.md, data-model.md (with its
amendment box), contracts/api.md (with its amendment box), quickstart.md,
plan-review.md.

**Tests**: included. This repo's bar requires them, and the review demands specific
preference-ordering and parser tests (F1, F4, F17). Paths are relative to the
worktree root. Backend package root:
`backend/src/main/java/com/ballknowers/draftsim/` (written `BK/` below). Test root:
`backend/src/test/java/com/ballknowers/draftsim/` (written `BT/` below).

**Hard rules for every task**:
- Migrations are append-only.
- `web/src/api.ts` mirrors the Java records in the same task.
- No `Map.of` with nullable values.
- Hand-set constants are labelled in config.
- Do not commit.

## Phase 1: Setup

- [x] T001 Re-check that V43 is still free: `git fetch`, then list `V4*__` across `origin/main` and all local and remote branches. Record the result in `specs/053-historical-draft-game/research-notes.md`. If V43 is taken, stop and report.
- [x] T002 Add the config block to `backend/src/main/resources/application.yml` under `historical:`. Write each comment exactly as given:
  - `kaggle-zip-url` (default `https://www.kaggle.com/api/v1/datasets/download/loganlauton/nba-players-and-team-data?datasetVersionNumber=2`, overridable by env `HISTORICAL_KAGGLE_ZIP_URL`)
  - `kaggle-zip-sha256: 7a127548ce7143e85540ea899c93bada25a5944344d7adb4cfc89645ef3d46a4`
  - `era-threshold: 0.90`, commented `# hand-set (spec 053 FR-007, Allan 2026-11-02), not fitted`
  - `rank-standard-games: 72` and `rank-min-games: 20`, both commented `# hand-set (F6)`
  - `teams: 12`, `rounds: 14`, `reversal-round: 0`, commented `# Sleeper default: plain snake (F8)`
  - `scoring:` with exactly `pts 0.5, reb 1, ast 1, stl 2, blk 2, to -1, tpm 0.5, dd 1, td 2, bonus_pt_40p 2, bonus_pt_50p 2, bonus_reb_20p 1, bonus_ast_15p 1, tf -2, ff -2`, commented `# Legacy Ball Knowers league scoring (FR-002, O1)`

  Bind them with a `@ConfigurationProperties("historical")` record at `BK/config/HistoricalProperties.java`.
- [x] T003 [P] Create the resource files under `backend/src/main/resources/historical/`, each with a header comment explaining its purpose:
  - `historical-name-aliases.csv`, columns `season_end,box_name,season_name`. Seed: `Alvin Attles→Al Attles`, `Jojo White→Jo Jo White`.
  - `historical-same-name.csv`, columns `season_end,team,name,person_key`. Seed: the 23 no-TOT collisions and the 4 adjacent-season cases (Charles Smith 1997/1998, Charles Jones 1998/1999, Michael Smith 1995/1996, Chris Johnson 2013/2014) from plan-review F2. **Every** season row for those names must be keyed. Curate by running the import locally (T019).
  - `team-codes.csv`, columns `box_code,season_code`. Seed: `BKN→BRK`, `BLT→BAL`, `GOS→GSW`, `PHL→PHI`, `PHX→PHO`, `SAN→SAS`, `UTH→UTA`, then extend from the import report.

## Phase 2: Foundational (blocks every story)

- [x] T004 Write the migration `backend/src/main/resources/db/migration/V43__historical_draft_game.sql` per data-model.md **and its amendment box**:
  - `historical_season`: `season_start int PK`, `label`, `source text check in ('KAGGLE','SLEEPER')`, `opening_night date`, `last_counted_date date`, `week_count`, `game_count`, `counted_stats text[]`, `excluded_stats jsonb`, `stat_coverage jsonb`, `ranking_source text`, `imported_at timestamptz`.
  - `historical_player`: `id bigserial`, `source`, `source_key`, `name`, `unique(source, source_key)`, and **no positions column**.
  - `historical_player_season`: `(player_id, season_start) PK`, `team text null`, `age int null`, `games int not null`, `positions text[] not null`, plus typed nullable totals `pts reb ast stl blk "to" tpm fgm fga ftm fta numeric`.
  - `historical_player_game`: `(player_id, game_id bigint) PK`, `season_start`, `game_date`, `week`, `team`, nullable smallint `pts reb ast stl blk "to" tpm fgm fga ftm fta tf ff`, and smallint 0/1 derived `dd td bonus_pt_40p bonus_pt_50p bonus_reb_20p bonus_ast_15p`, which are null when not computable. Index `(season_start, week)`.
  - `historical_game`: per data-model, plus `reversal_round int not null`, `board jsonb not null`, `status text check in ('DRAFTING','COMPLETE','ABANDONED')`, `result jsonb null`.
  - `historical_game_pick`: `(game_id, pick_no) PK`, `unique(game_id, player_id)`, `on delete cascade` from the game.
  - `historical_import_job`: `id bigserial`, `part`, `status check in ('RUNNING','DONE','FAILED')`, `started_at`, `finished_at`, `report jsonb`.
  - `UNLOGGED historical_kaggle_staging`, whose columns mirror the box CSV as text.

  Note: `"to"` is a reserved word in SQL; quote it everywhere.
- [x] T005 [P] Extract the core of `RealizedLineupService.bestLineup` (`BK/engine/RealizedLineupService.java:65`) into `public WeekLineup bestLineup(List<ScoredPlayer> scored, LeagueSettings settings, SportRules rules)` with `record ScoredPlayer(Player player, double points)`. Sort by points desc, then `player.id()` asc (F20). The existing JSON method resolves its ids and delegates, so there is one implementation. The existing `RealizedLineupService` tests must still pass unchanged. Add a test in `BT/engine/RealizedLineupServiceTest.java` (or the existing test file) asserting both entry points give identical totals and lineups for the same input.
- [x] T006 [P] `BK/historical/EraScoring.java`, pure with no Spring dependencies. Input: per-stat played-row coverage, the set of stats the league tracked (from the season file), the source (`KAGGLE`/`SLEEPER`) and the base scoring map. Output: `record Era(Map<String,Double> scoring, Map<String,Double> coverage, Map<String,String> excluded)`.
  - Rule (FR-007): for KAGGLE, a stat counts iff it was tracked **and** coverage ≥ `era-threshold`.
  - `dd`/`td` count only if all of pts/reb/ast/stl/blk count.
  - `bonus_pt_*` follow pts, `bonus_reb_20p` follows reb, `bonus_ast_15p` follows ast.
  - `tf`/`ff` are always excluded for KAGGLE, with reason `"not recorded in historical box scores"`.
  - For SLEEPER, every key counts (research R2: Sleeper omits zeros).

  Tests in `BT/historical/EraScoringTest.java` must assert the research R2 outcome table for these coverage inputs:
  - 1970-71: pts, reb, ast only.
  - 1979-80: adds tpm.
  - 1982-83: adds blk but not stl or to.
  - 1983-84: everything including dd/td.
  - Assists at 89.9% are excluded; at 90.0% they count.
- [x] T007 [P] `BK/historical/HistoricalWeeks.java`, pure. Given the game dates of a season and `lastCountedDate`, week 1 is the Monday–Sunday week containing opening night. Number only the weeks that contain games, so an empty calendar week is skipped (F16). The 2019-20 cutoff is `2020-03-11`. Test in `BT/historical/HistoricalWeeksTest.java`: a Tuesday opener lands in week 1, the following Monday starts week 2, and an empty week (e.g. the All-Star break with no games) doesn't consume a number.
- [x] T008 [P] `BK/historical/HistoricalRepository.java`, plain JDBC. Upsert the player on `(source, source_key)` and **never delete players** (F9). Replace season rows by deleting and inserting `historical_player_season`/`historical_player_game` for one season. Bulk insert via `JdbcTemplate.batchUpdate` with batches of 1,000. Game CRUD: `lockForUpdate(id)` with `select … for update`, mirroring `BK/store/MockDraftRepository.java:154`. Insert picks, set status, write the result. **Bind `text[]` through `connection.createArrayOf("text", …)`, never a raw `String[]`; bind timestamps as `OffsetDateTime`** (bug class 3). Integration test `BT/historical/HistoricalRepositoryIT.java` against Postgres: array bind round-trips, upsert keeps the id stable across two imports, and `"to"` reads back.

## Phase 3: User Story 1 — Draft a random past season and see how you'd have done (P1) 🎯 MVP

**Goal**: start a game, draft with prior-season cards, and get a replayed season with standings.
**Independent test**: quickstart §2–§3.

### Import (data US1 needs)

- [x] T009 [P] [US1] `BK/historical/KaggleCsv.java`: a streaming RFC-4180 reader (quoted fields, commas inside quotes, empty first header) with **no new dependency**. Also an explicit column map `REB→reb, AST→ast, STL→stl, BLK→blk, TOV→to, FG3M→tpm, FGM→fgm, FGA→fga, FTM→ftm, FTA→fta, PTS→pts, MIN→min`, and a date parser using `DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("MMM d, yyyy")` with `Locale.US` (F17). The season comes from `Game_ID` characters 4–5 (index 3..5): `≥46` means `19xx`, otherwise `20xx`, which gives the start year. **Never use the CSV's `Season` column**, which is the calendar year (research R3). An empty cell maps to null, not 0. Tests in `BT/historical/KaggleCsvTest.java`: `"NOV 19, 1946"` parses; `0024600034` maps to season 1946; `0029800001` maps to 1998; `0020100001` maps to 2001; a quoted comma; empty-to-null.
- [x] T010 [US1] `BK/historical/KaggleHistoricalSource.java` phase 1: download `kaggle-zip-url` to a temp file, verify SHA-256 against config, and fail with a clear message on mismatch. Stream the `NBA Player Box Score Stats(1950 - 2022).csv` entry from the zip **once** into `historical_kaggle_staging` via pgjdbc `CopyManager` (`PGConnection.getCopyAPI()`), only for season starts 1969..2016 (F3). Never hold the file in memory, because prod runs at `-Xmx1024m`. Read `NBA Player Stats(1950 - 2022).csv` (28k rows, small enough for memory) into season lines, using **end-year** `Season` − 1 as the start year (research R3).
- [x] T011 [US1] `KaggleHistoricalSource` phase 2, one season at a time from staging:
  - Drop non-played rows (minutes and points both 0) (F11).
  - Normalise names (NFKD, strip accents and punctuation, lower-case, collapse whitespace).
  - Match box rows to season-file rows by (season, team via `team-codes.csv`, name), then (season, name), then `historical-name-aliases.csv` (research R5).
  - Resolve person keys via `historical-same-name.csv`, failing if a curated name has an unkeyed row.
  - Run collision detection: a season with multiple non-TOT rows and no TOT row, **and** the age-continuity check (a name in S and S+k must have ages differing by k−1..k+1) (F2).
  - Derive dd/td/bonus flags per game from the recorded stats (dd = ≥2 of pts/reb/ast/stl/blk ≥ 10, td = ≥3; `bonus_pt_40p` = pts ≥ 40; `bonus_pt_50p` = pts ≥ 50, both set at 50+; `bonus_reb_20p` = reb ≥ 20; `bonus_ast_15p` = ast ≥ 15). A flag is null when any input stat is null.
  - Map positions per season: hyphenated → union, first listed = primary; `G` → [PG,SG], `F` → [SF,PF]. An empty set fails (F5).
  - Fantasy-relevant = within the top `teams×rounds×1.5` by counting-stat fantasy points that season. Unmatched fantasy-relevant player-seasons, uncovered collisions or empty positions put the season in the report and **do not commit**. Non-relevant unmatched rows are skipped and counted in the report.
  - Compute played-row coverage per stat and "tracked" (the season file has the stat for >50% of rows) and write `historical_season` via `EraScoring`.

  Unit tests in `BT/historical/KaggleIdentityTest.java` on small fixtures: Charles Jones aged 40 then 23 is flagged; a traded player with a TOT row is one person; an alias resolves; an empty position fails.
- [x] T012 [US1] Add `seasonTotals(String sport, int season)` to `BK/ingest/SleeperPlayerStatsClient.java`: `GET /v1/stats/nba/regular/{season}` returns a map from player id to stats, defensively typed like `week()`. `BK/historical/SleeperHistoricalSource.java`:
  - For seasons 2016..2025, totals become `historical_player_season` (2016 is cards only).
  - For 2017..2025, weekly `week(nba, y, w)` for **every w in 1..30, unconditionally**, keeping the non-empty weeks, become `historical_player_game`. Record the first and last non-empty week in the report. *(Amended after analyze U1: "until two consecutive empty weeks" could silently truncate a season. Measured 2023: weeks 1–25 have data, week 26 is empty.)*
  - Drop `TEAM_*` ids and entries with empty stats (`BasketballRules.playedIn`, `BK/sport/BasketballRules.java:440`) (F11).
  - The game id is Sleeper's `game_id` parsed to a long; if it isn't numeric, use a stable hash and record that in the report.
  - Positions come from `/v1/players/nba` `fantasy_positions` mapped to PG/SG/SF/PF/C. Age comes from `birth_date` relative to Oct 1 of the season (F19).
  - Apply the 2019 cut at `2020-03-11`.
  - The Sleeper label is the **start year** (research R3).

  Test with fixture JSON: TEAM ids are dropped, `{}` stats are dropped, and the 2019 cut applies.
- [x] T013 [US1] `BK/historical/HistoricalImportService.java` and `BK/api/HistoricalImportController.java`:
  - `POST /api/admin/historical/import {part}` creates a `historical_import_job`, runs it on a virtual thread and returns 202 `{jobId}`.
  - `GET /api/admin/historical/import/{jobId}` returns status plus the report (contracts amendment box, F14).
  - Both are admin-gated via `BK/config/AdminAccess.java` (header `X-Admin-Token`); follow an existing admin controller's pattern. Reject a second concurrent job with 409.
  - Truncate staging at the start and end.

  Test `BT/api/HistoricalImportControllerIT.java`: 401/403 without the token, and 202 with it using a tiny fixture zip via the URL override plus a matching SHA in the test profile.
- [x] T014 [US1] Offered seasons: `HistoricalRepository.offeredSeasons()` returns seasons 1970..2025 with a committed `historical_season` row **and** committed prior-season lines (F14). `GET /api/history/seasons` per the contract, in `BK/api/HistoricalGameController.java`.

### Board and game

- [x] T015 [US1] `BK/historical/HistoricalBoardBuilder.java`, for season S:
  - Candidates are players with at least one played game in S.
  - The rank score is the max over seasons S−1 and S−2 that have games ≥ `rank-min-games` of (counting-stat fantasy points per game under S's counted keys, **no bonuses** (F12)) × `rank-standard-games`. The best-of-two season is recorded for the card (F6).
  - Players with no qualifying prior line rank after all others, in a `Random(seed)` order.
  - Sort by score desc, ties by `historical_player.id` asc. `adp` = 1-based rank. `positionalRank` within primary position.
  - The `Player` record gets `id` = historical id, `sport` NBA, `sleeperId` = `"h" + id` (unique, so nothing keyed by sleeperId collides), positions from the **prior** season line (the drafted season's line only for players with none) (F5), `team`/`age` from the prior line or null (F19), status and injury null.
  - **The returned list must be sorted by `adp` ascending** (PickDecider takes candidates in list order; review F9).

  Preference-ordering test in `BT/historical/HistoricalBoardBuilderTest.java`, required by bug class 1: a 30-pt/g player with 17 games last year but 82 the year before ranks above a 15-pt/g player with 82 games; a higher-scoring player is never ranked lower; a rookie ranks after every veteran; the cards contain no drafted-season stats.
- [x] T016 [US1] `BK/historical/HistoricalGameService.java`:
  - `create(user, slot?)`: random offered season via `SecureRandom`, slot random if absent. Builds the board, **snapshots it into `historical_game.board`** (F9) along with the era scoring map (`scoring`) and `reversal_round`. Builds `DraftContext` via `DraftContextFactory.build(LeagueSettings 6-arg incl. reversalRound, seats=[USER at slot], fittedProfiles=Map.of(), PositionalPriors.uniform(Sport.NBA), board, completed)` (`BK/engine/DraftContextFactory.java:56`, `BK/profile/PositionalPriors.java:114`). Advances with `MockDraftEngine.advanceUntilUserOrEnd` (`BK/engine/MockDraftEngine.java:59`) and persists bot picks.
  - `pick`/`auto(PICK|FINISH)`/`abandon`: all take `lockForUpdate` and reject when status ≠ DRAFTING (409) (F10). Turn and myPicks checks use `DraftSlot` with the stored `reversal_round` (F8). Every call rebuilds `BoardEntry`s **from the snapshot**, never from reference tables.
  - When the last pick lands, call `SeasonReplay` in the same transaction and write `result` and `COMPLETE`.
  - Ownership: another user's game → empty (404).
- [x] T017 [US1] `BK/historical/SeasonReplay.java` (scoring parts; comparisons are T022):
  - Load S's played games for all rostered players.
  - Player-week score = max over that week's games of `GameScoringService.score(game.scoring, statMap)` (`BK/engine/GameScoringService.java:42`). Null stats are absent from the map; for a counted Kaggle stat, null = 0 per FR-007, so fill 0 for counted keys.
  - Team-week = the T005 overload. A player with no game that week is not passed, and an empty roster-week = 0 points, **not** `absent` (F20). Comment that at the call site.
  - Schedule: a seeded circle-method round robin over `week_count` weeks.
  - Standings W-L-T, then points for, then a seeded coin (F16).
  - Per-starter `gameDate` of the locked game.

  Tests in `BT/historical/SeasonReplayTest.java`:
  - A player with 3 games (10, 25, 18) scores 25 that week.
  - A week whose only game is −2 scores −2.
  - The lineup obeys `NBA_ROSTER` eligibility.
  - Equal totals produce a tie.
  - Rerunning with the same seed gives an identical result (byte-equal JSON).
- [x] T018 [US1] `BK/api/HistoricalGameController.java` implements `/api/history/games` POST/GET, `/{id}` GET, `/{id}/pick`, `/{id}/auto` and `/{id}/abandon` per contracts/api.md and its amendment box. A missing `X-Sleeper-User` → 401 (F18). Records: `HistoricalGameState`, `HistoricalPlayerCard`, `Prior`, `SeasonView`, `Result`, … build nullable maps mutably (no `Map.of`). **While DRAFTING, the response contains no drafted-season data** (FR-003). **Same task**: add the mirrored types to `web/src/api.ts` plus client functions (`startHistoricalGame`, `getHistoricalGame`, `historicalPick`, `historicalAuto`, `abandonHistoricalGame`, `listHistoricalGames`, `historicalSeasons`) sending the `X-Sleeper-User` header the same way the mock client functions do. Test `BT/api/HistoricalGameMvcIT.java`: 401 without the header; 404 for another user's game; 409 on a pick out of turn; and a JSON assertion that the DRAFTING state has no field from season S (seed two seasons with distinct values and check that S's values are absent). Check the CORS config allows the routes (`AccessControlMvcIT` pattern).
- [x] T019 [US1] Local import and curation loop. Run the import against the local 5433 DB. Extend the three CSVs until the report has no `unmatchedFantasyRelevant`, no `uncoveredCollisions` and no empty positions. Record the final counts and the quickstart §1 game counts (1970-71 = 697, 1975-76 = 738, 1990-91 = 1107, 1998-99 = 725, 2010-11 = 1230, 2011-12 = 990) in `specs/053-historical-draft-game/research-notes.md`. Also record the DB size of the `historical_*` tables (O3).

### Web

- [x] T020 [US1] `web/src/pages/HistoricalDraft.tsx` (start screen: "Draft a random year" button, settings source line, how-it-works text) and `web/src/pages/HistoricalRoom.tsx`:
  - Compose the existing `DraftRoomLayout`, `DraftBoard`, `OnTheClockPickInput`, `TurnIndicator`, `AvailabilityPanel` and `FormatSummary` the way `web/src/pages/MockDraftView.tsx` does, without forking that file.
  - Show the season label, counted stats with coverage, excluded stats with reasons, and the ranking label "ranked by last season's points" (never "ADP").
  - The card shows the prior line labelled with its season ("1994-95 · 79 G · 29.3 pts …"), the best-of-two season, and "excludes bonuses".
  - On COMPLETE, render `web/src/components/HistoricalResults.tsx`: standings with your row highlighted, plus weekly matchups and your weekly lineup with each starter's locked game date.
  - Add routes `/history` and `/history/:id` inside the signed-in shell in `web/src/App.tsx`, a global rail entry in `web/src/components/Rail.tsx` next to Managers, and the page titles in `web/src/analytics.ts`'s route list (spec 052).
  - Vitest: `web/src/pages/HistoricalRoom.test.tsx` renders the DRAFTING fixture with no "ADP" text, and renders COMPLETE standings.
  - *Built 2026-11-02 (web only; not yet driven in a real browser).* Deviation: `AvailabilityPanel` and `OnTheClockPickInput` are **not** used. The panel tiers and prints "ADP 12" and an ADP column, which FR-009 forbids for these games, and it needs survival data that doesn't exist here. `web/src/components/HistoricalPlayerList.tsx` replaces them; `DraftRoomLayout`, `DraftBoard`, `TurnIndicator`, `FormatSummary` and `RoomControls` are reused. Rail label: "Time Machine". Mapping helpers in `web/src/historicalCopy.ts`.

## Phase 4: User Story 2 — See how your draft compares (P2)

**Goal**: the hindsight, bust and steal panel. **Independent test**: finish a game and hand-check the three figures.

- [x] T021 [P] [US2] Tests first in `BT/historical/ComparisonsTest.java`:
  - **Bust** (F1): user picks at 3 (season-value rank 150) and 160 (rank 2). The bust is pick 3, and pick 160 is never the bust. If all gaps ≤ 0, the bust is null.
  - **Steal passed on** (F19): it excludes players the user drafted later.
  - **Hindsight** (F4): when the greedy-by-raw-value choice would be a 4th C who never starts, the lineup-aware choice picks a starter instead. When hindsight < user, `userBeatIt = true` and no negative gap is shown.
- [x] T022 [US2] Implement in `BK/historical/SeasonReplay.java` (or `Comparisons.java`) to the tests above:
  - Season value = the sum of player-week scores.
  - Bust = argmax(seasonValueRank − pickNo) over user picks, null if none is positive.
  - Steal = the max over user picks of an available-at-that-pick player not drafted by the user, by value − takenValue.
  - Hindsight = at each user pick choose the candidate (available at that pick in the real draft, minus earlier hindsight picks; bot picks allowed) maximising the hindsight roster's season best-ball total. Score it standalone, labelled `"greedy hindsight, bots unchanged"`.

  Write it into `result` at completion.
- [x] T023 [US2] `web/src/components/HistoricalResults.tsx`: a comparison panel showing your points vs hindsight with the label (or "you beat the greedy hindsight draft"), bust and steal with the numbers behind them. Mirror the new fields in `web/src/api.ts` in the same task if T018 didn't. Vitest for both the `userBeatIt` and normal cases.

## Phase 5: User Story 3 — Know what each season's numbers can and can't tell you (P2)

**Goal**: disclosure is complete and correct. **Independent test**: a 1975-76 game shows reb/ast coverage, the stl/blk exclusion and the fallback label.

- [x] T024 [US3] Verify and finish the disclosure end to end:
  - The `season` view carries `statCoverage`, `excludedStats` (with reasons) and `settingsSource`.
  - Cards drop non-counted stats (SC-004).
  - `dd`/`td` exclusion is explained before 1983-84 ("double-doubles need steals and blocks, which weren't fully recorded").
  - Backend test in `BT/historical/HistoricalBoardBuilderTest.java` (or a new one): a 1975-76 card carries no stl/blk although the season file has them (review F12).
  - Vitest asserting the coverage line renders.
  - *UI half done 2026-11-02:* `HistoricalRoom.tsx` shows coverage, exclusion reasons, ranking label and settings source; `HistoricalRoom.test.tsx` asserts the coverage line and no "ADP". **Left for backend:** the `dd`/`td` explanation text, the card-drops-uncounted-stats test in `HistoricalBoardBuilderTest`, and live end-to-end check.

## Phase 6: User Story 4 — Revisit past games (P3)

**Goal**: list and reopen. **Independent test**: two games, then reload both.

- [x] T025 [US4] `GET /api/history/games` (newest first: id, seasonLabel, status, userSlot, finish|null, createdAt) is already in T018; add the list UI to `web/src/pages/HistoricalDraft.tsx` with resume for DRAFTING, view for COMPLETE and abandon. Test in `BT/api/HistoricalGameMvcIT.java`: reopening a COMPLETE game returns a `result` byte-identical to the stored one, even after the `historical.scoring` config changes (FR-013).
  *UI half done 2026-11-02:* list with Resume / View / Abandon in `HistoricalDraft.tsx`, tested in `HistoricalDraft.test.tsx`. **Left for backend:** the byte-identical reopen test in `HistoricalGameMvcIT`.

## Phase 7: Polish & cross-cutting

- [x] T026 [P] Source parity (SC-005): a test or script comparing Kaggle vs Sleeper season fantasy points for 2018-19 (both sources present: Kaggle box to 2022, Sleeper 2017+). Results go in `research-notes.md`, with disagreements listed. This needs a one-off load of Kaggle 2018 into a scratch table or an in-memory compare. Don't import Kaggle seasons ≥ 2017 into the real tables (FR-015).
- [x] T027 [P] SC-007: a test asserting every `person_key` group in `historical-same-name.csv` maps to distinct `historical_player` ids with no merged game rows (`HistoricalRepositoryIT` or a new IT).
- [x] T028 Update `README.md` (feature list) and `HANDOFF.md` (state, deploy order: backend first, then web; post-deploy admin import; Railway volume check per O3) in the worktree. Note the provenance caveat (plan "Risks") for Allan.
- [x] T029 SC-001: log server-side wall time for `create` and for the final pick (which includes the replay) in `HistoricalGameService` at INFO. Record measured values for one Kaggle season and one Sleeper season in `research-notes.md`. *(Added after analyze C1.)* Then run `cd backend && ./gradlew test` and check the **skip count** (project memory: ITs skip silently when Postgres is down). Run `cd web && npx tsc -b && npm run build && npx vitest run`, then fix failures.

## Dependencies

- T001 → T004. T002/T003 are independent. T004 → T008 → T010–T014, T016.
- T005, T006, T007, T009 are pure and parallel after T002.
- T010 → T011. T012 is independent of T010/T011. T011 and T012 → T013 → T019.
- T015 needs T006/T008. T016 needs T015 and T017. T017 needs T005/T006/T007. T018 needs T016. T020 needs T018.
- US2 (T021–T023) needs T017. US3 (T024) needs T015/T018/T020. US4 (T025) needs T018/T020.
- T026/T027 need T019. T029 is last.

## Parallel opportunities

- Wave A (after T004): T005, T006, T007, T008, T009 touch disjoint files.
- Wave B: T012 (Sleeper) alongside T010→T011 (Kaggle).
- US2's tests (T021) can be written while T018–T020 are built.

## Implementation strategy

MVP = Phases 1–3 (US1): a playable random season with honest scoring, verified by
quickstart §1–§3. Then US2 (the payoff), US3 (the disclosure polish), US4 (history).
Live verification follows the full build (AGENTS.md pipeline step 7).
