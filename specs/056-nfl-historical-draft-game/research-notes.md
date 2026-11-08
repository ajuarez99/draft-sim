# Research notes: Football Time Machine (spec 056)

## Build notes, 2026-11-05: Phases 1-2 (T001, T002, T004-T013; T014 partial)

Everything below is labelled **ran** (executed today, result quoted) or **assumed** (reasoned, not executed).

### T001: migration number check (ran)
- `git log --all --oneline --diff-filter=A -- 'backend/src/main/resources/db/migration/V45*'` lists only `cf84da0`
  ("renumber migration V43 -> V45"), which `ff6711f` reverted. V45 exists at no branch tip. V45 is free.
- The shared 5433 DB's `flyway_schema_history` has max version 44 (read-only `select max(version::int)`).
- Not re-fetched from the remote: this checked the refs the machine already has.

### T002: scratch Postgres on 5439 (ran)
- `docker run -d --name draftsim-pg-056 -p 5439:5432 -e POSTGRES_HOST_AUTH_METHOD=trust -e POSTGRES_USER=draftsim -e POSTGRES_DB=draftsim postgres:16`.
  Docker Desktop was already up. Port 5439 was free (a stopped `draftsim-pg-055` container once owned it).
- Launch entries `056-api` (bootRun from this worktree, 8156, `ADMIN_TOKEN=local-056`, `DB_URL` -> 5439,
  `CORS_ORIGINS=http://localhost:5256`) and `056-web` (vite 5256, proxy -> 8156) were appended to the **main checkout's**
  `.claude/launch.json`. 8156 and 5256 appear in no other entry. The other 40 entries are untouched (the file still parses; 42 configurations).
  **Not started or exercised**: only the suite ran against 5439.
- The ITs read the datasource through Spring (`DB_URL`), so `DB_URL=jdbc:postgresql://localhost:5439/draftsim` points the whole suite at 5439.
  About 70 ITs also hard-code `localhost:5433` for a **reachability `assumeTrue` only**. Those connect but do not write.
  Two do write there: `PlayerSpotlightTopOfNightIT` and `PlayerSpotlightWeekIT` run `cleanUp(new JdbcTemplate(new DriverManagerDataSource(JDBC_URL...)))` against 5433.
  Because the brief forbids modifying the shared 5433 DB, those two classes were **excluded** from the run (a scratch gradle init script, not in the repo).

### T004: V45 applied (ran)
- Flyway applied V1..V45 on a fresh 5439 DB the first time a Spring IT started: `flyway_schema_history` max = 45, `bool_and(success)` = true.
  That covers V43 + V44 + V45 in sequence, every statement of V45.
- A bare `psql` pass of V43, V44, V45 also showed V44 fails without the earlier league tables (not a V45 issue: V44 needs the V1.. chain). Flyway is the real test.
- Probe (rolled back): `historical_game.sport` and `historical_season.sport` are NOT NULL with **no default**. An NFL 2005 and an NBA 2005
  season coexist, and an NFL game joins the NFL row only (one row, label "2005"). An `insert into historical_game` that omits `sport` fails
  with `null value in column "sport" ... violates not-null constraint` (F8 works).
- Constraint names F2 relied on existed on a V43 DB: `historical_season_pkey` and `historical_game_season_start_fkey` dropped without error.
  The FK Postgres named for the new key is `historical_game_sport_season_start_fkey`.
- Surprise worth recording: the two source checks are the *inline column* checks of V43, and their default names
  (`historical_season_source_check`, `historical_player_source_check`) were right. Nothing needed guessing.

### Design choices that go beyond the task text
- `SeasonRow` is now `(sport, seasonStart, ..., importedAt, expectedGameCount, droppedRows, sourceSha256)`. `sport` first, the three provenance fields last.
- `HistoricalDataRepository.offeredSeasons(sport)` replaces the old no-arg method and `FIRST/LAST_OFFERED` became `NBA_FIRST_OFFERED`/`NBA_LAST_OFFERED` +
  `NFL_FIRST_OFFERED`/`NFL_LAST_OFFERED` (2000..2025). `offeredSeasons(sport, from, to)` is now public. The prior-lines `exists` reads `historical_player_season`
  for NBA and `historical_nfl_player_season` for NFL, chosen by a closed switch, never from the caller's string.
  **Note**: `HistoricalSport` also exposes `firstOffered()/lastOffered()`. The NFL values live in two places until the NFL sport lands (the
  repository constants and `NflHistoricalProperties.firstOffered/lastOffered`). T020 should have `NflHistoricalSport` read the properties and the repository take the range from its caller, or drop the repository constants.
- `HistoricalBoardBuilder.build(...)` has a new 8-argument form with an `Options(sport, counting, label, mode, rookiePicks)` record. `RankMode` has one value
  (`PRIOR_PER_GAME`); T019 adds the football mode there. Two package-private conveniences stay so the 053 tests did not need editing:
  the 7-argument `build` (NBA options) and the one-argument `toEntry(card)` (NBA). Neither is reachable from outside the `historical` package.
- `HistoricalGameService` takes a `HistoricalSports` instead of `HistoricalProperties`, and reads the sport from the game row everywhere.
  `data` is still a constructor argument but is no longer read (kept so the constructor shape changes by one argument only).
- The board snapshot now stores `dataNotes` and `attribution`. `readSnapshot` reads them with `has()`/`isNull()`; a 053 snapshot (object or bare array) reads as `[]`/null.
  **Not yet run against a 053-shape fixture string** (that is T023's IT). The code path was only exercised on snapshots written by the new code.
- `HistoricalPlayerCard` keeps an 8-argument constructor (rookiePick null) next to the 9-argument canonical one. `Prior` gained `totals` (null normalised to `{}`).
- The controller still serves NBA only: `HistoricalGameController` passes `"NBA"` from one constant (`SPORT_UNTIL_T021`) and does **not** yet require `sport`,
  because the web client does not send it until T024 and requiring it now would break the live page. `SeasonSummary` is unchanged. T021 does the required-sport change and the 400 tests.
- `HistoricalGameState.SETTINGS_SOURCE` and the controller's copy are gone (F10); the NBA string lives on `NbaHistoricalSport.SETTINGS_SOURCE`.
- Web: `api.ts` mirrors `sport`, `season.dataNotes`, `season.attribution`, `GameSummary.sport`, `card.rookiePick`, `prior.totals`, all required fields (the Java side never emits them absent).
  `tsc -b` first flagged the three typed vitest fixtures that lacked them; they were updated (see below). `npm ci` was run in the worktree's `web/` (no `node_modules` existed there).

### Existing tests changed (all mechanical, none loosened)
- `api/HistoricalGameMvcIT`: `service.create(user, slot, S)` -> `service.create(user, "NBA", slot, S)` (2 sites); the two `new HistoricalGameService(... props ...)` became
  `... new HistoricalSports(List.of(new NbaHistoricalSport(changedProps, data))) ...` (the service no longer takes `HistoricalProperties`, and these tests exist to swap the props);
  `data.offeredSeasons()` -> `data.offeredSeasons("NBA")`; two imports added.
- `historical/HistoricalRepositoryIT`: `SeasonRow` gained `"NBA"` first and `null, 0, null` last; `offeredSeasons`/`findSeason` take `"NBA"`; `games.create(...)` takes `"NBA"`.
- `historical/HistoricalRealSeasonIT`: `offeredSeasons`, `findSeason`, `service.create` take `"NBA"`. (Both of its tests **skip** here for lack of real data, so this edit is compile-checked only.)
- `historical/HistoricalSyntheticSeason`: `SeasonRow` constructor as above.
- `web/src/pages/HistoricalDraft.test.tsx` (`sport: 'NBA'` on the summary fixture) and `HistoricalRoom.test.tsx` (`sport`, `totals: {}`, `rookiePick: null`, `dataNotes: []`, `attribution: null`).
- Unchanged and green: `HistoricalBoardBuilderTest`, `SeasonReplayTest`, `ComparisonsTest`, `HistoricalPropertiesTest`, `KaggleHistoricalSourceTest`, `SleeperHistoricalSourceTest`, `HistoricalWeeksTest`.

### New tests (ran, green)
`config/NflHistoricalPropertiesTest` (2 tests: the real yml binds, bad values are refused), `historical/HistoricalSportsTest` (4: unknown/null sport throws, duplicate beans refused,
`Prior.totals` never null and in `StatOrder` order, the 8-argument card has a null `rookiePick`), `historical/SeasonReplayNullDateTest` (1: undated football-style lines replay, starters' `gameDate` is null, no NPE).
Not verified: that `SeasonReplayNullDateTest` fails against the 053 `SeasonReplay` (I did not revert the fix to see it go red; by reading, `l.date().toString()` throws on a null date).

### T014: regression run (ran; NOT a clean "0 skipped, all green")
- Command: `DB_URL=jdbc:postgresql://localhost:5439/draftsim ./gradlew test` in `backend/`, plus a scratch init script excluding the two 5433-writing classes above.
- **Result on this branch: 2688 tests, 13 failed, 54 skipped** (1m44s). `npx tsc -b`: clean. `npx vitest run` of the two Historical page tests: 16 passed.
- **Baseline: base commit 5a47471 in a throwaway worktree against a second fresh DB (`draftsim_base` on 5439): 2681 tests, 13 failed, 54 skipped.** The 13 failed tests are the *same 13*
  (`AccessControlMvcIT.draftRoutesAre404WithNoIdentity`, `AdminDigestControllerIT.anExplicitDateIsAccepted`, 8 in `TargetControllerIT`, `EspnRefreshOnVisitIT`, 2 in `LeagueRepositorySuccessorIT`).
  They need real league/board data (or a cleaned `manager` table) that a fresh scratch DB lacks. This branch adds 7 tests, none failing: 2688 - 2681 = 7.
- The 54 skips are all `Assumption failed: <real league / real season / NBA 2025 ... is not in the local database>`. **None is a "Postgres unreachable" skip.** They cannot go to 0 on a fresh DB.
  So the brief's "skipped ITs must be 0" is not met; the honest statement is "0 connectivity skips, 54 data-absence skips, identical on base".
- The historical ITs that ran (not skipped): `HistoricalRepositoryIT`, `HistoricalSeasonsMvcIT`, `HistoricalImportControllerIT`, `HistoricalImportConcurrencyIT`, `HistoricalGameMvcIT` (synthetic-season tests;
  one real-data test in it skipped). The 053 synthetic flow (create, bots, auto FINISH, replay, reopen, reversal round 3, 401/404/409) therefore passed on a V45 database.
- Not run: the two excluded classes (`PlayerSpotlightTopOfNightIT`, `PlayerSpotlightWeekIT`; they write to the shared 5433), and anything that needs real basketball data (`HistoricalRealSeasonIT`).

## Build notes, 2026-11-05 (second session): backend of the football game (T015-T023, T026, T027)

Labelled **ran** (executed, result quoted) or **assumed**. Scratch DB: docker `draftsim-pg-056` on 5439 (V45 applied). The shared 5433 DB was never written.

### What was built
- `NflverseSource` (T015, T017): downloads `.csv.gz` (or a `file:` URL, used by the ITs), gzip-sniffing, streams through 053's `KaggleCsv`, same redirect-following `HttpClient` as Kaggle, SHA-256 and byte count recorded. Fails loudly on a missing column, a second REG row for one (player, week), a row from another season, an unreadable number. Also parses `draft_picks.csv` (skill positions with a GSIS id, FB/HB to RB, a player's first draft).
- `NflHistoricalRepository` (T016): week/season/draft-pick tables, `NflSeasonLine`/`NflWeekLine` (the `StatLine`/`WeekStatLine` impls), `replaceSeason` (players upserted, never deleted; season row written last).
- `HistoricalImportService` part `NFLVERSE` (T018): 1999..lastOffered, one transaction per season, then the draft picks. `ALL` is still KAGGLE + SLEEPER only.
- `HistoricalBoardBuilder` mode `POINTS_OVER_REPLACEMENT` (T019), `NflHistoricalSport` (T020), controller `sport` required (T021).
- Tests: `HistoricalBoardBuilderNflTest` (14), `NflverseSourceTest` (13), `NflHistoricalRepositoryIT` (8), `NflGameFlowIT` (7), `SportCoexistenceIT` (5), `NflImportIT` (3), two NFL cases in `ComparisonsTest`, 400 cases in `HistoricalSeasonsMvcIT`/`HistoricalGameMvcIT`.

### Deviations and judgement calls (each one is a decision to review)
1. **Blank-id rows are `player_id` blank OR the literal `0`.** The real files (2000, measured) write team-total rows with id `0`, not an empty cell; 2025 writes them empty. Treated alike. One more oddity seen in 2000: a row with id `XX-0000001`, name `S.Fernando`, no position. It is a non-skill row, not a blank-id row, and is ignored.
2. **Admin route gains optional `fromSeason`/`toSeason`** (NFLVERSE only; 400 on any other part, a range outside 1999..lastOffered, or from > to). Additive to the contract. It lets one season be re-imported without 27 downloads.
3. **`replaceDraftPicks` replaces the picks of the draft years present in the file, not the whole table.** A full file still replaces every year it covers, so a fixture file can never wipe real picks.
4. **A rookie's card shows `rookiePick` for any NFL draft slot of the drafted season (rounds 1 to 7), but only rounds <= `rookieMaxRound` (2) are placed by it** (F14). Later picks keep the pick number on the card and stay in the seeded tail. "Rookie" = a player with no prior line at all whose NFL draft season is the drafted season.
5. **Tie rule in the ranking (my choice, not in the docs):** on an equal points-over-replacement score a veteran sorts before a rookie, then veterans by id and rookies by pick. A rookie valued as "the veteran at positional rank 13" therefore lands directly behind that veteran. A position with no veterans values its rookies at 0 over replacement; a position with fewer veterans than rank 12+k uses the last one (tested).
6. **The repository's `NFL_FIRST/LAST_OFFERED` constants are gone** (T020's "one source"): `NflHistoricalSport` reads `NflHistoricalProperties`. `HistoricalDataRepository.offeredSeasons("NFL")` (no range) now throws; the ranged form is what both sports use.
7. `dataNotes` wording for dropped rows is `"{n} source rows with stats but no player id were left out"` (the parent's correction), not the task text's "player rows".
8. `NflHistoricalSport.COUNTING` is the single list of the 13 keys; `NflverseSource.KEYS` aliases it. `StatOrder` still carries its own copy of the football order (T008, unchanged); no test asserts the two agree, so that is **assumed**.

### Ran: failing-first for T019
The builder tests were written before the implementation. First run with the types stubbed and the builder still ordering by raw per-game points: 4 of 14 failed (`aWideReceiverOverReplacementOutranksAQuarterbackWithMoreRawPoints`, `anEarlyRookieIsValuedBetweenTheFifthAndThirtiethVeteranAtHisPosition`, `earlyRookiesAtDifferentPositionsAreOrderedAmongTheirOwnPositionOnly`, the card-shape test). After the implementation 14 of 14 pass and the 053 `HistoricalBoardBuilderTest` (16) is unchanged and green. The other ten passed against the stub too, on purpose (they pin the list contract, the lookback rule and the position check). `aPositionShorterThanItsDepthUsesTheLastVeteranAsReplacementNotZero` is the one meant to catch a "short position falls back to replacement 0" regression, but I did not mutate the code to watch it go red, so that is **assumed**.

### Ran: real files from the JVM (TLS, redirect, gzip, header parsing)
`POST /api/admin/historical/import {"part":"NFLVERSE","fromSeason":"2010","toSeason":"2012"}` against a bootRun on 5439: DONE in 4.6 s, `failedSeasons: []`. GitHub's release URL redirects to a CDN host; the JVM's default trust store handled it (the Python failure in the parent's notes was Python's store, not the JVM's).

| season | games / expected | maxWeek | player-weeks | player-seasons | droppedRows | rawBlankIdRows |
|---|---|---|---|---|---|---|
| 2010 | 256 / 256 | 17 | 5558 | 562 | 0 | 17 |
| 2011 | 256 / 256 | 17 | 5611 | 567 | 0 | 17 |
| 2012 | 256 / 256 | 17 | 5620 | 574 | 1 | 17 |

Draft picks: 3643 skill-position rows read, 580 stored (only players already imported at that point).

### Ran: one real 2012 game, end to end over HTTP
Created until the random season was 2012 (first attempt): slot 6, board 574 players. Top of the board: Arian Foster, Ray Rice, Aaron Rodgers, LeSean McCoy, Calvin Johnson. Rookies placed by slot: Trent Richardson (pick 3) rank 25, Doug Martin rank 28, Justin Blackmon rank 51. Data notes: the dropped-row note and the always-on positions note (no games note: 256 of 256). Attribution present. `auto FINISH` gave COMPLETE: **userFinish 6, weekCount 17, week startDate null**, hindsight 2704.72 against the user's 1825.14, bust pick 78 Chris Cooley, steal pick 78 Robert Griffin III. Week 1 starters were TE, QB, WR, WR, RB, RB, FLEX with every `gameDate` null.
Server-side ms from the log: **create 464 ms, replay 425 ms, auto FINISH 578 ms total** (client side 568 and 605 ms). One sample each, not a distribution.

### Ran: the full 27-season import
`{"part":"NFLVERSE"}`: **DONE, 27 seasons, 0 failed, wallSeconds 113.955** (1.2 to 7.7 s per season; the spread is the download). 150,396 player-week rows. Games loaded vs expected: 1999 = 247 (prior-only, expected null), **2000 = 246/248**, 2001 = 248/248, 2002-2020 = 256/256, 2021 = 272/272, **2022 = 271/271**, 2023-2025 = 272/272. Max REG week 17 through 2020 and 18 from 2021. droppedRows: 2001 = 3, 2003 = 1, 2005 = 1, 2012 = 1, all others 0; rawBlankIdRows 17 or 18 every season. **These match the parent's 2026-11-05 measurements.** Draft picks: 3643 read, 2300 stored. The import is left in 5439 `draftsim`, which also holds a half-finished league ingest (see below).

### Ran: suite
`./gradlew test` on a FRESH database `draftsim_suite` on 5439 (init script excluding the two 5433-writing classes): **2744 tests, 13 failed, 54 skipped.** The 13 failures are exactly the pre-existing list (AccessControlMvcIT.draftRoutesAre404WithNoIdentity, AdminDigestControllerIT.anExplicitDateIsAccepted, 8 x TargetControllerIT, EspnRefreshOnVisitIT, 2 x LeagueRepositorySuccessorIT). The 54 skips are the same data-absence skips as the base (T014 note).
A first run against the *used* `draftsim` database showed a 14th failure, `DraftGradesReadIT.nfl2025`. Cause (verified by query): running bootRun on that database cold-started the app's league ingest, which left a half-ingested NFL 2025 draft in it (draft rows 81 and 82 exist; at the T014 run this test skipped). It is not a code change, and the fresh database gives 13.

### Found, not fixed (053 code)
`HistoricalGameRepository.summaries` reads `finish` and then calls `rs.wasNull()` after reading `user_slot`, so `wasNull()` is about `user_slot` and a DRAFTING game's NULL finish comes out as **0**, not null. The 053 test only checks a COMPLETE game. The wire type says `finish` is null until COMPLETE, so the web list may show a finish of 0 for an unfinished game. The fix is one line (read `finish` into an `Integer` first); left for the parent because it is already-deployed 053 behaviour.
## T003 measurements (2026-11-05, parent session, scratchpad script nfl_t003.py, all 27 `.csv.gz` files)

| season | REG games | max week | skill rows | blank-id rows (raw) | blank-id with skill pos or scored stat | FR-003 PPR (INT −2) = source PPR ±0.01 | dup (player, week) |
|---|---|---|---|---|---|---|---|
| 1999 | 247 (real 248; prior lines only, not offered) | 17 | 5426 | 0 | 0 | 5426/5426 | 0 |
| 2000 | **246** (real 248) | 17 | 5260 | 0 | 0 | 100% | 0 |
| 2001 | 248 | 17 | 5397 | 17 | 3 | 100% | 0 |
| 2002–2020 | 256 each | 17 | 5251–5670 | 17 each | 2003: 1, 2005: 1, 2012: 1, others 0 | 100% every season | 0 |
| 2021 | 272 | 18 | 5987 | 18 | 0 | 100% | 0 |
| 2022 | **271** (real 271, Bills–Bengals cancelled) | 18 | 5918 | 18 | 0 | 100% | 0 |
| 2023–2025 | 272 each | 18 | 5897–6108 | 18 each | 0 | 100% | 0 |

The six "meaningful" blank-id rows are:
- **Team-level stats**: 2001 GB 42 passing yards, 2001 ARI and BAL one team fumble lost each, 2003 PHI −1 rushing yard, and 2005 "Team LV" one special-teams TD.
- **One real but unidentified player**: 2012 "D.Bryant" (TEN) with 2 rec for 11 yd.

So the F7 data note ("N player rows had no player id and were left out") shows 1–3 on five seasons and is truthful. Python's own SSL store failed on github.com (CERTIFICATE_VERIFY_FAILED), so the files were fetched via PowerShell. **The Java importer's TLS needs its own live check.**
