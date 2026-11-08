# Code review: spec 056, Football Time Machine

A bug-hunting review of the whole uncommitted change on `056-nfl-historical-draft-game` against HEAD 5a47471
(origin/main): 31 modified files and 20 new ones in `backend/` and `web/`, plus V45. It was judged against
`spec.md`, `plan.md` (its "Amended after review" table wins), `data-model.md`, `contracts/api.md`, `plan-review.md`,
`tasks.md` and `research-notes.md`, and against AGENTS.md's hard rules and its 8 bug classes. Reviewed 2026-11-05.
No source file was edited.

**How this was checked.**
- Backend, focused suite: `./gradlew test --tests '*Historical*' --tests '*Nfl*' --tests '*SportCoexistence*'
  --tests '*SeasonReplay*' --tests '*Comparisons*'` on `draftsim_suite` (5439), with the scratch init script that
  excludes the two 5433-writing classes. **163 tests, 0 failed, 5 skipped.** All 5 skips are real-data absence
  (`HistoricalGameMvcIT.postCreatesARealSeasonGame…`, `HistoricalRealSeasonIT` ×2, `HistoricalSameNameIT`,
  `TradeVerdictServiceIT.reconciliationNfl2025`), none is a connectivity skip.
- Web: `npx tsc -b` is clean. `HistoricalDraft.test.tsx` + `HistoricalRoom.test.tsx` pass 24/24.
- A scratchpad vitest probe (not in the repo) rendered the new web against 053-shape payloads (finding B1).
- Live backend: bootRun from this worktree on 8156 against 5439 `draftsim` (the full 27-season import). It
  exercised the 400 paths and created 8 NFL games over HTTP (seasons 2004, 2007, 2011, 2015, 2018, 2019, 2020 ×2)
  and auto-finished each one. The server was stopped afterwards.
- SQL, read-only against 5439 `draftsim`: PPR column mapping, a team-week recompute, bot roster composition and
  board shape.

**Labels.** MEASURED = I ran it or queried it. READ = I read the exact code path end to end. SUSPECTED = reasoned
about and not proven.

## Findings

### B1. BLOCKER, MEASURED: the new web crashes every NBA page against today's backend, so "deploy web first" breaks the live NBA game

- **Evidence.** The web reads fields that only the new backend sends, with no fallback:
  - `web/src/pages/HistoricalDraft.tsx:158` has `g.sport.toLowerCase()` and `SPORT_LABEL[g.sport]` on each past-game row.
  - `web/src/pages/HistoricalRoom.tsx:77` has `s.dataNotes.length` in `SeasonDisclosure`, which renders on every room.
  - `web/src/components/HistoricalPlayerList.tsx:50` has `FILTERS[sport].map`, where `sport` is `state.sport`.

  The 053 backend now in prod sends none of `sport`, `season.dataNotes`, `season.attribution` or
  `GameSummary.sport`. A scratchpad vitest fed the new pages 053-shape payloads, and both cases fail with an empty
  render:
  - room: `TypeError: Cannot read properties of undefined (reading 'length')` in `<SeasonDisclosure>`, then `(reading 'map')` in `<HistoricalPlayerList>`;
  - start page with one past game: `TypeError: … (reading 'toLowerCase')` in `<HistoricalDraft>`.
- **Failure scenario.** Plan F1 and contracts F1 say to deploy the web before the backend. Between those two
  deploys, every user with an NBA game in progress or finished gets a blank room, and anyone with a past game gets
  a blank start page. Railway's two services deploy independently and have drifted apart before (memory,
  2026-09-15), so this window can last a long time. A backend rollback reopens it. There is a second problem in the
  same window. The Football tab calls `?sport=NFL`, and the old backend ignores the param and returns NBA seasons,
  so the tab shows the NBA settings line. Starting a game from it creates an **NBA** game, since the old backend
  ignores `sport`.
- **The other order doesn't work either (measured on the new backend).** `GET /api/history/seasons` with no sport
  returns 400, and `POST /api/history/games` with `{}` or no body returns 400. So the old web cannot start an NBA
  game once the new backend is live. Neither deploy order is safe as written.
- **Fix.** Make the web tolerate the old shape, so web-first really is safe:
  - `const sport = state.sport ?? 'NBA'`, `(s.dataNotes ?? [])` and `g.sport ?? 'NBA'`. Mark the TS types as
    possibly-absent for one release, or normalise once in `api.ts` where the JSON is parsed.
  - Show the Football choice only when `historicalSeasons('NFL')` returns rows whose `sport === 'NFL'`. Then the old
    backend can't silently turn a football start into an NBA game. This also covers N7.
  - Add a vitest with 053-shape payloads: the room renders and the list renders. The probe at
    `C:\Users\allan\AppData\Local\Temp\claude\fa985f71cf5ac359\a33b9d2f-e4aa-42ae-ac25-21f334b23184\scratchpad\probe056\oldBackend.test.tsx`
    can be lifted as is (switch its `W/` alias imports to relative ones).
  - Keep `sport` required on the backend (contracts). The tolerance belongs in the web, for the deploy window only.

### S1. SHOULD-FIX, MEASURED: `GET /api/history/games` returns `finish: 0`, not `null`, for every unfinished game (053 bug, carried over)

- **Evidence.** `HistoricalGameRepository.java:114-117` reads `int f = rs.getInt("finish")`, then `rs.getString`
  three times, then `rs.getInt("user_slot")`, and only then `rs.wasNull() ? null : f`. So `wasNull()` reports on
  `user_slot`, which is never null. Measured live: all 8 freshly created NFL games came back
  `"status":"DRAFTING", … "finish":0`. The code path is the same for NBA.
- **Failure scenario.** The contract and `api.ts` both say `finish` is null until COMPLETE. Today's web only reads
  `finish` for COMPLETE games (`statusText`), so nothing on screen is wrong yet. The next caller that trusts the
  type ("Finished 0th", a sort or an average over finishes) will be.
- **Fix.** `Integer finish = rs.getObject("finish", Integer.class);` (or read `getInt` and `wasNull()` back to back
  before any other column). Add one assertion to `HistoricalRepositoryIT` or `SportCoexistenceIT` that a DRAFTING
  row's `finish` is null. Today's tests only check a COMPLETE game.

### S2. SHOULD-FIX (Allan's call, not a code bug), MEASURED: points over replacement fixed QB hoarding in the early rounds but moved it to the bench (T032 numbers)

- **Measured.** These are bot rosters from 9 auto-finished real games (game 38 = 2012, plus the 8 above): 99 bot
  rosters in all.
  - **QBs per bot roster: mean 2.87.** Distribution: 1 QB: 15 rosters, 2: 25, 3: 33, 4: 15, **5: 6, 6: 5**.
    Split by round: **1.33 QBs per roster in rounds 1-8, 1.54 in rounds 9-15.**
  - TEs per bot roster: mean 2.2 to 3.5 by game, max 5.
  - **Rookies drafted by bots: 168 in 9 games (18.7 per game).** All 168 have an NFL pick ≤ 64 (the rounds 1-2
    rookies placed by F14). Bots never reached a seeded-tail rookie.
  - Where the zero line falls on the board (positional rank:overall rank). 2018: QB12 at 83, RB24 at 51, WR24 at 65,
    TE12 at 86. 2007: QB24 at 113, QB30 at 132, QB36 at 142. So QBs 13-36 sit in the overall 88-207 band, which is
    rounds 8-15. In game 38, 33 of the top 180 were QBs.
- **Why.** The code does what F6 specifies (READ: `HistoricalBoardBuilder.overReplacement:199-236`). Once a bot's
  starters are filled, `FootballRules.rosterNeed` returns `benchFloor` for everyone, and the bot takes the best
  rank. Below replacement, the QB curve is much flatter than the RB/WR curves, so backup QBs out-rank RB/WR depth.
  Ignoring the FLEX makes it worse (RB/WR replacement sits shallower than the real starter pool). The auto-pick for
  the user seat follows the same order: in game 63 the user's auto-drafted roster has 5 QBs.
- **Fix options (each a hand-set change, so Allan decides; none is applied here).**
  - A deeper baseline for the bench, such as a second replacement level at teams × (dedicated + bench share).
  - Counting the FLEX in RB/WR depth.
  - A per-position roster cap for bots, as Sleeper leagues have.

  Per AGENTS.md, do not retune `rookie-positional-offset`/`rank-*` to make this number look right. Record these
  numbers in `research-notes.md` (T032) as measured.

### S3. SHOULD-FIX, READ: football's settings line duplicates the config it describes (two copies of one rule)

- **Evidence.** `NflHistoricalSport.java:41-42` hard-codes
  `"Hand-set 12-team, 15-round roster: QB, 2 RB, 2 WR, TE, FLEX + 8 bench · full PPR"`, while `teams`, `rounds`
  and `roster` come from `NflHistoricalProperties`. In the same class, `replacementDepth()` is derived from the
  roster, so the ranking follows a config change but the label doesn't. The web copies the offered range a third
  time (`HistoricalDraft.tsx:32,35`, "2000 to 2025"). `HistoricalImportService.NFL_FIRST_SEASON = 1999` is "first
  offered − 1", written separately.
- **Failure scenario.** Someone changes `historical-nfl.teams` to 10, or bumps `last-offered` to 2026 next spring.
  The draft screen still says "12-team" and "2000 to 2025" (FR-002 requires that label to be true). This is the
  class memory calls "two implementations of one rule".
- **Fix.** Build `settingsSource()` from `props.teams()`, `props.rounds()`, a roster summary and the bench count.
  Have the web take the range from the seasons response (the first and last `seasonStart`). Derive the import's
  first season as `firstOffered - 1`, or assert that relation in `NflHistoricalPropertiesTest`.

### N1. NIT (spec-conformant, flag for Allan), MEASURED: a uniform rookie offset values a #1-overall QB as a backup

`rookie-positional-offset: 12` is the same for every position, but the depths differ (QB 12, RB 24). The first
early rookie RB is valued as RB13, a solid starter. The first early rookie QB is valued as QB13, which is below
replacement. Measured on the 2012 board: **Andrew Luck (NFL pick 1) at rank 95, RG3 behind him, Trent Richardson
(pick 3) at rank 25, Doug Martin at 28.** This is what F14 says, but its effect on QBs and TEs is probably not what
was pictured. A possible fix, if Allan wants one, is an offset per position or "offset = depth/2". Do not tune it
to an expected number.

### N2. NIT, READ: football results can't tell "played" from "no game" for a starter

`SeasonReplay` writes `gameDate` null both when the starter's line has no date (every NFL line) and when he had no
game (`l == null`). The web shows "Week N" for both (`HistoricalResults.tsx:175`). A starter on bye who scored 0
reads like he played. A fix is to emit a `played` boolean on `Starter`, or a sentinel, and show "no game" for it.
Small, and F4 chose "Week N" on purpose, so this is a NIT.

### N3. NIT, READ: three copies of the football stat order

`StatOrder.ORDER`'s football tail (`StatOrder.java:18-20`) and `NflHistoricalSport.COUNTING` are identical today.
Nothing asserts that (research-notes deviation 8 says "assumed"). The web's `STAT_ORDER` (`historicalCopy.ts`) puts
`st_td` before `fum_lost`, a different order again. The impact is display order only. A one-line test,
`StatOrder.ORDER.subList(...) == COUNTING`, closes the backend half.

### N4. NIT, READ: `player_id = "NA"` would be read as a real player

`NflverseSource.parse:149-150` treats only blank and `"0"` as "no id". It parses number cells' `NA` as 0, but not an
id's. nflverse writes `NA` for missing values in other columns. If a future file wrote `NA` ids, every such row
would become one player called "NA", and two in the same week would fail the season as a duplicate. The real files
use blank or `0` (measured by the build session), so this is latent. Add `|| id.equalsIgnoreCase("NA")`. The same
applies to `gsis_id` in `parseDraftPicks`, where it is harmless (the insert-select matches no player).

### N5. NIT, READ: the dropped-rows note overstates what it counts

`NflHistoricalSport.dataNotes:139-141` says "source rows **with stats** but no player id". F7 also counts a blank-id
row that has only a skill position (`NflverseSource.parse:152`). The wording would be "with a skill position or a
scored stat", or the counter should be split.

### N6. NIT, SUSPECTED: a rolling-deploy overlap fails old-backend writes for a few seconds

V45 drops the `sport` default (F8, intended). If Railway runs the old container briefly after Flyway has applied
V45, its `insert into historical_game` (no `sport`) fails NOT NULL, and its `upsertSeason … on conflict
(season_start)` fails too. That is a 500 for a create in those seconds and nothing worse. Worth one line in the
HANDOFF deploy steps.

### N7. NIT, MEASURED (NBA path): the Football tab is live before the post-deploy import

Until the admin runs `NFLVERSE` in prod, a football start returns 409 "no historical NFL seasons have been imported
yet". This was measured with the NBA code path on 5439, which has no NBA data, and NFL is the same code. The page
shows that error after the click. Hiding or disabling Football while `historicalSeasons('NFL')` is empty fixes it
for free (see B1's fix).

### N8. NIT, READ, inherited: the download follows redirects with `Redirect.ALWAYS`

`NflverseSource.download:344` allows an https to http downgrade on redirect, the same as 053's Kaggle client.
`Redirect.NORMAL` (which refuses https to http) is enough for GitHub's CDN 302.

### N9. NIT, READ, inherited from 053: the board and the rookie order only include players who played in the drafted season

`NflHistoricalRepository.candidates` takes the players with a REG row in S. That was 053's documented rule, but it
leaks more in football:
- A veteran who missed the whole season is not on the board (a real drafter would have taken him).
- A round-1 rookie who never played shifts the "k-th early rookie" order up for later rookies.
- A player drafted in S-1 who missed his whole rookie year has no prior line and no S pick, so he lands in the
  seeded tail.

The disclosure doesn't mention any of this. One data-note line, or a spec note, is enough.

## Checked and fine

- **SC-008, NBA arithmetic (READ + suite).** `SeasonReplay.statMap` now puts `Double` where it put `Integer`, and
  `GameScoringService.contributions:70-72` reads every value through `Number.doubleValue()`, so the sums are
  identical. The `GameLine.stat` and `SeasonLine.stat` switches are verbatim moves (null for unrecorded keys, same
  default arms). In `HistoricalBoardBuilder`, the comparator only adds `thenComparing(Row::rookie)`, which is always
  false for NBA. The NBA `Options` carry `Map.of()` picks and `nfl = null`, and `prior()` with an empty
  `totalKeys` gives the same `perGame` and `fantasyPoints`. The 053 builder, replay, comparisons and MVC tests pass
  unchanged.
- **SC-008, reopening (suite).** `SportCoexistenceIT` reopens both a literal 053-shape board and a real game
  rewritten to 053 shape: `dataNotes` `[]`, `attribution` null, `totals` `{}`, `rookiePick` null, and the frozen
  `result` byte-equal. `readSnapshot` uses `has()`/`isNull()` (F9). The shared 5433 DB has 0 `historical_game` rows,
  so no real 053 game could be replayed here. Prod's count is still owed before V45, as planned.
- **Sport-scoped SQL (MEASURED, live and suite).** `upsertSeason on conflict (sport, season_start)`, `findSeason`,
  `offeredSeasons` (closed switch for the prior-lines table) and the `summaries` join on both columns all ran: the
  27-season import, 8 HTTP games and `SportCoexistenceIT`. V45 ran on 5439 in the F2 order (research-notes T004).
  The NFL batch inserts (`numeric` via `setDouble`), `text[]` via `createArrayOf`, and the `integer[]` delete in
  `replaceDraftPicks` all executed in the real import (bug classes 2 and 3).
- **PPR column mapping (MEASURED).** The hand-indexed `NflverseSource.stats:199-220` map, recomputed in SQL with
  INT −2 against `src_ppr` within 0.01: **100.000% of player-weeks in 2000 (5260), 2003 (5346), 2014 (5599) and
  2024 (5935).** A partial SC-004 check; T030 still owns the formal record.
- **One team-week by hand (MEASURED, SC-003 shape).** Game 63 (2007), user lineup weeks 3 and 9: all 13 stored
  starter scores equal the FR-003 recompute (INT −1) from `historical_nfl_player_week` to 0.01. In week 9 the
  second WR slot is empty because no rostered WR played (Andre Johnson, Isaac Bruce and Eddie Kennison have no
  row), which is correct.
- **Ranking direction (READ + measured boards).** Higher points over replacement sorts first, and the list is
  rank-ascending (`HistoricalBoardBuilder.java:150-152`), which `PickDecider` reads as a pick number. Replacement is
  the raw score of the Nth veteran by raw score, with ties by id. Rookies read the raw scores before the veterans
  are rewritten. A veteran beats a rookie on an equal score. On real boards RB1 and WR1 come first and QB1 is at
  6-8. Bots take QBs at 1.33 per roster in rounds 1-8 (S2).
- **The required `sport` (MEASURED).** `seasons` with no param, `?sport=`, `?sport=nfl` (lowercase): 400.
  `create` with `{}`, `{"sport":null}`, `{"sport":"MLB"}`, a `null` body and no body: 400. `userSlot: 13` on NFL:
  400 "1..12".
- **FR-004 (READ + measured cards).** The drafted season's lines (`nfl.lines(s)`) reach the builder only through
  `firstNonEmpty` for a rookie's position, which is the documented leak. A rookie's card has `team` null,
  `prior` null and `rookiePick` (a pre-season fact). Veterans show only the prior line. `dataNotes` describe the
  data, not players.
- **`api.ts` mirror (READ).** `HistoricalGameState.sport`, `SeasonView.dataNotes`/`attribution`,
  `GameSummary.sport`, `HistoricalPlayerCard.rookiePick`, `Prior.totals` and `SeasonSummary.sport`/`dataNotes`
  each have a TS field, and `source` gained `'NFLVERSE'`. The import report is untyped on both sides, as in 053.
- **`Map.of` with nullables.** `writeSnapshot` uses a `LinkedHashMap` (attribution is null for NBA). The new
  `Map.of()` uses (`weekStarts`, NBA `rookiePicks`) hold no values or have no null values.
- **Concurrency.** Every mutating path still takes `lockForUpdate`, and `sportOf(row)` only reads the locked row.
  A re-import during play keeps player ids (upsert on `(NFLVERSE, gsis_id)`, never deleted). The week-row delete and
  reinsert is one transaction, so a concurrent replay sees the old or the new rows, never neither.
- **Import robustness (READ + the build's live run).** A missing header fails the season. A duplicate (player,
  week) fails it. Each season is its own transaction, and one failed season doesn't stop the rest. The draft-picks
  step deletes only the years present in the file. Each file is downloaded to a temp file and parsed one record at
  a time (about 5.5k kept rows per season in memory, which is trivial at `-Xmx1024m`).
- **Timing (MEASURED, server log, 8 samples).** Create took 68-172 ms (board 558-618 players), and the replay
  174-292 ms. SC-001's "a few seconds" holds with a wide margin.

## Summary

| # | Severity | Label | Finding |
|---|---|---|---|
| B1 | BLOCKER | MEASURED | The new web crashes the NBA room and start page against the 053 backend (missing `sport`/`dataNotes`). The Football tab on the old backend creates NBA games. Backend-first 400s the old web. |
| S1 | SHOULD-FIX | MEASURED | `summaries` gives `finish: 0` for unfinished games (`wasNull` after `user_slot`), carried over from 053 |
| S2 | SHOULD-FIX (Allan) | MEASURED | Points over replacement leaves bench QB stacking: 2.87 QBs per bot roster, 11 of 99 rosters with 5-6 |
| S3 | SHOULD-FIX | READ | NFL `settingsSource`, the web's "2000 to 2025" and the import's 1999 repeat values the config owns |
| N1 | NIT | MEASURED | Uniform rookie offset: Luck (pick 1) at rank 95, Richardson (pick 3) at 25 |
| N2 | NIT | READ | NFL results show "Week N" for a starter with no game |
| N3 | NIT | READ | The football stat order exists in three places, with no equality test |
| N4 | NIT | READ | A `player_id` of "NA" would become a real player |
| N5 | NIT | READ | The dropped-rows note says "with stats" but also counts position-only rows |
| N6 | NIT | SUSPECTED | Rolling-deploy overlap: old-backend inserts fail once V45 drops the default |
| N7 | NIT | MEASURED | The Football tab is live before the prod NFLVERSE import (409 on start) |
| N8 | NIT | READ | `Redirect.ALWAYS` allows an https to http downgrade (inherited) |
| N9 | NIT | READ | "Played in S" board rule leaks more for football (inherited) |

Counts: **1 BLOCKER, 3 SHOULD-FIX, 9 NIT.**

## Fixes applied (2026-11-05)

S2 (bench QB piling, waiting on Allan) and N1, N8, N9 are untouched, as instructed. N6 got documentation only.

| Finding | What changed | Covered by |
|---|---|---|
| B1 | `web/src/api.ts`: `sport`, `dataNotes`, `attribution` typed optional on game state / list row / season row, with a comment that this is the deploy window only. `historicalCopy.ts`: new `sportOf()` (missing = NBA; reads old payloads only, requests still always carry an explicit sport). `HistoricalRoom.tsx` (`sportOf(state)`, `dataNotes ?? []`, `attribution ?? null`), `HistoricalResults.tsx`, `HistoricalDraft.tsx` (`sportOf(g)` on list rows), `HistoricalPlayerList.tsx` (`sport` defaults to NBA). `HistoricalDraft.tsx` fetches `historicalSeasons('NBA')` and `historicalSeasons('NFL')` on mount; the Football choice renders only when the NFL reply has at least one row whose own `sport === 'NFL'` (an old backend's NBA rows without `sport` do not count). The backend's required `sport` is NOT relaxed. Deploy order (web, then backend, then NFLVERSE import) is in `quickstart.md`, "Deploy order". | New `web/src/pages/HistoricalOldBackend.test.tsx` (the reviewer's probe as a regression test: old-shape NBA room, start page and games list render, both seasons requests carry an explicit sport, Football hidden). `HistoricalDraft.test.tsx` football test now reaches Football only through the seasons gate. |
| N7 | Resolved by B1's seasons gate (Football hidden until the import has put an NFL season in). | The hidden case in the new test; the shown case in the football test. |
| N6 | Documented only (quickstart "Deploy order", step 2). | n/a |
| S1 | `HistoricalGameRepository.summaries`: `Integer f = rs.getObject("finish", Integer.class)`. | `SportCoexistenceIT.sameSeasonNumberInTwoSportsGivesExactlyOneListRowPerGame`: DRAFTING NBA and NFL summaries have `finish() == null`, and `finish` is JSON null over HTTP. |
| S3 | `NflHistoricalSport.settingsSource()` is built from `NflHistoricalProperties` (teams, rounds, roster slots, bench count); the `SETTINGS_SOURCE` constant is gone. `HistoricalImportService.NFL_FIRST_SEASON` became `nflFirstSeason()` = `firstOffered - 1`. `HistoricalDraft.tsx`: the football range is the min/max `seasonStart` of the seasons response, no literals. | `NflStatKeysAgreeTest.settingsLineIsBuiltFromTheConfigAndFollowsIt` (12-team and a 10-team variant); `NflGameFlowIT` settings assertion; the football test in `HistoricalDraft.test.tsx` (range from a 2000/2007/2025 reply). |
| N2 | Checked, no behaviour change. A starter can never be a player with no game: the lineup is built only from players passed in `scored`, which requires a `Locked` entry (`SeasonReplay.TeamScorer.week`). A null date therefore means "played, source has no date", so "Week N" is truthful, and a 0-point starter played and scored 0. The `l == null` arm is defensive; a comment in `SeasonReplay` says so. | Reasoning on the code (READ); `SeasonReplayNullDateTest` already exists. |
| N3 | `historicalCopy.ts`: `NFL_STAT_ORDER` (the documented 13, in `NflHistoricalSport.COUNTING` order) is spread into `STAT_ORDER`. One display detail changed: football totals now read "3 fum lost · 2 ret TD" (backend order) instead of "2 ret TD · 3 fum lost". | `NflStatKeysAgreeTest.statOrderTailCountingAndParserKeysAreTheSameThirteen`; `NflHistoricalPropertiesTest.scoringKeysInTheYmlAreExactlyTheCountingKeysInTheirDisplayOrder`; new `web/src/historicalCopy.statOrder.test.ts`; `HistoricalRoom.test.tsx` expectation updated. |
| N4 | `NflverseSource.parse`: an id of `NA` (case-insensitive) counts as no id, like blank and `0`. The `gsis_id` in `parseDraftPicks` is left as is (harmless, per the review). | `NflverseSourceTest.aLiteralNaPlayerIdIsNoPlayerJustLikeBlankAndZero`. |
| N5 | `NflHistoricalSport.dataNotes`: "N source rows with no player id (a skill position or scored stats) were left out", which is what `droppedRows` counts. | `NflGameFlowIT` (`NOTE_DROPPED`). |

**Verification of the fixes (ran).** Backend, full suite on `draftsim_suite` (5439) with the init script: 2748 tests,
14 failed, 53 skipped. Thirteen are the pre-existing list (AccessControlMvcIT, AdminDigestControllerIT, 8 x
TargetControllerIT, EspnRefreshOnVisitIT, 2 x LeagueRepositorySuccessorIT). The fourteenth,
`DraftGradesReadIT.nfl2025` ("invariant 1: expected 180 but was 0"), is not on that list. A focused re-run of the
Historical, Nfl and SportCoexistence classes: 125 tests, 0 failed, 4 skipped. Web: `npx tsc -b` clean,
`npm run build` ok, `npx vitest run` 160 files, 2763 tests, all passed.

Note on `DraftGradesReadIT.nfl2025` (assumed unrelated, not investigated): it probes 5433 only to decide whether to
run, then reads draft 1254190894563729408 from the suite DB, where a 180-pick draft row (id 82) exists with no
data to grade. Nothing in this change touches drafts or grades.
