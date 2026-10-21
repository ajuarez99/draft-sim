# Code review: spec 042 (bug hunt, not a style pass)

**Date**: 2026-10-20. **Reviewer**: a separate pass on the session's model, reading the change cold.

**Reviewed**: the uncommitted worktree vs HEAD `c987b92` (`git diff HEAD` plus every untracked file in
`git status --short`), in the worktree `.claude/worktrees/042-fantasypros-design-features`. Read against
spec.md, plan.md, research.md, data-model.md, contracts/*, plan-review.md, t001-measurements.md and
tasks.md (including the implementers' Log lines), with AGENTS.md's "Recurring bug classes" and
`claude/lessons.md` as the checklist.

**Ran**:
- Backend: `./gradlew.bat test --tests '*LineupCheck*' '*NextGames*' '*PlayerStatsServiceTest'
  '*PlayerTrendsServiceTest' '*StatusCaptureStatusAsOfIT' '*LeagueAvailabilityIT' '*NextMatchupService*'
  '*ScheduleGridService*' '*LeagueAnalysisServiceTest'`. 198 tests, **0 skipped**, 0 failed (Postgres up
  on 5433). The full suite was not run here. That's T018's job.
- Web: `npx tsc -b` (clean), then `npx vitest run` on the 9 changed or new test files (LineupCheck,
  OwnershipMark, LeagueHome, PlayerPage, PlayerTrends, StatLeaderboard, StatCalculator, statCalcUrl,
  statLeaderboard). 307/307 passed.
- SQL against the local DB (`draftsim-pg`, 5433): team codes in `player` vs `sport_schedule`, NBA
  `injury_status` counts, NBA `leg` vs the stored schedule weeks, and `player_game` seasons.

Each finding below says **verified-by-running** or **by-reading**.

---

## BUGS

### B1. A DTD/GTD bench player is dropped from the options without a word, and the empty-list copy then says something false

- `backend/.../engine/LineupCheck.java:151`: `if (reasonOf(p, rules, schedule) != null) continue;`
- `web/src/components/LineupCheck.tsx:86-87`: "No bench player is eligible for {slot} with a game this week."

`reasonOf` returns `LISTED` for any non-null injury tag. So a bench player tagged **DTD, GTD or
Questionable**, who is eligible and has a game, is never an option. The data model does say "Options never
include a player who is himself flagged". But FR-010's own definition is "eligible bench players who have
a game in the week", and research R3 says "Nothing is dropped silently". The build follows the first and
breaks the other two.

**How common, measured**: of 623 NBA players with a team in the local DB, 90 are `DTD` and 20 are `GTD`
(18%). Out/IR is 20 (3%).

**Failure scenario**: PG starter tagged Out. The only bench PG is tagged DTD, and his team plays twice
this week. Expected: one option, marked DTD. Actual: `options=[]`, and the page prints "No bench player
is eligible for PG with a game this week." That sentence is false. A second case: a LISTED (DTD) starter
whose bench alternatives are also DTD shows none of them.

The exclusion was **verified by running**: `LineupCheckTest.aBenchPlayerWhoIsHimselfFlaggedOrHasNoGameIsNotAnOption`
pins it and is green. The copy consequence is **by reading**.

**Fix**: exclude only OUT and NO_GAME_IN_WEEK bench players. Keep LISTED ones, and carry their tag on
`BenchOption` (add `injuryStatus`, mirrored in `api.ts`) so the line reads "… · Listed: DTD". If the
data-model rule is kept instead, change the empty-list copy to "No untagged bench player…" and add a count
of the tagged players that were left out. Either way, amend data-model "Validation" visibly.

### B2. Early in a season, "sorted by fp/g, highest first" sorts by who has already played, not by value

- `LineupCheckService.java:158-166`: `seasonAverages`
- `LineupCheck.java:162-165`: nulls last
- `LineupCheck.tsx:37`: the sentence

`readLeaderboard` answers the URL season as soon as it has **any** box scores. The fallback to an earlier
season only happens when `box.games().isEmpty()` (`PlayerStatsService.java:~478`). So from the first
ingested game night of 2026 onward, the averages are 2026's. A player whose team hasn't played yet has no
leaderboard row, so his `fpPerGame` is null and he sorts **last**. Meanwhile 1-game averages sort first.

**Failure scenario**: 2026-10-21, after opening night (2 games, 4 teams). Starter Out at G. Bench
options: A, a 52-fp/g star whose team opens on the 22nd, and B, a 14-fp/g reserve who played on the 20th.
The output is B ("14.0 fp/g · 2026 avg · 1 game"), then A ("no average yet"), under the heading "Sorted by
fantasy points per game, highest first." Every row is individually true. The order isn't what the heading
claims, which is the AGENTS.md class 5 / lessons #28 shape. It lasts until every team has played, about a
week, and that week is the one this ships into. The local DB has no 2026 NBA box scores yet (checked:
`player_game` has nba 2024/2025 only), so this is **by reading**.

**Fix (pick one)**: (a) use FP_PER_GAME only when every option in the flag has an average from the same
season and otherwise order by NAME, which `byFp` already falls back to honestly. (b) Ask the leaderboard
for the previous season too when the current season's `qualification.maxTeamGames` is below a labelled,
hand-set floor (F10 allows one read per request, so this needs a visible amendment). Option (a) is the
smaller change and never overstates. Add a test: one option with an average, one without, and assert
NAME.

---

## SHOULD-FIX

### S1. The header's "Position rank" is the answered season's rank, shown unlabelled next to the URL season's next game

- `web/src/pages/PlayerPage.tsx:193-197` (`HeaderFacts`)

On the opening-night fallback (`requestedSeason=2026`, `season=2025`), the facts row shows "Status: Out (as
of Oct 21…)", "Next game: Wed Oct 21 · vs OKC" and "Position rank: #2 C". The first two are current. The
rank is 2025's, and nothing beside it says so. The fallback note sits at the top of the page, above the
header. This is the same shape as lessons #28 (2025's award winner on the 2026 home, with no year). **By
reading.**

**Fix**: when `d.requestedSeason != null`, label it ("#2 C in 2025-26", via `seasonLabel(d.season)`). Or
drop the rank from the facts row on a fallback.

### S2. The schedule's `fetchedAt` is never shown, although research R5 and t001 (b) say it must be

R5: "A game played earlier today can still read `pre_game` until the next schedule refresh, so the
schedule's `fetchedAt` is shown." t001 (b): "The NFL schedule was last fetched on 10-07, so print its
`fetchedAt`." But `PlayerStatsPage` has no schedule as-of, and neither does `LineupCheck`. **By reading**
(grep: there's no `fetchedAt` in the new records or in `api.ts`'s new types).

**Failure scenario**: a player page late on game night still says "Next game: Tue Oct 20" after that game
has finished. Nothing on the page says how old the schedule is.

**Fix**: add `scheduleAsOf` (from `SportScheduleRepository.fetchedAt`) to both payloads, mirror it in
`api.ts`, and print it beside "Next game" and the lineup check's game-check note.

### S3. `NextGames.hasCountedGameInWeek` lost the "week not in the schedule" guard its sibling has

- `ingest/NextGames.java:64-69` vs `ScheduleGridService.teamsWithoutGame` (`:~162`)

The sibling's javadoc spells out the rule: return empty "when the schedule … does not hold `week`: 'not
loaded' is a different answer from 'everyone plays'". `NextGames` answers `false` for every team in that
case. So in `LineupCheckService.java:125-133`, a `leg` that isn't among the stored weeks (a partially
stored last week, which memory records for local NBA 2024/2025, or a leg past week 25) flags **every**
starter "has no game in week N". That's two implementations of one rule, the multi-sport-landmines class.
Today's local data doesn't trigger it: NBA 2024/2025 legs are 24 and 21, and the schedule holds weeks
1–25. **By reading, plus SQL to show it's latent, not live.**

**Fix**: in `LineupCheckService`, set `gameCheck = games.stream().anyMatch(g -> g.week() == week)`, not
`!games.isEmpty()`. A missing week then reads as "not checked", not "no games".

### S4. A starter whose player row is missing disappears, and the section can then say "All starters are active"

- `LineupCheck.java:119-120` (`if (p == null) continue;`) and `LineupCheck.tsx:114-115`

The javadoc defends this ("nothing is known about him"). But "All starters are active" is then a positive
claim about a player the server knows nothing about. t001 (c) measured 0 unknown starters in 2,268, so
it's rare. It's still the honesty rule's exact case. **By reading.**

**Fix**: emit a flag with a new reason `UNKNOWN_PLAYER` (with the id, and no options), mirrored in
`api.ts` with a sentence. Or count them in a payload field, and have the client not print "All starters
are active" when the count is above zero.

### S5. When the week is unknown, NBA copy blames the schedule

- `LineupCheckService.java:125` makes `games = List.of()` when `week == null`, so `gameCheck=false`
- `LineupCheck.tsx:125` then says "The schedule isn't loaded, so games this week aren't checked."

When `leg` is null that sentence is false: the schedule may be fully loaded. **By reading.**

**Fix**: put a reason on the payload (`gameCheckReason: NO_SCHEDULE | NO_WEEK`, mirrored), or word the
NBA line neutrally ("Games this week weren't checked").

### S6. The bench averages' season-to-label mapping in `LineupCheckService.seasonAverages` has no test

- `LineupCheckService.java:158-167`

Every `LineupCheckServiceTest` case stubs `readLeaderboard` to return `null`, and the MVC IT asserts
`fpPerGame == null`. So nothing executes the mapping from `fpPerGame`, `board.season()` and
`r.stats().games()` to `FpAvg`. By reading, it's correct today: `board.season()` is `dataSeason`, the
answered season, and `stats` is the SEASON `Window`, whose `games()` is the window's game count. But no
test pins that a fallback board labels its averages with the answered season (2025), not the requested one
(2026). That label is what F10 is for. **By reading.**

**Fix**: add a service test with a stubbed `StatLeaderboard` (`season=2025`, `requestedSeason=2026`, one
row with `fpPerGame=31.4` and `stats.games=71`). Assert `fpSeason=2025`, `fpGames=71`, FP_PER_GAME order,
and that a row with `stats == null` is skipped.

### S7. The lineup check's added latency is unmeasured, and the expensive branch is the common one

`seasonAverages` runs a full `readLeaderboard`: every player's window figures, ranks, replacement level,
and the draft/ADP join. It runs on any league-home load where some flag has options. At 18% DTD/GTD,
nearly every NBA league home on a game night has a LISTED starter. So the "lazy, most weeks nothing is
flagged" reasoning in `LineupCheckService.java:143` doesn't hold for basketball. plan.md already says
"measure it in verification". This raises the priority. **By reading.**

**Fix**: measure it in verification (timed `curl`, warm and cold). If it's material, build a slimmer
averages read that reuses `windowFigures` for the SEASON window only.

### S8 (minor). Two "mine" rules for a manager with two rosters

The leaderboard and Trends `isMe` (`RosterOwners.ownerNames`) mark **both** of a two-roster manager's
rosters, so "My roster" lists both. The lineup check uses `NextMatchupService.myRosterId`, the lowest
roster id. Each is defensible, but the two pages disagree for the same person. **By reading.**

**Fix**: none needed now. Add one line to data-model saying which rule each page uses.

### Nits (no action needed for correctness)

- `OwnershipMark`'s `asOfLabel` prop has no caller. FR-005 is still met by the existing notes ("Rosters
  as of …" on Trends, `nowLabel` in the FilterBar).
- The player page's own `SeasonPicker` doesn't carry `?tab=`, so switching seasons returns to Overview.
- `aria-controls` on unselected tabs points at a panel id that isn't rendered (only the active panel
  exists).
- `AdvancedCard`'s group toggle resets when you leave and return to the Stats tab (it unmounts). No number
  changes.
- `LeagueHome` shows `idleStartersNote` while the lineup check is loading, then hides it once the check
  answers. That's a brief flash.

---

## Checked and found correct

- **api.ts mirrors**, field by field: `TrendRow.rosteredByMe`; `PlayerStatsPage` +`status`, `nextGame`,
  `nextGameReason`, `upcoming`; `PlayerStatus` (including the added `out`); `NextGame` (`isHome` Boolean
  maps to `boolean | null`); `LineupCheck`, `LineupFlag`, `BenchOption` (`fpGames` int maps to `number`,
  non-null). None is missing or extra. `FpAvg`, `Roster` and `Schedule` are internal and never serialized.
- **No `Map.of` with nullable values** in any response path. Every nullable value goes in a record. The
  `Map.of()` passed as `fp` in the first pass is empty and only read.
- **F1 (BLOCKER) is honoured**: `PlayerStatsService.header` reads only `requested` and the player's current
  `team`. The test feeds zero 2026 box scores and asserts a 2026 `nextGame`.
- **"Today" in ET**: `NextGames.today` uses `America/New_York`, with a test at 01:30 UTC (the 21st ET).
  `gameDay` parses at local noon, so no zone can shift the date. `statusAsOfLabel` prints the instant in
  ET with the zone name, never "7 AM".
- **Team codes**: no `player.team` value is missing from `sport_schedule` for either sport (SQL returned
  zero rows). So NO_GAME_IN_WEEK can't fire from a code mismatch on today's data (**verified by running**).
- **The `slotKnown` probe**: `BasketballRules.isEligible` and `FootballRules.isEligible` read only
  `player.positions()`, so a stand-in per `Position` classifies every real slot correctly. Real slots are
  PG/SG/G/SF/PF/F/C/UTIL and QB/RB/WR/TE/FLEX/K/DEF. SUPER_FLEX, WRRB_FLEX, REC_FLEX and IDP slots come
  back SLOT_UNSUPPORTED, as F3 intends. **Caveat**: this depends on `isEligible` staying positions-only.
  A future rule that also reads `team`, `status` or `sport` would make the probe (null team, null status)
  misclassify a real slot. Worth one comment at `isEligible`.
- **Ordering direction**: `LineupCheckTest.aHigherFpPerGameBenchPlayerSortsFirstAndAMissingAverageSortsLast`
  uses names that run against alphabetical order (Zed 31.4, Aaron 18.0, Ann none), so it would fail if the
  sort were reversed or by name (lessons class 1).
- **Gating**: anonymous users and strangers get 404 through `visibleLeague` (MVC IT). ESPN goes through
  `LeagueAvailability` ROUTES/FIXED, is covered by the generic ROUTES walk plus an explicit 422 test, and
  the client gate `allow('lineupCheck')` with its `BLOCK_LABELS` entry means an ESPN league never fetches.
  Complete, pre_draft and drafting leagues get NOT_IN_SEASON before any Sleeper call. A playoff week is
  checked.
- **"Mine"** in the lineup check is `NextMatchupService.myRosterId`, extracted rather than copied.
  NextMatchupService's tests are unchanged and green.
- **useBlock keys**: lineup check `[id, version, okLineup, viewerIsMember]`; player page
  `[leagueId, playerId, version]` (a tab switch doesn't refetch); leaderboard `[leagueId, win, version]`
  (a filter change in the URL doesn't refetch, correctly).
- **URL state**: `own=mine` round-trips through `readFilters`/`writeFilters`, which the leaderboard and the
  calculator both use (one encoding). `stripRoster` drops `own=r:` and keeps `mine` on a season switch.
  `mine` is dropped, and named, when no row is the viewer's. `setFilters` rewrites only pos/team/own and
  keeps every other param.
- **SC-004 tab split**: every moved section still receives `d` exactly as before (`RanksCard
  ranks={d.fantasy.ranks}`, `BreakdownCard d`, `SeasonLine d`, `AdvancedCard d`, `GameLog d`). No
  section's props or computations changed. Only mount order and visibility did. The 24 existing tests that
  gained an `onTab(...)` line kept their assertions.
- **SC-006 copy**: the LineupCheck strings contain no start/sit/add/drop/recommend verbs, and the
  word-bounded test sweeps every reason × option state × sport.
- **`statusAsOf` SQL**: `greatest()` ignores nulls in Postgres, and both-null comes back as empty. The IT
  ran, 5 tests, 0 skipped.
- **Trends `rosteredByMe`**: null exactly when `rostered` is null, false for a free agent and for an
  unknown-owner roster.

---

## Fixes applied (2026-10-20)

Fix pass by a separate agent, uncommitted in the same worktree. S7 is left for live verification. Nothing
committed, no migration.

- **B1 fixed.** `LineupCheck.flag` now drops a bench player only when he is OUT (`isUnavailable` or
  suspended) or has no game in the week. A LISTED one stays; `BenchOption` gained `injuryStatus` (String,
  nullable), mirrored in `web/src/api.ts`, shown as "Listed: DTD" in `components/LineupCheck.tsx`. The empty-list
  copy is now "No bench player is eligible for PG who isn't out and has a game this week" (the last clause only
  when the game check ran). Test `aBenchPlayerWhoIsOutOrHasNoGameIsNotAnOptionButAListedOneStaysWithHisTag`
  replaces the one that pinned the exclusion, plus `aListedStarterWhoseOnlyBenchPlayerIsDtdStillSeesHim`.
  `data-model.md` Validation amended with a dated note.
- **B2 fixed.** `FP_PER_GAME` is chosen per flag only when every option has an average and all share one
  season; otherwise NAME, and `optionsOrder` says NAME. The old ordering test now gives every option an average;
  new tests `oneOptionWithoutAnAverageMeans...` (mixed null/non-null) and
  `averagesFromDifferentSeasonsAreNotComparedEither`.
- **S1 fixed (frontend only).** `PlayerPage.HeaderFacts` prints "#12 C (2025-26)" when `requestedSeason` is set.
  Test added.
- **S2 fixed.** `scheduleAsOf` (OffsetDateTime, nullable, from `SportScheduleRepository.fetchedAt`) on
  `LineupCheck` and `PlayerStatsPage`, mirrored in `api.ts`. Printed as "schedule as of ..." (same ET formatter as
  the status as-of) beside Next game, on the Schedule tab line, and in the lineup check's game-check note
  ("schedule time unknown" when null). Tests in `PlayerStatsServiceTest`, `LineupCheckServiceTest`, the two
  component test files.
- **S3 fixed.** New `ScheduleGridService.scheduleHoldsWeek(games, week)` (the rule `teamsWithoutGame` applies to the
  grid's weeks, which are every stored game's week); `LineupCheckService` sets `gameCheck` from it. `teamsWithoutGame`
  itself is unchanged (it takes a grid, not raw games), so the rule is stated twice, in two adjacent methods, with
  cross-referencing javadoc. Test `theGameCheckReasonSaysWhy...` covers a week-30 league against a week-1 schedule.
- **S4 fixed.** New reason `UNKNOWN_PLAYER`: a starter with no player row is flagged (player `known=false`, no
  options, optionsOrder NAME), in `LineupCheck`, `api.ts` and `data-model.md` (amended note). UI sentence "We don't
  have this player's details", no options note, and "All starters are active" is not shown. Tests in
  `LineupCheckTest` and `LineupCheck.test.tsx`.
- **S5 fixed.** `gameCheckReason`: `NO_SCHEDULE` | `WEEK_UNKNOWN` | `WEEK_NOT_IN_SCHEDULE` | null on the payload,
  `api.ts` and `data-model.md`. Copy per reason (football keeps "No game check for football yet." for
  NO_SCHEDULE). Tests as in S3, plus component tests.
- **S6 fixed.** `LineupCheckServiceTest`: `aFallbackBoardLabelsTheAveragesWithTheAnsweredSeasonAndItsGames`
  (stub board `season=2025`, `requestedSeason=2026`, fp 31.4, games 71; asserts fpSeason 2025, fpGames 71) and
  `aRowWithNoSeasonStatsIsSkipped...` (a `stats == null` row is skipped).
- **S7 not changed.** Latency is measured in live verification, as instructed.
- **S8 documented only.** One sentence added to the amended note in `research.md` R2.
- **Nit, `OwnershipMark.asOfLabel`: wired, then prop removed.** The mark now builds its tooltip from the
  ownership's own `asOf` ("Rosters as of Oct 20, 11:02 AM EDT" / "Rosters at the end of week 18"), so every caller
  gets it without passing anything. Test updated.
- **Nit, `LineupCheck.slotKnown`:** comment added that it assumes `isEligible` reads only `player.positions()`.
- **Other nits** (`?tab=` on the season picker, `aria-controls`, AdvancedCard toggle reset, the idle-starters
  flash) were "no action needed" and are untouched.

**Ran after the fixes**: targeted backend (`*LineupCheck* *PlayerStats* *NextGames* *ScheduleGrid*`) green; full
`./gradlew.bat test` 2109 tests, 1 failed (the known unrelated
`BoardRepositoryLatestBeforeIT.theDraftRowCarriesTheScheduledStartTimeFromBothQueries`), 1 skipped; web `tsc -b`
clean, `vitest run` 2295 tests (one existing assertion narrowed, see below), `npm run build` ok.
The one narrowed web assertion: `PlayerPage.test` "shows the next game as a date..." asserted no `h:mm AM/PM`
anywhere on the page; the schedule's as-of line now legitimately prints a time, so it checks the next-game text
alone.
