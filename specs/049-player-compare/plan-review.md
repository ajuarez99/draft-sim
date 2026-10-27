# Adversarial plan review: spec 049 (Compare two players)

This review read spec.md, plan.md, research.md, data-model.md, contracts/api.md, contracts/ui.md and
quickstart.md cold on 2026-10-25, before any code existed. It checked them against the code in worktree
`049-player-compare` (main `ec72031`), AGENTS.md's recurring bug classes and `claude/lessons.md`.

**Nothing in this review was executed.** No backend, Vite server or Postgres was listening on
8080/5173/5433 (`netstat`, checked). Every item is labelled **read** (from code, not run), **quoted** (a
number an earlier spec measured, not re-measured here) or **suspect** (inferred).

## Claims confirmed

| Claim | Where checked | Result |
|---|---|---|
| Season is resolved per league, never per player (R1) | `PlayerStatsService.java:305-323` (read) | ✅ `resolver.resolve(requested.sleeperId(), STORED_GAMES)` and `dataSeasonBefore(sport, requested.season())` take no player input. `currentOwnership` (`:369-374`) is read only when `requestedSeason != null`, also league-only. Two calls for one league answer the same `season`, barring an ingest landing between them (F14). |
| `smallSample` is minutes-only | `AdvancedStats.java:258-280`, `weights.yml:219,231` (read) | ✅ `minutes < smallSampleMinutes` (100). A 0-game window is `smallSample: true` (0 < 100). Note the weights comment scopes it to "a shooting/usage rate", not every row. |
| LAST_10 / LAST_5 for a short player | `AdvancedStats.java:94-97` (read) | ✅ `select` returns the whole season when `size <= lastN`, so a 3-game player's LAST_10 = LAST_5 = SEASON. `Window.games` carries the real count. |
| Compose, no new endpoint | `PlayerStatsService.read`, `useBlock.ts` (read) | ✅ Same payload, so FR-002 holds for anything the payload carries. `useBlock` cancels a stale response, so a per-column `useBlock` keyed on the player id is safe against a late overwrite after a swap. |
| 404 vs available-without-games | `PlayerStatsService.java:345-351` (read) | ✅ `Optional.empty()` (the controller's 404) only when no games *and* no player row. A player with a row and no games is `available` with `reason: NO_PLAYER_GAMES`, windows at 0 games. US2-3 and the "not found" edge case map cleanly onto `Block.notFound`. |
| PlatformGate `"stats"` on an NBA Sleeper league | `PlatformGate.tsx:31-46`, `LeagueAvailability.java:77` (read) | ✅ A Sleeper league renders children with no request. The gate is about platform (ESPN), not sport. Football is left to the page via `NOT_BASKETBALL`, as the plan says. |
| Route does not collide | `App.tsx:139-180` (read) | ✅ No `/leagues/:id/compare` exists. Precedent for a two-entity comparison: `/managers/:aId/versus/:bId`. |
| Box scores and schedule share game ids | `PlayerGameIngestService.java:251` (read) | ✅ The ingest already calls `schedule.isAway(entry.game_id, team)`, so a box row can be matched to a schedule row by `gameId`, no date join needed (see F3). |
| Leaderboard rows carry name, team, ownership | `PlayerStatsService.java:559-567` (read) | ⚠️ For every player *with a game*, but `name`, `positions` and `team` are null/empty when there is no `player` row (`pl == null`). The chooser must handle a null name (F11). |

## Findings

### F1. HIGH (read). The SC-006 verdict scan fails on the plan's own required copy

R6's test scans for `/\b(start|sit|better pick|recommend|winner|wins \d)/i`. `\bstart` has no trailing
boundary, so it matches "starter". contracts/ui.md requires the note "This league credits one game per
**starter** each week". The test as written fails against the page it's meant to pass. It also matches
"started", "starts", and `\bsit` matches "site" and "situation". Either the test is weakened during the
build to make it pass (silently), or the copy is changed to dodge the regex. Neither is a decision a build
agent should make.

**Amendment.** Specify the scan in contracts/ui.md as whole words and phrases:
`/\b(start|sit|bench)\b(?!er)/i` *plus* `/\b(better pick|recommend(ed|s)?|winner|edge)\b/i` and
`/\b\d+\s*(of\s*\d+\s*)?(rows?|categories)\s+won\b/i`. Add a positive control: the test renders the
games-this-week note and asserts it does **not** trip the scan, and renders a planted "Start Jokić" and
asserts it does.

### F2. HIGH (read + quoted). "More games isn't more points" is contradicted by spec 044's measurements

contracts/ui.md's note says "so more games isn't more points", and spec Context says a player with four
games "is not worth more this week" than one with two. These leagues run `game_mode: 1` with
**lock-ins** (spec 044, `specs/044-lock-in-threshold/spec.md` Context). There, the credited game "beat
the week's average game by ~14–19%" (quoted, measured in 044), and the lock thresholds are learned *per
number of games left*, because each extra game is another chance to lock a good one. The player page
itself carries a Lock-ins tab (`PlayerPage.tsx:689,778-786`). So more games is worth *something* (the
best-of-N option). It isn't proportional, which is what 540/540 showed. And zero games is worth zero,
which is the one count that matters most (`ScheduleGridService.teamsWithoutGame` javadoc, `:181-189`).

Not marking the row is still right, since nothing has measured how much an extra game is worth. But the
note states a wrong claim as fact (AGENTS.md "Never retune ... name it wrong": this is the reverse
error).

**Amendment.** Change the note to something like: "This league credits one game per player each week:
the one you lock in, or his last. More games means more chances at a good one, not more games' points."
(This also avoids "starter", see F1.) When `played + remaining == 0`, say "No game this week" plainly.
That's a fact, not a verdict. Correct spec Context's "is not worth more" line in place with an "amended
after review" note.

### F3. HIGH (read). `thisWeek` adds a third "current week" rule; two already exist

Plan decision 3 picks the grid week whose `[firstDate, lastDate]` contains `NextGames.today(clock)`.
R3 says "the client has no single source for which week is today". Both claims are off:

- `ScheduleGridService.Week.firstDate/lastDate` are not calendar bounds. They're the min/max *game
  dates* of counted, non-exhibition games in that week (`ScheduleGridService.java:113-125`). Any day with
  no NBA games at a week's edge (a game-less Monday, the days around the All-Star break, the gap before a
  playoff week) lies in **no** week, so `thisWeek` goes null on a day when the league's week has begun.
  A week whose only games are postponed has `firstDate == null`, which the containment check has to
  handle.
- The schedule page's "current week" is the league's `leg` (`ScheduleGridService.java:76,87`,
  `Result.currentWeek`, on the client at `api.ts:3043`, used `ScheduleGrid.tsx:48,257`). Lineup check
  uses `leg` too (`LineupCheckService.java:93`). Lock-ins already solved the stale-`leg` Monday problem
  with `LockInService.liveWeek(schedule, day, legWeek)` (`LockInService.java:470-485`): "the week
  containing it, or the next one across a gap". quickstart scenario 9 checks `thisWeek` against "the
  schedule page's current week", which is `leg`. That's a different rule from the one the plan builds,
  so the check can fail on a correct build or pass on a wrong one.

Lessons #25 and #47 and the multi-sport-landmines memory are this exact class.

**Amendment.** Pick the week with the existing `LockInService.liveWeek(schedule, NextGames.today(clock),
leg)` (same package; make it package-visible if it isn't). Count `played` as box lines with
`line.week() == week`, and `remaining` as `NextGames.forTeam(...)` filtered to `g.week() == week` and
`gameId` not among his box lines. That's lock-ins' own counting (`LockInService.java:527-539`), with no
date-range join. Drop `firstDate`/`lastDate` from the contract, or fill them from the grid as display
only. Rewrite quickstart 9 to compare against the lock-ins/`liveWeek` answer and state the `leg` read and
when it was ingested (lesson #47).

### F4. HIGH (read). Shooting percentages are marked on any number of attempts (bug class #1)

FR-004 marks FG%, 3P% and FT% higher-better. The only guards are "both present" (a `Rate` with
`NO_ATTEMPTS` is missing) and `smallSample`, which counts minutes, not attempts. With 300 minutes each
across ten full games, a center at 3P% 100.0 (1-1) gets the mark over a guard at 38.0 (38-100), and a
big at FT% 100.0 (2-2) over a 1-of-6-per-game 85% shooter. The rule is well formed and points the reader
the wrong way. Structural tests won't see it. The weights comment on `small-sample-minutes`
(`weights.yml:219`) already says it's a *rate* flag, so it was never meant to bound attempts.

**Amendment, for Allan to choose (it changes what's marked):**
(a) leave the three percentages unmarked and show made-attempted under each, the way `RateValue` does
(`PlayerPage.tsx:88-104`); or (b) mark only when both players' attempts clear a floor. That floor would
be a new hand-set constant in `weights.yml`, labelled ARBITRARY. Recommended: (a). It needs no new
constant and the spec's "no new threshold" assumption holds. Add a preference-ordering test with the
1-1 vs 38-100 fixture, whichever way it's decided.

### F5. MEDIUM (read). Per-game turnovers marked lower-better mostly marks the smaller role

TOV per game scales with usage and minutes, the same way `pf` does, and the plan leaves `pf` unmarked for
"no better direction". Under FR-004 a 12-minute backup at 0.6 TOV beats a 36-minute star at 3.4 on
nearly every pair. The fantasy rows already net turnovers through the league's scoring. If this league
weights `to` at 0, the row has no fantasy direction at all (not checked: no DB). `tovPct`, the per-possession
rate, is the version with a real direction, and FR-004 names it, but contracts/ui.md's row list doesn't
include it. Spec and contract disagree.

**Amendment.** Either leave per-game TOV unmarked (like `pf`) and add `tovPct` lower-better (pulling in
F4's attempts question for its denominator), or keep TOV lower-better and write down why. Make the row
list and FR-004 agree in the same edit. Acceptance scenario US1-3 changes with it, which is Allan's call.

### F6. MEDIUM (read). Games per window and season fantasy total are schedule counts, marked as merit

contracts/ui.md lists "games" per window, and FR-004 marks "every row except turnovers" higher-better,
so SEASON games and season fantasy total are marked. LAST_N games can never be marked: the window-full
rule makes them equal. SEASON games differ mostly by team schedule (one team has played 4, the other 3)
and injuries. That's the same reasoning the spec uses to leave "games this week" unmarked. Season fantasy
total is a **sum of every box score**. Under one-credited-game-per-week that isn't what the league
credited, and it grows with games played (bug class #5: a correct number, read as something it isn't).

**Amendment.** Put SEASON games and season fantasy total on the unmarked list in FR-004 and
contracts/ui.md. Label the total "box-score total (not the league's credited points)" or leave it off
the comparison. It's on the player page only as the breakdown's denominator (`PlayerPage.tsx:421`).

### F7. MEDIUM (read). The client "window-full" rule restates part of the server's `qualify`, and misses the stale arm

Plan decision 2 adds a client check, `games == window length`. The server already has that rule.
`PlayerStatsService.qualify` (`:761-775`) refuses LAST_N unless `window.size() >= lastN`, and also when
the last game is more than `recency-days` (14) before the season's latest (`NOT_QUALIFIED_STALE`). With
the client rule, a player back from three weeks out has a full but stale LAST_5 and gets marked against a
current one, which the page's own ranks refuse. The `10`/`5` literals would also be a second copy of
`WindowKind.lastN` (`PlayerPage.tsx:66-70` already holds one; lesson #25).

**Amendment.** Ship per-window qualification on the wire. `Computed.qualification` is already computed
(`:234-236,640`) and isn't serialized. Add it to the player page response as one more additive optional
field beside `thisWeek` (e.g. `windowQualification: {SEASON: {qualified, reason}, ...}`), and have
`markRow` withhold on `NOT_QUALIFIED_STALE` and on the lastN shortfall from that field. Show the reason
in the cell ("3 of 10 games", "last game 21 days ago"). Don't use `qualified` on SEASON as a mark guard:
its minutes-per-game floor is a ranking rule. That stays a design choice for the plan to state.

### F8. MEDIUM (read). `destinations.ts` is missing from the plan, and `destinations.test.ts` will fail

`destinations.ts:22-25`: "Adding a league-scoped route means adding a row here. destinations.test.ts
fails if App.tsx grows one without it". The test reads `App.tsx?raw` and asserts every `/leagues/...`
path has a matching row (`destinations.test.ts:195-224`). Without a row, the rail also fails to recognise
the page as inside a league, the exact bug that file's header describes. The sport gate for basketball
lives in that table (`sports: ['nba']`, "the ONE place that says so", `:228-230`). FR-013's "entry point
absent on football" should read it (`playerPagesFor`), not a second check.

**Amendment.** Add a `compare` row to the project structure and tasks: `sports: ['nba']`, `idKind:
'league'`, `inRail: false` (reached from a player page, like `players`), `match:
/^\/leagues\/([^/]+)\/compare\/?$/`. Add `'compare'` to `DestinationKey`. Decide what the rail's season
links do with `?a=&b=`. `href(ctx)` gets no query string, so a season switch drops both players. Either
carry the query through or say the drop is accepted.

### F9. MEDIUM (quoted). R4's leaderboard size is already measured, and it's over the plan's own threshold

R4 and the plan's Performance Goals say the payload is "not measured yet" and set "> ~1 MB" as the switch
to a slim endpoint. Spec 023's code review measured the SEASON leaderboard at **1.46 MB raw, 327 KB
gzipped** (`specs/023-draft-room-player-stats/code-review.md:102`, quoted). That was likely a full
season. Today's 5-day NBA 2026 board will be smaller, so a T001 measurement this week would pass the
threshold and fail it by midseason. The threshold also doesn't say raw or gzipped.

**Amendment.** Quote 023's number in R4. Restate the threshold as gzipped transfer and warm latency
measured on a full season (NBA 2025 locally), not the 5-day board. Reuse `draftStatsCache.fetchDraftStats`
(`draftStatsCache.ts:15-26`, a module-level promise cache) so the chooser never refetches within a
session. Reuse the existing accent fold (`targets.ts:12` `fold`, or `CommissionerListPanel.normalizeName`)
rather than a third copy.

### F10. MEDIUM (read). "Same rounding as the player page" needs a shared module, and two figures aren't on the page

The helpers `fixed`, `num`, `minutes` are module-private consts in `PlayerPage.tsx:72-79`. The plan says
the comparison uses "the same helpers", which means moving them out (e.g. to `statCells.tsx` or a new
`statFormat.ts`), or the build agent writes a copy (lesson #25). Also, per-game **TOV** and per-game
**3PM** are not displayed on the player page. It shows TOV per 36 (`:455-462`), and 3PM only as the
made-attempted total under 3P% (`:324`). FR-002/SC-001 say "equals the same figure on that player's own
page", which can't be checked by reading the page for those two. R2's "equality after the same rounding"
also only holds if the comparison compares the *formatted strings*. Comparing raw doubles can mark 12.04
over 12.03, which both print as 12.0.

**Amendment.** Add a task that extracts the formatters to a shared module, used by both pages. Reword
SC-001 as "equals the player page's payload, formatted with the page's formatter". Name TOV/g and 3PM/g
as payload figures the page doesn't show. Have `markRow` compare the display strings, with a test that
12.04 vs 12.03 is unmarked.

### F11. MEDIUM (read). Chooser edge cases: null names, fallback seasons, football with no `a`

- Leaderboard rows have `name: null` when there's no player row (`:559`). The chooser must skip them
  (they can't be found by name) or show the id. Say which.
- On a fallback season (`requestedSeason` set), the board's rows are the *earlier* season's players. That
  includes players since retired and excludes this year's rookies. The chooser should carry the same
  fallback note.
- contracts/ui.md derives the football line from "the first response's `reason === 'NOT_BASKETBALL'`".
  With no `a` and no `b` there's no player response, only the chooser's leaderboard (which also answers
  `NOT_BASKETBALL`). Better: decide football from the `destinations.ts` sport row (F8) before any request,
  the way `PlayerPage`'s own header comment says pages should ("the page holds no sport rule").

### F12. MEDIUM (read). `thisWeek` nulled on a fallback season blanks it when it matters, and contradicts `header()`'s rule

data-model nulls `thisWeek` when `requestedSeason != null`. `header()`'s javadoc (`:388-401`) is
explicit: everything about games about to be played comes from the **URL** league, never the resolved
league, because blanking it on the fallback "would blank it exactly when it matters". With
`STORED_GAMES`, the fallback means the URL season has no stored games yet
(`LeagueSeasonResolver.java:104-108`). So `played = 0` is true for the URL season, and `remaining` comes
from the URL season's schedule.

**Amendment.** Compute `thisWeek` from `requested` (the URL season's schedule, the URL season's box
lines via `boxCache.get(sport, requested.season())`) under `header()`'s rule, and drop the fallback-null
case. Also keep the existing gates (complete league, no team, no schedule). Add a fixture test for "URL
season has a schedule and no box scores yet".

### F13. LOW (read). `played` lags the box-score ingest; say so

`played` counts stored box lines, and `remaining` counts unsettled schedule rows. Tonight's finished game
is in neither until the box ingest and the schedule refresh run. A game that's finished and not yet
ingested still reads as remaining (the player page already prints `scheduleAsOf` for this reason,
`PlayerPage.tsx:195-199`). **Amendment.** Show the schedule's as-of beside the this-week line, the way
the header does, and name the lag in the contract.

### F14. LOW (suspect). Two calls can straddle an ingest

Two parallel reads can land on either side of a nightly box ingest (the `SeasonBoxCache` token changes),
so the columns would show `dataAsOf` values minutes apart. The plan guards on `season` only.
**Amendment.** Also compare `dataAsOf`. When they differ, refetch the older column once, or print both
as-ofs and withhold marks.

### F15. LOW (read). Small loose ends to state in the plan

- Fetch each column with `useLeagueDataVersion(leagueId)` in its deps, as `PlayerPage.tsx:772-773`
  does, or a league refresh leaves stale columns.
- FR-015 at 375 px: three cells per row (label + two values), with shooting printing "47.3%" and
  "12-25" beneath. Test with the longest real names in the league, not a fixture name.
- SC-007 excludes the chooser's leaderboard load. Say so in SC-007, or the measured number will be
  misread as covering the chooser.
- The Compare link placement: `PageHeader` (`PlayerPage.tsx:797-801`) has no action slot today. Say
  whether the link goes in the `Header` card or `PageHeader` grows a prop.

## Summary for the plan

Before tasks: F1–F4 change the plan or the contract. F4, F5 and F6 change *which rows are marked*, so
they go to Allan. F7–F12 are build-shape corrections that belong in tasks. F13–F15 are notes. Nothing
here argues for a new endpoint or a migration. The compose decision (R1) holds.
