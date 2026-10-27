# Spec 049: bug-hunting code review

Reviewer: a separate pass (Opus), 2026-10-26. Scope: the uncommitted diff on branch `049-player-compare` against
`ec72031`, untracked files included (`web/src/compare.ts`, `statFormat.ts`, `testPlayerStats.ts`,
`components/PlayerChooser.tsx`, `pages/PlayerCompare.tsx` and their tests). Read against spec.md, plan.md's
"Amended after review" A1–A15, contracts/ui.md, contracts/api.md, data-model.md and tasks.md's "Amended during
build". This is not a style pass. Evidence labels:
- **EXECUTED**: I ran it (vitest, a scratch test outside the repo, or gradle).
- **READ**: reasoned from the code.

Baseline, EXECUTED: `npx tsc -b` is clean. The five touched web suites pass (151 tests). The backend
`PlayerStatsServiceTest` and `PlayerStatsControllerTest` pass. No server was started.

**Result: three B-level defects.** B1 is a spec acceptance scenario that isn't built. B2 is a mark the contract
forbids. B3 shows the wrong league's owner. There are also four should-fix items and seven nits.

## Bugs

### B1. Once a column has a player, he can't be swapped (READ)
- **Where:** `web/src/pages/PlayerCompare.tsx:150-151`. `ColumnHead` renders `PlayerChooser` only when `!id`.
  Once a column has a player there is no "Change" control anywhere.
- **Scenario:** the manager opens Compare from Jokić's page and picks Sabonis for B, then wants Embiid instead.
  - Nothing on the page lets him change B.
  - `pick` uses `replace: true` (`:92`), so Back leaves the comparison altogether rather than returning to the
    empty chooser.
  - His only route is editing `?b=` by hand.
- **Spec:** US3 says "either player can be swapped without starting over". Acceptance scenario US3-3 is "When the
  manager swaps one player, Then the other column is unchanged and the address updates". contracts/ui.md says
  "Swapping a player rewrites only that param".
- **Why the tests are green:** the test named "swapping one column keeps the other and replaces history"
  (`PlayerCompare.test.tsx:289`) mounts `?b=2` and *fills the empty A column*, so it is a second copy of the
  "pick for the empty column" test. It doesn't check replace-vs-push either: it asserts only the final URL, which
  is the same under both.
- **Fix:**
  - Give a filled `ColumnHead` a small "Change" button that swaps the head back to a `PlayerChooser` (local
    state), with `excludeId` set to the other column. `pick` already rewrites only that side.
  - Rewrite the test so it starts from `?a=1&b=2`, changes B to 3, and asserts A was fetched once.
  - Assert replace through `MemoryRouter`'s history length, or via a `useNavigationType` probe that reads
    `REPLACE`.

### B2. A stale LAST_10 window is marked when the player is also under the minutes floor (server READ, client EXECUTED)
- **Where:**
  - `PlayerStatsService.qualify` (`PlayerStatsService.java:830-833`) returns `NOT_QUALIFIED` for
    `size < N || !minutesOk` *before* it tests recency. So a window that is both under the 15-minute floor and
    stale reports `NOT_QUALIFIED`, never `NOT_QUALIFIED_STALE`.
  - `windowWithheld` (`web/src/compare.ts:164-178`) withholds a LAST_N window only on `games < lastN` or
    `reason == 'NOT_QUALIFIED_STALE'`.
- **Scenario:** a bench player's last 10 games were at 12 minutes per game.
  - That is 120 minutes, so `smallSample` is false.
  - His last game was 5 weeks before the season's newest, because he has been hurt since.
  - The server says `NOT_QUALIFIED`. The client sees 10 of 10 games and no STALE reason, so the LAST_10
    fantasy-points, minutes and box rows are marked against a current starter.
  - FR-005 and contracts/ui.md say a stale window is withheld.
- **Range:** only LAST_10 at 10.0–14.9 minutes per game. A LAST_5 window needs at least 100 minutes (20 per game)
  to escape `smallSample`, which clears the floor anyway.
- **Evidence:**
  - EXECUTED: a scratch vitest (outside the repo) gave `markRow` that exact pair: LAST_10 at 10 games / 120
    minutes / last game 2026-01-01, `windowQualification.LAST_10 = {qualified:false, reason:'NOT_QUALIFIED',
    lastN:10}`, against a fresh full player. It returned `mark: 'A'`.
  - READ: the server ordering.
- **Fix:**
  - Don't reorder `qualify`: ranks and percentiles read its reason.
  - Add `boolean stale` to `WindowQualification`, computed on its own (`last.isBefore(latest.minusDays(recency))`
    for LAST_N, false for SEASON). Mirror it in `api.ts` (optional, as the other fields are).
  - Have `windowWithheld` test `q.stale` instead of the reason string.
  - Add a backend test for the 10-games, 12-minutes, stale case and a `compare.test.ts` case for it.

### B3. The owner shown is the answered season's, without its label, so on a fallback season it is last year's owner (READ)
- **Where:** `PlayerCompare.tsx:182-186` renders `d.ownership` with `OwnershipMark` and nothing else.
- **What the player page does** (`PlayerPage.tsx:116-118, 150-163`):
  - It labels `d.ownership` with `ownershipAsOf(own.asOf, d.season)`.
  - On a fallback it also shows `d.currentOwnership` as "Now (2026–27)" with its own as-of.
- **Why that matters:** on a fallback, `ownership` is `PlayerOwnership.forSeasonView` over the *resolved earlier
  league* (`PlayerStatsService.java:378-381`), and `currentOwnership` is the URL league's.
- **Scenario:** a new-season league (pre-draft, or the first days before box scores) falls back to 2025.
  - Compare shows "Yours" or "Owned by X" from last season's league, unlabelled, for a player who may now be on
    another roster.
  - The page's fallback note covers the stats, not the owner.
  - For a complete league, the end-of-regular-season owner also loses its "as of" label.
- **Spec:** FR-003 ("owner in this league", "as the player page does"), US1-5, and the edge case "Ownership
  unavailable or stale: the column shows the same owner wording and 'as of' the player page shows".
- **Fix:** render the same pair the player page renders. Extract `Header`'s `pp-own-list` block into a small
  shared component and use it in `ColumnHead`. Add a compare test with `requestedSeason: 2026` and a
  `currentOwnership` that differs from `ownership`.

## Should-fix

### S1. A player with no games this season isn't told as such (READ)
- **Where:** `PlayerCompare.tsx:164-166`. The column prints `reasonSentence` only when `!available`.
- **Scenario:** a player with no games is `available: true, reason: 'NO_PLAYER_GAMES'`.
  - The column shows the name, then "0" games and dashes.
  - Every marked row carries "small sample (0 min)" (see S2).
  - It never says "He hasn't played a game this season."
- **Spec:** US2-3 requires the column to say so. `statCopy.reasonSentence('NO_PLAYER_GAMES')` already has the
  sentence.
- **Fix:** after the identity block in `ColumnHead`, print `reasonSentence(d.reason)` when `d.reason` is set.
  Add a test.

### S2. An empty window reads "small sample (0 min)" instead of "no games", and a short window names minutes, not games (EXECUTED)
- **Where:** `compare.ts:162`. `smallSample` is checked first, and `AdvancedStats.window` sets `smallSample`
  true for an empty window (0 < 100).
- **Evidence:** EXECUTED. A scratch test gave `markRow` a SEASON window with 0 games. It returned
  `withheld: { A: 'small sample (0 min)' }`.
- **Spec:**
  - US2-2 says the cell says "no games".
  - FR-005 says "the game count MUST be shown in those cells".
  - A 3-game LAST_10 with 84 minutes prints "small sample (84 min)", so the per-game table (which has no Games
    row) never shows the count.
- **Fix:**
  - `if (x.games === 0) return 'no games'` first.
  - For LAST_N, test `games < lastN` before `smallSample`, or print both ("3 of 10 games, 84 min").

### S3. "No game this week" can be a false statement when the schedule isn't stored (READ)
- **Where:**
  - `PlayerStatsService.thisWeek`: with an empty schedule and a `leg`, `week = leg` and `remaining` is always 0.
  - `ThisWeekCell` (`PlayerCompare.tsx:263`) prints "No game this week" for `played == 0 && remaining == 0`.
- **Scenario:** the schedule fetch failed or hasn't run for the season, and his first game this week is tonight.
  The cell says he has no game this week. The note beside it reads "schedule as of time unknown", which doesn't
  undo the claim.
- **Spec:** the edge-case list says "If the schedule isn't stored, the cell says so".
- **Fix:** have the client print "Schedule not stored: N played" when `scheduleAsOf == null`, or have the server
  return null for `remaining` in that case (a contract change). In either case, "No game this week" only when a
  schedule exists.

### S4. The "teams this season" disclosure is missing (READ)
- **Where:** `ColumnHead` (`PlayerCompare.tsx:167-187`).
- **Scenario:** for a traded player the column shows his current team only. The player page adds "Played for
  SAC, CHI in 2026–27." (`PlayerPage.tsx:142-144`). The compare page shows no `teamsThisSeason`, `teamGamesMissed`
  or `!p.known` line.
- **Spec:** the edge case "the column shows his current team and the same 'teams this season' disclosure the
  player page shows".
- **Fix:** print the `otherTeams` line, ideally from the same shared header piece as B3.

## Nits

- **N1. The SC-006 scan reads `container.textContent`, which joins adjacent elements with no space**
  (`PlayerCompare.test.tsx:149`).
  - A verdict rendered right after another element ("Alpha One" + "Start") becomes "OneStart". `\bstart\b` then
    misses it.
  - The positive controls append with a leading space, so they can't show this.
  - Scan `innerText`-like text instead: join `container.querySelectorAll('*')` text nodes with spaces, or use
    `screen.queryByText(re)` per pattern.
- **N2. The leaderboard cache is never refreshed.** `fetchDraftStats` (`draftStatsCache.ts`) has no data-version
  key.
  - After a nightly refresh, the chooser and the page's sport probe serve the pre-refresh board for the life of
    the tab, including its owner marks.
  - The `board` block's deps (`PlayerCompare.tsx:69`) also leave out `version`.
  - This is acceptable for a name list, but the owner shown in the chooser can disagree with the column's owner
    after a pick.
- **N3. The page never resolves a football league when the only player id is unknown.** If `?a=<bad id>` 404s and
  `b` is empty, there's no sport.
  - Unlikely in football, since the player endpoint answers `NOT_BASKETBALL` before it looks up the player
    (`PlayerStatsService.java:319`).
  - It does hold for an NBA league whose leaderboard is unavailable: the B chooser shows the board's reason, and
    nothing else on the page explains it.
- **N4. The withheld reason repeats on every marked row.** In the per-game table a short LAST_10 prints
  "3 of 10 games" under PTS, REB, AST, STL, BLK, 3PM and turnover rate: seven times per cell. Print it once per
  window (the group header row in the main table, the table caption in the per-game card).
- **N5. A teamless player's this-week cell is blank, with no reason.** `ThisWeekCell` returns an empty `<td>`
  when `thisWeek` is null while the other column has one. A free agent reads as missing data. Print "No team".
- **N6. `remaining` counts a game that is in progress and not yet ingested.** `NextGames.forTeam` keeps
  `in_game` as unsettled. Lock-ins' own games-left count excludes `in_game` (`LockInService` `computeLive`,
  `STATUS_IN_GAME`). During tonight's game the cell can read "0 played · 1 left" while he is on the floor. The
  contract names the lag, so this is consistent with the docs, but it is not lock-ins' counting as A3 claims.
- **N7. Every player page now does three more reads for `thisWeek`:** `leagues.currentLeg`,
  `schedules.forSeason`, and a second `boxCache.get` (a token query, plus a reload on the fallback season). The
  player page is also the spotlight's source. This isn't measured. Check it against SC-007 in verification.

## Checked, no defect

- **Marking direction (bug class #1).**
  - READ: `HIGHER`/`LOWER` are applied as `va > vb` / `va < vb`.
  - EXECUTED: the existing flip tests and my scratch run confirm turnover rate marks the lower value.
  - The NONE rows (games, total, TOV, PF, shooting) never mark (vitest).
  - Equality is decided on the displayed strings.
- **Withheld guards.**
  - Either side null or unavailable gives no mark and no withheld text.
  - A `Rate` with a reason reads as null.
  - An older backend with no `windowQualification` withholds every LAST_N row ("last-N rule unavailable").
  - The stale-text maths prints N only when N exceeds `recencyDays`, which matches the server's
    `isBefore(latest - recency)`.
- **Season/as-of mismatch.** One retry per `(a, b, version)`, keyed so it can't loop. The note waits for the new
  response object. `dataAsOf` is season-level (`box.token().maxFetchedAt()`), so two players never differ
  permanently.
- **Column B gating.**
  - A 404 or error on A releases B, and the gate is sticky.
  - A football A blocks B.
  - A hung A would hold B, with no timeout, but `apiFetch` failing releases it.
  - `a == b` gives one column plus a chooser, and one fetch.
- **`thisWeek` backend.**
  - Box-line `week` and schedule `week` are both Sleeper's week. The game ids are the same id space: the ingest
    already joins them in `SportSchedule.isAway`.
  - DNPs aren't box lines (`sp <= 0` skipped), so `played` means "played minutes".
  - A traded player's old-team lines count as played. Remaining uses his current team with the id filter, so
    nothing is counted twice.
  - The `Integer.MIN_VALUE` sentinel is returned only when there is no leg and no counted game on or after today.
  - On opening night, the URL season's empty box gives `played == 0` (tested).
- **`api.ts` mirrors.** EXECUTED by the MockMvc test: `EnumMap` keys serialise as `SEASON`/`LAST_10`/`LAST_5`;
  a null `thisWeek` is present and null; an empty `windowQualification` is `{}`. The app sets no Jackson
  inclusion override, so the test's mapper matches production. The field names and nullability match the Java
  records.
- **`PlayerPage` formatter extraction.** It is byte-identical in output: `RateValue` now calls `madeAttempted`,
  which has the same template. `signed` still uses its own minus. The existing `PlayerPage.test.tsx` passes
  unchanged. The Compare link is gated on `available && comparePagesFor(sport)`, both ids are URL-encoded, and
  `useSearchParams` decodes them.
- **PlayerChooser.**
  - The load effect depends on `[open, leagueId]` only (the builder's fix holds).
  - Null-name rows and the excluded id are filtered.
  - Enter with no options is a no-op.
  - The fallback note is shown.
- **Routes and destinations.** The `compare` row has `inRail: false`, and both the rail (`LeagueRailSection`) and
  `searchIndex` skip it. `KeyedByLeague` remounts on a league change.
- **375 px.** Not checked live. The table is `table-layout: fixed` with `overflow-wrap: anywhere` and a 28%
  label column. A 56 px face fits a column of about 99 px, and nothing has a fixed width wider than its column.
  Check it in the verification pass with the league's longest names and the withheld text.

## Fixes applied (2026-10-26)

A separate fix pass. No migration; nothing committed.

**Bugs**
- **B1, fixed.** Every column with a player id now has a "Change player A/B" button (`ColumnHead`, local `changing` state) that opens `PlayerChooser` straight into its search box (new `startOpen` / `onCancel` props). A pick rewrites only that param. History stays `replace: true`, as contracts/ui.md says, so Back leaves the page; that is the contract's accepted behaviour, not a regression. The old "swap" test was rewritten: from `?a=1&b=2` it changes B to 3, asserts the URL is `?a=1&b=3`, asserts navigation type `REPLACE`, and asserts A was fetched once. A second test covers Cancel.
- **B2, fixed.** `WindowQualification` (Java) gains `boolean stale`, and the internal `Qualification` record gains it too (a two-arg constructor keeps old call sites). `qualify` evaluates recency before its short/minutes check. `qualified` and `reason` are unchanged, so ranks and the leaderboard see the same values. SEASON is always `stale=false`. `api.ts` mirrors it as optional `stale?`. `windowWithheld` tests `q.stale ?? reason === 'NOT_QUALIFIED_STALE'`. Tests: `qualify` 10 games / 12 mpg / old last game gives `NOT_QUALIFIED` + `stale`; a controller JSON assertion; compare.test.ts and PlayerCompare.test.tsx reproduce the reviewer's scenario (unmarked), plus an older-backend fallback case. The existing "short" window test now expects `stale=true` where the player's last game was 27 days before the season's latest, which is correct.
- **B3, fixed.** New `components/PlayerHeaderParts.tsx` (`OwnerList`, `TeamLines`, `UnknownPlayerNote`) extracted from `PlayerPage.Header`. Both pages render it, so the as-of label and the fallback "Now (season)" block are the player page's own. PlayerPage output is unchanged (its tests pass untouched). New compare test uses `requestedSeason` with a differing `currentOwnership`.

**Should-fix**
- **S1, fixed.** The column prints `reasonSentence(d.reason)` when the available page has a reason ("He hasn't played a game this season.").
- **S2, fixed.** `windowWithheld` order: no games ("no games"), then games shortfall ("X of N games"), then stale, then small sample.
- **S3, fixed with a contract change.** `ThisWeek` gains `scheduleStored` (false when the season has no stored schedule). The cell reads "N played · schedule not stored", and the note says "no schedule is stored for this season". `scheduleAsOf` alone couldn't be used because it is also null for a stored schedule with no fetch time. Mirrored in `api.ts`; see contracts/api.md amendment.
- **S4, fixed.** `TeamLines` (shared) prints "Played for X, Y in season" and the missed-games line; `UnknownPlayerNote` prints the no-record line.

**Nits**
- **N1, fixed.** The SC-006 scan joins text nodes with spaces (`spacedText`). A positive control puts "Start" in its own element after the name, where `textContent` misses it and `spacedText` catches it.
- **N2, fixed.** `fetchDraftStats(league, window, version?)` refetches when handed a newer league data version than the cached entry was fetched under; callers that omit it are unchanged. The chooser and the page's sport probe pass `useLeagueDataVersion`. New `draftStatsCache.test.ts`.
- **N3, fixed.** The page falls back to the leaderboard fetch for the sport whenever every named player failed to load, not only when none was named.
- **N4, fixed.** The withheld reason is printed once per window in a group header row (main table and per-game card); the withheld cells have a visually-hidden "not compared" cue and no mark.
- **N5, fixed.** A teamless player's this-week cell says "No current team" (other null cases say "No week to show").
- **N6, fixed.** `remaining` excludes `in_game`, matching `LockInService.computeLive` (`STATUS_IN_GAME` is now package-private and reused). Lock-ins' choice was kept, not the alternative of counting the game as played: it isn't ingested yet, so counting it as played would claim a box line that doesn't exist. Test added.
- **N7, left for verification.** The extra reads per player page are unmeasured; check against SC-007 live.

**Results**
- `npx tsc -b`, `npm run build`: clean.
- `npx vitest run`: 148 files, 2604 tests, all pass.
- `./gradlew test`: 2442 tests, 1 failed, 6 skipped. The one failure is the known `HallOfFameServiceIT.nameLastPlaceOnlyWhenTheRuleCouldReadIt`.
