# Code review: spec 053, historical draft game

Bug-hunting review of everything uncommitted on `053-historical-draft-game`: the new `historical/` package, the
two controllers, `HistoricalProperties`, the `RealizedLineupService` extraction, `SleeperPlayerStatsClient.seasonTotals`,
V43, `resources/historical/*`, `application.yml`, `build.gradle.kts`, the web pages/components/copy, `api.ts`,
`App.tsx`, `Rail.tsx`, `analytics.ts`, and the tests. Judged against `spec.md`, `plan.md` (including the
"Amended after review" table), `data-model.md`, `contracts/api.md`, `plan-review.md` and `research-notes.md`.
Reviewed 2026-11-02.

**How this was checked.** No source file was edited.

- Backend: `./gradlew test --tests 'com.ballknowers.draftsim.historical.*'` gave 94 tests, 0 skipped, 0 failed.
  `--tests '*Historical*' --tests '*RealizedLineup*'` gave the four `api/Historical*IT` classes (15 tests) and
  `RealizedLineupServiceTest` (7 tests), all green. Both runs used the scratch Postgres on 5438, and the skip
  count was 0, so the ITs really ran.
- Web: `npx tsc -b` is clean, and `HistoricalDraft.test.tsx` + `HistoricalRoom.test.tsx` pass 10/10.
- Data: read-only SQL against the fully imported scratch DB (`draftsim-pg-053`, import job 16, part ALL, DONE,
  no failed seasons).
- Photos: four `curl` HEAD-style GETs, plus a 41-id sweep, against `sleepercdn.com` (finding 3).

"CONFIRMED" below means I ran it, queried it, or read the exact code path end to end. "SUSPECTED" means I
reasoned about it and could not prove it cheaply.

## Findings

### 1. HIGH, CONFIRMED: All-Star, Rising Stars and NBA Cup-final games are imported as ordinary regular-season games (Sleeper seasons 2017-18 to 2025-26)

- **Evidence:**
  - `SleeperHistoricalSource.parseWeek` (`:95-147`) drops only `TEAM_*` ids, empty stats and post-cutoff
    dates. Nothing looks at what kind of game an entry is. Every row is stored by `replaceSeasonGames`
    (`:355-369`) and scored by `SeasonReplay.scorePlayerWeeks`.
  - **Exhibition rows (measured).** Rows with exhibition team codes, per season:
    - 2017: LBN, STP, USA, WLD
    - 2018: GNS, LBN, USA, WLD
    - 2019: GNS, LBN, USA, WLD
    - 2020: DRT, LBN
    - 2021: BAR, IAH, PAY, WOR, DRT, LBN
    - 2022: GNS, LBN
    - 2023: EST, WST
    - 2024: CHK, SHQ
    - 2025: STP, STR

    That is 15 to 52 rows a season, 285 in all.
  - **Cup finals (measured).** These are present as ordinary games:
    - 2023-12-09 IND v LAL
    - 2024-12-17 MIL v OKC
    - 2025-12-16 NYK v SAS
  - **Totals agree (measured).** Sleeper's season totals exclude the All-Star game but the box rows include it.
    In 2018-19, Harden has 78 G in his card line and 79 game rows. Kemba Walker has 82 and 83.
  - **Inflated game counts.** `historical_season.game_count` is inflated for Sleeper seasons, for example 1234
    for 2021-22. SC-002's "equals the real schedule" is false for the Sleeper era.
- **Failure scenario (measured).** I scored every Sleeper row with the Legacy weights and ranked each player's
  games within their week. An exhibition game was that player's best game of the week, and so the locked
  lock-in game, in 53 player-weeks across the nine seasons: 11, 16, 9, 3, 5, 4, 4, 1 and 0. Jayson Tatum's
  55-point 2023 All-Star game, with its 40- and 50-point bonuses, is one of them. A drafted All-Star's All-Star
  week is scored on an exhibition.
- **Kaggle side: clean (verified).**
  - `stageBoxScores` (`KaggleHistoricalSource:228`) keeps only `Game_ID` starting `002`. That prefix is the
    regular season: All-Star is 003, playoffs 004, play-in 005.
  - Loaded game counts match the real schedule in every Kaggle season I checked:
    - 697 for 1970-73
    - 738 for 1974-75
    - 902 for 1976-79
    - 943 for 1980-87
    - 1025 for 1988
    - 1107 for 1989-94
    - 725 for 1998-99
    - 990 for 2011-12
    - 1229 for 2012-13 (the cancelled Boston game)
    - 1230 for 2004-16
  - The Cup does not exist before 2023.
- **The repo already has the rule. Do not write a third one.** AGENTS.md's recurring class is "two
  implementations of one rule", and there are already two:
  - **(a) The box-level rule.** `NbaGameLines.seasonTeamCodes` + `isTeamGame` (`engine/NbaGameLines.java:55-77`)
    says a game is a team's game when its opponent is a season team code, taken from `TEAM_` rows. It **cannot be
    reused as is.** The import report shows `teamEntriesDropped: 0` for 2017, 2018 and 2019: Sleeper sent no
    `TEAM_` rows in those seasons, so the code set would be empty and every game would be dropped. The import
    also never captures `opponent`.
  - **(b) The schedule-level rule (spec 017).** `ScheduleGridService.exhibitionGames` (`engine/ScheduleGridService.java:202`)
    flags a side with fewer than `EXHIBITION_SHARE` (0.25) of the median team's game count. It is already
    shared by `NextGames` and `LockInService`. It needs only the two team codes per game, which the weekly
    entries carry.
  - **Cup finals** are a third, separate, configured list: `NbaGameProperties.excludedGameIds`
    (`config/weights.yml:245`). It holds Sleeper's **raw** game-id strings, which the historical import hashes
    away (`stableHash`, `:128`). `"20241217_OKC_MIL"` became `14521331066943203`.
- **Fix:**
  1. In `importSeason`, before any game is built, group the season's `SleeperGame`s by raw game id into one
     `SportSchedule.Game(id, 0, date, "complete", teamA, teamB)` per game. Call
     `ScheduleGridService.exhibitionGames(list)` once and drop every entry whose game it flags.
     - Keep rows with a null team. 2019-20 has 14 real games for players with no team code (Isaiah Thomas, C.J.
       Miles…). `isExhibitionSide(null)` already returns false, so the shared predicate does the right thing.
     - Do not hand-list `USA/WLD/LBN…`: that would be the third rule, and the codes change yearly.
  2. Keep the raw game-id string on `SleeperGame` and drop ids in `NbaGameProperties.excludedGameIdSet()`
     before hashing. Inject `NbaGameProperties` into the source.
  3. Add the 2023-24 final (IND v LAL, 2023-12-09) to `weights.yml`'s `excluded-game-ids`; it is not there today.
     The existing features never read 2023, so adding it changes nothing else.
  4. Re-run the Sleeper part. Then assert `game_count = 1230` for 2017, 2018, 2021, 2022, 2023, 2024 and 2025,
     1080 for 2020, and the real count to 2020-03-11 for 2019. 2019-20 also has the null-team ids of finding 10.
  5. Add a `SleeperHistoricalSourceTest` fixture with two franchise games plus an All-Star game whose sides
     appear once each. Assert that the All-Star rows are gone and the null-team row is kept.

### 2. MEDIUM, CONFIRMED: Sleeper 2017-18, 2018-19 and 2019-20 never carry the bonus keys, so every bonus scores 0 while the screen lists it as counted

- **Evidence:**
  - `SleeperHistoricalSource.parseWeek` reads `bonus_pt_40p/50p`, `bonus_reb_20p` and `bonus_ast_15p` straight
    from Sleeper's stat map, and a missing key becomes 0 (`:132-138`).
  - `EraScoring.resolve` counts every key for a Sleeper season (`:123-126`), so `counted_stats` lists all four
    bonuses for 2017-2019.
  - **Measured** (40+-point games, then 50+, 20+ reb, 15+ ast, then how many carry the flag):

    | Season | 40+ pts | 50+ pts | 20+ reb | 15+ ast | Flagged |
    |---|---|---|---|---|---|
    | 2017 | 91 | 13 | 56 | 38 | 0 |
    | 2018 | 137 | 22 | 72 | 47 | 0 |
    | 2019 | 105 | 20 | 47 | 23 | 0 |

    From 2020 on, the flags match the box stats exactly: 119 of 119, 205 of 205, and so on. `dd`, `td`, `tf`
    and `ff` are present in 2017.
- **Failure scenario:** Harden's 2018-19 has dozens of 40-point games and every one scores without its bonus.
  The disclosure says bonuses count. Research R2's premise, "Sleeper omits zeros, so absence means 0", is wrong
  for these keys in these seasons: absence meant "not computed". This is the dishonest-certainty case AGENTS.md
  calls out.
- **Fix:**
  - Derive the four bonuses, and dd/td, from the box stats for Sleeper rows too. Call
    `KaggleIdentity.derive(pts, reb, ast, stl, blk)`, the one implementation the Kaggle path already uses, after
    `parseWeek` builds `v`. Keep it consistent: either always derive, or OR the derived flag with Sleeper's own.
  - Add a fixture test: a 41-point entry with no `bonus_pt_40p` key must store `bonus_pt_40p = 1`.
  - Correct R2 in place with an "amended after review" note.

### 3. MEDIUM, CONFIRMED: the draft board shows real Sleeper headshots of the wrong people

- **Evidence:**
  - `historicalCopy.cardToPlayerRef` (`web/src/historicalCopy.ts:58`) sets `sleeperId: String(c.id)`, which is
    the `historical_player` bigserial.
  - `DraftBoard` renders `<PlayerFace sport="nba" sleeperId=… />` for every filled cell
    (`DraftBoard.tsx:~337`). `PlayerFace` loads `https://sleepercdn.com/content/nba/players/thumb/{sleeperId}.jpg`
    (`PlayerFace.tsx:24-25`).
  - Historical ids run from 10 to 23607.
  - **Measured:** id 2000 is Greg Ballard (KAGGLE), and `thumb/2000.jpg` returns 200 with a 39 KB JPEG of
    whoever Sleeper's NBA player 2000 is. In a sweep of ids 1000..3000 in steps of 50, 25 of 41 return a real
    photo.
- **Failure scenario:** in a 1985-86 draft, Greg Ballard's cell, and about 60% of all cells, shows a modern
  player's face. It is visibly wrong, and on the one page whose whole premise is "what a 1985 drafter knew".
- **Fix:**
  - Never pass a historical id as a Sleeper id. The smallest change is `sleeperId: \`h${c.id}\`` (the CDN answers
    403, and `PlayerFace` falls back to the team logo or initials). Better is an explicit `noPhoto` prop.
  - Add a historicalCopy test pinning that `cardToPlayerRef(c).sleeperId` is never a bare number.
  - If photos are wanted for Sleeper-era players, carry `source`/`source_key` on the card as a separate field.
    Kaggle players have no Sleeper id.

### 4. MEDIUM, CONFIRMED: every veteran on a 2017-18 board is labelled "rookie" (and so is anyone who sat out the season before)

- **Evidence:**
  - Sleeper's 2016 lines are cards only: there are no weekly logs for 2016, so `lastTeam.get(id)` is always
    null (`SleeperHistoricalSource:283,347`).
  - **Measured:** 486 Sleeper 2016 lines, 0 with a team. 17 of 547 2018 lines and 17 of 529 2019 lines are
    teamless too.
  - `HistoricalBoardBuilder` takes `team`/`age` from the S-1 line only (`:88-89`), so a player ranked on an
    S-2/S-3 line (the F6 widening) also gets team = null and age = null.
  - `HistoricalPlayerList` renders `{p.team ?? 'rookie'}` (`:~185`).
- **Failure scenario:** in a 2017-18 game, LeBron's card reads "rookie". So does every other player on the
  board. In any season, a player who missed all of S-1 and is ranked on S-2 also reads "rookie" next to a full
  prior line.
- **Fix:**
  - UI: say "rookie" only when `p.prior == null`, and show "—" for an unknown team otherwise.
  - Builder: take `team`/`age` from the line actually shown (`shown`), not from `l1`, so card and line agree.
  - Optionally, fill the Sleeper 2016 team from the totals payload if it carries one. That is unmeasured.

### 5. LOW, CONFIRMED: the Kaggle/Sleeper seam shrinks the ranking window for 2017-18 and 2018-19

- **Evidence:** a Sleeper player has Sleeper lines only from 2016 on (`FIRST_TOTALS_SEASON = 2016`), so the
  3-season lookback is really 1 season for 2017 and 2 for 2018.
- **Measured:** players with Sleeper 2016 games < 20 but a 20+-game Kaggle 2015 line fall into the unranked,
  shuffled tail on a 2017 board. Examples: Mario Chalmers, Lance Stephenson, Jerryd Bayless, Jarrett Jack,
  Justise Winslow. For 2018 the examples are John Jenkins and Udonis Haslem.
- **Impact:** small, because none of them is a star. But it is the exact F6 failure the lookback widening was
  meant to fix, re-introduced at the seam.
- **Fix:**
  - Set `FIRST_TOTALS_SEASON = 2014`, if `/v1/stats/nba/regular/2014` and `/2015` answer. That is unmeasured.
  - Otherwise, say so in research R3 / the card copy.

### 6. LOW, CONFIRMED: Kaggle dd/td is null (scores 0) when steals or blocks are missing, even if points and rebounds already make the double-double

- **Evidence:**
  - `KaggleIdentity.derive` (`:87-94`) yields dd = null unless all five inputs are non-null. The replay maps
    null to 0 (`SeasonReplay.statMap:143`).
  - **Measured:** in counted-dd seasons, 1983-84 has 6 rows and 1984-85 has 15 rows with pts ≥ 10 and reb ≥ 10
    (or ast) whose dd is null.
- **Fix:**
  - dd = 1 when at least 2 *known* inputs reach 10.
  - dd = 0 only when the known tens plus the unknown count is below 2.
  - Otherwise null. Do the same for td at 3.
  - Add a unit test.

### 7. LOW, SUSPECTED: a finished game's disclosure is read live, so a re-import can make it contradict the frozen scoring

- **Evidence:** `HistoricalGameService.state` (`:399-404`) takes `countedStats` from the frozen scoring
  snapshot, but takes `excludedStats` and `statCoverage` from the live `historical_season` row.
- **Failure scenario:** a re-import after any change moves a stat across the era threshold. The trigger could
  be a threshold change, or finding 1's fix changing coverage. A COMPLETE game then shows a stat both under
  "Stats that count" (frozen) and under "Not counted" (live). The scores stay frozen, so only the explanation
  drifts, against FR-013's "reopened unchanged".
- **Fix:** snapshot `excluded_stats` and `stat_coverage` into the game at creation, and read them from there.
  V43 has never been applied anywhere shared, so a column can still be added to V43 before merge. Otherwise it
  is V44.

### 8. LOW, SUSPECTED: a re-import that re-keys a person silently zeroes him in every in-progress game

- **Evidence:**
  - Players are upserted on `(source, source_key)` and never deleted, which is good.
  - But a curation edit changes the Kaggle `source_key`, for example adding `george-johnson-2` to
    `historical-same-name.csv`. That creates a **new** id and re-points that season's game rows to it.
  - A DRAFTING game's board snapshot still holds the old id. At the last pick, `finishDraft` calls
    `data.playedGames(season, byId.keySet())` (`:366`), finds no rows for him, and he scores 0 all season with
    no error.
  - Separately, `findSeason`, `weekStarts` and `playedGames` are three reads with no snapshot isolation, so a
    re-import committing in between could mix week counts.
- **Fix:**
  - In `finishDraft`, fail loud if a drafted player has zero game rows but appears in the season's candidate set
    at creation (the snapshot only has players who played).
  - Alternatively, refuse an import while DRAFTING games exist on a season it would replace.

### 9. LOW, SUSPECTED: a fast double click sends two picks, and the loser shows an error although the pick went through

- **Evidence:** `HistoricalRoom.run` guards on `busy` (`:99`), a React state value captured in the click
  closure. Two clicks that land before the re-render both pass the guard.
- **Backend: safe (verified by reading).** `lockForUpdate` serialises the two calls. The second sees the cursor
  moved and throws a 409 from `requireUsersTurn`, and `fail(e)` shows it as an error banner over a successful
  pick.
- **Fix:** keep an in-flight `useRef` guard alongside `busy`. The mock room has the same shape, so check it
  too.

### 10. LOW, CONFIRMED: rows with a null team carry their own hashed game ids, which inflates `game_count`

- **Evidence:** 2019-20 has 14 played rows with no team code (Isaiah Thomas ×4, C.J. Miles, Tyler Johnson ×2…).
  They sit under 11 game ids that no teammate shares, because the raw Sleeper id was hashed per row.
- **Impact:** scoring is unaffected (each is a real game for that player). It is only the SC-002 count, and a
  trap for finding 1's fix if that fix filtered on team codes.
- **Fix:** none needed beyond keeping these rows (finding 1, step 1). Optionally note the count in the import
  report.

### 11. LOW, CONFIRMED: stat order on screen depends on jsonb and on `Map.copyOf` hash order (cosmetic)

- **Evidence:**
  - `HistoricalProperties` does `scoring = Map.copyOf(scoring)` (`:~52`), whose iteration order is randomised
    per JVM. `EraScoring.resolve` and the Sleeper path store `counted_stats` / `excluded_stats` in that order.
    The DB shows `{dd,ff,bonus_reb_20p,ast,td,…}`.
  - The game's `countedStats` come back from a jsonb object, which sorts keys by length, then bytes.
  - The pills in `SeasonDisclosure` render in that order.
- **Impact:** scoring is unaffected. `create` uses a `TreeMap`, and `GameScoringService` sums in the stored
  key order, which is deterministic.
- **Fix:** sort for display with `historicalCopy`'s `STAT_ORDER` (extended with the derived keys), and use
  `new LinkedHashMap<>(scoring)` in the properties.

## Checked and fine

- **Ranking direction (class 1).**
  - The board is sorted by score descending, so rank 1 is the best, and `toEntry` passes rank as `adp`
    ascending, which is what `PickDecider` reads.
  - Ties break by id.
  - The unranked tail is sorted by id, then shuffled with the stored seed.
  - Pinned by `HistoricalBoardBuilderTest`.
- **Bust and hindsight math.**
  - The bust gap is `rank − pickNo`, positive only, with ties to the earlier pick. Pick 3 finishing 150th is a
    bust; pick 160 finishing 2nd is not.
  - Steal excludes the user's own later picks (F19) and allows players a bot took later.
  - Hindsight is lineup-aware. Its branch-and-bound bound (`positiveValue` + rounding slack) is valid for
    non-negative marginal adds.
  - `userBeatIt` is a strict `<`.
- **Replay.**
  - The weekly max is a strict `>`, so the earlier game wins a tie.
  - A counted stat missing from a box score maps to 0, and a non-counted key is never in the scoring map.
  - An all-negative week keeps its negative max.
  - An empty roster-week is 0, not absent (F20).
  - W-L-T compares rounded cents. Standings sort on wins + ½ ties, then PF, then a seeded coin.
  - The round-robin circle method is correct for 12 teams and any week count.
- **Era rule.**
  - Coverage is computed over played rows (min > 0 or pts > 0; the MIN column parses, since pts = 0 row counts
    match the Sleeper era's).
  - "Tracked" comes from the season file (> 50% of rows).
  - tf/ff are always excluded for Kaggle, and dd/td need stl and blk.
  - The DB's per-season counted sets match O2 exactly: reb/ast from 1970, tpm from 1979, blk from 1982, all
    from 1983.
- **Weeks.**
  - Mon–Sun weeks run from opening night, and empty calendar weeks are skipped.
  - 2019-20 stops at 2020-03-11 (`droppedAfterCutoff: 611`).
  - The lockouts (1998-99: 14 weeks, 2011-12: 19 weeks) and 2020-21 (opening 2020-12-22) load.
  - 2011-12 opened on a Sunday, so its week 1 is a single day. That is per spec, not a bug.
- **FR-003 while DRAFTING.**
  - Cards carry the prior line only. `team`/`age` are prior or null.
  - `result` is null and `available` is in prior-rank order.
  - `season` carries the rules disclosure only.
  - The board-composition leak (only players who played) is R6's, which is accepted. The rookie-position leak
    is F5's, also accepted.
  - No "ADP" text renders: `DraftBoard`'s ADP strings sit behind `valueView`/`adpAtDraft`, which this page
    never passes, and the copy says "ranked by last season's points".
- **Locking and transactions.**
  - pick, auto and abandon go through `lockForUpdate` inside `@Transactional` methods called from the
    controller, through the proxy, so there is no self-invocation.
  - The replay runs once, at `adv.complete()`, on the locked DRAFTING row. After that, `requireDrafting` makes
    every mutation a 409.
  - The unique `(game_id, player_id)` backs up the taken check.
  - The import uses an explicit `TransactionTemplate` per season, not `@Transactional`, so the virtual-thread
    job has no proxy pitfall.
  - A second start is a 409, via the `AtomicBoolean` and `IllegalStateException`.
  - Staging is truncated at the start and in `finally`.
- **Import idempotency.**
  - Players are upserted and never deleted.
  - `replaceSeasonLines(s, source, …)` deletes only its own source's rows, so season 2016's Kaggle and Sleeper
    lines coexist (486 + 486 measured).
  - Game rows are replaced per season. No FK from game picks to game rows blocks a re-import.
- **JDBC/SQL (classes 2/3).**
  - `text[]` binds use `createArrayOf`, and `bigint[]` uses `any(?)`.
  - jsonb is bound via `?::jsonb`, and timestamptz via `OffsetDateTime`.
  - `"to"` is quoted everywhere.
  - Smallint is bound via `setShort` and numeric via `setDouble`.
  - The COPY goes through `CopyManager` with UTF-8 bytes and quoted fields; an unquoted empty field means NULL.
  - Every statement has executed in the ITs and in the full import.
- **Memory.** The zip is streamed to disk, and the box entry is streamed through COPY in 32 KB chunks, never
  whole. Per season, Kaggle phase 2 loads only that season's staged rows (about 25-30k). Sleeper phase A holds
  about 225k compact rows. I did not profile the heap; this is inferred from the code against `-Xmx1024m`.
- **API.**
  - A missing or blank `X-Sleeper-User` gives 401.
  - Someone else's game gives 404 (owner filter on the locked or read row).
  - `IllegalStateException` gives 409 and `IllegalArgumentException` gives 400 (`ErrorHandler`).
  - Admin gating is the `/api/admin/**` interceptor, and CORS `/api/**` allows `X-Sleeper-User`.
  - No `Map.of` with a nullable value is in any response path (the controllers build `LinkedHashMap`s).
- **`api.ts` against the Java records, diffed field for field:**
  - `HistoricalPlayerCard`/`Prior` (incl. `bestOfTwo`)
  - `SeasonView` (incl. `statCoverage`, `settingsSource`)
  - `PickView`
  - `GameSummary`
  - `Week`/`Matchup`/`Standing` (incl. `ties`)
  - `LineupWeek`/`Starter`
  - `Hindsight` (incl. `userBeatIt`)
  - `Bust` (incl. `seasonValue`)
  - `Steal`
  - `SeasonSummary`
  - `isUsersTurn` serialises under that name (the MvcIT asserts it).

  All match.
- **Web wiring.**
  - The routes sit inside the signed-in shell, and `KeyedHistoricalRoom` remounts on `gameId`.
  - The rail entry and analytics routes are present.
  - Loading, 404 and error states render.
  - Null-safe renders hold for `prior`, `team`, `age`, `biggestBust`, `bestStealPassedOn` and `startDate`.

## Fixes applied (2026-11-02)

Fix pass for the 11 findings above. **Verified** = executed. Scratch DB 5438, backend on port 8153 (stopped afterwards).

| # | Finding | What changed | Evidence |
|---|---|---|---|
| 1 | Exhibition and Cup-final games imported | `SleeperHistoricalSource`: `SleeperGame` keeps the raw game id; new `dropExhibitions` builds one `SportSchedule.Game` per raw id (sides = the team codes of its rows) and calls the existing `ScheduleGridService.exhibitionGames` (already public static, so no refactor and no third rule; null-team rows are kept). Cup finals are dropped in `parseWeek` on the RAW id from `NbaGameProperties.excludedGameIdSet()` before hashing (constructor-injected). `config/weights.yml` gained `"20231209_LAL_IND"`; id read from Sleeper `GET /stats/nba/2023/7` (rows dated 2023-12-09 carry `game_id` `20231209_LAL_IND`, teams IND and LAL). `NbaGamePropertiesTest` updated for the new list. | Tests `SleeperHistoricalSourceTest.anAllStarGameAndACupFinalAreDroppedAndAnOrdinaryGameAndATeamlessRowAreKept`, `NbaGamePropertiesTest.realWeightsYmlBindsBothCupFinalsAsStrings`. Re-import job 58 (DONE, empty problem report). SQL: zero Sleeper rows with any exhibition team code (LBN, USA, WLD, STP, GNS, DRT, BAR, IAH, PAY, WOR, EST, WST, CHK, SHQ, STR); zero rows on the three Cup-final dates for the finalists; `game_count` 1230 for 2017, 2018, 2021-2025, 1080 for 2020, 971 for 2019. Tatum's 2023 All-Star week (2024-02-12..25) now holds only BOS games: 41, 20, 25, 19 pts, no 55. Report notes per season: `exhibitionGamesDropped`, `exhibitionRowsDropped`, `cupFinalRowsDropped`. |
| 2 | Bonus, dd, td missing for Sleeper 2017-19 | `parseWeek` now runs `KaggleIdentity.derive` (the one implementation) on the box stats and ORs the result into Sleeper's own flags, for every Sleeper row. | Test `aSleeperLineWithoutBonusKeysGetsThemDerivedFromTheBoxStats` (41-pt line gets `bonus_pt_40p = 1`). SQL on re-imported data: Sleeper 2017-2019 rows with pts >= 40: 333, of which `bonus_pt_40p = 1`: 333; pts >= 50: 55, `bonus_pt_50p = 1`: 55 (before the fix: 0 of 333). R2 amended in `research.md`. |
| 3 | Wrong Sleeper headshots | `PlayerFace` takes `noPhoto` (starts at initials, never requests the CDN); `DraftBoard` takes `hidePhotos`; `HistoricalRoom` passes it. `cardToPlayerRef` now sets `sleeperId: 'h' + id` so it can never look like a Sleeper id. Backend `Player.sleeperId` is not used on this path (the page reads cards). | Vitest `HistoricalRoom review fixes > never requests a Sleeper headshot...` (no `img` src contains `sleepercdn`; I confirmed it fails with `hidePhotos` removed). |
| 4 | "rookie" mislabel | UI no longer prints "rookie": team renders `team ?? '—'`, and "no NBA stats last season" comes only from `priorLine(prior == null)`. `HistoricalBoardBuilder` takes `team`/`age` from the line the card SHOWS, not S-1. | Vitest `labels "no NBA stats last season" from prior == null, never from a null team` (veteran with team null shows his line, no "rookie" anywhere); `HistoricalBoardBuilderTest.teamAndAgeComeFromTheLineTheCardShowsNotFromAnEmptySeasonMinusOne`. |
| 5 | Seam lookback | `FIRST_TOTALS_SEASON = 2014`. `/v1/stats/nba/regular/2014` and `/2015` answer (494 and 481 players, with `gp`). 2014/2015 are lines only (`linesOnly: true`), source SLEEPER. | SQL: `historical_player_season` Sleeper lines 2014: 492, 2015: 476, 2016: 486; `historical_season` rows for Sleeper 2014/2015: 0; total `historical_season` rows still 56. `SleeperHistoricalSourceTest` constant check updated. R3 amended in `research.md`. |
| 6 | Kaggle dd/td when stl/blk missing | `KaggleIdentity.derive`: dd/td treat a missing stat as 0 (FR-007), null only when all five inputs are null; bonuses unchanged. Kaggle part re-imported (job 59, DONE, empty problem report; `teamCodeMismatches` identical to the previous run). | Test `KaggleIdentityTest.aBonusIsNullWhenItsInputIsNullButDdTdTreatMissingStatsAsZero`. SQL: Kaggle rows 1983-84/1984-85 start years with null dd: 0 (was 6 + 15 qualifying rows). |
| 7 | Disclosure read live | `excludedStats` and `statCoverage` are now frozen into the game's existing `board` jsonb (`{"cards":[...],"excludedStats":{...},"statCoverage":{...}}`); V43 untouched. `readSnapshot` also reads the older bare-array form (then falls back to the live season row). | IT `HistoricalGameMvcIT.theDisclosureIsFrozenAtCreationAndStatsComeBackInTheCanonicalOrder` (season row changed after creation; the game still shows the original). |
| 8 | Re-import identity drift | Simplest honest option: `finishDraft` fails loud. A drafted player with no game rows (the board only holds players who played) logs an error and throws `IllegalStateException` (409, message says the data was re-imported in progress); the pick transaction rolls back and the game stays DRAFTING. HANDOFF also says not to re-import while games are DRAFTING. | IT `aDraftedPlayerWhoLostAllGameRowsToAReImportFailsLoudAtTheLastPickInsteadOfScoringZero`. Not done: refusing an import while DRAFTING games exist (a bigger behaviour change). |
| 9 | Double click | `HistoricalRoom.run` also guards on an in-flight `useRef`. The mock room (`MockDraftView`) has the same `busy` closure shape and was NOT changed (outside this feature). | Vitest `sends one pick for a fast double click on Draft` (two synchronous clicks, one `historicalPick` call). |
| 10 | Null-team rows / `game_count` | Rows with a null team are kept (covered by 1); `game_count` is the count of distinct ids of kept games only. | SQL: 2019 team-less rows 14, under 11 distinct ids, still present; `game_count` values as in 1. |
| 11 | Stat display order | New `StatOrder` (pts, reb, ast, stl, blk, to, tpm, then fgm.. fta, dd, td, bonuses, tf, ff). `HistoricalGameService.state` emits `countedStats`, `excludedStats` and `statCoverage` through it; `HistoricalPlayerCard.Prior` sorts `perGame` in its constructor (so it also holds after the jsonb round trip); `HistoricalProperties.scoring` is a `LinkedHashMap` copy, not `Map.copyOf`. No Java record changed shape, so `api.ts` is unchanged. | Same IT as 7 asserts `countedStats`, `statCoverage` and `perGame` keys come back as pts, reb, ast. |

**Runs.**
- Backend, `DB_URL=jdbc:postgresql://localhost:5438/draftsim`: `--tests 'com.ballknowers.draftsim.historical.*' '*Historical*' '*ScheduleGrid*' '*RealizedLineup*' '*NbaGameProperties*'` plus `*LockIn*`/`*NextGames*` as a check on the shared rule: 213 tests, 0 failed. The only skips (4) are `LockInControllerIT`, which is outside the requested filter; the requested classes had 0 skipped (checked in the result XML).
- Web: `npx tsc -b` clean, `npx vitest run` 158 files / 2,737 tests passed, `npm run build` ok.
- SC-005 re-measured: 523 of 527 matched players (99.2%) within 0.5 pts as imported (see `research-notes.md`).
- VACUUM FULL ANALYZE run on `historical_player`, `historical_player_season`, `historical_player_game`, `historical_season`. It first hung for ~5 minutes behind a runaway read-only review query (pid 743, 38 min old, a correlated subquery on the game table) which I terminated.
