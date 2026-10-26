# Research: Compare two players (spec 049)

All read from the code at `origin/main` `ec72031` on 2026-10-25. Nothing below is measured unless it says so.

> **Amended after review 2026-10-26.** The decisions below are superseded where plan.md's "Amended after review"
> table (A1–A15) says so:
> - R2: the marked rows are narrowed (A4–A6), and the window-full rule now comes from the server (A7).
> - R3: the week rule is lock-ins' `liveWeek`, not a grid date range (A3, A12). R3's claim that "the client has
>   no single source for which week is today" was half right: `leg` exists, but it is the stale-Monday rule
>   that lock-ins already corrects.
> - R4: the leaderboard was already measured in spec 023 at 1.46 MB raw / 327 KB gzipped (A9). "Unmeasured" was
>   wrong.

## R1. Compose two player-page calls vs a new comparison endpoint

- **Decision**: compose. The page calls `getPlayerStats(leagueId, a)` and `getPlayerStats(leagueId, b)` in
  parallel (`web/src/api.ts`, end of the 022 block).
- **Rationale**: FR-002 requires each figure to equal the player page's. Re-using the same response makes that
  true by construction; a new endpoint would be a second implementation of one rule, the class recorded in the
  "multi-sport landmines" memory. Season consistency: `PlayerStatsService.read` resolves the data season from the
  league (`resolver.resolve(requested.sleeperId(), Rule.STORED_GAMES)`, then `dataSeasonBefore` when the box cache
  is empty, `PlayerStatsService.java:305-322`); no player input is involved, so both calls answer the same season.
- **Alternatives**: `GET /compare?a=&b=` returning both pages. Same numbers, one round trip, but a new contract,
  new auth surface (FR-014) and new tests for no figure the composed version lacks. Revisit only if T001 shows
  two calls miss SC-007.
- **Roadmap note**: roadmap 3.3 is "M, thin views over 3.1". FR-006 excludes projections, so nothing of 3.1 is
  needed. Smaller than stated.

## R2. The marking rule

- **Decision**: mark the better value only if both present, unequal, neither window `smallSample`, and (for
  `LAST_10`/`LAST_5`) both players have `games` equal to the window length. Higher is better except `tov` (per
  game) and `tovPct`. Unmarked rows: team, positions, owner, `pf`, games this week.
- **Read in code**: `smallSample` is minutes-only, `window minutes < small-sample-minutes` (100,
  `config/weights.yml:231`; `AdvancedStats.java:258`). The window kinds are `SEASON | LAST_10 | LAST_5`
  (`api.ts:3214`); 042's "7/14/30 days" was wrong.
- **Rationale**: five days into the NBA season most players have 2–4 games, so `LAST_10` and `LAST_5` are mostly
  the same games as `SEASON`. Marking would make a thin comparison look decided.
- **Alternatives**: a new games threshold (rejected: a new hand-set constant for no measured reason); marking
  everything and badging small samples (rejected: the mark is the claim, the badge would be the footnote).
- **Equality**: compared after the same rounding the page displays, so two values that print equal are never
  marked differently.

## R3. "Games this week"

- **Decision**: one additive nullable field on `PlayerStatsPage`, computed server-side (plan decision 3).
- **Why not client-side**: the page has `gameLog[].week` and `upcoming[].week`, but nothing that says which week
  is *today's*. `upcoming[0]` may be next week's game. The only other "current week" on the client
  (`StartersWithoutGame.week`, from `SeasonStartService.firstWeek`) is "latest final + 1", a different rule.
  Reading a week from one source and counts from another is the "count and label, one source" bug (memory).
- **Week rule**: `ScheduleGridService.Week(week, firstDate, lastDate)` (`ScheduleGridService.java:48`) whose range
  contains `NextGames.today(clock)`, the same "today" `header()` already uses. Same grid the schedule page draws.
- **Why unmarked**: NBA starters are credited one game per week in these leagues (540/540, spec 019/030).

## R4. The chooser's player list

- **Decision**: the SEASON stat leaderboard's `rows` (every player with a game in the window, with `name`, `team`,
  `ownership`).
- **Unmeasured**: payload size and warm latency for the real NBA league. T001 measures both. Threshold for
  switching to a slim endpoint: > ~1 MB or > 1 s warm (a guess, labelled as one).
- **Gap accepted**: players with no games this season aren't searchable; they remain comparable by address.

## R5. Route and address

- **Decision**: `/leagues/:sleeperLeagueId/compare?a=&b=`, `gate="stats"` like the player page
  (`App.tsx:156-158`). Not `/players/compare`: it would share a path shape with `/players/:sleeperPlayerId`.
- Keyed by league (the `KeyedByLeague` wrapper); the column state follows the query string.

## R6. Verdict-free copy

- **Decision**: no summary, no tally, no "edge". A rendered-text scan is the test (SC-006). The intro line says
  what the marks mean: "The marked value is the higher one (lower for turnovers). Small samples and part-filled
  windows aren't marked."
