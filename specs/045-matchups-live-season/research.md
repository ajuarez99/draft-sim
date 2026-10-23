# Research: spec 045 (Matchups shows the live season, then lock-ins on Matchups)

Measured 2026-10-23 against Sleeper (live), the local DB on `localhost:5433`, and the code at `origin/main`
5512cd7. The DB matches prod's shape for the NBA league.

## Measured inputs

| What | Value | Source |
|---|---|---|
| NBA 2026 league `1339351318115946496` (local id 210) | `status in_season`, stored `leg 1`, `last_scored_leg` null, 0 `roster_week_points` rows | local DB |
| Second NBA 2026 league `1414306784801239040` (id 34534) | same shape | local DB |
| NBA 2026 `player_game` | 350 rows, 2026-10-20..10-21 (local data is two days stale) | local DB |
| NFL 2026 league id 460 | `status` null, `leg 1`, 0 weeks | local DB. This is the "not started" shape. It must NOT read as live. |
| NFL 2026 ids 3, 4 | `leg` 4/7, `last_scored_leg` 3/6 | local DB. The current NFL week is never stored either. |
| Sleeper `/matchups/1` for the NBA league | 12 rows, `points` 101.5–217.0, `matchup_id` 1–6 | live |
| `/state/nba` | week 1, leg 1, `season_has_scores: true` | live |

## R1: Which season Matchups shows

**Decision**: Add a third resolver rule, `Rule.LIVE_WEEK`. A season qualifies if it has a stored scored
week (the `PLAYED_WEEKS` test), **or** if all of these hold: it's not complete, `status` is `in_season`,
the stored `leg` is ≥ 1, and the sport-season has stored games (`STORED_GAMES`'s test). Remove the
one-argument `resolve(String)`, so every caller names a rule. Matchups names `LIVE_WEEK`. Every other caller
names `PLAYED_WEEKS`, which keeps their behaviour unchanged (clarified).

**Rationale**: `status in_season` alone isn't enough, because Sleeper flips a league to `in_season` at the
draft, before any real game. The stored-games test is what separates "drafted, preseason" from "games
played". Requiring all of those conditions keeps FR-005 / Story 1 scenario 4 (offseason, fall back) for free:
an offseason season has no games. The NFL id 460 row (`status` null) fails on status.

**Alternatives**: Reading Sleeper's live `/state` inside the resolver was rejected. It would put a network
call into a pure, widely-used lookup, and it would only change the answer for the minutes between a
refresh and a state change. Using `STORED_GAMES` on its own was rejected because it ignores `status`, so a
completed season with games would be judged by games rather than by weeks. That's harmless today, but it
isn't what "live" means.

## R2: Where live scores come from, and what decides "the live week"

**Decision**: A new `LiveWeekSource` (engine). For a Sleeper league it reads `sleeper.league(id)` (for
`settings.leg` and `status`) and then `sleeper.matchups(id, leg)`. Both are cached together for 60 seconds
per league, the same TTL as 044's live roster cache. The live week is **Sleeper's `leg` at read time**, not
the stored one, so a rollover between refreshes doesn't mislabel last week's final as "in progress" (spec
edge case 1). It returns `Optional.empty()` for ESPN leagues, complete leagues, and `leg < 1`. A Sleeper
failure is a typed `FAILED` result, not an exception.

**Rationale**: two Sleeper calls per league per minute at most. HANDOFF names `LineupCheckService`'s
pattern of reading live, and 044's `liveCache` is the in-repo precedent for the TTL map. The page is
never more than 60 s behind Sleeper, which is what SC-002 allows.

**Alternatives**: storing live points into `roster_week_points` was rejected. It would bypass spec 009's
finality rule, which already shipped a partial-forever bug once (`LeagueHistoryIngestService` javadoc), and
the spec says nothing new is stored.

## R3: When the page uses the live path

**Decision**, in `WeeklyReportService.forWeek`, after resolving with `LIVE_WEEK`:

- The live week `L` exists when `LiveWeekSource` returns a week, **and** that week isn't stored. A stored
  week, partial or not, keeps today's path, so the reader never sees two "in progress" scores for one week
  (spec edge case 2).
- `requestedWeek <= 0` and nothing stored for the season and `L` exists: show `L` live (opening week).
- `requestedWeek == L` and `L` isn't stored: show `L` live.
- Otherwise it's today's path, unchanged. That includes mid-season defaults: the latest final week stays the
  default.
- `WeeklyReportService.defaultWeek` is **not changed**. The live default is decided in `forWeek` before it. *(Amended
  after plan review F14: it has no caller outside `WeeklyReportService` today, so the spotlight claim was
  stale; the spotlight reads `weekly-report/0` over HTTP instead, see plan amendment A3.)*

`Result` gains `Integer liveWeek` (the unstored live week when one exists, else null) and `boolean live`
(this response's scores are live from Sleeper). The page's existing "Week N in progress →" link also
appears when `liveWeek` is non-null and greater than the week shown.

## R4: What a live response contains

**Decision**: `available: true, live: true, weekFinal: false`, with the matchups built from the live
payload. Rosters are paired by `matchup_id`, and a null `matchup_id` is skipped, as `pairedWithScores`
does for stored weeks. Each side's `record` is the record **before** this week, which is
`recordAfter(..., week - 1, ...)`, or `0-0` in week 1. Names, avatars and `isMe` are built the same way as
the stored path. The ranking lists (`topPerformers`, or `bestNights`/`bestWeek`,
by the sport's existing either/or rule) are sent **empty**, and so are `awards`/`awardsOmitted`. The
page renders one "Week N in progress, rankings and awards come once it's final" note in their place when
`live` is true (FR-003). No new `sectionsUnavailable` code is needed: that union is `BEST_NIGHTS` |
`BEST_WEEK` with per-reason copy, and widening it would touch the 018/022 rendering for nothing. A failed live read gives
`Result.unavailable("live scores unavailable from Sleeper right now", …)` with the stored
`latestScoredWeek`/`latestFinalWeek`, so the page's existing links still work. It never falls back to last
season.

**Rationale**: winners, record changes and rankings would all claim a decided week. One page-level
note is enough (amended after plan review F19: an earlier draft of this rationale also claimed a new
reason code, which the Decision rejects).

## R5: Shown season's league id

**Decision**: `Result` gains `String sleeperLeagueId`, the id of the season actually shown. That's the
resolved row, which in the fallback case differs from the route's id. The page uses it to call
`/lock-ins` for the right season.

## R6: Lock-ins on Matchups, live (Story 3)

**Decision**: The page calls the existing `GET /api/leagues/{shownId}/lock-ins` (no `player` means
`live=true` by default) only when the Matchups response is `live` or `weekFinal == false`, and the sport is
NBA. It pairs `live.calls[]` to each matchup's two `rosterId`s. It renders nothing when `status` is
`NOT_SLEEPER`, `NOT_NBA` or `GAME_MODE`. Otherwise, if `live.status != OK`, it shows the reason in words:
`NO_HISTORY`, `ROSTERS_FAILED` or `NOT_LIVE`. For the live 2026 league, `computedOf` returns
`NO_SCORED_WEEKS`, and `report()` still goes on to live calls (`LockInService.java:283`). Thresholds come
from the complete 2024/2025 seasons.

**Rationale**: one roster read per page view, cached for 60 s by 044 (FR-010). SC-007's oracle is the same
endpoint, so the pairing is the only new logic. There are no backend changes for Story 3.

**Alternatives**: embedding the calls in the weekly-report response was rejected. It would couple two
services, and it would compute lock-ins for football and non-lock-in leagues on every Matchups load.

## R7: Past-week locks (Story 4)

**Decision**: Add `?week=N` to `GET /lock-ins`. When present (and `player` is absent), the response is the
report with `locks` = every provable lock (`Computed.allLocks()`) with `week == N`. `live` is null for
that request, and the manager and player summaries are emptied to keep the payload small, mirroring the
`player` form (F14). The `player` and no-param responses are byte-identical to today (FR-012). Each lock
already carries `rosterId`, `player`, `standIn`/`projection` margins and `laterGames`, which is "games
left" for a lock. The page shows a lock's margin under the kind it has: projection when present, else
stand-in, labelled. It never pools them (FR-013). `rodeOut` credits aren't in `allLocks`, so they can't
appear (SC-008).

A week outside the computed `weeks` (not final, or no stored games) gets an empty `locks` plus
`status` unchanged. To tell "no locks" apart from "can't tell" (Story 4 scenario 3), the response
gains `Boolean weekCovered`: true when N is in the computed weeks. It's null when no `week` is asked for.

## R8: The resolver callers, renamed (FR-007)

These calls switch from `resolve(id)` to `resolve(id, Rule.PLAYED_WEEKS)`, with no behaviour change:
`ExpectedWinsService:361, :373`, `PlayoffOddsService:311`, `RosterManagementService:140`,
`SeasonSuperlativesService:254`, `RecapService:113, :205`, `AdminRecapController:45, :54, :66, :79`,
`LeagueRefreshService:162`. Only `WeeklyReportService:188` changes, to `Rule.LIVE_WEEK`.

`LeagueRefreshService:162` picks the "shown" season for the refresh status pill. It's left on
`PLAYED_WEEKS` as clarified, which means the pill describes 2025 while Matchups shows 2026. That
mismatch is **noted, not fixed**, and flagged for the plan review.

## R9: Testing approach

- Resolver unit tests: `LIVE_WEEK` qualifies opening week (games, `in_season`, leg 1, no weeks). It doesn't
  qualify for: status null, `complete`, leg absent, or no games. `PLAYED_WEEKS` stays as today.
- `WeeklyReportService` opening-week test (FR-008): stub `LiveWeekSource` with 12 rows and 6 pairings.
  Expect the current season, `live`, no `requestedSeason`, and no rankings.
- Mid-season test: a final week exists, so the default is still the latest final, with `liveWeek` set.
- Live failure test: unavailable, current season, not last season.
- An identical-response check before and after (SC-003/SC-004), the 021 harness way: snapshot the
  JSON of every stored week of both real leagues, and every other resolver page, on main's server and on
  this branch's.
- `/lock-ins?week=` IT on 2025: the week's locks equal `allLocks` filtered by week. The no-param and
  `player` responses are unchanged.
