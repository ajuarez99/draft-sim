# Code review: spec 019 (minutes trends and streaming candidates)

**Date:** 2026-10-06
**Stage:** bug-hunting code review. I ran it cold against the uncommitted working tree in the
worktree `019-minutes-streaming` (base `origin/main` 787abd9). This is not a style pass.

**What I reviewed:**
- The `git diff`: RosterSeasonRepository, LeagueHistoryIngestService, PlayerGameRepository,
  SpotlightOwnership, HealthController, DraftSimApplication, AccessControlMvcIT,
  HealthControllerTest, weights.yml, api.ts, destinations.ts(+test), App.tsx and styles.css.
- The untracked files: V28, RosterOwners, PlayerTrendsProperties, PlayerTrendsService,
  PlayerTrendsController, PlayerTrends.tsx, playerTrends.ts and the new tests.

I read these against:
- the rewritten data-model.md and contracts/api.md (invariants 1–8);
- spec.md, plan.md's "Amended after review" table and plan-review.md F1–F13;
- verification.md, AGENTS.md, and lessons #29, #32 and bug class #3.

**What I ran:**
- GETs against the worktree backend on :8086 as `1122386008709910528`, for:
  - NBA 2025 (`1229…`): 200 in 1.28 s;
  - NBA 2026 (`1339…`): 200 in 0.85 s;
  - NBA 2024 (`1141…`): 200 in 0.65 s. Nobody had checked this one before.
- No-header and stranger-header requests (both 404), and `/api/health`.
- A script that diffs the live JSON keys against the `api.ts` types.
- Read-only SQL on :5433 that re-derives the role lists, the excluded counts, one player's
  usage, the 2025 opening cutover and the absence table's shape.
- Sleeper `/rosters` for NBA 2025, intersected with `player_game` ids.
- `./gradlew test` on the four new unit-test classes: 41 tests, 0 skipped, 0 failed.
- vitest on `playerTrends.test.ts`, `PlayerTrends.test.tsx` and `destinations.test.ts`: 43 passed.

I did not run the two ITs (shared DB) and did not drive the UI in a browser.

## Findings

### B1. High, measured: `missedTeamGames` is always 0. It reads a basis that never has a game id

`PlayerTrendsService.java:412-415` keeps only absences with `basis = TEAM_PLAYED_NO_ENTRY` **and**
`gameId != null`. The ingest only writes `TEAM_PLAYED_NO_ENTRY` as a **week-level** row, with
`game_id` and `game_date` null by construction (`PlayerGameIngestService.java:333`; the
`PlayerAbsenceRepository` javadoc says the same). A basketball missed *game* is stored as
`ENTRY_WITHOUT_PLAY`, with the game id (`PlayerGameIngestService.java:269-271`).

Measured on :5433:

| season | basis | rows | rows with game_id |
|---|---|---|---|
| 2024 | ENTRY_WITHOUT_PLAY | 17,114 | 17,114 |
| 2024 | TEAM_PLAYED_NO_ENTRY | 52 | **0** |
| 2025 | ENTRY_WITHOUT_PLAY | 16,692 | 16,692 |
| 2025 | TEAM_PLAYED_NO_ENTRY | 160 | **0** |

**Failure scenario:**
- Every row the endpoint ever returns has `missedTeamGames: 0`. All 60 rows across the three
  live payloads are 0, so the "Missed last N team games" line can never render.
- This was F2's fix for "a benched player never reads as a faller". It is dead code in
  production.
- The unit tests pass because `PlayerTrendsServiceTest.java:497-500` builds a
  `TEAM_PLAYED_NO_ENTRY` row *with* a game id, which the ingest can never produce. That's
  lesson #29's shape: the test and the producer disagree on one rule.

**Root cause:** plan-review F2's "`TEAM_PLAYED_NO_ENTRY` per team game (16,852 rows in 2025)".
16,852 is the two bases added together (16,692 + 160). The review was wrong, and the build
followed it.

**What the fix would show (measured):** the same run rule over `ENTRY_WITHOUT_PLAY`, with the
team taken from the player's last paired game. At the end of 2025, **216** players would have
a non-zero run, **108** of them ≥ 3, and the longest run is 77. Most of these are injured, not
benched, which the spec already concedes ("can't tell benching from injury").

**Fix:**
- Use `ENTRY_WITHOUT_PLAY`. Its `team` column could replace the last-game team lookup.
- Rebuild the test fixture from the real row shape.
- Add a read-IT assertion that some 2025 row has `missedTeamGames > 0`.
- Correct plan-review F2's count visibly.

### B2. Medium, measured by simulation: the Free agents / Rostered split comes after the global top-10 cap, so "Rostered: None." can be false

The server caps risers and fallers at `list-size` over *all* players
(`PlayerTrendsService.java:308`). `RoleList` (`PlayerTrends.tsx:117-143`) then splits those 10
rows by `rostered` and prints "None." for an empty half.

**Failure scenario:** I applied the end-of-2025 Sleeper rosters to the 2025 lists, which is
what the page will do in season once rosters are loaded:
- The top 10 risers hold **0** rostered players, so the page says "Rostered: None.". In fact,
  **15** of the 84 risers are rostered (SQL over the same rule).
- Fallers: 3 of the top 10 are rostered, out of 22.
- "+74 more" sits under both halves, with no way to reach the rostered ones.

The spec's US1 says "each list is split into players rostered in this league and free agents".
A reader takes "Rostered: None." as a fact about the league.

**Fix:** either:
- cap per ownership group on the server (top N free agents and top N rostered, with a total for
  each) when ownership is known; or
- at minimum, replace "None." with "None in the top {listSize}" and put the "+N more" on the
  list as a whole.

### B3. Medium-low, measured: the exclusion line overstates what it hid, and "in the last 14 days" isn't relative to today

`PlayerTrendsService.java:265-275` counts `excludedNoTeam` and `excludedStale` over **every**
player with a game in the roles season (582 in 2025). That includes STEADY players and players
with fewer than 5 games, who could never be listed. `PlayerTrends.tsx:222` then says
"{excludedStale} not shown: no game in the last 14 days. {excludedNoTeam} without an NBA team."

**Measured, 2025 (SQL reproduces the endpoint's 67 / 90 exactly):**
- Of the 67 stale players, **10** would have been risers or fallers.
- Of the 90 with no team, **18** would have been.
- So "67 not shown" is about 6.7 times what was actually hidden.

**Second problem:** `isStale` (`:332-334`) measures against the data season's **latest game
date**, not today. On the fallback page that date is 2026-04-12, so in October "no game in the
last 14 days" describes April. That's the month problem N15 fixed for the header, which came
back in the footer.

The counts also cover only the role lists, not streaming, but they sit under the role section,
so that part is fine.

**Fix:**
- Count only rows that would have been RISER or FALLER. Or keep the totals and say "of all {n}
  players with games".
- Word the date as "no game in the 14 days before {latest} ({season} season)".

### B4. Low-medium, measured: completed seasons are shown with today's teams, and players without one disappear from history

The team is `player.team` (Sleeper's current team, F10), and teamless players are excluded
(`:267-270`). F10 chose this for the **fallback** case: the current season is about to be
played. For a **complete** league it rewrites history.

Measured on the NBA 2024 payload:
- Kentavious Caldwell-Pope shows as `PHI` and Jalen Green as `PHX`, with 2024-25 numbers.
- `excludedNoTeam` is **152**: retired or unsigned players removed from the 2024 lists.
- The rail's Trends link is season-scoped, so anyone browsing 2024 lands here.

**Fix:** for `complete` leagues:
- take the team from the player's last game's team-row pairing (`G.teamCode`, already
  computed);
- don't apply the no-team exclusion;
- or keep it and label the column "current team".

### B5. Low, read: the one-game share scores the previous season with the viewed league's scoring

`PlayerTrendsService.java:184,196-201`: `scoring` is `leagues.scoringOf(league.id())`. When
the weeks come from `before` (the previous season), its games are still scored with the
current league's rules. They're then compared with credited `players_points` that Sleeper
computed under the previous season's rules.

**Failure scenario:** a chain changes scoring between seasons, and the new season has no
scored weeks yet. Then almost no games match within 0.005, the share falls below 0.9, and the
note silently disappears. It fails closed, so it doesn't make a false claim.

No effect today: 210 and 211 have identical `scoring_json` (plan-review N6). 211 → 212 differ,
but 211 has its own weeks.

**Fix:** `leagues.scoringOf(before.get().id())` for the measured season.

### B6. Low, read: `ptsPerMin` and `rostersFetchedAt` are shipped but never rendered

- `PlayerTrends.tsx` never reads `ptsPerMin`. Spec US1 says "next to minutes it shows a usage
  rate and league points per minute", and FR-004 defines it.
- `rostersFetchedAt` is also unused. Plan-review N9 asked for "rosters as of {time}". The
  disposition kept only the min, but with streaming known to lag a roster refresh by up to an
  hour, the timestamp is the honest caveat.

**Fix:** render both, or amend the spec visibly.

### B7. Low, read: a null league status never reaches `NOT_DRAFTED`

data-model F5: "Emptiness is only a fallback for a null status." `streamingReason`
(`:317-323`) has no such fallback. With `status == null` and fetched-but-empty rosters, the
reason is null, and every player is listed as unrostered.

`league.status` is null only before the first chain walk, which also writes the rosters. So
this is rare, but it's a stated rule that the code doesn't implement.

**Fix:** if the status is null and every stored roster is empty, return `NOT_DRAFTED`.

## Notes

- **N1. Ownership note when both lists are empty.** `ownershipKnown`
  (`PlayerTrends.tsx:155`) is derived from the rows. When risers and fallers are both empty,
  `every` returns true and the "not split" explanation is hidden. It's harmless, because there
  are no lists, but `d.streamingReason == null` is the real signal.
- **N2. Performance.**
  - Every request parses the previous season in full, even after both cutovers are met. In
    season that's about 29k JSON rows plus 2.5k team rows on every view for nothing.
  - `currentLeg` is queried twice (`:153`).
  - Measured 0.65–1.28 s locally. Production is still owed (F13).
  - Nothing is quadratic: the team-row lookup is hashed, and the missed-run loop is players ×
    one team's games.
- **N3. Cutover denominator.** It is "teams seen so far", not 30. I measured it on the 2025
  opening:
  - 4 teams seen on 10-21, 28 on 10-22, 30 from 10-23.
  - No team reaches 3 games before 10-24.
  - So it can't flip early on that calendar: the ≥ 3 rule flips 10-26 (24/30) and the ≥ 5 rule
    10-29 (16/30), exactly as data-model says.
  - It would misfire on a schedule where a few teams played 3 games before most had played 1.
    That's not plausible for the NBA.
- **N4. Complete, non-fallback seasons.** The roles header reads "2025 season, last 3 games vs
  season" with no "end of season". These lists are April rest and tank rotations too (Carlson
  11.6 → 37.4), the same N15 concern without the fallback flag.
- **N5. A theoretical permanent `ROSTERS_NOT_LOADED`.** It happens when a `roster_season` row
  isn't in the current `/rosters` response (league shrank), because its `players` stays null
  forever. `rosteredPlayers` then returns empty, and the copy promises a refresh. I didn't
  observe it; every league has a fixed roster count per season.
- **N6. In-progress games.** `player_game` can hold an in-progress game, because the ingest
  stores any entry with play and only gates "not yet played" on date. A mid-game refresh would
  put a partial game into the recent median. That's pre-existing ingest behaviour, not this
  spec's, but this page is the first to weight the latest game this heavily.

## Areas checked clean

- **Math (measured).**
  - Independent SQL over the same rules gives **84 risers / 59 fallers** for 2025, equal to
    the endpoint.
  - Branden Carlson's pooled last-3 usage is **22.817** in SQL vs 22.82 on the wire, using a
    team-row join on `game_id` with suffix ≠ opponent.
  - Team-row `sp` is present on every `TEAM_*` row, so `TmMIN/5` covers OT.
  - Median-of-3, the season mean (including the recent games), and the formPts/seasonPts
    windows with their ≥ 3 gate all match data-model.
  - Missing keys are 0 and the turnover key is `to`.
  - Rounding happens after the role decision. Sorts use the rounded values with deterministic
    tie-breaks.
- **All-Star and `TEAM_` (read + measured).**
  - The bare `TEAM_` row is skipped, and games whose opponent isn't a team code are dropped.
  - `left(id, 5)` is used with no LIKE, and both reads filter by sport.
  - 0 `TEAM_` ids in any payload.
- **Streaming exclusion ids (measured).** All **177** ids on the NBA 2025 Sleeper rosters
  (players ∪ reserve ∪ taxi) exist as `player_game.sleeper_player_id`. It's the same id space,
  so exclusion by `roster_season.players` works.
- **V28 and the upsert.**
  - V28 is append-only, and V27 is the highest on `origin/main`.
  - `Upsert` has one producer (grep), which always writes a non-null list built from the
    `/rosters` it already fetched. No path writes `players = null` over stored players.
  - The manual ingest (`Set.of()`) goes through the same producer and writes real arrays.
  - The `createArrayOf("text")` and `TIMESTAMP_WITH_TIMEZONE` binds round-trip: leagues 4 and
    210 are filled, and the IT exists.
  - `setDouble` into `numeric(8,2)` is fine.
  - No other `new RosterSeasonRepository(` outside Spring.
- **Ownership states.**
  - The order is `SEASON_COMPLETE` → `NOT_DRAFTED` (`pre_draft`/`drafting`) →
    `ROSTERS_NOT_LOADED`, as data-model specifies.
  - Invariant 3 holds: `rostered` is null on every row of all three live payloads.
  - `rosteredPlayers` uses the minimum fetch time and is empty if any row is null.
- **Spotlight refactor.** `RosterOwners.ownerNames` is behaviour-identical to the removed
  inline block (team name, then manager name, then "Roster N"; blank handling; avatar; isMe).
- **Games this/next week.** The lookup is by week number via `weeks.indexOf` (N8). It's null
  for a complete league. `currentWeek` comes from the same `currentLeg` the grid uses, so
  there's one source. NBA 2024 (no schedule) answers 200 with null games.
- **State order.** `NOT_CONFIGURED` → `NOT_BASKETBALL` → `NO_GAMES`. A partial config block
  reads as `NOT_CONFIGURED`. The `/api/health` `playerTrendsLoaded` flag is true, and its
  `Map.of` only holds non-null booleans.
- **Access.** No header gives 404, and a stranger's header gives 404. The route is in
  `AccessControlMvcIT`.
- **TS mirror (measured).** The live JSON keys equal the `PlayerTrends` and `TrendRow` type
  keys in both directions. The nullability matches the Java boxing.
- **Frontend.**
  - Route, destination (`sports: ['nba']`, season-scoped href) and `useBlock` keyed on
    `[id, version]` are all in place.
  - Rows are keyed by `sleeperPlayerId`.
  - Reason copy has no ingest wording, and `SEASON_COMPLETE` promises no refresh.
