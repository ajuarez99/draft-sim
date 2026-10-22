# Adversarial plan review: spec 042

Read cold on 2026-10-20, before any code exists. Each claim in `plan.md`, `research.md`,
`data-model.md`, `contracts/*` and `quickstart.md` was checked against branch
`042-fantasypros-design-features` @ `2df1682` (identical code to local `main`). Also checked:
`origin/main` @ `c987b92`, which is **4 commits ahead** (specs 039 and 040 merged).

Everything here is **read** (from code or git refs). Nothing was executed: no server, no database,
no Sleeper call.

## Findings

Ranked by severity.

### F1 (BLOCKER). The next game is nulled on exactly the nights it matters

**Plan** (`contracts/player-page-c1-delta.md`): "For a past season (`requestedSeason` set or season
complete): `nextGame` is null, `NO_GAMES_LEFT`, `upcoming = []`."

**Code**: `requestedSeason` doesn't only mean "a past season". `PlayerStatsService.read:272-280`
also sets it when the URL's season has no box scores yet: `dataSeason` falls back to the newest
earlier season, and `requestedSeason = requested.season()`. The resolver at `:263` can move it too
(`Rule.STORED_GAMES`). On 2026-10-20 (NBA opening night) and until the first 2026 games are stored,
every NBA 2026 player page takes that branch. So the rule as written blanks the next game for the
whole first stretch of the season, which is the window this feature is for. It's the same class as
spec 017 review F1.

**Amend**: compute `status`, `nextGame` and `upcoming` from **`requested`** (the URL's league row,
the one `visibleLeague` returned), never from `resolved.league()` or `dataSeason`. Gate them on
`requested`'s own status only (not complete and not pre-draft), and use the player's **current**
team (`player.team`), not `teamsThisSeason`. Add a test: an NBA 2026 league with no 2026 box scores
must still return a `nextGame`.

### F2 (SHOULD-FIX). `status_capture.captured_at` is not a correct as-of for `injury_status`

**Plan** (R4): "Each successful ingest calls `recordCapture`… the as-of time shown is the latest
`status_capture.captured_at`."

**Code**: `PlayerIngestService.ingest:107` upserts every player (including `injury_status`) first.
`recordCapture` (`:139`) runs only inside a separate `try`, and only when Sleeper's
`/state/{sport}` answers `season_type == "regular"` (`:117-127`). So:
- In preseason (the NBA was `"pre"` on 2026-10-05, measured in `PlayerIngestService`'s own javadoc),
  and in the postseason, statuses are refreshed and **no capture is written**. The as-of is then
  null, or last season's.
- If the state call fails, statuses are fresh and the as-of is older than the data. That's honest
  in direction but wrong.
- Rows are keyed `(sport, season, week = leg)`. "Latest for the current season" has to be
  `max(captured_at)` across weeks. Say so explicitly.

There's also a second, better-fitting record, `daily_capture (sport, capture_date, kind='PLAYERS',
completed_at)`, written by `DailyRefreshService.players:124-135`. It isn't written by the manual
`/api/ingest/players` or `/api/ingest/all` routes (`IngestController:84, 252`).

**Amend**: R4 names the gap. Pick one as-of and state what it misses. Recommended:
`greatest(max(status_capture.captured_at), max(daily_capture.completed_at where kind='PLAYERS'))`
for the sport, with no migration. The cleaner fix is for `PlayerIngestService` to record its own
completion time whatever the season type. That's code-only only if it reuses `daily_capture`, and
then `DailyRefreshService.players`' skip-if-already-today gate (`:127`) changes behaviour, so
decide that with Allan. Whichever is chosen, null means "unknown", never "fresh".

Also correct R4's follow-up: a players-only trigger **already exists**
(`POST /api/refresh/players`, `RefreshController:81`). But it goes through
`DailyRefreshService.players`, which **skips** if a players capture already exists that UTC day
(`:127-128`). A second pre-tip-off cron would therefore do nothing as built. Record that in the
open follow-up.

### F3 (SHOULD-FIX). The plan points at the wrong slot-eligibility rule

**Plan** (R6): eligibility "reuses `LeagueAnalysisService`'s slot rule (`:890-900`)… extracted to a
package-private static if needed".

**Code**: `LeagueAnalysisService:888-910` is `byLineupCard`, a display **ordering** comparator,
not eligibility. The real rule is already public on the sport interface:
`SportRules.isEligible(Player, String rosterSlot)` (`SportRules.java:131`). The basketball version
(`BasketballRules.java:418-430`) covers PG/SG/SF/PF/C/G/F/UTIL across multiple positions. The
football version (`FootballRules.java:320-325`) covers the dedicated slots and FLEX, and **returns
false for any other slot name** (SUPER_FLEX, REC_FLEX, WRRB_FLEX).

**Amend**: use `rulesRegistry.get(sport).isEligible(p, slot)`. Get the slot from the starting
entries of `league.rosterPositions()`, filtered with the same `NON_STARTING_SLOTS = {BN, IR, TAXI}`
(`LeagueAnalysisService:713`; make it package-visible rather than copying it). For a slot that no
player can fill, because `isEligible` doesn't know the slot name, return an explicit
`optionsOrder`/reason such as `SLOT_UNSUPPORTED`. Don't return an empty `options` list, which would
read as "no bench player fits".

### F4 (SHOULD-FIX). The "won't play" rule already exists, and NBA suspension isn't in `injury_status`

**Plan** (R3): flag starters whose `player.injury_status` is in a new hand-set
`weights.yml lineupCheck.flaggedStatuses`.

**Code**:
- `LeagueAnalysisService.isUnavailable:1103-1107` is the existing rule, Out/IR/Sus. It's what zeroes
  NBA players on the Analysis page and in the home matchup block. A second list in `weights.yml` is a
  second implementation of one rule (the multi-sport-landmines class), and the two can drift.
- NBA suspensions live in `player.status = "SUS"`, not `injury_status` (`BasketballRules.isSuspended`,
  `:445-453`, measured 2026-09-22). A rule that reads only `injury_status` silently misses a
  suspended NBA starter. `isUnavailable`'s "Sus" check only ever fires for football.

**Amend**: one rule. "Out" means `isUnavailable(injuryStatus) || rules.isSuspended(player)`. Make
`isUnavailable` package-visible or move it beside `SportRules`, and have LeagueAnalysis keep calling
it. Any other non-null `injury_status` (Questionable, Doubtful, DTD, GTD) is the second, "Listed:
<value>" tier, as R3 already says. If T001 shows a list really is needed, it replaces
`isUnavailable`'s literals so both readers share it. Put `status` on `PlayerStatus` too, so the
player page can show SUS. Also: `weights.yml` is at the repo root (`config/weights.yml`), not under
backend resources as the plan's tree shows.

### F5 (SHOULD-FIX). League home already has an NBA "your starters with no game" note

**Plan**: US3 adds a new "no game left this week" check and a new `NextGames` helper. It never
mentions what's already there.

**Code**: `SeasonStartService.startersWithoutGame:222-247` already computes the viewer's starters
with no game in the week from `ScheduleGridService.teamsWithoutGame:175-191`. That method applies
the counted-status rule (`SportSchedule.counts`) and the All-Star exhibition filter
(`ScheduleGridService:56-65, 193`). `LeagueHome.idleStartersNote:683-706` renders it in the NBA
`DRAFTED_UNSCORED` state ("N of your projected starters have no game in week X"). Those are
**projected-optimal** starters from the lineup pass, not Sleeper's actual `starters`. Shipping US3
as planned puts two "your starters" lists on one page, from two definitions of who starts and two
"has a game" rules.

**Amend**:
(a) Build "games this week / games left" on `ScheduleGridService.Result`. Put `NextGames` in
`ingest/SportSchedule`'s package and reuse `SETTLED`/`counts`, which are private today. Don't
re-list `{complete, canceled, postponed}`, and keep the exhibition filter.
(b) Decide in the plan what happens to `idleStartersNote` once actual starters are available:
recommend that the lineup check replaces it, and the note stays only when there are no live
starters. Label the two clearly otherwise.
(c) Name the "today" rule. The plan uses `America/New_York` while `PlayerGameIngestService:168`
uses UTC. ET is the right call for evening games, but state the divergence in code. A game played
earlier today still stores `pre_game` until the next schedule refresh, so "games left" should show
the schedule's `fetchedAt`.

### F6 (SHOULD-FIX). "No game left this week" misreads how this league scores

**Plan** (US3 scenario 2, R6): flag an NBA starter with no game **left**, and order options by games
left (`GAMES_LEFT` fallback).

**Code and memory**: NBA starters are credited **one game per week** (540/540 roster-weeks,
memory "NBA one game per week is correct", spec 018 R2). The credited-game mechanism is unknown. A
starter who already played on Monday and has nothing left isn't obviously a lineup hole, and "3
games left" isn't worth more than "1 left" the way it is in a sum-of-games league. Whether the
lineup even locks weekly or daily isn't established anywhere in the repo.

**Amend**: flag only a starter whose team has **no counted game in the week at all**, which is the
existing `teamsWithoutGame` meaning. Show games left as a fact, never as a sort key that implies
value. Drop `GAMES_LEFT` as an ordering. Add an owed measurement to T001: whether this league's NBA
lineups lock weekly or daily. If they lock weekly, mid-week bench options can't be acted on and the
copy has to say so.

### F7 (SHOULD-FIX). The contract's auth cases are wrong

**Contract** (`lineup-check.md`): "No header, or a header that matches no member: `available=false,
reason=NOT_MEMBER`" (200).

**Code**: `LeagueMembership.visibleLeague:187-191`, through `canSee:167-175`, returns empty for an
anonymous caller (unless they hold the admin token) and for a non-member. Every league route turns
that into a **404** (for example `PlayerStatsController:39-40`). `NOT_MEMBER` with a 200 can only
happen for (a) an admin token with no header, or (b) a chain member with no roster this season
(`canSee` walks the league chain).

**Amend**: no header or a stranger gets 404, the same as every other route. `NOT_MEMBER` is kept
for (a) and (b). The web already hides the section when signed out (quickstart step 4), so there's
no UX change. Same fix for `trends-delta.md`'s "with no X-Sleeper-User": that only happens with the
admin token.

### F8 (SHOULD-FIX). Which roster is "mine" has two existing rules; the plan invents a third

**Plan** (R2): "takes the viewer's roster (`owner_id` = the resolved viewer)".

**Code**:
- The lineup pass uses `owner_id == header || callerManagerId == managerId`
  (`LeagueAnalysisService:1030-1031`). The second clause is how an ESPN claimant is "me" (spec 035
  S6).
- `NextMatchupService:83-93` uses the manager id from `ManagerRepository.caller` against
  `roster_season`, and **the lowest roster id when a manager holds two** (review F10, "a rule, not
  an accident").
- Neither reads Sleeper's `co_owners`. A co-owner is never "me" anywhere in the app.

**Amend**: pick NextMatchupService's rule, because the block sits next to it on league home:
caller manager id, then lowest roster id. Then select that `roster_id` from the live list. Say
co-owners are unsupported, as everywhere else. A plain `owner_id` match would quietly disagree with
the next-matchup block for a two-roster manager.

### F9 (SHOULD-FIX). The URL encoding for the availability filter already exists

**Plan** (R7): new `?avail=all|free|rostered|mine|roster:<id>` on the leaderboard.

**Code**: the stat calculator (spec 032) already URL-encodes the **same** `Filters.availability` as
`own=free|rostered|r:<id>` (`statCalcUrl.ts:94-100, 163-165`), with `stripRoster` (`:74-78`) for the
season-switch rule R7 cites. It also renders the **same** `FilterBar` (`StatCalculator.tsx:794`).
So adding a "My roster" option to `FilterBar` shows up in the calculator too, where
`readCalcState` doesn't know `mine` and would push it to `dropped`.

**Amend**: use one encoding on both pages. Reuse `own=`, add `own=mine`, extend `readCalcState`,
`availKey`/`parseAvail` (`statControls.tsx:78-80`) and `matchesAvailability` (`statLeaderboard.ts:320`)
together, and add `mine` to the `Availability` union (`statLeaderboard.ts:296`). Note: the
leaderboard's `SeasonPicker` carries **no** query string today (`statControls.tsx:135-151`), so
"`roster:<id>` dropped on season switch" needs a `keepSearch: stripRoster`, or every filter is lost.
`mine` can survive a season switch, because it's re-resolved per season.

### F10 (SHOULD-FIX). Bench "season average" early in the season is last season's, or one game

**Plan** (R6): order NBA options by SEASON fp/g "from the same computation the player page uses",
measuring the cost per bench player.

**Code**: `read()` builds the full percentile population for every call (`PlayerStatsService:317-323`).
`readLeaderboard` (`:378-491`) produces every player's windows in one pass, so call it **once** per
lineup check, not once per bench player. Either way, the same fallback as F1 applies: until 2026 box
scores exist, the "season average" is 2025's, and after that it's a 1-3 game sample.

**Amend**: one leaderboard read per request. Carry the answered `season` and the games behind each
`fpPerGame` on `BenchOption`. The UI prints "2025 avg" when the season fell back, and shows the
game count. That's this project's honesty rule: thin data must not read as a settled number.

### F11 (SHOULD-FIX). The branch is behind `origin/main`

**Plan**: "highest on main is V37; spec 040's unmerged branch holds V38."

**Git**: `origin/main` @ `c987b92` has merged 039 (PR #37) and 040 (PR #38), including
`V38__conduct_entry_through_week.sql`. It also changed `web/src/api.ts` and `LeagueHome.test.tsx`,
both files this plan edits. Local `main` is stale at `2df1682`.

**Amend**: rebase onto `origin/main` before T001. Correct the plan's sentence: V38 is on main, and
the next free number is V39, though none is needed. Re-check the line citations after the rebase.

### F12 (NIT). Small corrections

- R2 says "Nothing stores [starters]". `roster_week_points.starters` (V18) stores them per **scored
  week** from `/matchups`. The current week's aren't stored, so the decision stands. The V18 data
  does give T001 a free check for R2's owed question: compare stored NBA 2025 `starters[i]`
  positions against starting `roster_positions[i]` across all weeks, with no waiting for a live
  league. Empty slots are `"0"`; the precedent is `WeeklyReportService.startersOf:569-581`.
- Bench = `players − starters − reserve − taxi` (football leagues can have `taxi`). Load only the
  roster's players (`players.byIds`), not `players.findAll` the way `LineupPass` does.
- ESPN: say "not available for ESPN leagues yet", not that ESPN has no starters. ESPN rosters carry
  lineup slots; the app just doesn't store current ones (`LeagueRosterSource.espnRosters:47-58`).
  Consider adding a `lineupCheck` page key to `LeagueAvailability.ROUTES`/`FIXED` ("THE list",
  `:63-87`), so `PlatformGuard` and LeagueHome's `allow(key)` gate it like every other block,
  instead of a service-only `NO_STARTERS`.
- `PlayerStatsPage` gains **4** fields in data-model (`nextGameReason` included). The plan's
  constitution table lists 3.
- The player route takes no `?season=` (`PlayerStatsController:35-38`). Drop `[?season=]` from the
  C1 delta.
- FR-007's positional rank: the page already has `Ranks.positionRank` (`PlayerStatsService:87`).
  Say the header reuses it.
- FR-003 requires an "Available only" filter on every in-scope list. The plan adds it to the
  leaderboard only. Trends' risers/fallers have none, and the streaming table is free agents by
  construction. Either add it or amend FR-003's scope visibly.
- The lineup check's gate shouldn't copy `NextMatchupService`'s "regular season over" stop
  (`:65-67`). Starters in a playoff week still need checking. Gate on `league.complete()` and
  pre-draft only.
- 11:00 UTC is 7:00 ET only until 2026-11-01. After that it's 6:00 EST. Print the as-of time, not
  "7 AM".
- SC-001 compares marks with live `/rosters`, but marks come from the stored V28 rosters
  (`players_fetched_at`). Compare at a known as-of, or a lag will read as a mismatch.
- The new block on LeagueHome should key `useBlock` on `version` like its neighbours
  (`LeagueHome.tsx:164-185`), so the visit's refresh refetches it.

## Claims verified correct

| Claim | Where |
|---|---|
| `PlayerOwnership.Ownership(state, rosterId, ownerName, avatarId, isMe, asOf)` and its four states | `PlayerOwnership.java:66-71, 45-48` |
| Leaderboard `OwnerCell` shows the avatar and me-ring, with no "Yours" text | `StatLeaderboard.tsx:59-75` |
| `FilterBar` Availability has All / Free / Rostered / per-roster, disabled with a reason; it's component state on the leaderboard | `statControls.tsx:112-130`; `StatLeaderboard.tsx:177` |
| Player page "Yours" pill | `PlayerPage.tsx:109-117` (`OwnerChip`) |
| Trends drops `isMe`, and it **is** in hand for the viewer: header → `ManagerRepository.caller` → `RosterOwners.ownerNames(…, callerManagerId)` | `PlayerTrendsController:34-37`; `PlayerTrendsService:189-194, 387-409`; `RosterOwners:28-44` |
| Trends renders the owner as plain text | `PlayerTrends.tsx:111` |
| Live rosters come from `LeagueRosterSource.rosters`; Sleeper's carry `starters`, ESPN's carry only `roster_id/owner_id/players/reserve` | `LeagueRosterSource:39-58`; `SleeperClient:79-82` |
| `injury_status` is written only by `PlayerIngestService` | `PlayerIngestService:98`; `PlayerRepository:27-34` (only writer) |
| Daily cron `0 11 * * *` | `.github/workflows/daily-refresh.yml:9` |
| `sport_schedule` has a date and no time: the payload keys are `game_id, week, date, home, away, status`, and the parser keeps the date only | `SportSchedule.parse:44-67`; 017 research (measured) |
| No migration needed | the reads above |
| CORS allows `X-Sleeper-User` on all of `/api/**` | `WebConfig:48-61` |
| No golden/snapshot test freezes `PlayerStatsPage` or `TrendRow`; each has one constructor call site | grep of `backend/src` |
| `Map.of` risk avoided: new nullable fields are planned as records | data-model |
| Stat leaderboard, Trends and player page are basketball-only | `PlayerStatsService:258-260`; `PlayerTrendsService:158-160` (`NOT_BASKETBALL`) |
