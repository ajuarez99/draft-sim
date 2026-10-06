# Code review (T047): spec 017

**Date:** 2026-10-05
**Stage:** bug-hunting code review, run cold against the uncommitted working tree on
`017-nba-schedule-grid` (HEAD `22470e0`). This is not a style pass.

**What I reviewed:** `git diff` (PlayerGameIngestService, LeagueRefreshService,
LeagueRepository, the four test edits, App.tsx, api.ts, destinations.ts(+test),
LeagueHome.tsx(+test)) and the untracked files: `ingest/SportSchedule.java`,
`store/SportScheduleRepository.java`, `V27__sport_schedule.sql`,
`engine/ScheduleGridService.java`, `engine/NextMatchupService.java`,
`api/ScheduleController.java`, `api/NextMatchupController.java`, `pages/ScheduleGrid.tsx`,
plus both NBA fixtures. I read them against `contracts/api.md`, `data-model.md`, plan.md's
"Amended after review" and `verification.md`. I probed the running backend on :8084 with GETs
only.

## Findings

### R1. Medium, CONFIRMED: a finished season's grid promises a refresh that never comes

`ScheduleGridService.java:88-89`, together with `LeagueRefreshService.java:68-70,199`.

The "nothing stored" reason says *"The {season} NBA schedule hasn't been loaded yet. It
loads with the league's next refresh."* But `trigger` puts every `loaded_complete` season
into `skip` (`RefreshDecision.decide` → `SKIP_COMPLETE`), and `refreshChain` only calls
`refreshSportSeason` (where the schedule is now stored) for non-skipped seasons. So a
season that was already COMPLETE before V27 shipped never gets its schedule stored by a
refresh. Its page keeps the "loads with the next refresh" sentence for good, and the
version-keyed refetch (F8) never fires, because no refresh runs.

The `schedule` destination has no `requiresStatus` and is season-scoped, so the rail offers
"Schedule grid" on every NBA season page, finished ones included.

Evidence (live, :8084):

```
GET /api/leagues/1229352720222134272/refresh  -> seasons: 2025 COMPLETE, 2024 COMPLETE
GET /api/leagues/1141438340626231296/schedule ->
  {"season":2024,"available":false,
   "reason":"The 2024 NBA schedule hasn't been loaded yet. It loads with the league's next refresh.",
   "currentWeek":24,"lastLeagueWeek":24,"seasonOver":true,"playoff":{"startWeek":22,"endWeek":24,...}}
```

NBA 2025 shows the same thing on a fresh database. Verification V4 only got a 2025 grid
locally by calling the admin ingest route by hand. Production will look like the 2024 case
for 2025 (and 2024) until someone does the same there.

Fix (any one is enough, and the first two together are best):
1. Add a backfill step to T049's deploy: `POST /api/ingest/player-games/{nba-2025-id}?season=2025`
   (and 2024) with the admin token.
2. Don't promise what won't happen. When the league row is `complete()`, use a different
   sentence, e.g. "The {season} NBA schedule wasn't saved for this finished season."
3. Or have `refreshChain` store the schedule for skipped seasons too when none is stored
   (one Sleeper call each, no per-game work).

### R2. Low, PLAUSIBLE (follows directly from the code, not run): a league with a refused playoff window reads "season over" during its own playoffs

`ScheduleGridService.java:139` and `:80-81`.

When `lastPlayoffWeek()` is empty (round type null/unknown, or ≠ 0, as West Coast FF's 1 is),
`lastLeagueWeek` falls back to `playoff_week_start − 1`. From week `start` on,
`currentWeek > lastLeagueWeek`, so `seasonOver` is true while the league is still playing its
playoffs, and the page prints "This league's season is over." in the Next-N view.

Example: start 20, round type null, leg 20 → `lastLeagueWeek` 19, `seasonOver` true, and the
league isn't complete.

This matches data-model's written rule, so it's a spec-level bug, not a deviation from the
spec. No current NBA league hits it: all three NBA seasons return an `endWeek`, and football
never shows the grid. That's why this is Low.

Fix: compute `seasonOver` only from `league.complete()` and from `currentWeek >
lastPlayoffWeek()` when that is present. When the playoff end is unknown, don't claim the
season is over before Sleeper marks the league complete. Keep the `start − 1` fallback only for
bounding the default columns. Amend data-model's F2 row to match.

### R3. Low, PLAUSIBLE (follows directly from the code): the "Next N weeks" label overstates the sum near the end of the season

`ScheduleGrid.tsx:227` (`cols.slice(0, n)`) and `:239` / `:161` (the label).

`cols` stops at `lastLeagueWeek` (F2), so with fewer than N weeks left the sum covers fewer
weeks than the header says. Example: `currentWeek` 21, `lastLeagueWeek` 22, "Next 4 weeks"
selected → the column says "Next 4 weeks" but sums weeks 21–22. The ranking is still right,
but the label promises a number that isn't the one shown. That's the same class as the
"label the axis, spell out the number" lesson.

Fix: label with `Math.min(n, cols.length)` ("Next 2 weeks (all that's left)"), or disable the
N buttons that are larger than `cols.length`.

## Checked and found fine

- **`Schedule` → `SportSchedule` lift**: compared line by line with `git show HEAD:…/PlayerGameIngestService.java`.
  `parse`, `sideTeam`, `stringOrNull`, `lastStartedWeek`, `isAway`, `teamOf`,
  `hasCompleteGame`, `teamPlayed` and `isFinal` (`SETTLED`) are byte-for-byte the same logic.
  Only visibility changed, plus `games()` (from the de-duplicated `byId`) and `counts()`.
- **Store placement**: `replaceSeason` runs after the week loop and the no-entry pass.
  `doRefresh`/`refreshChain` aren't `@Transactional`, so catching a `RuntimeException` from the
  proxied `@Transactional` call can't produce an `UnexpectedRollbackException` (no outer
  transaction exists to mark rollback-only). The repository's own transaction rolls back the
  delete, so the old rows survive. A throw earlier in the method (week loop, no-entry pass)
  already failed the run before this change. An empty list is a no-op. Single-flight per
  `sport:season` shares one `Result`, so every chain waiting on it sees the same
  `scheduleStoreFailed`.
- **`refreshChain` accounting**: the schedule throw comes after trending and after the
  `weeksFailed` throw. Both land in the existing catch, which records failure for every target.
  The reason text has no `/api/`.
- **JDBC binds**: `LocalDate`/`OffsetDateTime` via `setObject`, `setNull(DATE)`. The read uses
  `getObject(…, LocalDate/OffsetDateTime)`. Live `fetchedAt` serializes as ISO-8601 Z and
  dates as `yyyy-MM-dd`. `int[]` serializes as a JSON array (live: ATL's 25 values, total 80).
- **`PlayoffFormat`**: `playoff_round_type` is read uncoalesced via `wasNull`. The
  `ceil(log2)` bit trick is right for 2, 4, 6, 8 and 12 teams. The positional-constructor
  callers were updated.
- **`currentLeg`**: absent → empty, never defaulted.
- **`ScheduleGridService`**: columns are built from distinct stored week numbers, not 1..max.
  Dates come from counted games only. Excluded counts are split postponed/canceled. Teams are
  ordered by code. F1 holds: `bySleeperId`, not the resolver (live: NBA 2026 → season 2026).
  The football and unavailable branches keep `playoff` filled and return empty lists, with no
  `Map.of` anywhere.
- **`NextMatchupService`**: `Fixture.rosterId` is `int`, so the `==` against the `Integer
  myRoster` unboxes rather than comparing references. `matchupId` uses `.equals`. Bye = absent
  or alone on its id. Two rosters → lowest id. A caller with no manager id → `me` null,
  `available` true. A null team name or manager id is handled (HashMap null key/value). The
  complete / ≥ start check runs before the fixtures read.
- **Controllers**: the membership gate runs first. A missing header → 404 (live and MvcIT).
- **Frontend**: weeks are looked up by number (`findIndex(w.week === week)`), and a missing
  week renders "not in Sleeper's schedule" and adds 0 to sums. The sort is total (count desc,
  code asc), so it's deterministic. React keys (week numbers, team codes) are unique.
  `shortDate` splits the string (no `Date` parsing), so there's no timezone off-by-one. Null
  `currentWeek`/`lastLeagueWeek` fall back to the stored bounds. `seasonOver` hides only the
  ahead view, and playoff weeks stay reachable. Both fetches key on `useLeagueDataVersion`.
- **LeagueHome**: `getNextMatchup` is only fetched when `sport === 'nba'`, so the NFL path is
  unchanged. `scheduleLink`'s fake lineage only feeds `destinationsFor`'s sport/status filter
  and `href(ctx.season)`, and `season` is the URL's own draft summary, so a non-current season
  links to its own id (spec 011's rule). No link renders when the draft summary isn't loaded.
- **Exhibition rows** (All-Star codes): already decided and documented in research R4/N7. The
  2026 fixture has 30 codes, all `pre_game`. Not re-raised.
- **No user-facing reason contains `/api/`** (grepped both services and both controllers).

## Fixes (2026-10-05, same session)

All three were fixed by a Sonnet coding pass, and the diff was read by the parent session.

- **R1, fixed.** `ScheduleGridService`: a complete league with no stored schedule now
  says "The {season} NBA schedule wasn't saved for this season: it finished before the
  app started storing schedules." A non-complete league keeps the refresh wording.
  T049 also gains a one-off post-deploy backfill of NBA 2025/2024 through the admin
  route. Live after restart: NBA 2024 → the new reason; 2025/2026 unchanged.
- **R2, fixed.** `seasonOver` = complete, or `currentWeek` > the **known** playoff end.
  The `lastLeagueWeek` fallbacks no longer drive it. The page shows `currentWeek` …
  last stored week when it's past `lastLeagueWeek` and the season isn't over.
  data-model and the contract are amended in place.
- **R3, fixed.** Next-N buttons beyond the remaining weeks are disabled (with a title
  saying how many are left), and the sum header uses the real count.

Suites after the fixes: backend 1,132 tests, 0 failed, 0 skipped. Web tsc clean, 1,000/1,000, build OK.
