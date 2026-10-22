# Research: spec 042

Each item says whether it was **read in code**, **measured**, or is **assumed / owed as a build task**.
Nothing here was measured against a database. The local Postgres (5433) was not running on 2026-10-20.

## R1. Ownership already exists (read in code)

- **Decision**: Reuse `engine/PlayerOwnership` (spec 022) unchanged. US1 is presentation plus one field.
- **Found**:
  - `PlayerOwnership.Ownership(state, rosterId, ownerName, avatarId, isMe, asOf)` already resolves
    ROSTERED / FREE_AGENT / NOT_DRAFTED / UNAVAILABLE, gated on the draft, with an as-of time.
  - The stat leaderboard (`web/src/pages/StatLeaderboard.tsx:59` `OwnerCell`) renders it with an avatar
    and a me-ring.
  - `components/statControls.tsx` `FilterBar` already has an **Availability** select with options
    All / Free agents / Rostered / one per roster, disabled with a reason when ownership is unknown.
  - The player page (`PlayerPage.tsx:114-116`) renders the avatar and a "Yours" pill.
  - Trends (`PlayerTrendsService.row`, `:388-408`) sets `rostered`/`rosteredBy` and drops `isMe`, although
    `RosterOwner.isMe` is in hand. The page renders the owner as plain text (`PlayerTrends.tsx:111`).
- **What's missing**: one component (three renderings today), a "My roster" option (available only
  through your own roster's name in the select), URL persistence (the filter is component state; no
  `useSearchParams` in `StatLeaderboard.tsx`), and `isMe` on Trends.
- **Alternatives**: a new ownership endpoint. Rejected, because it would be a second implementation of
  one rule (the multi-sport-landmines class).

## R2. Starters come from the live roster call (read in code)

> **Amended after review 2026-10-20 (F8, F12):** "mine" follows `NextMatchupService:83-93`, the block
> beside it on league home: the caller's manager id, then the **lowest** roster id when one manager holds
> two. That `roster_id` is then selected from the live list. Co-owners are unsupported, as they are
> everywhere in the app. Not a bare `owner_id` match. ESPN copy: "not available for ESPN leagues yet",
> gated as a `lineupCheck` key in `LeagueAvailability` rather than a service-only reason. V18 already
> stores scored-week starters, so T001 checks starter order against `roster_positions` on NBA 2025 data
> now. The gate is `complete`/pre-draft only, not the next-matchup block's regular-season stop.
> **F6, owed in T001**: measure whether this league's NBA lineups lock weekly or daily. If weekly, the
> copy says mid-week options can't be acted on.
> **Code review 2026-10-20 (S8), documented, not changed:** for a manager holding two rosters, the stat
> leaderboard and Trends "mine" (`RosterOwners.ownerNames`) mark **both** rosters, while the lineup check
> uses `NextMatchupService.myRosterId`, the lowest roster id. Each is defensible for its page; the two
> disagree for that one person.

- **Decision**: `LineupCheckService` reads `LeagueRosterSource.rosters(league)`, the same live Sleeper
  `GET /league/{id}/rosters` that `LeagueAnalysisService` makes per request, and takes the viewer's roster
  (`owner_id` = the resolved viewer) and its `starters` array.
- **Found**: `SleeperClient.rosters` javadoc lists `starters[]`. Nothing stores it: `roster_season` has
  `players`/`reserve` only (V28). For ESPN leagues the source returns `players`/`reserve` only, with no
  `starters` key.
- **Rules**: a `"0"` entry is an empty slot. It is shown as "Empty slot" and flagged (it is a real
  lineup hole). An ESPN league answers `available=false, reason=NO_STARTERS`.
- **Alternatives**: store starters (needs V38/V39, which collides with spec 040's branch), or read
  `/matchups/{currentWeek}`. Rejected: the roster call is already made, is current, and needs no schema.
- **Owed (build task)**: confirm against a real NBA league that `starters` order matches the starting
  `roster_positions` order, which the slot labels rely on.

## R3. Injury status vocabulary (owed: measure first)

- **Decision** *(amended after review 2026-10-20, F4: the weights.yml list is withdrawn)*: "Out" is ONE rule,
  `LeagueAnalysisService.isUnavailable(injuryStatus)` (Out/IR/Sus) **or** `rules.isSuspended(player)`. NBA
  suspension lives in `player.status = "SUS"`, not `injury_status`. `isUnavailable` becomes package-visible
  and LeagueAnalysis keeps calling it, so there is no second list. ~~Original: flag a starter whose
  `player.injury_status` is in a labelled, hand-set list in `weights.yml`.~~ A non-null status *not* in the list is still shown,
  under the same heading, as "Listed: <value>". Nothing is dropped silently.
- **Why measure first**: Sleeper doesn't document the vocabulary. `V1__init.sql:12` comments
  "Questionable / Out / null". `PlayerIngestService` uses `rules::isSuspended`, so "Sus" exists for
  some sport.
- **Owed (T001)**: `select sport, injury_status, count(*) from player group by 1,2` on a fresh ingest of
  both sports. Write the list from that output and quote the output in the task log. Don't write it
  from memory.

## R4. How fresh a status is (read in code; changes SC-005)

- **Found**: player rows, including `injury_status`, are written only by `PlayerIngestService.ingest`.
  It runs from the daily refresh (`.github/workflows/daily-refresh.yml`, cron `0 11 * * *`, 11:00 UTC
  = 7:00 ET) or the manual `/api/ingest/players`. Each successful ingest calls
  `StatusCaptureRepository.recordCapture(sport, season, week, now)`.
- **Decision** *(amended after review 2026-10-20, F2)*: `status_capture` alone is wrong. It isn't written
  outside `season_type == regular`, or when the state call fails, while player rows are still rewritten.
  The as-of is now `greatest(max(status_capture.captured_at), max(daily_capture.completed_at where
  kind='PLAYERS'))` for the sport, max across weeks. That's no migration. A manual `/api/ingest/players`
  writes neither when it's preseason. Null means **unknown**, never fresh. ~~Original: the latest
  `status_capture.captured_at`.~~ **On an NBA game night, a status that changes after 7:00 ET won't show until the next morning.**
  The page says so ("Injury status as of 7:02 AM").
- **Consequence for the spec**: SC-005 as written ("every starter tagged Out in Sleeper") isn't
  achievable on this data. It's amended to "as of the last status refresh" (spec amended after planning).
- **Not built, Allan's call**: a second, players-only refresh before tip-off. *(Amended after review
  2026-10-20, F2.)* `POST /api/refresh/players` already exists, but `DailyRefreshService.players` skips
  it once a players capture exists that UTC day. So a second cron does **nothing** as built: the
  follow-up needs that gate changed too. It's still open.
- **Print the as-of time, not "7 AM"**: 11:00 UTC is 6:00 EST after 2026-11-01 (F12).

## R5. Next game (read in code)

- **Found**: `sport_schedule` (V27) has `game_date date`, `home`, `away` and `status`, with **no time**.
  It's written by `PlayerGameIngestService` per refresh for the sport being ingested.
- **Decision** *(amended after review 2026-10-20, F5a/F5c)*: `NextGames` lives beside `ingest/SportSchedule`
  and reuses its `SETTLED`/`counts` rules (made package-visible) and `ScheduleGridService`'s All-Star
  exhibition filter. It does not re-list the statuses. It picks the earliest counted, unsettled game with
  `game_date >= today` for the team.
  "today" is `America/New_York` on purpose: `PlayerGameIngestService:168` uses UTC, and the divergence is
  commented in code. A game played earlier today can still read `pre_game` until the next schedule
  refresh, so the schedule's `fetchedAt` is shown. ~~Original: status not in {complete, canceled,
  postponed}.~~
- **F1 (BLOCKER, amended after review 2026-10-20)**: `status`/`nextGame`/`upcoming` are computed from the
  **URL's league row** (`requested`, the one `visibleLeague` returned) and the player's **current**
  `player.team`. They're never computed from `resolved`/`dataSeason`, because `PlayerStatsService.read`
  sets `requestedSeason` whenever the season has no box scores yet, which is every NBA 2026 page on
  opening night. They're gated on `requested`'s own status (not complete, not pre-draft) only. Test: an
  NBA 2026 league with zero 2026 box scores still returns a `nextGame`.
  "today" is `America/New_York`: Sleeper's NBA dates are US dates. That's **assumed, not verified**, so
  check it against tonight's games in verification. The header shows the date and "vs/@ OPP", with no time.
- **Owed (T001)**: whether NFL rows exist in `sport_schedule`. Memory says the football half of 017
  waited on a measurement. If none exist, the football lineup check says "no game check for football
  yet" (FR-014) rather than flagging every NFL starter.

## R6. Bench options (read in code; ordering partly owed)

- **Decision** *(amended after review 2026-10-20, F3/F12)*: options are `players − starters − reserve −
  taxi`, loaded by id. They must be eligible via **`SportRules.isEligible(player, slot)`**, and (NBA) have a
  counted game in the week. The slot comes from the starting entries of `roster_positions`, filtered by
  `LeagueAnalysisService.NON_STARTING_SLOTS` (made package-visible). A slot `isEligible` doesn't know
  (football SUPER_FLEX and the like) answers `optionsOrder = SLOT_UNSUPPORTED`, never an empty list that
  would read as "nobody fits". ~~Original: reuse `LeagueAnalysisService:890-900`. That code is a display
  comparator, not eligibility.~~
- **Ordering** *(amended after review 2026-10-20, F6/F10)*: NBA uses SEASON fp/g from **one**
  `readLeaderboard` call per request, carrying the answered season and game count. The UI prints "2025
  avg" on fallback, and the game count always. `GAMES_LEFT` is **dropped** as an ordering: NBA starters
  are credited one game per week, so games left isn't value. It's shown as a fact only. Football orders by name and says so. No NFL
  per-game average is wired to this page, and inventing one is out of scope.
- **Copy**: the heading says "Bench options", never "Recommended" (FR-010, SC-006).

## R7. URL state (read in code)

- **Decision** *(amended after review 2026-10-20, F9)*: use the **existing** `own=` encoding from
  `statCalcUrl.ts` (spec 032), plus `own=mine`, on both the leaderboard and the calculator, because they
  render the same `FilterBar`. Extend `readCalcState`, `availKey`/`parseAvail`, `matchesAvailability` and
  the `Availability` union together. The leaderboard's `SeasonPicker` gets `keepSearch: stripRoster`.
  `mine` survives a season switch (it's re-resolved per season). ~~Original: a new `?avail=` on the
  leaderboard.~~
- Player page tab: The
  player page tab goes in `?tab=` (`overview|stats|log|schedule`, default overview). An unknown value
  falls back to the default. `roster:<id>` is dropped when switching seasons, the same rule as spec 032's
  `own=r:` (roster ids repeat every season).
- "Persists for the viewer" (FR-003) means the URL, so a reload or a shared link keeps it. It doesn't mean
  localStorage.
